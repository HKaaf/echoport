<p align="center">
  <img src="assets/icon.svg" width="96" alt="Echoport logo">
</p>

<h1 align="center">Echoport</h1>

<p align="center">
  Share microphones and speakers between your devices over the local network.<br>
  Free, open source, no accounts, no time limits.
</p>

<p align="center">
  <b>English</b> · <a href="README.fa.md">فارسی</a>
</p>

---

## What it does

Every device running Echoport has two roles:

- **Server** – sends audio: either its **microphone** or **what it is playing** (system audio).
- **Player** – receives audio and plays it into its **speakers** or into a **virtual microphone**.

So you can, for example:

| Server sends | Player plays into | Use it for |
|---|---|---|
| Phone microphone | PC virtual mic | Use your phone as a wireless mic in Discord, Zoom, OBS |
| PC system audio | Phone speaker | Listen to your PC on headphones connected to your phone |
| Phone microphone | PC speakers | Baby monitor, intercom |
| PC system audio | Another PC's virtual mic | Play music or sound effects into a call |

## Features

- Wi-Fi or USB tethering, devices find each other automatically (mDNS)
- QR code pairing, or type an IP address; every network address of the server is listed
- Microphone boost up to 400%, noise suppression (RNNoise on Windows), equalizer presets
- Adjustable buffer for the latency / stability trade-off
- Optional PIN so only your devices can listen
- Record what you hear to a WAV file
- Several players can listen to one server at the same time
- 14 themes (light and dark), follows the system mode by default
- English and Persian (فارسی) with full right-to-left layout
- Starts with Windows and lives in the tray

## Platforms

| | Server: mic | Server: system audio | Player: speakers | Player: virtual mic |
|---|---|---|---|---|
| Windows 10 / 11 | ✅ | ✅ | ✅ | ✅ (needs [VB-Cable](https://vb-audio.com/Cable/)) |
| Android 8+ | ✅ | ✅ Android 10+ | ✅ | ❌ not allowed by Android |

## Install

Website: **https://hkaaf.github.io/echoport/**

Download the latest Windows installer and Android APK from
[Releases](https://github.com/HKaaf/echoport/releases).

- **Windows:** the installer isn't code-signed yet, so SmartScreen may warn you. Click
  *More info → Run anyway*. For the virtual microphone, install the free
  [VB-Cable](https://vb-audio.com/Cable/) driver once.
- **Android:** allow installing from unknown sources for your browser or file manager.
  On Xiaomi / Redmi / Samsung phones, open *Settings → Allow running in the background*
  inside Echoport so the system doesn't stop the audio.

## How it works

```
 Player                              Server
   | -- TCP hello (port 47210) -----> |
   | <-- welcome + session token ---- |
   | -- UDP "hi" -------------------> |   opens the path through firewalls
   | <== UDP audio, 5 ms frames ===== |   48 kHz 16-bit PCM
   | <-> ping / pong every second --> |   latency + liveness
```

The full wire format is in [PROTOCOL.md](PROTOCOL.md). It's small on purpose, so other
clients can be written against it.

## Project layout

```
core/      Rust: protocol, audio I/O (WASAPI via cpal), effects, jitter buffer, discovery
desktop/   Windows app: Tauri 2 (Rust) + Svelte 5 UI
android/   Android app: Kotlin + Jetpack Compose
assets/    Logo and icon source
```

## Building

**Windows app** – needs Rust (MSVC toolchain), Visual Studio Build Tools (C++), Node.js 20+.

```bash
cd desktop
npm install
npm run tauri dev      # run in development
npm run tauri build    # installer in target/release/bundle/nsis
```

Core tests: `cargo test -p echoport-core`

**Android app** – needs JDK 17 and the Android SDK (platform 35).

```bash
cd android
./gradlew assembleRelease   # APK in app/build/outputs/apk/release
```

## Contributing

Issues and pull requests are welcome. Translations are especially welcome: the desktop
strings live in `desktop/src/locales/*.json` and the Android strings in
`android/app/src/main/res/values-*/strings.xml`.

## License

[GPL-3.0-or-later](LICENSE). Echoport is an independent project and is not affiliated
with any other audio-sharing app.
