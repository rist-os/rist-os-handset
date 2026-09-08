#!/usr/bin/env python3
"""Tests for the OTA manifest generator and server."""
import http.client
import json
import os
import signal
import socket
import struct
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

PAYLOAD = b"".join(bytes([i % 251]) for i in range(200000))
TOKEN = "test-token-not-real"
FAKE_SIG = (b"untrusted comment: signature from a test\n"
            b"RUQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n"
            b"trusted comment: fixture\n"
            b"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n")


def write_release(directory, name, manifest, sig=FAKE_SIG):
    path = os.path.join(directory, name)
    raw = json.dumps(manifest, indent=2, sort_keys=True).encode() + b"\n"
    with open(path, "wb") as fh:
        fh.write(raw)
    if sig is not None:
        with open(path + ".minisig", "wb") as fh:
            fh.write(sig)
    return raw
BIG_SIZE = 16 * 1024 * 1024


def payload_data_offset(zip_path, member="payload.bin"):
    with zipfile.ZipFile(zip_path) as z:
        info = z.getinfo(member)
    with open(zip_path, "rb") as fh:
        fh.seek(info.header_offset)
        sig, _ver, _flag, _method, _t, _d, _crc, _cs, _us, name_len, extra_len = \
            struct.unpack("<IHHHHHIIIHH", fh.read(30))
        assert sig == 0x04034B50, "not a local file header at %d" % info.header_offset
        return info.header_offset + 30 + name_len + extra_len


def build_fake_ota(path):
    props = (
        "FILE_HASH=0000000000000000000000000000000000000000000=\n"
        "FILE_SIZE=%d\n"
        "METADATA_HASH=1111111111111111111111111111111111111111111=\n"
        "METADATA_SIZE=4096\n" % len(PAYLOAD)
    )
    # payload.bin must be written first so its offset cannot move.
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        z.writestr("payload.bin", PAYLOAD)
    offset = payload_data_offset(path)

    meta = (
        "ota-property-files=payload.bin:%d:%d\n"
        "ota-required-cache=0\n"
        "ota-streaming-property-files=payload.bin:%d:%d\n"
        "ota-type=AB\n"
        "post-build=google/stallion/stallion:17/TEST.000000.000/2026072200:user/release-keys\n"
        "post-build-incremental=2026072200\n"
        "post-sdk-level=37\n"
        "post-security-patch-level=2026-07-05\n"
        "post-timestamp=1784749407\n"
        "pre-device=stallion\n" % (offset, len(PAYLOAD), offset, len(PAYLOAD))
    )
    with zipfile.ZipFile(path, "a", zipfile.ZIP_STORED) as z:
        z.writestr("payload_properties.txt", props)
        z.writestr("META-INF/com/android/metadata", meta)
    assert payload_data_offset(path) == offset, "payload.bin moved when members were appended"
    return offset


class OtaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="ota-test-")
        real = os.environ.get("RIST_OTA_TEST_ZIP")
        if real:
            cls.zip_path = real
            cls.synthetic = False
        else:
            cls.zip_path = os.path.join(cls.tmp, "stallion-ota_update-2026072200.zip")
            build_fake_ota(cls.zip_path)
            cls.synthetic = True

        out = subprocess.run(
            [sys.executable, os.path.join(ROOT, "tools", "ota_manifest.py"), cls.zip_path,
             "--url", "https://ota.example.com/" + os.path.basename(cls.zip_path)],
            capture_output=True, text=True)
        assert out.returncode == 0, "manifest generation failed: %s" % out.stderr
        cls.manifest = json.loads(out.stdout)

        pkg_dir = os.path.join(cls.tmp, "pkgs")
        os.makedirs(pkg_dir, exist_ok=True)
        link = os.path.join(pkg_dir, os.path.basename(cls.zip_path))
        if not os.path.exists(link):
            os.symlink(os.path.abspath(cls.zip_path), link)
        cls.manifest_bytes = write_release(pkg_dir, "m.json", cls.manifest)

        os.environ["RIST_OTA_TOKEN"] = TOKEN
        import server as srv
        cls.srv = srv
        cls.pkg_dir = pkg_dir
        cls.big = os.path.join(pkg_dir, "big.bin")
        with open(cls.big, "wb") as fh:
            fh.truncate(BIG_SIZE)
        cls.store = srv.Store(pkg_dir)
        cls.httpd = srv.make_server("127.0.0.1", 0, cls.store, token=TOKEN)
        cls.port = cls.httpd.server_address[1]
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()
        time.sleep(0.2)

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()

    def spawn(self, store=None, **opts):
        httpd = self.srv.make_server("127.0.0.1", 0, store or self.store, token=TOKEN, **opts)
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        self.addCleanup(httpd.server_close)
        self.addCleanup(httpd.shutdown)
        return httpd.server_address[1]

    def conn(self, port=None, timeout=15):
        c = http.client.HTTPConnection("127.0.0.1", port or self.port, timeout=timeout)
        self.addCleanup(c.close)
        return c

    def on(self, c, path, headers=None, method="GET"):
        h = {"Authorization": "Bearer " + TOKEN}
        h.update(headers or {})
        c.request(method, path, headers=h)
        r = c.getresponse()
        return r.status, dict(r.getheaders()), r.read()

    def req(self, path, headers=None, method="GET", port=None):
        c = http.client.HTTPConnection("127.0.0.1", port or self.port, timeout=30)
        h = {"Authorization": "Bearer " + TOKEN}
        h.update(headers or {})
        try:
            c.request(method, path, headers=h)
            r = c.getresponse()
            return r.status, dict(r.getheaders()), r.read()
        finally:
            c.close()

    def hold_a_stream(self, port):
        c = self.conn(port, timeout=15)
        c.request("GET", "/pkg/big.bin", headers={"Authorization": "Bearer " + TOKEN})
        r = c.getresponse()
        self.assertEqual(r.status, 200)
        return c

    def test_manifest_carries_what_applyPayload_needs(self):
        m = self.manifest
        self.assertEqual(m["device"], "stallion")
        self.assertEqual(m["ota_type"] if "ota_type" in m else "AB", "AB")
        self.assertIsInstance(m["payload_offset"], int)
        self.assertIsInstance(m["payload_size"], int)
        keys = [p.split("=", 1)[0] for p in m["payload_properties"]]
        self.assertEqual(keys, ["FILE_HASH", "FILE_SIZE", "METADATA_HASH", "METADATA_SIZE"])

    def test_manifest_payload_size_matches_file_size_property(self):
        m = self.manifest
        file_size = int([p for p in m["payload_properties"] if p.startswith("FILE_SIZE=")][0].split("=")[1])
        self.assertEqual(file_size, m["payload_size"])

    def test_missing_token_is_rejected(self):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        c.request("GET", "/v1/ota/stallion/stable")
        self.assertEqual(c.getresponse().status, 401)

    def test_same_build_gets_204(self):
        status, _, _ = self.req("/v1/ota/stallion/stable?build=%s" % self.manifest["build"])
        self.assertEqual(status, 204)

    def test_older_build_gets_the_manifest(self):
        status, _, body = self.req("/v1/ota/stallion/stable?build=2026010100")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["build"], self.manifest["build"])

    def test_unknown_device_is_404(self):
        status, _, _ = self.req("/v1/ota/nosuch/stable")
        self.assertEqual(status, 404)

    def test_the_manifest_is_served_byte_for_byte_as_it_is_on_disk(self):
        status, h, body = self.req("/v1/ota/stallion/stable")
        self.assertEqual(status, 200)
        self.assertEqual(body, self.manifest_bytes,
                         "served manifest is not byte-identical to the file that was signed")
        self.assertEqual(h.get("Content-Type"), "application/json")
        self.assertEqual(int(h["Content-Length"]), len(self.manifest_bytes))

    def test_the_url_field_is_not_rewritten(self):
        body = self.req("/v1/ota/stallion/stable")[2]
        self.assertEqual(json.loads(body)["url"], self.manifest["url"])

    def test_the_signature_sidecar_is_served(self):
        status, h, body = self.req("/v1/ota/stallion/stable.minisig")
        self.assertEqual(status, 200, "OtaCheck.signatureUrl has no route on this server")
        self.assertEqual(body, FAKE_SIG)
        self.assertTrue(h.get("Content-Type", "").startswith("text/plain"))

    def test_the_sidecar_route_is_not_shadowed_by_the_manifest_route(self):
        self.assertIsNone(self.srv.MANIFEST_RE.match("/v1/ota/stallion/stable.minisig"))
        self.assertIsNotNone(self.srv.SIG_RE.match("/v1/ota/stallion/stable.minisig"))

    def test_a_sidecar_for_an_unknown_device_is_404(self):
        self.assertEqual(self.req("/v1/ota/nosuch/stable.minisig")[0], 404)

    def test_head_on_the_sidecar_sends_no_body(self):
        status, h, body = self.req("/v1/ota/stallion/stable.minisig", method="HEAD")
        self.assertEqual(status, 200)
        self.assertEqual(body, b"")
        self.assertEqual(int(h["Content-Length"]), len(FAKE_SIG))

    def test_an_unsigned_manifest_is_not_served_at_all(self):
        d = tempfile.mkdtemp(prefix="ota-unsigned-", dir=self.tmp)
        name = os.path.basename(self.zip_path)
        os.symlink(os.path.abspath(self.zip_path), os.path.join(d, name))
        write_release(d, "a.json", self.manifest, sig=None)
        port = self.spawn(store=self.srv.Store(d, reload_interval=0))
        self.assertEqual(self.req("/v1/ota/stallion/stable", port=port)[0], 404)
        self.assertEqual(self.req("/v1/ota/stallion/stable.minisig", port=port)[0], 404)

    def test_an_empty_sidecar_is_treated_as_no_sidecar(self):
        d = tempfile.mkdtemp(prefix="ota-emptysig-", dir=self.tmp)
        name = os.path.basename(self.zip_path)
        os.symlink(os.path.abspath(self.zip_path), os.path.join(d, name))
        write_release(d, "a.json", self.manifest, sig=b"   \n")
        port = self.spawn(store=self.srv.Store(d, reload_interval=0))
        self.assertEqual(self.req("/v1/ota/stallion/stable", port=port)[0], 404)

    def test_a_sidecar_landing_late_is_picked_up_without_a_restart(self):
        d = tempfile.mkdtemp(prefix="ota-latesig-", dir=self.tmp)
        name = os.path.basename(self.zip_path)
        os.symlink(os.path.abspath(self.zip_path), os.path.join(d, name))
        write_release(d, "a.json", self.manifest, sig=None)
        port = self.spawn(store=self.srv.Store(d, reload_interval=0))
        self.assertEqual(self.req("/v1/ota/stallion/stable", port=port)[0], 404)

        with open(os.path.join(d, "a.json.minisig"), "wb") as fh:
            fh.write(FAKE_SIG)
        self.assertEqual(self.req("/v1/ota/stallion/stable", port=port)[0], 200)

    @unittest.skipUnless(shutil.which("minisign"), "minisign is not on PATH")
    def test_a_real_minisign_signature_verifies_against_the_served_bytes(self):
        d = tempfile.mkdtemp(prefix="ota-realsig-", dir=self.tmp)
        name = os.path.basename(self.zip_path)
        os.symlink(os.path.abspath(self.zip_path), os.path.join(d, name))
        manifest_path = os.path.join(d, "a.json")
        write_release(d, "a.json", self.manifest, sig=None)

        keys = tempfile.mkdtemp(prefix="ota-key-", dir=self.tmp)
        pub, sec = os.path.join(keys, "t.pub"), os.path.join(keys, "t.key")
        self.assertEqual(subprocess.run(["minisign", "-G", "-W", "-p", pub, "-s", sec],
                                        capture_output=True).returncode, 0)
        self.assertEqual(subprocess.run(
            ["minisign", "-S", "-s", sec, "-m", manifest_path, "-t", "ristos-ota test"],
            capture_output=True).returncode, 0)

        port = self.spawn(store=self.srv.Store(d, reload_interval=0))
        status, _, served_manifest = self.req("/v1/ota/stallion/stable", port=port)
        self.assertEqual(status, 200)
        status, _, served_sig = self.req("/v1/ota/stallion/stable.minisig", port=port)
        self.assertEqual(status, 200)

        with open(manifest_path, "rb") as fh:
            self.assertEqual(served_manifest, fh.read())

        out = os.path.join(d, "from-the-wire.json")
        with open(out, "wb") as fh:
            fh.write(served_manifest)
        with open(out + ".minisig", "wb") as fh:
            fh.write(served_sig)
        r = subprocess.run(["minisign", "-V", "-p", pub, "-m", out], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0,
                         "minisign refused the bytes the server served:\n%s%s" % (r.stdout, r.stderr))

    def test_head_advertises_range_support(self):
        status, h, _ = self.req("/pkg/" + self.manifest["filename"], method="HEAD")
        self.assertEqual(status, 200)
        self.assertEqual(h.get("Accept-Ranges"), "bytes")
        self.assertEqual(int(h["Content-Length"]), self.manifest["zip_size"])

    def test_head_with_range_reports_206_not_200(self):
        off = self.manifest["payload_offset"]
        status, h, _ = self.req("/pkg/" + self.manifest["filename"],
                                {"Range": "bytes=%d-%d" % (off, off + 99)}, method="HEAD")
        self.assertEqual(status, 206)
        self.assertEqual(h["Content-Range"], "bytes %d-%d/%d" % (off, off + 99, self.manifest["zip_size"]))

    def test_ranged_read_matches_disk_at_the_payload_offset(self):
        off, n = self.manifest["payload_offset"], 65536
        status, _, body = self.req("/pkg/" + self.manifest["filename"],
                                   {"Range": "bytes=%d-%d" % (off, off + n - 1)})
        self.assertEqual(status, 206)
        with open(self.zip_path, "rb") as fh:
            fh.seek(off)
            expected = fh.read(n)
        self.assertEqual(body, expected, "ranged read differs from the bytes on disk")

    def test_payload_offset_actually_points_at_the_payload(self):
        off = self.manifest["payload_offset"]
        with open(self.zip_path, "rb") as fh:
            fh.seek(off)
            head = fh.read(16)
        if self.synthetic:
            self.assertEqual(head, PAYLOAD[:16])
        else:
            self.assertEqual(head[:4], b"CrAU")

    def test_traversal_is_refused(self):
        status, _, _ = self.req("/pkg/../../../etc/passwd")
        self.assertIn(status, (400, 404))

    def test_unsatisfiable_range_is_416(self):
        status, _, _ = self.req("/pkg/" + self.manifest["filename"],
                                {"Range": "bytes=999999999999-"})
        self.assertEqual(status, 416)

    def test_an_absurdly_long_range_does_not_crash_the_handler(self):
        status, _, _ = self.req("/pkg/" + self.manifest["filename"],
                                {"Range": "bytes=" + ("9" * 5000) + "-"})
        self.assertEqual(status, 416)

    def test_a_wrong_token_is_rejected(self):
        status, _, _ = self.req("/v1/ota/stallion/stable",
                                {"Authorization": "Bearer not-the-right-token"})
        self.assertEqual(status, 401)

    def test_head_on_the_manifest_sends_no_body_and_does_not_desync_the_connection(self):
        req = ("%s /v1/ota/stallion/stable HTTP/1.1\r\nHost: 127.0.0.1\r\n"
               "Authorization: Bearer " + TOKEN + "\r\n\r\n")
        s = socket.create_connection(("127.0.0.1", self.port), timeout=10)
        self.addCleanup(s.close)
        s.sendall(((req % "HEAD") + (req % "GET")).encode())

        data = b""
        deadline = time.time() + 10
        while time.time() < deadline:
            try:
                chunk = s.recv(65536)
            except socket.timeout:
                break
            if not chunk:
                break
            data += chunk
            if data.count(b"HTTP/1.1 200 OK") >= 2 and data.endswith(b"}"):
                break

        head, rest = data.split(b"\r\n\r\n", 1)
        self.assertTrue(head.startswith(b"HTTP/1.1 200 OK"), head[:64])
        self.assertTrue(rest.startswith(b"HTTP/1.1 200 OK"),
                        "the HEAD sent a body: the next response starts %r" % rest[:64])
        body = rest.split(b"\r\n\r\n", 1)[1]
        self.assertEqual(json.loads(body)["build"], self.manifest["build"])
        length = int([l.split(b":")[1] for l in head.split(b"\r\n")
                      if l.lower().startswith(b"content-length")][0])
        self.assertEqual(length, len(body))

    def test_saturating_the_stream_cap_returns_503_with_retry_after(self):
        port = self.spawn(max_streams=1)
        self.hold_a_stream(port)
        status, h, _ = self.req("/pkg/big.bin", port=port)
        self.assertEqual(status, 503)
        self.assertGreaterEqual(int(h["Retry-After"]), 1)

    def test_the_manifest_endpoint_survives_a_saturated_stream_cap(self):
        port = self.spawn(max_streams=1)
        self.hold_a_stream(port)
        status, _, body = self.req("/v1/ota/stallion/stable", port=port)
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["build"], self.manifest["build"])

    def test_a_stream_slot_is_returned_when_the_client_disappears(self):
        port = self.spawn(max_streams=1)
        c = self.hold_a_stream(port)
        self.assertEqual(self.req("/pkg/big.bin", port=port)[0], 503)
        c.close()
        deadline = time.time() + 10
        status = None
        while time.time() < deadline:
            status, _, _ = self.req("/pkg/" + self.manifest["filename"], port=port)
            if status == 200:
                break
            time.sleep(0.1)
        self.assertEqual(status, 200, "stream slot was never returned after the client went away")

    def test_the_connection_cap_refuses_with_503_rather_than_queueing(self):
        port = self.spawn(max_connections=1, max_refusals=4)
        held = self.conn(port)
        self.assertEqual(self.on(held, "/v1/ota/stallion/stable")[0], 200)

        status, h, body = self.req("/v1/ota/stallion/stable", port=port)
        self.assertEqual(status, 503)
        self.assertGreaterEqual(int(h["Retry-After"]), 1)
        self.assertEqual(json.loads(body)["error"], "server busy")

        held.close()
        deadline = time.time() + 10
        status = None
        while time.time() < deadline:
            status, _, _ = self.req("/v1/ota/stallion/stable", port=port)
            if status == 200:
                break
            time.sleep(0.1)
        self.assertEqual(status, 200, "connection slot was never returned")

    def test_with_no_refusal_threads_left_the_connection_is_dropped_not_hung(self):
        port = self.spawn(max_connections=1, max_refusals=0)
        held = self.conn(port)
        self.assertEqual(self.on(held, "/v1/ota/stallion/stable")[0], 200)
        c = self.conn(port, timeout=5)
        with self.assertRaises((http.client.RemoteDisconnected, ConnectionResetError,
                                BrokenPipeError)):
            self.on(c, "/v1/ota/stallion/stable")

    def test_a_resumed_ranged_download_is_never_rate_limited(self):
        size = self.manifest["zip_size"]
        port = self.spawn(rate_capacity=4 * size, rate_per_second=1.0)
        chunks, n = [], 40
        step = (size + n - 1) // n
        for start in range(0, size, step):
            end = min(start + step, size) - 1
            status, h, body = self.req("/pkg/" + self.manifest["filename"],
                                       {"Range": "bytes=%d-%d" % (start, end)}, port=port)
            self.assertEqual(status, 206, "resume request at offset %d was refused" % start)
            self.assertEqual(h["Content-Range"], "bytes %d-%d/%d" % (start, end, size))
            chunks.append(body)
        with open(self.zip_path, "rb") as fh:
            self.assertEqual(b"".join(chunks), fh.read(),
                             "resumed download did not reassemble to the file on disk")

    def test_a_request_is_admitted_whole_even_when_the_budget_cannot_cover_it(self):
        size = self.manifest["zip_size"]
        port = self.spawn(rate_capacity=1024, rate_per_second=0.001)
        status, h, body = self.req("/pkg/" + self.manifest["filename"], port=port)
        self.assertEqual(status, 200)
        self.assertEqual(len(body), size, "the download was truncated by the limiter")

        status, h, _ = self.req("/pkg/" + self.manifest["filename"], port=port)
        self.assertEqual(status, 429)
        self.assertGreaterEqual(int(h["Retry-After"]), 1)

    def test_repeated_full_downloads_are_eventually_refused(self):
        size = self.manifest["zip_size"]
        port = self.spawn(rate_capacity=2 * size, rate_per_second=1.0)
        seen = []
        for _ in range(6):
            status, _, _ = self.req("/pkg/" + self.manifest["filename"], port=port)
            seen.append(status)
            if status == 429:
                break
        self.assertEqual(seen[0], 200)
        self.assertEqual(seen[1], 200, "a budget of two packages must cover two packages")
        self.assertIn(429, seen, "unbounded repeats of a full package were never refused")

    def test_a_head_costs_no_budget(self):
        port = self.spawn(rate_capacity=1024, rate_per_second=0.001)
        for _ in range(5):
            status, _, _ = self.req("/pkg/" + self.manifest["filename"], method="HEAD", port=port)
            self.assertEqual(status, 200)
        self.assertEqual(self.req("/pkg/" + self.manifest["filename"], port=port)[0], 200)

    def test_the_manifest_endpoint_is_not_rate_limited(self):
        port = self.spawn(rate_capacity=1024, rate_per_second=0.001)
        self.assertEqual(self.req("/pkg/" + self.manifest["filename"], port=port)[0], 200)
        self.assertEqual(self.req("/pkg/" + self.manifest["filename"], port=port)[0], 429)
        for _ in range(5):
            self.assertEqual(self.req("/v1/ota/stallion/stable", port=port)[0], 200)

    def test_forwarded_for_is_honoured_only_from_a_trusted_proxy(self):
        size = self.manifest["zip_size"] // 2
        trusting = self.spawn(rate_capacity=size, rate_per_second=0.001,
                              trusted_proxies=("127.0.0.1",))
        pkg = "/pkg/" + self.manifest["filename"]
        self.assertEqual(self.req(pkg, {"X-Forwarded-For": "10.0.0.1"}, port=trusting)[0], 200)
        self.assertEqual(self.req(pkg, {"X-Forwarded-For": "10.0.0.2"}, port=trusting)[0], 200)
        self.assertEqual(self.req(pkg, {"X-Forwarded-For": "10.0.0.1"}, port=trusting)[0], 429,
                         "a trusted proxy's client should have its own, spendable bucket")

        untrusting = self.spawn(rate_capacity=size, rate_per_second=0.001)
        self.assertEqual(self.req(pkg, {"X-Forwarded-For": "10.0.0.3"}, port=untrusting)[0], 200)
        self.assertEqual(self.req(pkg, {"X-Forwarded-For": "10.0.0.4"}, port=untrusting)[0], 429,
                         "an untrusted X-Forwarded-For minted a fresh bucket")

    def _second_release_dir(self):
        d = tempfile.mkdtemp(prefix="ota-reload-", dir=self.tmp)
        name = os.path.basename(self.zip_path)
        os.symlink(os.path.abspath(self.zip_path), os.path.join(d, name))
        write_release(d, "a.json", self.manifest)
        newer = dict(self.manifest)
        newer["build"] = "2099010100"
        newer["timestamp"] = self.manifest["timestamp"] + 1
        return d, newer

    def test_a_new_pair_dropped_in_is_served_without_a_restart(self):
        d, newer = self._second_release_dir()
        store = self.srv.Store(d, reload_interval=0)
        port = self.spawn(store=store)
        status, _, body = self.req("/v1/ota/stallion/stable", port=port)
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["build"], self.manifest["build"])

        write_release(d, "b.json", newer)
        status, _, body = self.req("/v1/ota/stallion/stable", port=port)
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["build"], "2099010100")

    def test_the_reload_interval_bounds_how_often_the_disk_is_restatted(self):
        d, newer = self._second_release_dir()
        store = self.srv.Store(d, reload_interval=3600)
        port = self.spawn(store=store)
        self.assertEqual(self.req("/v1/ota/stallion/stable", port=port)[0], 200)
        write_release(d, "b.json", newer)
        status, _, body = self.req("/v1/ota/stallion/stable", port=port)
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["build"], self.manifest["build"])

    @unittest.skipUnless(hasattr(signal, "SIGHUP"), "no SIGHUP on this platform")
    def test_sighup_forces_a_reload_immediately(self):
        d, newer = self._second_release_dir()
        store = self.srv.Store(d, reload_interval=-1)
        previous = signal.getsignal(signal.SIGHUP)
        self.addCleanup(signal.signal, signal.SIGHUP, previous)
        self.assertTrue(self.srv.install_sighup(store))
        port = self.spawn(store=store)

        write_release(d, "b.json", newer)
        self.assertEqual(json.loads(self.req("/v1/ota/stallion/stable", port=port)[2])["build"],
                         self.manifest["build"])

        os.kill(os.getpid(), signal.SIGHUP)
        time.sleep(0.2)
        status, _, body = self.req("/v1/ota/stallion/stable", port=port)
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["build"], "2099010100")


if __name__ == "__main__":
    unittest.main(verbosity=2)
