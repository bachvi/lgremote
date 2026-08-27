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

The app connects with subprotocol `lgtv` and no `Origin` header — webOS rejects connections that send `Origin: http://localhost` with close code `1008` (`invalid origin`). Newer webOS serves plain WS on port **3000** (and WSS on **3001**), while older firmware uses WSS on **3000**, so the app tries `ws://3000` → `wss://3001` → `wss://3000` in order, advancing only when the socket dies before the handshake opens.

The TV uses a self-signed certificate, so a trust-all TLS context is used — **only on `wss://` connections**. Applying the SSL socket factory to a plain `ws://` connection makes the WebSocket library silently attempt a TLS handshake against a plain-WS endpoint, which the TV answers by closing the socket (`SSLHandshakeException: connection closed`).

The register handshake mirrors the reference `aiowebostv` client:

1. On socket open, send a hello announcement:

```json
{ "type": "hello", "id": "hello", "payload": {} }
```

2. On the TV's hello reply, request system info (newer webOS requires this before registration):

```json
{ "type": "request", "id": "get_sys_info", "uri": "ssap://system.info/getSystemInfo", "payload": {} }
```

3. Then register the app:

```json
{
  "type": "register",
  "id": "register_0",
  "payload": {
    "forcePairing": false,
    "pairingType": "PINS",
    "manifest": { "manifestVersion": 1, "appVersion": "1.1", "permissions": [ ... ] },
    "client-key": "..."  // only when a key is already stored
  }
}
```

`pairingType: "PINS"` makes the TV display a 4-digit PIN on screen instead of a plain accept prompt. Some firmware (notably newer webOS) ignores `PINS` and always shows an on-TV confirmation prompt; the app detects which mode the TV actually uses from the register response and shows a PIN entry dialog for `PINS`, or simply waits for you to accept on the TV for `PROMPT`.

4. When the TV answers without a `client-key`:
   - **PIN mode** (`PINS`): the TV displays a 4-digit code; enter it in the app and the app re-registers with `"forcePairing": true` and `"pairingKey": "<code>"`.
   - **Confirm mode** (`PROMPT`): accept the connection on the TV; the TV completes the pairing itself.
   Either way the TV returns a `client-key`, which the app persists and re-sends on future connections — so no confirmation is ever needed again.

5. The manifest includes a `signed` block with a freshly generated random `serial` (unique per app process). A fixed well-known serial (e.g. `7a2b9c41` from the public SDK samples) causes webOS 6.0+ to treat the client as already paired by another app sharing that serial and close the socket; a unique serial avoids this while still satisfying older firmware that expects the `signed` block.

6. If a TV fails to pair (e.g. a stale key from another app), tap the **Forget** button on its row: this clears the stored `client-key` and forces a fresh pairing prompt on the next connect.

7. Send commands as requests:

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
- **The diagnostics panel** — when a connect attempt ends, a diagnostics panel appears at the bottom of the TV list. It records every address tried, every handshake message sent/received, and the socket close code. Tap **Copy** and paste the log when reporting an issue — it pinpoints whether the failure is at the TCP, TLS, or register step.
- **Touchpad doesn't move the cursor** — the pointer socket failed to open (some TVs disable the pointer input). Volume, channel, and D-pad buttons still work; re-connecting usually re-establishes it.
