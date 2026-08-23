package com.example.lgremote.net;

import android.os.Handler;
import android.os.Looper;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.extensions.IExtension;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.protocols.Protocol;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket client for the main LG WebOS control socket (wss://&lt;tv&gt;:3000).
 * Handles the register / pairing handshake and sending "ssap://" commands.
 */
public class LgTvClient extends WebSocketClient {

    /** The 4-digit code typed on the TV is shown in this constant. */
    private static final String PAIRING_ID = "register_0";

    private static final String MANIFEST = "{"
            + "\"manifestVersion\":1,"
            + "\"appVersion\":\"1.0.0\","
            + "\"signed\":{"
            + "\"created\":\"20260101\","
            + "\"appId\":\"com.example.lgremote\","
            + "\"vendorId\":\"com.example\","
            + "\"localizedAppNames\":{\"\":\"LG TV Remote\"},"
            + "\"localizedVendorNames\":{\"\":\"LG Remote\"},"
            + "\"permissions\":[\"LAUNCH\",\"APP_TO_APP\",\"CONTROL_AUDIO\",\"CONTROL_INPUT\","
            + "\"READ_INPUT_DEVICE_LIST\",\"WRITE_NOTIFICATION_ALERT\",\"CONTROL_POWER\"],"
            + "\"serial\":\"7a2b9c41\""
            + "},"
            + "\"permissions\":[\"LAUNCH\",\"APP_TO_APP\",\"CONTROL_AUDIO\",\"CONTROL_INPUT\","
            + "\"READ_INPUT_DEVICE_LIST\",\"WRITE_NOTIFICATION_ALERT\",\"CONTROL_POWER\"]"
            + "}";

    public interface Listener {
        void onConnected();

        /** The TV is showing a confirmation code and is waiting for the app to submit it. */
        void onPairingRequired();

        /** Pairing succeeded; the returned key must be persisted for future connections. */
        void onPaired(String clientKey);

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

    public LgTvClient(URI serverUri, String clientKey, Listener listener) {
        super(serverUri,
                new Draft_6455(Collections.<IExtension>emptyList(),
                        Collections.singletonList(new Protocol("lgtv"))),
                Collections.singletonMap("Origin", "http://localhost"),
                6000);
        this.listener = listener;
        this.clientKey = clientKey == null ? "" : clientKey;
    }

    public static LgTvClient connectTo(String ip, String clientKey, Listener listener) {
        try {
            LgTvClient client = new LgTvClient(new URI("wss://" + ip + ":3000/"), clientKey, listener);
            client.setSocketFactory(SslUtils.trustAllSslSocketFactory());
            client.setConnectionLostTimeout(15);
            client.connect();
            return client;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        sendRegister();
        listener.onConnected();
    }

    @Override
    public void onMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.optString("type");
            String id = json.optString("id");
            JSONObject payload = json.optJSONObject("payload");

            if ("hello".equals(type)) {
                replyHello();
            } else if ("registered".equals(type)) {
                handleRegistered(payload);
            } else if ("response".equals(type)) {
                if (PAIRING_ID.equals(id)) {
                    handlePairingResponse(payload);
                } else {
                    handleCommandResponse(id, payload);
                }
            }
        } catch (JSONException ignored) {
            // Malformed message from TV, ignore.
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        mainHandler.post(listener::onDisconnected);
    }

    @Override
    public void onError(Exception ex) {
        mainHandler.post(() -> listener.onError(ex == null ? "Unknown error" : ex.getMessage()));
    }

    // ------------------------------------------------------------------
    // Protocol handshake
    // ------------------------------------------------------------------

    private void sendRegister() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("forcePairing", false);
            payload.put("pairingType", "PROMPT");
            payload.put("manifest", new JSONObject(MANIFEST));
            if (!clientKey.isEmpty()) {
                payload.put("client-key", clientKey);
            }
        } catch (JSONException ignored) {
        }
        sendMessage(PAIRING_ID, "register", payload);
    }

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
            payload.put("forcePairing", false);
            payload.put("pairingType", "PROMPT");
            payload.put("pairingKey", pin);
            payload.put("manifest", new JSONObject(MANIFEST));
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
            mainHandler.post(() -> listener.onPaired(key));
        }
    }

    private void handlePairingResponse(JSONObject payload) {
        if (payload == null) {
            return;
        }
        String key = payload.optString("client-key", "");
        if (!key.isEmpty()) {
            mainHandler.post(() -> listener.onPaired(key));
        } else {
            mainHandler.post(listener::onPairingRequired);
        }
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
        send(msg.toString());
    }
}
