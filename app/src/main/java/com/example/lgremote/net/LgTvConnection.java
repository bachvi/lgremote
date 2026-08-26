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

    // ------------------------------------------------------------------
    // Connection lifecycle
    // ------------------------------------------------------------------

    public synchronized void connect(TvDevice tv) {
        this.device = tv;
        closeClients();
        pointerAttempts = 0;
        connectAttempts = 0;
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

        LgTvClient client = LgTvClient.connectTo(device.ip, scheme, port, device.clientKey,
                new LgTvClient.Listener() {
                    private boolean opened = false;

                    @Override
                    public void onConnected() {
                        opened = true;
                    }

                    @Override
                    public void onPairingRequired() {
                        setState(STATE_PAIRING);
                    }

                    @Override
                    public void onPaired(String newKey) {
                        if (newKey != null && !newKey.isEmpty() && !newKey.equals(device.clientKey)) {
                            saveClientKey(newKey);
                        }
                        setState(STATE_CONNECTED);
                        openPointer();
                    }

                    @Override
                    public void onDisconnected() {
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
        setState(STATE_DISCONNECTED);
    }

    private void closeClients() {
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

    private void openPointer() {
        if (tvClient == null || !tvClient.isOpen() || device == null) {
            return;
        }
        tvClient.sendCommand("ssap://com.webos.service.networkinput/getPointerInputSocket", null, payload -> {
            int port = payload == null ? -1 : payload.optInt("port", -1);
            if (port > 0) {
                connectPointer(port);
            }
        });
    }

    private void connectPointer(int port) {
        closePointer();
        if (pointerAttempts >= 2 || device == null) {
            return;
        }
        pointerAttempts++;
        final boolean ssl = pointerAttempts == 2;
        final int attempt = pointerAttempts;

        PointerClient client = PointerClient.connectTo(device.ip, port, ssl, new PointerClient.Listener() {
            private boolean opened = false;

            @Override
            public void onOpen() {
                opened = true;
            }

            @Override
            public void onClose(String reason) {
            }

            @Override
            public void onError(String message) {
                // The first attempt (ws) may fail on some firmwares; fall back to wss.
                if (!opened && pointerAttempts == attempt) {
                    connectPointer(port);
                }
            }
        });
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
        if (tvClient != null && tvClient.isOpen()) {
            tvClient.openHome();
        }
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
