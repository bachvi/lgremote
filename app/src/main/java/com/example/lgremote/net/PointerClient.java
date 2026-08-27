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

/**
 * WebSocket client for the TV's pointer/network-input socket, used for the
 * touchpad (move / click / scroll) and hardware-style buttons (OK, BACK, ...).
 */
public class PointerClient extends WebSocketClient {

    public interface Listener {
        void onOpen();

        void onClose(String reason);

        void onError(String message);
    }

    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public PointerClient(URI serverUri, Listener listener) {
        super(serverUri,
                new Draft_6455(Collections.<IExtension>emptyList(),
                        Collections.singletonList(new Protocol("lgtv"))),
                Collections.singletonMap("Origin", "http://localhost"),
                5000);
        this.listener = listener;
    }

    public static PointerClient connectTo(String ip, int port, boolean ssl, Listener listener) {
        try {
            String scheme = ssl ? "wss" : "ws";
            PointerClient client = new PointerClient(new URI(scheme + "://" + ip + ":" + port + "/"), listener);
            if (ssl) {
                client.setSocketFactory(SslUtils.trustAllSslSocketFactory());
            }
            client.setConnectionLostTimeout(15);
            client.connect();
            return client;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        mainHandler.post(listener::onOpen);
    }

    @Override
    public void onMessage(String message) {
        // The pointer socket currently only sends acknowledgements; nothing to handle.
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        mainHandler.post(() -> listener.onClose(reason));
    }

    @Override
    public void onError(Exception ex) {
        mainHandler.post(() -> listener.onError(ex == null ? "Pointer error" : ex.getMessage()));
    }

    public void move(int dx, int dy) {
        sendTouch("move", dx, dy);
    }

    public void wheel(int dx, int dy) {
        sendTouch("wheel", dx, dy);
    }

    public void click() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("type", "click");
        } catch (JSONException ignored) {
        }
        sendMessage("touch", payload);
    }

    public void button(String name) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("name", name);
        } catch (JSONException ignored) {
        }
        sendMessage("button", payload);
    }

    private void sendTouch(String type, int dx, int dy) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("type", type);
            payload.put("dx", dx);
            payload.put("dy", dy);
        } catch (JSONException ignored) {
        }
        sendMessage("touch", payload);
    }

    private void sendMessage(String type, JSONObject payload) {
        JSONObject msg = new JSONObject();
        try {
            msg.put("type", type);
            msg.put("payload", payload);
        } catch (JSONException ignored) {
        }
        send(msg.toString());
    }
}
