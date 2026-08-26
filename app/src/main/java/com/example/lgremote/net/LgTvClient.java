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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket client for the main LG WebOS control socket (wss://&lt;tv&gt;:3000).
 * Handles the register / pairing handshake and sending "ssap://" commands.
 */
public class LgTvClient extends WebSocketClient {

    /** The 4-digit code typed on the TV is shown in this constant. */
    private static final String PAIRING_ID = "register_0";

    /**
     * Manifest format used by current webOS firmware (Home Assistant
     * aiowebostv reference). The legacy {@code signed}/{@code serial} block is
     * dropped because webOS 6.0+ rejects registers whose serial was already
     * used to issue a client-key on the TV.
     */
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
            manifest.put("appVersion", "1.1");
            manifest.put("manifestVersion", 1);
            manifest.put("permissions", permissions);
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

    /**
     * The handshake must proceed in order: client hello, then system info,
     * then register. The TV usually responds to the client hello, which drives
     * the next step, but a watchdog forces progress on firmware that never
     * answers the hello exchange.
     */
    private volatile boolean systemInfoSent = false;
    private volatile boolean registerSent = false;
    private final Runnable handshakeWatchdog = () -> {
        if (!registerSent) {
            if (!systemInfoSent) {
                sendSystemInfoRequest();
            }
            sendRegister();
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
            LgTvClient client = new LgTvClient(new URI(scheme + "://" + ip + ":" + port + "/"), clientKey, listener);
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
        listener.onConnected();
        // Newer webOS versions expect the client to start the hello exchange;
        // system info must be retrieved before registration.
        sendClientHello();
        mainHandler.postDelayed(handshakeWatchdog, 6000);
    }

    @Override
    public void onMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.optString("type");
            String id = json.optString("id");
            JSONObject payload = json.optJSONObject("payload");

            if ("hello".equals(type)) {
                sendSystemInfoRequest();
            } else if ("registered".equals(type)) {
                handleRegistered(payload);
            } else if ("response".equals(type)) {
                if (PAIRING_ID.equals(id)) {
                    handlePairingResponse(payload);
                } else if ("get_sys_info".equals(id)) {
                    sendRegister();
                } else {
                    handleCommandResponse(id, payload);
                }
            } else if ("error".equals(type) && PAIRING_ID.equals(id)) {
                handlePairingError(payload);
            }
        } catch (JSONException ignored) {
            // Malformed message from TV, ignore.
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        mainHandler.removeCallbacks(handshakeWatchdog);
        mainHandler.post(listener::onDisconnected);
    }

    @Override
    public void onError(Exception ex) {
        mainHandler.removeCallbacks(handshakeWatchdog);
        mainHandler.post(() -> listener.onError(ex == null ? "Unknown error" : ex.getMessage()));
    }

    // ------------------------------------------------------------------
    // Protocol handshake
    // ------------------------------------------------------------------

    /**
     * Send the registration request. The initial register uses
     * {@code forcePairing: false} as the reference client does; the TV replies
     * with a PROMPT when pairing is needed and completes registration on its
     * own (or accepts the pairingKey submitted through {@link #pairWithPin}).
     */
    private void sendRegister() {
        registerSent = true;
        mainHandler.removeCallbacks(handshakeWatchdog);
        JSONObject payload = new JSONObject();
        try {
            payload.put("forcePairing", false);
            payload.put("pairingType", "PROMPT");
            payload.put("manifest", buildManifest());
            if (!clientKey.isEmpty()) {
                payload.put("client-key", clientKey);
            }
        } catch (JSONException ignored) {
        }
        sendMessage(PAIRING_ID, "register", payload);
    }

    /** Start the hello exchange from the client side (expected by newer firmware). */
    private void sendClientHello() {
        JSONObject payload = new JSONObject();
        sendMessage("hello", "hello", payload);
    }

    /** webOS 6.0+ requires a system-info request before registration. */
    private void sendSystemInfoRequest() {
        if (systemInfoSent) {
            return;
        }
        systemInfoSent = true;
        JSONObject msg = new JSONObject();
        try {
            msg.put("id", "get_sys_info");
            msg.put("type", "request");
            msg.put("uri", "ssap://systeminfo/getSystemInfo");
            msg.put("payload", new JSONObject());
        } catch (JSONException ignored) {
        }
        send(msg.toString());
    }

    /** Submit the confirmation code shown on the TV screen. */
    public void pairWithPin(String pin) {
        registerSent = true;
        mainHandler.removeCallbacks(handshakeWatchdog);
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

    private void handlePairingError(JSONObject payload) {
        String error = payload == null ? "" : payload.optString("error", "");
        final String message = error.isEmpty() ? "Pairing failed or was cancelled" : error;
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
        send(msg.toString());
    }
}
