package com.example.lgremote.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.example.lgremote.data.TvDevice;
import com.example.lgremote.data.TvRepository;

import org.json.JSONObject;

import java.util.List;

/**
 * Application-wide connection to one TV. Owns the main control socket and the
 * pointer socket, exposes high-level remote control commands, and tracks the
 * current connection state.
 */
public class LgTvConnection {

    private static final String TAG = "LgTvConnection";

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_PAIRING = 2;
    public static final int STATE_CONNECTED = 3;

    public interface Listener {
        void onStateChanged(int state);

        void onVolume(int volume, boolean muted);

        void onError(String message);
    }

    private static LgTvConnection sInstance;

    private static final int MAX_CONNECT_ATTEMPTS = 3;

    private final TvRepository repository;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TvDevice device;
    private LgTvClient tvClient;
    private PointerClient pointerClient;
    private Listener listener;
    private int state = STATE_DISCONNECTED;
    private int pointerAttempts = 0;
    private int connectAttempts = 0;
    private int pointerReconnects = 0;
    private boolean pairingPinRequired = false;
    private volatile boolean closing = false;

    public static synchronized LgTvConnection get(Context context) {
        if (sInstance == null) {
            sInstance = new LgTvConnection(context.getApplicationContext());
        }
        return sInstance;
    }

    private LgTvConnection(Context context) {
        this.repository = new TvRepository(context);
    }

    public synchronized void setListener(Listener l) {
        this.listener = l;
    }

    public int getState() {
        return state;
    }

    public TvDevice getDevice() {
        return device;
    }

    public boolean isConnected() {
        return state == STATE_CONNECTED;
    }

    /**
     * Whether the pairing prompt the TV is showing requires a 4-digit code to be
     * entered in the app, or is a plain accept-on-TV confirmation.
     */
    public boolean isPairingPinRequired() {
        return pairingPinRequired;
    }

    // ------------------------------------------------------------------
    // Connection lifecycle
    // ------------------------------------------------------------------

    public synchronized void connect(TvDevice tv) {
        this.device = tv;
        pointerReconnects = 0;
        closeClients();
        closing = false;
        pointerAttempts = 0;
        connectAttempts = 0;
        pairingPinRequired = false;
        setState(STATE_CONNECTING);
        connectAttempt(0);
    }

    /**
     * Connect to the main control socket, trying each scheme/port in turn.
     * Newer webOS serves plain WS on 3000 (and WSS on 3001) while older
     * firmware uses WSS on 3000, so the schemes are attempted in that order
     * and only advanced if the socket dies before the handshake opens.
     */
    private void connectAttempt(final int attempt) {
        connectAttempts = attempt;
        final String scheme;
        final int port;
        switch (attempt) {
            case 0:
                scheme = "ws";
                port = 3000;
                break;
            case 1:
                scheme = "wss";
                port = 3001;
                break;
            default:
                scheme = "wss";
                port = 3000;
                break;
        }
        final boolean lastAttempt = attempt >= MAX_CONNECT_ATTEMPTS - 1;
        DebugLog.d(TAG, "connectAttempt " + attempt + " " + scheme + "://" + device.ip + ":" + port);

        LgTvClient client = LgTvClient.connectTo(device.ip, scheme, port, device.clientKey,
                new LgTvClient.Listener() {
                    private boolean opened = false;

                    @Override
                    public void onConnected() {
                        DebugLog.d(TAG, "attempt " + attempt + " socket opened");
                        opened = true;
                    }

                    @Override
                    public void onPairingRequired(boolean pinRequired) {
                        DebugLog.d(TAG, "pairing required (pin=" + pinRequired + ")");
                        pairingPinRequired = pinRequired;
                        setState(STATE_PAIRING);
                    }

                    @Override
                    public void onPaired(String newKey) {
                        DebugLog.d(TAG, "paired, key=" + (newKey != null && !newKey.isEmpty()));
                        if (newKey != null && !newKey.isEmpty() && !newKey.equals(device.clientKey)) {
                            saveClientKey(newKey);
                        }
                        setState(STATE_CONNECTED);
                        openPointer();
                        dumpServiceList();
                    }

                    @Override
                    public void onDisconnected() {
                        DebugLog.d(TAG, "disconnected (attempt " + attempt + ", state " + state + ", opened " + opened + ")");
                        if (connectAttempts != attempt) {
                            return;
                        }
                        if (state == STATE_CONNECTING && !opened && !lastAttempt) {
                            connectAttempt(attempt + 1);
                        } else if (state == STATE_CONNECTING || state == STATE_CONNECTED) {
                            setState(STATE_DISCONNECTED);
                        } else if (state == STATE_PAIRING) {
                            setState(STATE_DISCONNECTED);
                            notifyError("Connection to TV closed during pairing");
                        }
                    }

                    @Override
                    public void onPairingError(String message) {
                        DebugLog.e(TAG, "pairing error: " + message);
                        if (connectAttempts != attempt) {
                            return;
                        }
                        if (state == STATE_CONNECTING || state == STATE_PAIRING) {
                            setState(STATE_DISCONNECTED);
                            notifyError(message);
                        }
                    }

                    @Override
                    public void onError(String message) {
                        DebugLog.e(TAG, "attempt " + attempt + " error: " + message);
                        if (connectAttempts != attempt) {
                            return;
                        }
                        if (state == STATE_CONNECTING && !opened && !lastAttempt) {
                            connectAttempt(attempt + 1);
                        } else if (state == STATE_CONNECTING) {
                            setState(STATE_DISCONNECTED);
                            notifyError(message);
                        }
                    }

                    @Override
                    public void onVolume(int volume, boolean muted) {
                        notifyVolume(volume, muted);
                    }
                });
        if (client == null) {
            if (!lastAttempt) {
                connectAttempt(attempt + 1);
            } else {
                setState(STATE_DISCONNECTED);
                notifyError("Unable to connect to TV");
            }
            return;
        }
        this.tvClient = client;
    }

    public void pair(String pin) {
        if (tvClient != null && tvClient.isOpen()) {
            tvClient.pairWithPin(pin);
        }
    }

    public synchronized void disconnect() {
        closeClients();
        pairingPinRequired = false;
        setState(STATE_DISCONNECTED);
    }

    private void closeClients() {
        closing = true;
        closePointer();
        if (tvClient != null) {
            try {
                tvClient.close();
            } catch (Exception ignored) {
            }
            tvClient = null;
        }
    }

    private void saveClientKey(String key) {
        if (device == null) {
            return;
        }
        device.clientKey = key;
        List<TvDevice> devices = repository.load();
        for (TvDevice d : devices) {
            if (d.ip != null && d.ip.equals(device.ip)) {
                d.clientKey = key;
                d.lastSeen = System.currentTimeMillis();
                break;
            }
        }
        repository.save(devices);
    }

    // ------------------------------------------------------------------
    // Pointer socket
    // ------------------------------------------------------------------

    private void dumpServiceList() {
        if (tvClient == null || !tvClient.isOpen()) {
            return;
        }
        tvClient.sendCommand("ssap://api/getServiceList", null, result -> {
            if (result == null) {
                DebugLog.d(TAG, "getServiceList: no payload");
                return;
            }
            StringBuilder sb = new StringBuilder("services:");
            org.json.JSONArray arr = result.optJSONArray("services");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    sb.append(' ').append(arr.optString(i));
                }
            } else {
                sb.append(' ').append(result.toString());
            }
            DebugLog.d(TAG, sb.toString());
        });
    }

    private void openPointer() {
        if (tvClient == null || !tvClient.isOpen() || device == null) {
            return;
        }
        tvClient.sendCommand("ssap://com.webos.service.networkinput/getPointerInputSocket", null, payload -> {
            if (payload == null) {
                DebugLog.e(TAG, "getPointerInputSocket returned no payload");
                return;
            }
            DebugLog.d(TAG, "getPointerInputSocket response: " + payload.toString());
            String socketPath = payload.optString("socketPath", "");
            if (!socketPath.isEmpty()) {
                connectPointer(socketPath);
                return;
            }
            int port = payload.optInt("port", -1);
            if (port > 0) {
                connectPointer("ws://" + device.ip + ":" + port + "/");
                return;
            }
            DebugLog.d(TAG, "no pointer socket path in response, falling back to ws://ip:3000/");
            connectPointer("ws://" + device.ip + ":3000/");
        });
    }

    private void connectPointer(String socketPath) {
        closePointer();
        if (pointerAttempts >= 2 || device == null) {
            return;
        }
        pointerAttempts++;
        final boolean ssl = pointerAttempts == 2;
        final int attempt = pointerAttempts;

        String uri;
        try {
            java.net.URI parsed = new java.net.URI(socketPath);
            String scheme = ssl ? "wss" : (parsed.getScheme() == null ? "ws" : parsed.getScheme());
            String host = parsed.getHost() != null ? parsed.getHost() : device.ip;
            int port = parsed.getPort() > 0 ? parsed.getPort() : 3000;
            String path = parsed.getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            uri = scheme + "://" + host + ":" + port + path;
        } catch (Exception e) {
            uri = (ssl ? "wss" : "ws") + "://" + device.ip + ":3000/";
        }
        DebugLog.d(TAG, "pointer socket connecting to " + uri);

        final PointerClient[] holder = new PointerClient[1];
        PointerClient client = PointerClient.connectTo(uri, ssl, new PointerClient.Listener() {
            private boolean opened = false;

            @Override
            public void onOpen() {
                opened = true;
                pointerReconnects = 0;
                DebugLog.d(TAG, "pointer socket opened (attempt " + attempt + ")");
            }

            @Override
            public void onClose(String reason) {
                if (holder[0] == pointerClient) {
                    pointerClient = null;
                }
                DebugLog.d(TAG, "pointer socket closed reason=" + reason
                        + " state=" + state + " closing=" + closing);
                if (state == STATE_CONNECTED && !closing && opened && pointerReconnects < 5) {
                    pointerReconnects++;
                    mainHandler.postDelayed(() -> {
                        if (state == STATE_CONNECTED && !closing && pointerClient == null) {
                            pointerAttempts = 0;
                            DebugLog.d(TAG, "reconnecting pointer socket (" + pointerReconnects + ")");
                            openPointer();
                        }
                    }, 1200);
                }
            }

            @Override
            public void onError(String message) {
                // The first attempt (ws) may fail on some firmwares; fall back to wss.
                if (!opened && pointerAttempts == attempt) {
                    connectPointer(socketPath);
                }
            }
        });
        holder[0] = client;
        this.pointerClient = client;
    }

    private void closePointer() {
        if (pointerClient != null) {
            try {
                pointerClient.close();
            } catch (Exception ignored) {
            }
            pointerClient = null;
        }
    }

    // ------------------------------------------------------------------
    // Remote control commands
    // ------------------------------------------------------------------

    public void volumeUp() {
        if (tvClient != null && tvClient.isOpen()) {
            tvClient.volumeUp();
        }
    }

    public void volumeDown() {
        if (tvClient != null && tvClient.isOpen()) {
            tvClient.volumeDown();
        }
    }

    public void toggleMute() {
        if (tvClient != null && tvClient.isOpen()) {
            tvClient.toggleMute();
        }
    }

    public void setMute(boolean mute) {
        if (tvClient != null && tvClient.isOpen()) {
            tvClient.setMute(mute);
        }
    }

    public void getVolume() {
        if (tvClient != null && tvClient.isOpen()) {
            tvClient.getVolume();
        }
    }

    public void channelUp() {
        if (tvClient != null && tvClient.isOpen()) {
            tvClient.channelUp();
        }
    }

    public void channelDown() {
        if (tvClient != null && tvClient.isOpen()) {
            tvClient.channelDown();
        }
    }

    public void home() {
        boolean pointerOpen = pointerClient != null && pointerClient.isOpen();
        boolean mainOpen = tvClient != null && tvClient.isOpen();
        if (pointerOpen) {
            // The pointer HOME key is the reliable way to open the launcher,
            // including on firmware where com.webos.app.home does not exist.
            pointerClient.button("HOME");
            return;
        }
        if (!mainOpen) {
            return;
        }
        tvClient.openHome(result -> {
            if (result != null && result.optBoolean("returnValue", false)) {
                return;
            }
            tvClient.openApp("com.webos.app.home", result2 -> {
                if (result2 != null && result2.optBoolean("returnValue", false)) {
                    return;
                }
                // Last resort: close whatever is in the foreground to get back to the launcher.
                tvClient.getForegroundAppInfo(fg -> {
                    String appId = fg == null ? null : fg.optString("appId", "");
                    DebugLog.d(TAG, "home fallback: foreground appId=" + appId);
                    if (appId != null && !appId.isEmpty() && !"com.webos.app.home".equals(appId)) {
                        tvClient.closeApp(appId, null);
                    }
                });
            });
        });
    }

    public void back() {
        if (pointerClient != null && pointerClient.isOpen()) {
            pointerClient.button("BACK");
        } else if (tvClient != null && tvClient.isOpen()) {
            tvClient.closeApp();
        }
    }

    public void turnOff() {
        if (tvClient != null && tvClient.isOpen()) {
            tvClient.turnOff();
        }
    }

    public void runDiagnostics() {
        LgTvClient client = tvClient;
        if (client != null && client.isOpen()) {
            client.sendCommand("ssap://com.webos.applicationManager/listLaunchPoints", null, result ->
                    DebugLog.d(TAG, "listLaunchPoints: " + (result == null ? "null" : result.toString())));
            client.getForegroundAppInfo(null);
            client.sendCommand("ssap://media.controls/getVolume", null, r ->
                    DebugLog.d(TAG, "media.controls/getVolume: " + (r == null ? "null" : r.toString())));
            client.sendCommand("ssap://media.controls/play", null, r ->
                    DebugLog.d(TAG, "media.controls/play: " + (r == null ? "null" : r.toString())));
            client.sendCommand("ssap://media.controls/pause", null, r ->
                    DebugLog.d(TAG, "media.controls/pause: " + (r == null ? "null" : r.toString())));
            client.sendCommand("ssap://media.controls/channelUp", null, r ->
                    DebugLog.d(TAG, "media.controls/channelUp: " + (r == null ? "null" : r.toString())));
            client.sendCommand("ssap://media.controls/volumeUp", null, r ->
                    DebugLog.d(TAG, "media.controls/volumeUp: " + (r == null ? "null" : r.toString())));
            client.sendCommand("ssap://tv/getExternalInputList", null, r ->
                    DebugLog.d(TAG, "tv/getExternalInputList: " + (r == null ? "null" : r.toString())));
            client.sendCommand("ssap://system.launcher/getAppState", null, r ->
                    DebugLog.d(TAG, "system.launcher/getAppState: " + (r == null ? "null" : r.toString())));
        }
        final String[] steps = {"move", "button:UP", "rawNewline", "jsonButton", "jsonCmdButton", "rawHomeNoNL"};
        mainHandler.post(new Runnable() {
            int i = 0;
            int waitTries = 0;

            @Override
            public void run() {
                if (i >= steps.length) {
                    DebugLog.d(TAG, "diagnostics complete");
                    return;
                }
                PointerClient pc = pointerClient;
                if (pc == null || !pc.isOpen()) {
                    if (waitTries++ < 20) {
                        mainHandler.postDelayed(this, 300);
                        return;
                    }
                    waitTries = 0;
                    DebugLog.d(TAG, "diag: pointer socket not open, skipping " + steps[i]);
                    i++;
                    mainHandler.postDelayed(this, 300);
                    return;
                }
                waitTries = 0;
                String step = steps[i++];
                switch (step) {
                    case "move":
                        pc.move(3, 3);
                        break;
                    case "rawNewline":
                        pc.sendRaw("\n");
                        break;
                    case "jsonButton":
                        pc.sendRaw("{\"type\":\"button\",\"name\":\"HOME\"}");
                        break;
                    case "jsonCmdButton":
                        pc.sendRaw("{\"cmd\":\"button\",\"name\":\"HOME\"}");
                        break;
                    case "rawHomeNoNL":
                        pc.sendRaw("type:button\nname:HOME");
                        break;
                    default:
                        pc.button(step.substring("button:".length()));
                        break;
                }
                mainHandler.postDelayed(this, 1500);
            }
        });
    }

    public void navigate(String key) {
        if (pointerClient != null && pointerClient.isOpen()) {
            pointerClient.button(key);
        }
    }

    public void pointerMove(int dx, int dy) {
        if (pointerClient != null && pointerClient.isOpen()) {
            pointerClient.move(dx, dy);
        }
    }

    public void pointerClick() {
        if (pointerClient != null && pointerClient.isOpen()) {
            pointerClient.click();
        }
    }

    public void pointerScroll(int dy) {
        if (pointerClient != null && pointerClient.isOpen()) {
            pointerClient.wheel(0, dy);
        }
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    private void setState(int newState) {
        if (state == newState) {
            return;
        }
        DebugLog.d(TAG, "state " + state + " -> " + newState);
        state = newState;
        final Listener l = listener;
        mainHandler.post(() -> {
            if (l != null) {
                l.onStateChanged(newState);
            }
        });
    }

    private void notifyError(String message) {
        final Listener l = listener;
        mainHandler.post(() -> {
            if (l != null) {
                l.onError(message);
            }
        });
    }

    private void notifyVolume(int volume, boolean muted) {
        final Listener l = listener;
        mainHandler.post(() -> {
            if (l != null) {
                l.onVolume(volume, muted);
            }
        });
    }
}
