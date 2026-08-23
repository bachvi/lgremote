package com.example.lgremote.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists the list of known TVs (including the client key obtained after pairing)
 * in SharedPreferences.
 */
public class TvRepository {

    private static final String PREFS = "tv_repo";
    private static final String KEY_DEVICES = "devices";

    private final SharedPreferences prefs;

    public TvRepository(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<TvDevice> load() {
        List<TvDevice> list = new ArrayList<>();
        String raw = prefs.getString(KEY_DEVICES, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                list.add(TvDevice.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public synchronized void save(List<TvDevice> devices) {
        JSONArray arr = new JSONArray();
        for (TvDevice d : devices) {
            try {
                arr.put(d.toJson());
            } catch (Exception ignored) {
            }
        }
        prefs.edit().putString(KEY_DEVICES, arr.toString()).apply();
    }
}
