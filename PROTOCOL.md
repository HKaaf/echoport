# Echoport Protocol v1

Echoport moves live audio between devices on the same network. Every device can be a
**server** (sends audio) and a **player** (receives audio). This document is the full wire
format, so anyone can write a compatible client.

All multi-byte integers are **big-endian** unless stated otherwise.

## Overview

```
Player                                  Server
  | --- TCP connect (port 47210) ---------> |
  | --- hello --------------------------->  |
  | <-- welcome (session token, format) --  |
  | --- UDP "hi" packet (token) --------->  |   from the socket that will receive audio
  | <== UDP audio packets ================  |   sent back to that socket's address
  | --- ping / <-- pong (every 1 s) ------  |   over TCP; 5 s silence = disconnected
```

The player opens the UDP flow, so audio passes stateful firewalls (Windows Firewall,
phone hotspots) without any port forwarding.

## Discovery

Servers advertise over mDNS / DNS-SD:

- Service type: `_echoport._tcp.local.`
- Port: the control port (default `47210`)
- TXT records:

| key    | value                                   |
|--------|-----------------------------------------|
| `id`   | stable device UUID                      |
| `name` | user-visible device name (UTF-8)        |
| `ver`  | protocol version, `1`                   |
| `src`  | `mic` or `system`                       |
| `pin`  | `1` if a PIN is required, else `0`      |

Players may also connect to a typed IP address (or one scanned from a QR code).
The QR code holds `echoport://<ip1>,<ip2>,...:<port>?pin=<pin>&name=<name>`; the player
tries each IP in order until one answers.

## Control channel (TCP)

Each message is a 4-byte length (u32) followed by that many bytes of UTF-8 JSON.
Unknown fields must be ignored. Maximum message size: 64 KiB.

### hello (player → server)

```json
{"type":"hello","proto":1,"id":"<uuid>","name":"Pixel 8","platform":"android","pin":"4819"}
```

`pin` is omitted when the player has none.

### welcome (server → player)

```json
{"type":"welcome","proto":1,"id":"<uuid>","name":"DESKTOP-HOME","source":"system",
 "token":3735928559,"udpPort":47210,"sampleRate":48000,"channels":2,
 "frameMs":5,"codec":"pcm_s16le"}
```

### error (server → player), then the server closes the connection

```json
{"type":"error","code":"bad_pin","message":"Wrong PIN"}
```

Codes: `pin_required`, `bad_pin`, `version`, `full`, `stopped`.

### format (server → player)

Sent when the source changes (for example mic → system audio). Same fields as the audio
part of `welcome`. The player resets its jitter buffer.

### ping / pong (either direction)

```json
{"type":"ping","t":123456}
{"type":"pong","t":123456}
```

`t` is the sender's monotonic clock in ms, echoed back to measure round-trip time.
Each side pings once per second. If nothing arrives for 5 seconds the peer is gone.

### bye (either direction)

```json
{"type":"bye"}
```

## Audio channel (UDP)

Every packet starts with an 12-byte header:

| offset | size | field     | notes                                        |
|--------|------|-----------|----------------------------------------------|
| 0      | 2    | magic     | ASCII `EP`                                   |
| 2      | 1    | version   | `1`                                          |
| 3      | 1    | kind      | `0` = audio, `1` = hi (player keep-alive)    |
| 4      | 4    | token     | session token from `welcome`                 |
| 8      | 4    | seq       | packet counter, wraps                        |

### hi (player → server)

Header only, `kind = 1`, `seq = 0`. Sent right after `welcome` and then every second.
The server sends audio to the source address of the latest valid `hi`.

### audio (server → player)

Header with `kind = 0`, followed by one frame of interleaved signed 16-bit
**little-endian** PCM. With the defaults (48 kHz, 2 channels, 5 ms) a frame is
240 samples × 2 channels × 2 bytes = 960 bytes.

Players keep a jitter buffer sized by the user's *Buffer* setting, drop packets
older than the play position, and play silence on underrun.

## Versioning

`proto` in `hello`/`welcome` and the UDP `version` byte are bumped on any incompatible
change. A server that cannot speak the player's version answers `error` / `version`.
Planned for v2: Opus codec (`codec: "opus"`).
