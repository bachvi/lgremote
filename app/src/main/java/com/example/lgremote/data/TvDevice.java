package com.example.lgremote.data;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A single LG WebOS TV that the app knows about.
 */
public class TvDevice {

    public String id = "";
    public String name = "";
    public String ip = "";
    public String clientKey = "";
    public String mac = "";
    public long lastSeen;

    public TvDevice() {
    }

    public TvDevice(String id, String name, String ip) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.ip = ip == null ? "" : ip;
    }

    public boolean isPaired() {
        return clientKey != null && !clientKey.isEmpty();
    }

    public String getDisplayName() {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        return "LG TV (" + ip + ")";
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id == null ? "" : id);
        o.put("name", name == null ? "" : name);
        o.put("ip", ip == null ? "" : ip);
        o.put("clientKey", clientKey == null ? "" : clientKey);
        o.put("mac", mac == null ? "" : mac);
        o.put("lastSeen", lastSeen);
        return o;
    }

    public static TvDevice fromJson(JSONObject o) {
        TvDevice d = new TvDevice();
        d.id = o.optString("id", "");
        d.name = o.optString("name", "");
        d.ip = o.optString("ip", "");
        d.clientKey = o.optString("clientKey", "");
        d.mac = o.optString("mac", "");
        d.lastSeen = o.optLong("lastSeen", 0L);
        return d;
    }
}
