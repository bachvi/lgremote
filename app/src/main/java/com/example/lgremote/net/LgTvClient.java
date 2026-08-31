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
    private static final String HELLO_ID = "hello";
    private static final String SYSINFO_ID = "get_sys_info";

    /**
     * Handshake mirrors the reference aiowebostv sequence, which works across
     * webOS 3.x-7.x: send hello, on the hello reply request system info, and
     * only then register. Newer webOS requires system info to be retrieved
     * before registration.
     */
    private static final int STEP_HELLO = 0;
    private static final int STEP_SYSINFO = 1;
    private static final int STEP_REGISTER = 2;
    private static final int STEP_DONE = 3;
    private int step = STEP_HELLO;

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

        /**
         * The TV is asking the user to confirm the connection on the TV screen.
         * When {@code pinRequired} is true the TV displays a 4-digit code that
         * must be entered in the app; otherwise the user just accepts on the TV
         * and the TV completes the pairing itself.
         */
        void onPairingRequired(boolean pinRequired);

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
    private volatile boolean registeredReceived = false;
    private volatile boolean muted = false;

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
                Collections.emptyMap(),
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
        DebugLog.d(TAG, "socket open, sending hello");
        lastMessageAt = System.currentTimeMillis();
        mainHandler.removeCallbacks(registerWatchdog);
        mainHandler.postDelayed(registerWatchdog, 2000);
        listener.onConnected();
        sendHello();
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
                if (step == STEP_HELLO) {
                    step = STEP_SYSINFO;
                    sendPreRegSystemInfo();
                } else {
                    replyHello();
                }
            } else if ("registered".equals(type)) {
                step = STEP_DONE;
                handshakeDone = true;
                registeredReceived = true;
                mainHandler.removeCallbacks(registerWatchdog);
                handleRegistered(payload);
            } else if ("response".equals(type)) {
                if (SYSINFO_ID.equals(id)) {
                    if (step == STEP_SYSINFO) {
                        step = STEP_REGISTER;
                        sendRegister();
                    } else {
                        handleCommandResponse(id, payload);
                    }
                } else if (PAIRING_ID.equals(id)) {
                    step = STEP_DONE;
                    handshakeDone = true;
                    mainHandler.removeCallbacks(registerWatchdog);
                    handlePairingResponse(payload);
                } else {
                    handleCommandResponse(id, payload);
                }
            } else if ("error".equals(type)) {
                if (SYSINFO_ID.equals(id)) {
                    if (step == STEP_SYSINFO) {
                        step = STEP_REGISTER;
                        sendRegister();
                    }
                } else if (PAIRING_ID.equals(id)) {
                    step = STEP_DONE;
                    handshakeDone = true;
                    mainHandler.removeCallbacks(registerWatchdog);
                    handlePairingError(payload);
                } else {
                    DebugLog.d(TAG, "command error " + id + ": " + json.optString("error", ""));
                    handleCommandResponse(id, payload);
                }
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

    /** First handshake step: announce ourselves. The TV replies with its hello. */
    private void sendHello() {
        sendMessage(HELLO_ID, "hello", new JSONObject());
    }

    /** Pre-registration system info request required by newer webOS. */
    private void sendPreRegSystemInfo() {
        JSONObject msg = new JSONObject();
        try {
            msg.put("id", SYSINFO_ID);
            msg.put("type", "request");
            msg.put("uri", "ssap://system.info/getSystemInfo");
            msg.put("payload", new JSONObject());
        } catch (JSONException ignored) {
        }
        DebugLog.d(TAG, "send: " + msg.toString());
        send(msg.toString());
    }

    /**
     * Send the registration request. When the app has no stored client-key the
     * register is sent with {@code pairingType: "PIN"} so the TV displays a
     * 4-digit code on screen (instead of a plain accept prompt) which the user
     * then enters in the app, and {@code forcePairing: true} so the TV starts a
     * fresh pairing prompt for the new app identity. The value "PIN" (not the
     * "PINS" used by newer firmware) matches what this TV advertises in its
     * hello {@code pairingTypes} list; sending "PINS" made it fall back to
     * "PROMPT".
     */
    private void sendRegister() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("forcePairing", clientKey.isEmpty());
            payload.put("pairingType", "PIN");
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

    /**
     * Submit the confirmation code shown on the TV screen. The PIN is not sent
     * via a second register (the TV rejects that with "409 register already in
     * progress"); instead it goes through the {@code ssap://pairing/setPin}
     * service, after which the TV pushes a {@code registered} message carrying
     * the client-key (mirrors ConnectSDK's WebOSTVServiceSocketClient).
     */
    public void pairWithPin(String pin) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("pin", pin);
        } catch (JSONException ignored) {
        }
        DebugLog.d(TAG, "submitting PIN to ssap://pairing/setPin");
        sendCommand("ssap://pairing/setPin", payload, result -> {
            if (result == null) {
                return;
            }
            String key = result.optString("client-key", "");
            if (!key.isEmpty()) {
                DebugLog.d(TAG, "setPin response contains client-key");
                mainHandler.post(() -> listener.onPaired(key));
                return;
            }
            DebugLog.d(TAG, "setPin response: " + result.toString());
            if (!result.optBoolean("returnValue", false)) {
                mainHandler.post(() -> listener.onPairingError("TV rejected the PIN code"));
            }
        });
        mainHandler.postDelayed(() -> {
            if (!registeredReceived) {
                DebugLog.e(TAG, "no registered confirmation within 10s of PIN submit");
                mainHandler.post(() -> listener.onPairingError("TV did not confirm the PIN code"));
            }
        }, 10000);
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
            String pairingType = payload.optString("pairingType", "PROMPT");
            final boolean pinRequired = "PIN".equals(pairingType)
                    || "PINS".equals(pairingType)
                    || "COMBINED".equals(pairingType);
            DebugLog.d(TAG, "register response, pairing required (" + pairingType + "): " + payload.toString());
            mainHandler.post(() -> listener.onPairingRequired(pinRequired));
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
        if (payload != null) {
            JSONObject volStatus = payload.optJSONObject("volumeStatus");
            if (payload.has("volume") || payload.has("muted") || volStatus != null) {
                final int volume = volStatus != null
                        ? volStatus.optInt("volume", -1)
                        : payload.optInt("volume", -1);
                final boolean isMuted = volStatus != null
                        ? volStatus.optBoolean("muted", false)
                        : payload.optBoolean("muted", false);
                muted = isMuted;
                mainHandler.post(() -> listener.onVolume(volume, isMuted));
            }
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
        sendCommand("ssap://audio/volumeUp", null, null);
    }

    public void volumeDown() {
        sendCommand("ssap://audio/volumeDown", null, null);
    }

    public void toggleMute() {
        setMute(!muted);
    }

    public void setMute(boolean mute) {
        JSONObject p = new JSONObject();
        try {
            p.put("mute", mute);
        } catch (JSONException ignored) {
        }
        sendCommand("ssap://audio/setMute", p, null);
    }

    public void getVolume() {
        sendCommand("ssap://audio/getVolume", null, null);
    }

    public void channelUp() {
        sendCommand("ssap://tv/channelUp", null, null);
    }

    public void channelDown() {
        sendCommand("ssap://tv/channelDown", null, null);
    }

    public void openHome(Callback cb) {
        JSONObject p = new JSONObject();
        try {
            p.put("id", "com.webos.app.home");
        } catch (JSONException ignored) {
        }
        sendCommand("ssap://system.launcher/launch", p, result -> {
            DebugLog.d(TAG, "launch home response: " + (result == null ? "null" : result.toString()));
            if (cb != null) {
                cb.onResult(result);
            }
        });
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
