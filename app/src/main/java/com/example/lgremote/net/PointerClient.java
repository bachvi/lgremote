package com.example.lgremote.net;

import android.os.Handler;
import android.os.Looper;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.extensions.IExtension;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.protocols.Protocol;

import java.net.URI;
import java.util.Collections;

/**
 * WebSocket client for the TV's pointer/network-input socket, used for the
 * touchpad (move / click / scroll) and hardware-style buttons (OK, BACK, ...).
 *
 * The network-input socket does NOT use the JSON "ssap" framing of the main
 * socket; it speaks a plain-text line protocol, one command per message:
 * <pre>
 *   type:button
 *   name:UP
 *   </pre>
 * <pre>
 *   type:move
 *   dx:10
 *   dy:0
 *   down:0
 *   </pre>
 * <pre>
 *   type:click
 *   </pre>
 * <pre>
 *   type:scroll
 *   dx:0
 *   dy:3
 *   </pre>
 *  Each command's last field is terminated by a single newline.  No trailing
 *  blank line is appended: some webOS 4.x network-input sockets reject a
 *  trailing blank line with WebSocket code 1008 "invalid message".
 */
public class PointerClient extends WebSocketClient {

    private static final String TAG = "PointerClient";

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
                Collections.emptyMap(),
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
            DebugLog.e(TAG, "connectTo failed", e);
            return null;
        }
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        DebugLog.d(TAG, "pointer socket open");
        mainHandler.post(listener::onOpen);
    }

    @Override
    public void onMessage(String message) {
        // The pointer socket currently only sends acknowledgements; nothing to handle.
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        DebugLog.d(TAG, "pointer socket closed code=" + code + " reason=" + reason);
        mainHandler.post(() -> listener.onClose(reason));
    }

    @Override
    public void onError(Exception ex) {
        DebugLog.e(TAG, "pointer socket error", ex);
        mainHandler.post(() -> listener.onError(ex == null ? "Pointer error" : ex.getMessage()));
    }

    public void move(int dx, int dy) {
        sendLine("type:move", "dx:" + dx, "dy:" + dy, "down:0");
    }

    public void wheel(int dx, int dy) {
        sendLine("type:scroll", "dx:" + dx, "dy:" + dy);
    }

    public void click() {
        sendLine("type:click");
    }

    public void button(String name) {
        sendLine("type:button", "name:" + name);
    }

    private void sendLine(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        String message = sb.toString();
        DebugLog.d(TAG, "send: " + message.replace("\n", " | "));
        send(message);
    }
}
