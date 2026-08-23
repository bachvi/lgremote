# LGRemote

An Android remote control app for LG Smart TVs running webOS. It talks directly to the TV's local WebSocket API — no cloud, no account, no LG ThinQ app required.

## Features

- **Scan & connect** — discovers LG webOS TVs on the same Wi-Fi network via UPnP/SSDP, or add a TV manually by IP.
- **One-time pairing** — the first time you connect, the TV shows a 4-digit confirmation code; enter it once and the app remembers the TV (client key), so later connections need no code.
- **Volume control** — volume up/down, mute/unmute, and live volume level display (polled from the TV).
- **Channel control** — channel up / channel down.
- **Touchpad** — drag to move the pointer, tap to click, two-finger drag to scroll.
- **D-pad** — up / down / left / right / OK.
- **Home / Back** — go to the webOS Home screen, go back.
- **Power** — turn the TV off (with confirmation).

## Requirements

- An LG webOS TV (webOS 3.x – 7.x) on the same local network as the phone.
- The TV must be reachable on TCP ports `3000` (control) and, for the touchpad, `3001` (pointer socket).
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
4. The first time, your TV will show a 4-digit code on screen. Enter it in the app and tap **Allow**.
5. The remote opens. Use the volume/channel buttons, the touchpad, and the D-pad to control the TV.

If the TV does not appear in a scan:

- Make sure the TV is powered on and on the same Wi-Fi network.
- Add the TV manually by IP address.
- Some routers block multicast; SSDP discovery may then fail even though manual connection works.

## How it works

LG webOS exposes a local control API over WebSockets.

### Control socket (`wss://<tv-ip>:3000`)

1. Connect with subprotocol `lgtv` and an `Origin: http://localhost` header. The TV uses a self-signed certificate, so the app uses a trust-all TLS context.
2. Send a `hello` reply when the TV says `hello`.
3. Register the app:

```json
{
  "type": "register",
  "id": "register_0",
  "payload": {
    "forcePairing": true,
    "pairingType": "PROMPT",
    "manifest": { "manifestVersion": 1, "appVersion": "1.0.0", "signed": { ... } }
  }
}
```

`forcePairing` must be `true` when the app has no stored `client-key` yet (first-time connection) so the TV shows its confirmation PIN; otherwise many firmwares silently close the socket. Once a key exists, it is included in the register message and `forcePairing` is `false`.

4. If the TV answers without a `client-key`, it is showing a PIN on screen; send the same register message with `"pairingKey": "<4-digit-code>"`. The TV then returns a `client-key`, which the app persists and re-sends on future connections — so no code is ever needed again.

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
