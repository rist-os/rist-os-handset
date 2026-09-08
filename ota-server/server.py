#!/usr/bin/env python3
"""Reference OTA server for RistOS."""
import argparse
import collections
import json
import os
import hmac
import re
import signal
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MANIFEST_RE = re.compile(r"^/v1/ota/([A-Za-z0-9_-]+)/([A-Za-z0-9_-]+)/?$")
SIG_RE = re.compile(r"^/v1/ota/([A-Za-z0-9_-]+)/([A-Za-z0-9_-]+)\.minisig$")
PKG_RE = re.compile(r"^/pkg/([A-Za-z0-9._-]+)$")
# Digit runs bounded: int() raises ValueError past ~4300 digits.
RANGE_RE = re.compile(r"^bytes=(\d{0,19})-(\d{0,19})$")

CHUNK = 1024 * 256

Release = collections.namedtuple("Release", "manifest raw sig")

DEFAULT_MAX_CONNECTIONS = 64
DEFAULT_MAX_REFUSALS = 32
DEFAULT_MAX_STREAMS = 16
REFUSE_TIMEOUT = 5
REFUSE_DRAIN_LIMIT = 64 * 1024
BUSY_RETRY_AFTER = 5
STREAM_RETRY_AFTER = 10

DEFAULT_RATE_CAPACITY = 16 * 1024 ** 3
DEFAULT_RATE_PER_SECOND = 4 * 1024 ** 3 / 3600.0
DEFAULT_RATE_MAX_CLIENTS = 4096
DEFAULT_RATE_IDLE_SECONDS = 3600.0

DEFAULT_RELOAD_INTERVAL = 5.0

BUSY_BODY = b'{"error": "server busy"}'
BUSY_RESPONSE = (
    b"HTTP/1.1 503 Service Unavailable\r\n"
    b"Content-Type: application/json\r\n"
    b"Content-Length: " + str(len(BUSY_BODY)).encode() + b"\r\n"
    b"Retry-After: " + str(BUSY_RETRY_AFTER).encode() + b"\r\n"
    b"Connection: close\r\n"
    b"\r\n" + BUSY_BODY
)


class RateLimiter:

    def __init__(self, capacity, per_second, max_clients=DEFAULT_RATE_MAX_CLIENTS,
                 idle_seconds=DEFAULT_RATE_IDLE_SECONDS):
        self.capacity = float(capacity)
        self.per_second = float(per_second)
        self.max_clients = max_clients
        self.idle_seconds = idle_seconds
        self.enabled = self.capacity > 0 and self.per_second > 0
        self.lock = threading.Lock()
        self.buckets = {}   # key -> [tokens, last_touched_monotonic]

    def _bucket(self, key, now):
        b = self.buckets.get(key)
        if b is None:
            b = [self.capacity, now]
            self.buckets[key] = b
            if len(self.buckets) > self.max_clients:
                self._prune(now)
        else:
            b[0] = min(self.capacity, b[0] + (now - b[1]) * self.per_second)
            b[1] = now
        return b

    def _prune(self, now):
        for k in [k for k, b in self.buckets.items()
                  if b[0] >= self.capacity and now - b[1] > self.idle_seconds]:
            del self.buckets[k]
        if len(self.buckets) > self.max_clients:
            oldest = sorted(self.buckets, key=lambda k: self.buckets[k][1])
            for k in oldest[:len(self.buckets) - self.max_clients]:
                del self.buckets[k]

    def admit(self, key):
        if not self.enabled:
            return True, 0
        now = time.monotonic()
        with self.lock:
            b = self._bucket(key, now)
            if b[0] > 0:
                return True, 0
            wait = (-b[0] + 1) / self.per_second
        return False, max(1, min(3600, int(wait) + 1))

    def charge(self, key, nbytes):
        if not self.enabled or nbytes <= 0:
            return
        now = time.monotonic()
        with self.lock:
            b = self._bucket(key, now)
            b[0] = max(-self.capacity, b[0] - nbytes)


class Store:

    def __init__(self, root, reload_interval=DEFAULT_RELOAD_INTERVAL):
        self.root = os.path.abspath(root)
        self.reload_interval = reload_interval
        self.lock = threading.Lock()
        self.reload_lock = threading.Lock()
        self.by_key = {}
        self.signature = None
        self.checked_at = None
        self.reload()

    def _signature(self):
        try:
            entries = sorted(os.listdir(self.root))
        except OSError:
            return None
        sig = []
        for fn in entries:
            if not fn.endswith(".json"):
                continue
            try:
                st = os.stat(os.path.join(self.root, fn))
            except OSError:
                continue
            try:
                sst = os.stat(os.path.join(self.root, fn + ".minisig"))
                side = (sst.st_mtime_ns, sst.st_size)
            except OSError:
                side = None
            sig.append((fn, st.st_mtime_ns, st.st_size, side))
        return tuple(sig)

    def reload(self):
        signature = self._signature()
        found = {}
        try:
            entries = sorted(os.listdir(self.root))
        except OSError as e:
            print("cannot read %s: %s" % (self.root, e), file=sys.stderr)
            return False
        for fn in entries:
            if not fn.endswith(".json"):
                continue
            path = os.path.join(self.root, fn)
            try:
                with open(path, "rb") as fh:
                    raw = fh.read()
                m = json.loads(raw)
            except (OSError, ValueError) as e:
                print("skipping %s: %s" % (fn, e), file=sys.stderr)
                continue
            missing = [k for k in ("device", "channel", "timestamp", "filename", "payload_offset",
                                   "payload_size", "payload_properties") if k not in m]
            if missing:
                print("skipping %s: missing %s" % (fn, ", ".join(missing)), file=sys.stderr)
                continue
            pkg = os.path.join(self.root, m["filename"])
            if not os.path.isfile(pkg):
                print("skipping %s: package %s is not here" % (fn, m["filename"]), file=sys.stderr)
                continue
            if m.get("zip_size") and os.path.getsize(pkg) != m["zip_size"]:
                print("skipping %s: %s is %d bytes, manifest says %d"
                      % (fn, m["filename"], os.path.getsize(pkg), m["zip_size"]), file=sys.stderr)
                continue
            sig_path = path + ".minisig"
            try:
                with open(sig_path, "rb") as fh:
                    sig = fh.read()
            except OSError as e:
                print("skipping %s: no signature at %s (%s). Sign it with "
                      "tools/ota_sign.py %s; an unsigned manifest is refused by every device, so "
                      "serving it would look like an outage rather than a missing sidecar."
                      % (fn, os.path.basename(sig_path), e, path), file=sys.stderr)
                continue
            if not sig.strip():
                print("skipping %s: %s is empty; re-run tools/ota_sign.py %s"
                      % (fn, os.path.basename(sig_path), path), file=sys.stderr)
                continue

            key = (m["device"], m["channel"])
            if key not in found or m["timestamp"] > found[key].manifest["timestamp"]:
                found[key] = Release(manifest=m, raw=raw, sig=sig)
        with self.lock:
            changed = found != self.by_key
            self.by_key = found
            self.signature = signature
        if changed:
            print("serving %d manifest(s): %s"
                  % (len(found), ", ".join("%s/%s" % k for k in found)))
        return changed

    def maybe_reload(self):
        if self.reload_interval < 0:
            return
        now = time.monotonic()
        if self.checked_at is not None and now - self.checked_at < self.reload_interval:
            return
        if not self.reload_lock.acquire(blocking=False):
            return
        try:
            now = time.monotonic()
            if self.checked_at is not None and now - self.checked_at < self.reload_interval:
                return
            self.checked_at = now
            if self._signature() != self.signature:
                self.reload()
        finally:
            self.reload_lock.release()

    def latest(self, device, channel):
        with self.lock:
            return self.by_key.get((device, channel))


def warn_url_mismatch(store, public_base):
    if not public_base:
        return
    base = public_base.rstrip("/")
    with store.lock:
        items = sorted(store.by_key.items())
    for (device, channel), rel in items:
        url = rel.manifest.get("url") or ""
        if not url.startswith(base + "/"):
            print("warning: %s/%s advertises url %r, which is not under --public-base %s.\n"
                  "         This server cannot rewrite it: the url is inside the signed bytes and "
                  "changing one octet\n"
                  "         invalidates the signature. Regenerate with tools/ota_manifest.py --url "
                  "and re-sign."
                  % (device, channel, url, base), file=sys.stderr)


def install_sighup(store):
    if not hasattr(signal, "SIGHUP"):
        return False
    signal.signal(signal.SIGHUP, lambda *_: store.reload())
    return True


class BoundedThreadingHTTPServer(ThreadingHTTPServer):

    daemon_threads = True
    block_on_close = False

    def __init__(self, addr, handler, max_connections=DEFAULT_MAX_CONNECTIONS,
                 max_refusals=DEFAULT_MAX_REFUSALS):
        self.slots = threading.Semaphore(max_connections)
        self.refuse_slots = threading.Semaphore(max_refusals) if max_refusals > 0 else None
        self.max_connections = max_connections
        self.max_refusals = max_refusals
        super().__init__(addr, handler)

    def process_request(self, request, client_address):
        if self.slots.acquire(blocking=False):
            return super().process_request(request, client_address)
        if self.refuse_slots is not None and self.refuse_slots.acquire(blocking=False):
            t = threading.Thread(target=self._refuse, args=(request,), daemon=True)
            try:
                t.start()
                return
            except RuntimeError:
                self.refuse_slots.release()
        self.close_request(request)

    def shutdown_request(self, request):
        try:
            super().shutdown_request(request)
        finally:
            # Plain Semaphore, not Bounded: a stray release would raise inside the accept loop.
            self.slots.release()

    def handle_error(self, request, client_address):
        exc = sys.exc_info()[1]
        if isinstance(exc, (ConnectionError, TimeoutError, BrokenPipeError)):
            sys.stderr.write("%s - connection ended: %s\n"
                             % (client_address[0] if client_address else "-",
                                exc.__class__.__name__))
            return
        super().handle_error(request, client_address)

    def _refuse(self, request):
        try:
            request.settimeout(REFUSE_TIMEOUT)
            request.sendall(BUSY_RESPONSE)
            try:
                request.shutdown(socket.SHUT_WR)
            except OSError:
                pass
            drained = 0
            while drained < REFUSE_DRAIN_LIMIT:
                chunk = request.recv(4096)
                if not chunk:
                    break
                drained += len(chunk)
        except OSError:
            pass
        finally:
            self.close_request(request)
            self.refuse_slots.release()


class Handler(BaseHTTPRequestHandler):
    server_version = "ristos-ota/1"
    protocol_version = "HTTP/1.1"

    timeout = 30

    def _raw(self, code, body, content_type, extra=None):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        # No body on a HEAD: leftover bytes desync a keep-alive connection.
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, code, obj, extra=None):
        return self._raw(code, json.dumps(obj).encode(), "application/json", extra)

    def _empty(self, code):
        self.send_response(code)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _authorised(self):
        token = self.server.token
        if not token:
            return True
        got = self.headers.get("Authorization", "")
        return hmac.compare_digest(got, "Bearer " + token)

    def _client_key(self):
        peer = self.client_address[0]
        if peer in self.server.trusted_proxies:
            xff = self.headers.get("X-Forwarded-For", "")
            last = xff.split(",")[-1].strip()
            if last:
                return last
        return peer

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def do_GET(self):
        if not self._authorised():
            return self._json(401, {"error": "unauthorised"})

        path = self.path.split("?", 1)[0]
        query = self.path.split("?", 1)[1] if "?" in self.path else ""

        # Must be tried before MANIFEST_RE.
        m = SIG_RE.match(path)
        if m:
            return self.signature(m.group(1), m.group(2))

        m = MANIFEST_RE.match(path)
        if m:
            return self.manifest(m.group(1), m.group(2), query)

        m = PKG_RE.match(path)
        if m:
            return self.package(m.group(1))

        return self._json(404, {"error": "no such endpoint"})

    def do_HEAD(self):
        self.do_GET()

    def _safe(self, path):
        root = self.server.store.root
        return os.path.abspath(path).startswith(root + os.sep)

    def manifest(self, device, channel, query):
        self.server.store.maybe_reload()
        release = self.server.store.latest(device, channel)
        if not release:
            return self._json(404, {"error": "no build for %s/%s" % (device, channel)})
        latest = release.manifest

        current = None
        for part in query.split("&"):
            if part.startswith("build="):
                current = part[len("build="):]

        if current and current == str(latest.get("build")):
            return self._empty(204)

        inc = latest.get("incremental_from")
        if inc and current and inc != current:
            return self._json(404, {"error": "no path from %s; full package required" % current})

        # Verbatim octets: the device verifies a signature over them; never re-serialise or rewrite url.
        return self._raw(200, release.raw, "application/json")

    def signature(self, device, channel):
        self.server.store.maybe_reload()
        release = self.server.store.latest(device, channel)
        if not release:
            return self._json(404, {"error": "no signature for %s/%s" % (device, channel)})
        return self._raw(200, release.sig, "text/plain; charset=utf-8")

    def _range_error(self, size):
        self.send_response(416)
        self.send_header("Content-Range", "bytes */%d" % size)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _pkg_headers(self, status, start, end, size):
        self.send_response(status)
        self.send_header("Content-Type", "application/zip")
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        if status == 206:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.end_headers()

    def package(self, filename):
        path = os.path.join(self.server.store.root, filename)
        if not self._safe(path) or not os.path.isfile(path):
            return self._json(404, {"error": "no such package"})

        size = os.path.getsize(path)
        start, end = 0, size - 1
        status = 200
        rng = self.headers.get("Range")
        if rng:
            m = RANGE_RE.match(rng.strip())
            if not m:
                return self._range_error(size)
            s, e = m.group(1), m.group(2)
            if s == "":
                n = int(e or 0)
                start, end = max(0, size - n), size - 1
            else:
                start = int(s)
                end = int(e) if e else size - 1
            if start >= size or end < start:
                return self._range_error(size)
            end = min(end, size - 1)
            status = 206

        if self.command == "HEAD":
            return self._pkg_headers(status, start, end, size)

        key = self._client_key()
        ok, retry = self.server.limiter.admit(key)
        if not ok:
            return self._json(429, {"error": "rate limited"}, {"Retry-After": str(retry)})

        if not self.server.stream_slots.acquire(blocking=False):
            return self._json(503, {"error": "too many concurrent downloads"},
                              {"Retry-After": str(STREAM_RETRY_AFTER)})

        try:
            self._pkg_headers(status, start, end, size)
            remaining = end - start + 1
            with open(path, "rb") as fh:
                fh.seek(start)
                while remaining > 0:
                    buf = fh.read(min(CHUNK, remaining))
                    if not buf:
                        break
                    # Charge per chunk, before the write.
                    self.server.limiter.charge(key, len(buf))
                    try:
                        self.wfile.write(buf)
                    except (BrokenPipeError, ConnectionResetError):
                        return
                    remaining -= len(buf)
        finally:
            self.server.stream_slots.release()


def make_server(bind, port, store, token=None, public_base=None,
                max_connections=DEFAULT_MAX_CONNECTIONS,
                max_refusals=DEFAULT_MAX_REFUSALS,
                max_streams=DEFAULT_MAX_STREAMS,
                rate_capacity=DEFAULT_RATE_CAPACITY,
                rate_per_second=DEFAULT_RATE_PER_SECOND,
                trusted_proxies=()):
    httpd = BoundedThreadingHTTPServer((bind, port), Handler,
                                       max_connections=max_connections,
                                       max_refusals=max_refusals)
    httpd.store = store
    httpd.token = token
    httpd.public_base = public_base or "http://%s:%d" % (bind, httpd.server_address[1])
    httpd.stream_slots = threading.Semaphore(max_streams)
    httpd.max_streams = max_streams
    httpd.limiter = RateLimiter(rate_capacity, rate_per_second)
    httpd.trusted_proxies = frozenset(trusted_proxies)
    return httpd


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--packages", required=True, help="directory of *.json manifests + their zips")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--bind", default="127.0.0.1")
    ap.add_argument("--public-base", default=None,
                    help="public base URL devices reach, e.g. https://ota.example.com. Checked "
                         "against each manifest's signed `url` at startup; never rewrites it.")
    ap.add_argument("--max-connections", type=int, default=DEFAULT_MAX_CONNECTIONS,
                    help="concurrent connections; past this, 503 with Retry-After")
    ap.add_argument("--max-refusals", type=int, default=DEFAULT_MAX_REFUSALS,
                    help="threads reserved for answering 503s; past this, connections are dropped")
    ap.add_argument("--max-streams", type=int, default=DEFAULT_MAX_STREAMS,
                    help="concurrent /pkg/ downloads; past this, 503 with Retry-After")
    ap.add_argument("--rate-limit-bytes", type=float, default=DEFAULT_RATE_CAPACITY,
                    help="per-client burst budget in bytes; 0 disables the rate limiter")
    ap.add_argument("--rate-limit-rate", type=float, default=DEFAULT_RATE_PER_SECOND,
                    help="per-client sustained bytes/second")
    ap.add_argument("--trusted-proxy", action="append", default=[],
                    help="peer address whose X-Forwarded-For may set the rate-limit key")
    ap.add_argument("--reload-interval", type=float, default=DEFAULT_RELOAD_INTERVAL,
                    help="seconds between manifest re-stats; negative disables auto-reload")
    args = ap.parse_args()

    if not os.path.isdir(args.packages):
        raise SystemExit("--packages %s is not a directory" % args.packages)

    store = Store(args.packages, reload_interval=args.reload_interval)
    httpd = make_server(args.bind, args.port, store,
                        token=os.environ.get("RIST_OTA_TOKEN") or None,
                        public_base=args.public_base,
                        max_connections=args.max_connections,
                        max_refusals=args.max_refusals,
                        max_streams=args.max_streams,
                        rate_capacity=args.rate_limit_bytes,
                        rate_per_second=args.rate_limit_rate,
                        trusted_proxies=args.trusted_proxy)
    install_sighup(store)
    warn_url_mismatch(store, args.public_base)
    print("listening on %s:%d, packages=%s, auth=%s"
          % (args.bind, args.port, store.root, "on" if httpd.token else "off"))
    print("bounds: %d connections, %d streams, rate-limit=%s, reload=%s"
          % (args.max_connections, args.max_streams,
             "off" if not httpd.limiter.enabled
             else "%.0f B burst @ %.0f B/s" % (args.rate_limit_bytes, args.rate_limit_rate),
             "off" if args.reload_interval < 0 else "every %.1fs + SIGHUP" % args.reload_interval))
    if httpd.limiter.enabled and not httpd.trusted_proxies:
        print("warning: no --trusted-proxy set. If TLS terminates in front of this process, every "
              "device shares one rate-limit bucket keyed on the proxy's address.", file=sys.stderr)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
