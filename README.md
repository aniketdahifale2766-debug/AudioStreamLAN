# AudioStreamLAN

Android app that captures the phone's permitted system/game/media playback and streams raw PCM audio to browsers on the same local network.

## Features

- Android MediaProjection + AudioPlaybackCapture
- 48 kHz, 16-bit PCM
- Stereo capture with mono fallback converted to stereo
- 20 ms WebSocket audio packets
- Local HTTP listener page and binary WebSocket stream
- Multiple simultaneous browser listeners
- Per-browser 80–1000 ms playback-delay control
- Jitter buffering with AudioWorklet when available
- Scheduled Web Audio compatibility fallback
- Automatic browser reconnect
- QR code and copy/shareable listener address
- Live connected-listener count
- Foreground service and partial wake lock while streaming
- No cloud server and no internet audio relay

## How to use

1. Install the debug APK from GitHub Actions.
2. Connect the phone and listener devices to the same Wi-Fi/LAN.
3. Open AudioStreamLAN and tap **Start Audio + LAN Server**.
4. Approve Android audio/screen capture permission.
5. Scan the QR code or open the displayed `http://PHONE-IP:8080/` address on another device.
6. Tap **Start Listening** in the browser.
7. Adjust the listener's delay independently from 80 to 1000 ms.

## Capture limitations

Android's playback-capture API only exposes playback that meets Android's capture rules. Some apps can block playback capture, so silence from a particular app does not necessarily mean AudioStreamLAN is broken.

## Security

The server is intentionally LAN-only and has no authentication. Anyone who can reach the phone on the same network can open the listener page while streaming is active. Stop the stream when finished.

This project is independent of PulseSync and the previous Sound project.
