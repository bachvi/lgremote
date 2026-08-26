# LGRemote

An Android remote control app for LG Smart TVs running webOS. It talks directly to the TV's local WebSocket API — no cloud, no account, no LG ThinQ app required.

## Features

- **Scan & connect** — discovers LG webOS TVs on the same Wi-Fi network via UPnP/SSDP, or add a TV manually by IP.
- **One-time pairing** — the first time you connect, the TV shows a confirmation prompt on screen; confirm it (or enter the on-screen 4-digit code) once and the app remembers the TV (client key), so later connections need no confirmation.
- **Volume control** — volume up/down, mute/unmute, and live volume level display (polled from the TV).
- **Channel control** — channel up / channel down.
- **Touchpad** — drag to move the pointer, tap to click, two-finger drag to scroll.
- **D-pad** — up / down / left / right / OK.
- **Home / Back** — go to the webOS Home screen, go back.
- **Power** — turn the TV off (with confirmation).

## Requirements

- An LG webOS TV (webOS 3.x – 7.x) on the same local network as the phone.
- The TV must be reachable on TCP port `3000` (control) and, for the touchpad, the dynamically reported pointer socket (typically `3001`).
- Android 8.0+ (API 26).

## Building

Open the project in Android Studio (the Gradle wrapper is included), or build from the command line:

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Notes:

- `local.properties` (Android SDK path) is machine-specific and not committed; Android Studio generates it for you.
- The app uses the **Java-WebSocket** library and AndroidX Material Components. No other services or permissions are needed beyond INTERNET / Wi-Fi multicast for discovery.

## Usage

1. Install the APK on your phone and open **LG TV Remote**.
2. Tap **Scan** (or **Add manually** and type the TV's IP). TVs on your network appear in the list.
3. Tap **Connect** next to your TV.
4. The first time, your TV shows a confirmation prompt. Confirm it on the TV (or, if the TV shows a 4-digit code, enter it in the app and tap **Allow**).
5. The remote opens. Use the volume/channel buttons, the touchpad, and the D-pad to control the TV.

If the TV does not appear in a scan:

- Make sure the TV is powered on and on the same Wi-Fi network.
- Add the TV manually by IP address.
- Some routers block multicast; SSDP discovery may then fail even though manual connection works.

## How it works

LG webOS exposes a local control API over WebSockets.

### Control socket

The app connects with subprotocol `lgtv` and an `Origin: http://localhost` header. The TV uses a self-signed certificate, so a trust-all TLS context is used. Newer webOS serves plain WS on port **3000** (and WSS on **3001**), while older firmware uses WSS on **3000**, so the app tries `ws://3000` → `wss://3001` → `wss://3000` in order, advancing only when the socket dies before the handshake opens.

The handshake is driven step-by-step, matching the reference Home Assistant client:

1. After the socket opens, the client starts a `hello` exchange: it sends `{"id":"hello","type":"hello","payload":{}}` and waits for the TV's `hello` reply.
2. On the `hello` reply it sends a `systeminfo/getSystemInfo` request (webOS 6.0+ requires system info before registration).
3. Once the system-info response arrives, register the app:

```json
{
  "type": "register",
  "id": "register_0",
  "payload": {
    "forcePairing": false,
    "pairingType": "PROMPT",
    "manifest": { "manifestVersion": 1, "appVersion": "1.1", "permissions": [ ... ] },
    "client-key": "..."  // only when a key is already stored
  }
}
```

The initial register always uses `forcePairing: false`. On webOS 6.0+ the TV replies with a PROMPT and then completes registration itself — the user confirms directly on the TV screen and the TV returns a `registered` message with a new `client-key`. On firmware that still uses the classic flow, the user enters the on-screen code and the app re-registers with `"forcePairing": true` and `"pairingKey": "<code>"`.

The manifest uses the current (non-`signed`) format that webOS 6.0+ accepts; the legacy `signed`/`serial`/`signatures` block is omitted because newer firmware rejects a register whose serial was already used to issue a `client-key` on the TV.

4. The `client-key` returned by the TV is persisted and re-sent on future connections — so no confirmation is ever needed again.

5. If a TV fails to pair (e.g. a stale key from another app), tap the **Forget** button on its row: this clears the stored `client-key` and forces a fresh pairing prompt on the next connect.

5. Send commands as requests:

| Action | URI |
| --- | --- |
| Volume up / down | `ssap://media.controls/volumeUp` / `volumeDown` |
| Mute | `ssap://media.controls/setMute` (`{"mute":true}`) |
| Get volume | `ssap://media.controls/getVolume` |
| Channel up / down | `ssap://media.controls/channelUp` / `channelDown` |
| Home | `ssap://system.launcher/launch` (`{"id":"com.webos.app.home"}`) |
| Back (fallback) | `ssap://system.launcher/close` |
| Turn off | `ssap://system/turnOff` |

### Pointer socket (`ws://<tv-ip>:3001`)

The TV reports the pointer port in the response to `ssap://com.webos.service.networkinput/getPointerInputSocket`. The app connects there (falling back to `wss://` if `ws://` fails) and sends:

- move: `{"type":"touch","payload":{"type":"move","dx":..,"dy":..}}`
- click: `{"type":"touch","payload":{"type":"click"}}`
- scroll: `{"type":"touch","payload":{"type":"wheel","dx":0,"dy":..}}`
- buttons: `{"type":"button","payload":{"name":"UP"|"DOWN"|"LEFT"|"RIGHT"|"OK"|"BACK"}}`

## Project layout

```
app/src/main/java/com/example/lgremote/
├── MainActivity.java        TV list: scan, manual add, connect & pairing PIN flow
├── RemoteActivity.java      Remote control UI + volume polling
├── data/
│   ├── TvDevice.java        TV model (ip, name, client key, …)
│   └── TvRepository.java    Persistence (SharedPreferences)
├── net/
│   ├── LgTvClient.java      Control socket: register/pairing handshake + ssap commands
│   ├── PointerClient.java   Pointer socket: touchpad + button events
│   ├── LgTvConnection.java  Singleton orchestrating sockets and connection state
│   ├── DiscoveryManager.java UPnP/SSDP TV discovery
│   └── SslUtils.java        Trust-all SSL context (self-signed TV cert)
└── ui/
    ├── TouchpadView.java    Multi-touch pad (move / click / scroll)
    └── TvAdapter.java       TV list adapter
```

## Troubleshooting

- **"Connection error" on connect** — double-check the IP, make sure the phone and TV are on the same network, and that nothing else (e.g., the LG ThinQ app) holds the pairing slot; reboot the TV's network or "De-register devices" in TV settings if pairing keeps failing.
- **Pairing code never appears** — enable "mobile device connection" / "external device" in the TV's connection settings.
- **Touchpad doesn't move the cursor** — the pointer socket failed to open (some TVs disable the pointer input). Volume, channel, and D-pad buttons still work; re-connecting usually re-establishes it.
