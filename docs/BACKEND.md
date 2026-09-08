# Building a backend

RistOS ships with **no backend endpoint compiled in**, so the phone does nothing until you point it
at a server you run.

**Two things are required. Everything else on this page is optional.**

1. **An `https://` URL.** A release build refuses cleartext and trusts only system CAs, so a
   self-signed certificate will not do. This is the one thing you cannot skip.
2. **Return a `DeviceResponse` with something to show.** `speech.text` is the simplest — it is
   displayed, not spoken. A `view` renders on its own, so a response carrying only a view is valid.
   A response carrying neither parses fine and puts nothing on screen.

**You do not need authentication to start.** A phone with no token sends an empty `auth_token` and
no `Authorization` header, and nothing on the device refuses to send without one — a server that
ignores the field works immediately. Add a token when you want one; there is a field for it in
Settings.

The authority for everything here is
[`app/src/main/proto/rist_request.proto`](../app/src/main/proto/rist_request.proto). The proto is
authoritative when this page and it disagree.

## What arrives

```proto
message DeviceRequest {
  string device_id  = 1;   // stable per OS install
  string session_id = 2;   // groups a conversation
  uint64 timestamp  = 3;
  oneof input {
    AudioInput audio = 4;  // push-to-talk capture
    string     text  = 5;  // typed
    ImageInput image = 10; // camera still
  }
  string       auth_token = 8;   // static bearer, empty until one is set
  Capabilities caps       = 9;   // what this device can render
  Location     location   = 11;  // always present; timezone-only without a fix
  // ... plus SMS, comms results, voicemail, geofence and notification fields
}
```

**The shipping build sends Ogg-Opus**, with `codec` set to `"opus"`, `sample_rate` 16000 and
`bit_depth` 0. Raw PCM16 mono arrives only when `codec` is `""` or `"pcm_s16le"`, which today is a
debug-only path (`RecordService.USE_OPUS_UPLINK`). **Branch on `codec`; do not assume PCM.**

There is no WAV header on either path. If you feed the bytes to a speech-to-text service that
expects a file, add the container yourself.

## What you must send back

```proto
message DeviceResponse {
  uint32   status   = 1;
  Speech   speech   = 2;   // optional; displayed, not spoken
  ViewSpec view     = 3;   // optional
  repeated Action actions = 4;
  bool     is_final = 5;
}

message Speech { string text = 1; string ssml = 2; bytes audio = 3; string audio_codec = 4; }
```

**The device does not synthesise speech.** If you set only `Speech.text`, the phone has nothing to
say out loud — it will show the text but stay silent. To make it talk, put encoded audio in
`Speech.audio` and set `audio_codec` to `"pcm_s16le"` (raw PCM16, 24 kHz, mono, no WAV header) or
`"opus"` (Ogg-Opus).

## A backend that works

Python 3, standard library plus `protobuf`. Generate the bindings first:

```sh
pip install protobuf grpcio-tools
python -m grpc_tools.protoc -I app/src/main/proto \
    --python_out=. app/src/main/proto/rist_request.proto
```

```python
#!/usr/bin/env python3
"""The smallest useful RistOS backend: it hears you, and it answers."""
from http.server import BaseHTTPRequestHandler, HTTPServer
import rist_request_pb2 as pb

# A phone that has not enrolled sends an empty auth_token, so this server does not
# require one. Set BEARER to a string to turn checking on -- see "Authentication" below.
BEARER = None

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        req = pb.DeviceRequest()
        req.ParseFromString(self.rfile.read(int(self.headers["Content-Length"])))

        # 401 clears the phone's stored token; use 403 for a merely unwelcome request.
        if BEARER is not None and req.auth_token != BEARER:
            self.send_response(403); self.end_headers(); return

        if req.WhichOneof("input") == "text":
            heard = req.text
        elif req.WhichOneof("input") == "audio":
            # Ogg-Opus when req.audio.codec == "opus" (the shipping default);
            # raw PCM16 mono at req.audio.sample_rate otherwise. No WAV header either way.
            heard = f"{req.audio.duration_ms} ms of audio"
        else:
            heard = "nothing"

        resp = pb.DeviceResponse()
        resp.status = 200
        resp.is_final = True
        resp.speech.text = f"I received {heard}."
        # To make the phone SPEAK, set resp.speech.audio to encoded audio and
        # resp.speech.audio_codec to "pcm_s16le" or "opus". Text alone stays silent.

        body = resp.SerializeToString()
        self.send_response(200)
        self.send_header("Content-Type", "application/x-protobuf")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
```

## Pointing a phone at it

Open the app drawer on the phone, choose **Settings**, and set the backend endpoint to your host —
`https://your-host/v1/device`.

The endpoint editor is hidden on a build made with `rist.releaseVariant=provisioned`, which has an
endpoint compiled in (see `app/build.gradle.kts`).

### Authentication, when you want it

**Type it into Settings.** The same screen as the endpoint has a token field. Enter the string you
set as `BEARER` and save. The token is stored, never displayed back, and the field is on the same
gate as the endpoint editor — a build with an endpoint compiled in hides both.

Two other routes exist:

- **Push a token file**, if you are scripting a fleet and not touching screens:

  ```sh
  echo -n 'choose-something' > rist-token.txt
  adb push rist-token.txt /sdcard/Android/data/watch.rist.assistant/files/
  ```

  The app reads it at startup, stores the token, then overwrites and deletes the file
  (`Config.importTokenFileIfPresent`). Restart the app afterwards.

- **Serve `POST /v1/enroll`.** What the app does on its own: a JSON route, not protobuf. It redeems
  a **pairing code the user types into Settings** — you mint the code, they read it across, the
  device posts `{nonce, device_id, label}` and stores the token you return. The same route also
  redeems an SMS nonce, which is the older path and is no longer triggered automatically. See
  `Enrolment.kt`. You do not need this to run a backend.

  Pairing codes are uppercase-only from `ABCDEFGHJKMNPQRSTUVWXYZ23456789` (no `I`, `L`, `O`, `0`,
  `1`), and the device uppercases before sending, so you can compare byte-exact and normalise
  nothing.

Once a token is set it travels both in `DeviceRequest.auth_token` and as an `Authorization: Bearer`
header. Every request also carries `X-Rist-Device`, derived from the platform `ANDROID_ID`.

**A `401` is expensive, so do not return one casually.** The device treats `401` as "the credential
is dead" and clears the stored token. Recovery is then a human: the home screen shows a "no longer
connected" row, and someone has to obtain a fresh pairing code and type it into Settings. There is
no automatic recovery, by design.

Return `401` when the credential genuinely needs replacing. For anything else — rate limiting, a
transient fault, an unknown route — use the status that actually describes it. **`503` for
"could not decide"** specifically: the device treats a single `401` as authoritative and does not
retry, so a service that answers `401` where it means `503` will unpair every handset at once.

`SECURITY.md` documents the threat model and the residual risks this accepts.

**Use HTTPS — the example above does not.** `HTTPServer` serves plain HTTP, and the published build
refuses cleartext at the platform level (`network_security_config.xml`), so a phone pointed at
`http://…` fails at the socket with nothing on screen to explain it. Put TLS in front of the example
before you point a handset at it. A debug build will accept `http://` for local testing; a release
build will not.

---

# Reference

## Transport

One round-trip per utterance:

```
POST <backend>/v1/device
Content-Type: application/x-protobuf     (body: DeviceRequest)
Accept:       application/x-protobuf     (reply: DeviceResponse)
X-Rist-Device: <stable device id>
Authorization: Bearer <token>            (only once the device has one)
```

Audio is buffered on-device for the length of the push-to-talk hold, then POSTed whole. The device
uses a 30 s read timeout and does not retry; a response that takes longer produces nothing on the
phone.

Streaming is disabled in the shipping client; a backend answers with a single `DeviceResponse`.

## Schema version

`DeviceRequest.caps.schema_version` carries the wire version, and the device sends
`SCHEMA_VERSION_CURRENT` on every request. **It is currently 12.** `SCHEMA_VERSION_CURRENT` in
`app/src/main/proto/rist_request.proto` is the authority.

A backend should accept a request whose `schema_version` it does not recognise rather than refusing
it, and should not send a field the requesting version did not define.

## The rest of what the device sends

- **`text`** — typed input from the on-screen message box (no STT needed).
- **`image`** (`ImageInput`) — camera captures.
- **`location`** (`Location`) — lat/lon, `accuracy_m`, `age_s`, `timezone`. **A `Location` is on
  every request**, so its presence tells you nothing: without permission or a cached fix the device
  sends a timezone-only `Location` so you do not have to guess local time from UTC. Test `lat`,
  `lon` and `accuracy_m`, not `HasField("location")`.
- **`confirm`** (`Confirmation`) — the user's yes/no to a `ConfirmRequest` from a
  previous reply (destructive actions gate on this).
- **`caps`** (`Capabilities`) — what this device can render. Components include
  `"map_tiles"` (device composites raw tile maps; see NavCommand below). Backends MUST
  feature-gate on this rather than assuming a device model.

## What else you may reply with

Any combination of:

- **`speech`** (`Speech`) — see "What you must send back". Not played at all if the user has
  muted reply voice.
- **`view`** (`ViewSpec`/`Component`) — a small declarative UI tree (weather cards,
  lists, calendar). `Action`s inside views POST back as new requests.
- **`nav`** (`NavCommand`) — start/stop turn-by-turn. See below.
- **`media`** (`MediaCommand` / `MediaProgress`) — play/pause/seek audiobooks, podcasts;
  audio streams straight from the referenced URL.
- **`confirm`** (`ConfirmRequest`) — "are you sure?" gate; the device answers
  with `Confirmation` on the next POST.
- **`location_request`** (`LocationRequest`) — the backend needs a (fresher) fix. The
  device acquires one and re-POSTs the SAME request once with `location` attached.
  One retry only, to prevent loops.
- **`geofences`** (`GeofenceArm`) / **`geofence_ack`** — place triggers. See below.
- **`attachments`** (`repeated Attachment`) — pictures, long text and files, shown beneath the
  reply. **Displayed, never spoken.** See below.

## Attachments (`Attachment`, field 15)

Send a picture, a long piece of text, or a file alongside the spoken reply. The device shows them
under the answer and speaks none of it, `title` included.

```
Attachment {
  kind    = "text" | "image" | "data"
  mime    = "text/markdown", "image/png", "application/json", ...
  title   = short label for the UI
  text    = the body, when kind=text
  data    = inline bytes, when kind=image|data (base64 over the JSON transport)
  uri     = an alternative to inline: the device fetches it
  tool_id = provenance
}
```

Inline `data` wins over `uri` when both are set.

**Limits the device enforces.** Over these, the attachment is dropped or shown as an error — never
silently omitted:

| Limit | Value |
|---|---|
| Bytes per attachment | 8 MiB, enforced during the read rather than after |
| Bytes per response | 24 MiB |
| Attachments per response | 8 |

**`uri` must be `https`.** Plain `http` works only on a debuggable build. `file:`, `content:` and
`data:` are refused before a socket opens, and a redirect landing on a refused scheme is refused
too. An `image` must genuinely decode as one: the bytes are checked against known image headers
before any decoder sees them, so a mislabelled `mime` is caught rather than trusted.

**The device sends its bearer token only to your configured backend host** — matched on scheme, host
AND port — and sends no credential anywhere else, including after a redirect. So a `uri` pointing at
a CDN or object store must be self-authenticating: a signed URL, a capability in the path, or public.
A bare link that expects the device's token will 403 and the user will see an error card.

## Place triggers (`Geofence`, schema v11)

Crossings are reported in `DeviceRequest.geofence_events` and resent until acked in
`DeviceResponse.geofence_ack`; `DeviceRequest.geofence_state` is sent on every request, even when
empty.

**A fence is not a notification.** The device never speaks or displays anything when one
fires. It reports; the backend runs the instruction and the outbound text arrives on a
later request as an ordinary `CommsCommand`.

**A fence is a snapshot, not a reference.** `lat`/`lon` are fixed onto the row when the
instruction is created, so a user who later changes where "home" is does not move fences
already armed. `label` is for logs only and is never authority.

`caps.max_geofences` (16 on this build) is mandatory: 0 or unset means "this device cannot
do place triggers", and the backend then refuses to create one out loud rather than storing
a fence nothing will ever evaluate.

## Navigation (`NavCommand`)

The backend does the routing (e.g. Valhalla) and the heavy cartography; the device is a
thin, deterministic renderer. Two rendering modes, chosen by device capability:

1. **Pre-rendered frames** (`NavFrame`, `Corridor`) — server-rendered PNG map frames
   (north-up overview + track-up follow/maneuver). Georeference: bounds interpolation
   when `m_per_px == 0`, centre/rotate/scale when `m_per_px > 0`. All fix→pixel math
   goes through `rn_project()` in the C core.
2. **Raw tiles** (`MapTile`, capability `"map_tiles"`) — 256px indexed-color XYZ slippy
   tiles at 2–3 zoom levels along the corridor (~600 KB for a typical drive). The device
   composites a rotating track-up view, draws the route polyline (`shape`), position
   marker, compass facing cone, and turn card locally. Fully pannable/zoomable.

`Maneuver` carries `type` (glyph id), `short_instruction`, and exit signage
(`exit_number`/`exit_branch`/`exit_toward`). The on-device nav state machine
(`app/src/main/cpp/ristnav/`) does route snapping, progress, off-route detection
(reroute = new request), and frame selection; it is dependency-free C99.
