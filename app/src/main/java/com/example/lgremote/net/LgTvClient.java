package com.example.lgremote.net;

import android.os.Handler;
import android.os.Looper;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.extensions.IExtension;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.protocols.Protocol;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket client for the main LG WebOS control socket.
 * Handles the register / pairing handshake and sending "ssap://" commands.
 */
public class LgTvClient extends WebSocketClient {

    private static final String TAG = "LgTvClient";

    /** The 4-digit code typed on the TV is shown in this constant. */
    private static final String PAIRING_ID = "register_0";

    /**
     * Fresh serial generated once per process. Older webOS reads the manifest's
     * {@code signed.serial} to identify the app; reusing a well-known serial
     * (e.g. "7a2b9c41") makes webOS 6.0+ treat the client as already paired by
     * another app and close the socket. A unique serial avoids that while still
     * providing the signed block older firmware expects.
     */
    private static final String SERIAL = UUID.randomUUID().toString().replace("-", "");

    private static JSONObject buildManifest() {
        JSONObject manifest = new JSONObject();
        try {
            JSONArray permissions = new JSONArray();
            for (String p : new String[]{
                    "APP_TO_APP", "CLOSE", "CONTROL_AUDIO", "CONTROL_DISPLAY",
                    "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_PLAYBACK",
                    "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_TEXT", "CONTROL_INPUT_TV",
                    "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_POWER", "CONTROL_TV_SCREEN",
                    "LAUNCH", "LAUNCH_WEBAPP", "READ_APP_STATUS", "READ_COUNTRY_INFO",
                    "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST", "READ_INSTALLED_APPS",
                    "READ_LGE_SDX", "READ_LGE_TV_INPUT_EVENTS", "READ_NETWORK_STATE",
                    "READ_NOTIFICATIONS", "READ_POWER_STATE", "READ_RUNNING_APPS",
                    "READ_SETTINGS", "READ_TV_CHANNEL_LIST", "READ_TV_CURRENT_TIME",
                    "READ_UPDATE_INFO", "SEARCH", "TEST_OPEN", "TEST_PROTECTED", "TEST_SECURE",
                    "UPDATE_FROM_REMOTE_APP", "WRITE_NOTIFICATION_ALERT",
                    "WRITE_NOTIFICATION_TOAST", "WRITE_SETTINGS"}) {
                permissions.put(p);
            }
            JSONObject signed = new JSONObject();
            signed.put("created", "20260101");
            signed.put("appId", "com.example.lgremote");
            signed.put("vendorId", "com.example");
            signed.put("localizedAppNames", new JSONObject().put("", "LG TV Remote"));
            signed.put("localizedVendorNames", new JSONObject().put("", "LG Remote"));
            signed.put("permissions", permissions);
            signed.put("serial", SERIAL);
            manifest.put("appVersion", "1.1");
            manifest.put("manifestVersion", 1);
            manifest.put("permissions", permissions);
            manifest.put("signed", signed);
        } catch (JSONException ignored) {
        }
        return manifest;
    }

    public interface Listener {
        void onConnected();

        /** The TV is showing a confirmation code and is waiting for the app to submit it. */
        void onPairingRequired();

        /** Pairing succeeded; the returned key must be persisted for future connections. */
        void onPaired(String clientKey);

        /** The TV rejected or cancelled the pairing (e.g. wrong PIN or user declined). */
        void onPairingError(String message);

        void onDisconnected();

        void onError(String message);

        void onVolume(int volume, boolean muted);
    }

    public interface Callback {
        void onResult(JSONObject payload);
    }

    private final Listener listener;
    private final String clientKey;
    private final AtomicInteger requestId = new AtomicInteger(0);
    private final Map<String, Callback> pendingRequests = new HashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final long REGISTER_TIMEOUT_MS = 12000;
    private long lastMessageAt = 0;
    private volatile boolean handshakeDone = false;

    private final Runnable registerWatchdog = new Runnable() {
        @Override
        public void run() {
            if (handshakeDone || !isOpen()) {
                return;
            }
            long idle = System.currentTimeMillis() - lastMessageAt;
            if (idle > REGISTER_TIMEOUT_MS) {
                DebugLog.e(TAG, "no response from TV within " + REGISTER_TIMEOUT_MS + "ms, closing socket");
                close();
                return;
            }
            mainHandler.postDelayed(this, 2000);
        }
    };

    public LgTvClient(URI serverUri, String clientKey, Listener listener) {
        super(serverUri,
                new Draft_6455(Collections.<IExtension>emptyList(),
                        Collections.singletonList(new Protocol("lgtv"))),
                Collections.singletonMap("Origin", "http://localhost"),
                6000);
        this.listener = listener;
        this.clientKey = clientKey == null ? "" : clientKey;
    }

    public static LgTvClient connectTo(String ip, String scheme, int port, String clientKey, Listener listener) {
        try {
            DebugLog.d(TAG, "connectTo " + scheme + "://" + ip + ":" + port + "/ key=" + (clientKey != null && !clientKey.isEmpty()));
            LgTvClient client = new LgTvClient(new URI(scheme + "://" + ip + ":" + port + "/"), clientKey, listener);
            if ("wss".equalsIgnoreCase(scheme)) {
                client.setSocketFactory(SslUtils.trustAllSslSocketFactory());
            }
            client.setConnectionLostTimeout(15);
            client.connect();
            return client;
        } catch (Exception e) {
            DebugLog.e(TAG, "connectTo failed", e);
            return null;
        }
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        DebugLog.d(TAG, "socket open, sending register");
        lastMessageAt = System.currentTimeMillis();
        mainHandler.removeCallbacks(registerWatchdog);
        mainHandler.postDelayed(registerWatchdog, 2000);
        listener.onConnected();
        sendRegister();
    }

    @Override
    public void onMessage(String message) {
        DebugLog.d(TAG, "recv: " + message);
        lastMessageAt = System.currentTimeMillis();
        try {
            JSONObject json = new JSONObject(message);
            String type = json.optString("type");
            String id = json.optString("id");
            JSONObject payload = json.optJSONObject("payload");

            if ("hello".equals(type)) {
                replyHello();
            } else if ("registered".equals(type)) {
                handshakeDone = true;
                mainHandler.removeCallbacks(registerWatchdog);
                handleRegistered(payload);
            } else if ("response".equals(type)) {
                if (PAIRING_ID.equals(id)) {
                    handshakeDone = true;
                    mainHandler.removeCallbacks(registerWatchdog);
                    handlePairingResponse(payload);
                } else {
                    handleCommandResponse(id, payload);
                }
            } else if ("error".equals(type) && PAIRING_ID.equals(id)) {
                handshakeDone = true;
                mainHandler.removeCallbacks(registerWatchdog);
                handlePairingError(payload);
            }
        } catch (JSONException e) {
            DebugLog.e(TAG, "Malformed message from TV", e);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        mainHandler.removeCallbacks(registerWatchdog);
        DebugLog.d(TAG, "socket closed code=" + code + " reason=" + reason + " remote=" + remote);
        mainHandler.post(listener::onDisconnected);
    }

    @Override
    public void onError(Exception ex) {
        mainHandler.removeCallbacks(registerWatchdog);
        DebugLog.e(TAG, "socket error", ex);
        mainHandler.post(() -> listener.onError(ex == null ? "Unknown error" : ex.getMessage()));
    }

    // ------------------------------------------------------------------
    // Protocol handshake
    // ------------------------------------------------------------------

    /**
     * Send the registration request. When the app has no stored client-key the
     * register uses {@code forcePairing: true} so the TV shows its confirmation
     * prompt; some firmware silently closes the socket otherwise. Once a key
     * exists it is included and {@code forcePairing} is {@code false}.
     */
    private void sendRegister() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("forcePairing", clientKey.isEmpty());
            payload.put("pairingType", "PROMPT");
            payload.put("manifest", buildManifest());
            if (!clientKey.isEmpty()) {
                payload.put("client-key", clientKey);
            }
        } catch (JSONException ignored) {
        }
        sendMessage(PAIRING_ID, "register", payload);
    }

    /** Reply to a hello initiated by the TV (expected by older firmware). */
    private void replyHello() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("hello", "world");
        } catch (JSONException ignored) {
        }
        sendMessage(null, "hello", payload);
    }

    /** Submit the confirmation code shown on the TV screen. */
    public void pairWithPin(String pin) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("forcePairing", true);
            payload.put("pairingType", "PROMPT");
            payload.put("pairingKey", pin);
            payload.put("manifest", buildManifest());
            if (!clientKey.isEmpty()) {
                payload.put("client-key", clientKey);
            }
        } catch (JSONException ignored) {
        }
        sendMessage(PAIRING_ID, "register", payload);
    }

    private void handleRegistered(JSONObject payload) {
        if (payload == null) {
            return;
        }
        String key = payload.optString("client-key", "");
        if (!key.isEmpty()) {
            DebugLog.d(TAG, "registered, got client-key");
            mainHandler.post(() -> listener.onPaired(key));
        }
    }

    private void handlePairingResponse(JSONObject payload) {
        if (payload == null) {
            return;
        }
        String key = payload.optString("client-key", "");
        if (!key.isEmpty()) {
            DebugLog.d(TAG, "register response contains client-key");
            mainHandler.post(() -> listener.onPaired(key));
        } else {
            DebugLog.d(TAG, "register response, pairing required: " + payload.toString());
            mainHandler.post(listener::onPairingRequired);
        }
    }

    private void handlePairingError(JSONObject payload) {
        String error = payload == null ? "" : payload.optString("error", "");
        final String message = error.isEmpty() ? "Pairing failed or was cancelled" : error;
        DebugLog.e(TAG, "pairing error: " + message);
        mainHandler.post(() -> listener.onPairingError(message));
    }

    private void handleCommandResponse(String id, JSONObject payload) {
        Callback cb;
        synchronized (pendingRequests) {
            cb = pendingRequests.remove(id);
        }
        if (cb != null) {
            final Callback fcb = cb;
            final JSONObject fPayload = payload;
            mainHandler.post(() -> fcb.onResult(fPayload));
        }
        if (payload != null && (payload.has("volume") || payload.has("muted"))) {
            final int volume = payload.optInt("volume", -1);
            final boolean muted = payload.optBoolean("muted", false);
            mainHandler.post(() -> listener.onVolume(volume, muted));
        }
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    /** Sends an ssap:// request; returns the message id assigned to it. */
    public String sendCommand(String uri, JSONObject payload, Callback cb) {
        String id = String.valueOf(requestId.incrementAndGet());
        if (cb != null) {
            synchronized (pendingRequests) {
                pendingRequests.put(id, cb);
            }
        }
        JSONObject msg = new JSONObject();
        try {
            msg.put("id", id);
            msg.put("type", "request");
            msg.put("uri", uri);
            msg.put("payload", payload == null ? new JSONObject() : payload);
        } catch (JSONException ignored) {
        }
        send(msg.toString());
        return id;
    }

    public void volumeUp() {
        sendCommand("ssap://media.controls/volumeUp", null, null);
    }

    public void volumeDown() {
        sendCommand("ssap://media.controls/volumeDown", null, null);
    }

    public void toggleMute() {
        sendCommand("ssap://media.controls/toggleMute", null, null);
    }

    public void setMute(boolean mute) {
        JSONObject p = new JSONObject();
        try {
            p.put("mute", mute);
        } catch (JSONException ignored) {
        }
        sendCommand("ssap://media.controls/setMute", p, null);
    }

    public void getVolume() {
        sendCommand("ssap://media.controls/getVolume", null, null);
    }

    public void channelUp() {
        sendCommand("ssap://media.controls/channelUp", null, null);
    }

    public void channelDown() {
        sendCommand("ssap://media.controls/channelDown", null, null);
    }

    public void openHome() {
        JSONObject p = new JSONObject();
        try {
            p.put("id", "com.webos.app.home");
        } catch (JSONException ignored) {
        }
        sendCommand("ssap://system.launcher/launch", p, null);
    }

    public void closeApp() {
        JSONObject p = new JSONObject();
        try {
            p.put("id", "com.webos.app.home");
        } catch (JSONException ignored) {
        }
        sendCommand("ssap://system.launcher/close", p, null);
    }

    public void turnOff() {
        sendCommand("ssap://system/turnOff", null, null);
    }

    private void sendMessage(String id, String type, JSONObject payload) {
        JSONObject msg = new JSONObject();
        try {
            if (id != null) {
                msg.put("id", id);
            }
            msg.put("type", type);
            msg.put("payload", payload == null ? new JSONObject() : payload);
        } catch (JSONException ignored) {
        }
        DebugLog.d(TAG, "send: " + msg.toString());
        send(msg.toString());
    }
}
