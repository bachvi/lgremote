package com.example.lgremote.net;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.MulticastLock;
import android.os.Handler;
import android.os.Looper;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.example.lgremote.data.TvDevice;

/**
 * Discovers LG WebOS TVs on the local network using UPnP/SSDP M-SEARCH.
 */
public class DiscoveryManager {

    public interface DiscoveryListener {
        void onTvFound(TvDevice tv);

        void onScanFinished(List<TvDevice> tvs);

        void onError(String message);
    }

    private static final String SSDP_ADDRESS = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final int SCAN_DURATION_MS = 3500;
    private static final String TARGET_MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean scanning = false;
    private Thread scanThread;

    public DiscoveryManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isScanning() {
        return scanning;
    }

    public void startScan(final DiscoveryListener listener) {
        if (scanning) {
            return;
        }
        scanning = true;

        scanThread = new Thread(() -> {
            MulticastLock multicastLock = null;
            DatagramSocket socket = null;
            final Map<String, TvDevice> found = new LinkedHashMap<>();

            try {
                WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    multicastLock = wifi.createMulticastLock("lg-remote-ssdp");
                    multicastLock.setReferenceCounted(false);
                    multicastLock.acquire();
                }

                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0));
                socket.setSoTimeout(1200);

                InetAddress group = InetAddress.getByName(SSDP_ADDRESS);
                sendSearch(socket, group, TARGET_MEDIA_RENDERER);
                sendSearch(socket, group, "ssdp:all");

                long deadline = System.currentTimeMillis() + SCAN_DURATION_MS;
                while (scanning && System.currentTimeMillis() < deadline) {
                    try {
                        byte[] buffer = new byte[8192];
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        socket.receive(packet);
                        String response = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                        String ip = packet.getAddress().getHostAddress();
                        if (isLgTvResponse(response)) {
                            TvDevice tv = new TvDevice(null, null, ip);
                            tv.lastSeen = System.currentTimeMillis();
                            found.put(ip, tv);
                        }
                    } catch (SocketTimeoutException ignored) {
                        // Wait for more responses.
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                mainHandler.post(() -> listener.onError(e.getMessage()));
            } finally {
                if (multicastLock != null && multicastLock.isHeld()) {
                    multicastLock.release();
                }
                if (socket != null) {
                    socket.close();
                }
            }

            enrichNames(found);
            scanning = false;
            mainHandler.post(() -> listener.onScanFinished(new ArrayList<>(found.values())));
        }, "ssdp-scan");
        scanThread.start();
    }

    public void cancelScan() {
        scanning = false;
        if (scanThread != null) {
            scanThread.interrupt();
        }
    }

    private void sendSearch(DatagramSocket socket, InetAddress group, String target) throws Exception {
        String message = "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: " + target + "\r\n"
                + "\r\n";
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(data, data.length, group, SSDP_PORT));
    }

    private boolean isLgTvResponse(String response) {
        if (response == null) {
            return false;
        }
        String lower = response.toLowerCase(Locale.US);
        if (!lower.startsWith("http/1.1 200") && !lower.contains("http/1.1 200")) {
            return false;
        }
        return lower.contains("webos")
                || (lower.contains("lg") && lower.contains("media"))
                || lower.contains("lg-fr");
    }

    private void enrichNames(Map<String, TvDevice> found) {
        for (TvDevice tv : found.values()) {
            try {
                // Re-query each device's descriptor to obtain the friendly name.
                TvDevice named = fetchDeviceInfo(tv.ip);
                if (named != null) {
                    if (named.name != null && !named.name.isEmpty()) {
                        tv.name = named.name;
                    }
                    if (named.id != null && !named.id.isEmpty()) {
                        tv.id = named.id;
                    }
                }
                if (tv.name == null || tv.name.isEmpty()) {
                    tv.name = null;
                }
            } catch (Exception ignored) {
            }
        }
    }

    private TvDevice fetchDeviceInfo(String ip) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + ip + ":5000/upnp/desc/desc.xml");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestProperty("User-Agent", "LG-Remote/1.0");
            int code = conn.getResponseCode();
            if (code != 200) {
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                return parseDescriptor(in);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private TvDevice parseDescriptor(InputStream in) {
        TvDevice tv = new TvDevice();
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, "UTF-8");
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG) {
                    continue;
                }
                String tag = parser.getName();
                if ("friendlyName".equals(tag)) {
                    tv.name = parser.nextText();
                } else if ("UDN".equals(tag)) {
                    tv.id = parser.nextText();
                }
            }
        } catch (Exception ignored) {
        }
        return tv;
    }
}
