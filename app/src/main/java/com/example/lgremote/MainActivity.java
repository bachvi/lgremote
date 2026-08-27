package com.example.lgremote;

import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.lgremote.data.TvDevice;
import com.example.lgremote.data.TvRepository;
import com.example.lgremote.net.DebugLog;
import com.example.lgremote.net.DiscoveryManager;
import com.example.lgremote.net.LgTvConnection;
import com.example.lgremote.ui.TvAdapter;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity
        implements LgTvConnection.Listener, TvAdapter.OnConnectListener, TvAdapter.OnForgetListener {

    private TvRepository repository;
    private final List<TvDevice> devices = new ArrayList<>();
    private TvAdapter adapter;
    private LgTvConnection connection;
    private DiscoveryManager discovery;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView statusText;
    private TextView emptyText;
    private TextView debugText;
    private ListView tvList;
    private com.google.android.material.button.MaterialButton btnScan;

    private ProgressDialog progressDialog;
    private AlertDialog pairingDialog;
    private boolean firstScanDone = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        repository = new TvRepository(this);
        connection = LgTvConnection.get(this);
        discovery = new DiscoveryManager(this);

        statusText = findViewById(R.id.statusText);
        emptyText = findViewById(R.id.emptyText);
        tvList = findViewById(R.id.tvList);
        btnScan = findViewById(R.id.btnScan);
        debugText = findViewById(R.id.debugText);
        findViewById(R.id.debugPanel).setOnClickListener(v -> showDebug());
        findViewById(R.id.btnCopyDebug).setOnClickListener(v -> copyDebug());

        adapter = new TvAdapter(LayoutInflater.from(this), devices);
        adapter.setConnectListener(this);
        adapter.setForgetListener(this);
        tvList.setAdapter(adapter);

        btnScan.setOnClickListener(v -> startScan());
        findViewById(R.id.btnAdd).setOnClickListener(v -> showAddTvDialog());

        reloadDevices();
    }

    @Override
    protected void onResume() {
        super.onResume();
        connection.setListener(this);
        adapter.setActiveIp(connection.isConnected() && connection.getDevice() != null
                ? connection.getDevice().ip : null);
        adapter.setConnectingIp(null);
        if (!firstScanDone) {
            firstScanDone = true;
            if (devices.isEmpty()) {
                handler.postDelayed(this::startScan, 400);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        connection.setListener(null);
        discovery.cancelScan();
        dismissProgress();
        if (pairingDialog != null) {
            pairingDialog.dismiss();
            pairingDialog = null;
        }
        super.onPause();
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    private void startScan() {
        if (discovery.isScanning()) {
            return;
        }
        btnScan.setEnabled(false);
        statusText.setText(R.string.status_scanning);
        discovery.startScan(new DiscoveryManager.DiscoveryListener() {
            @Override
            public void onTvFound(TvDevice tv) {
            }

            @Override
            public void onScanFinished(List<TvDevice> found) {
                btnScan.setEnabled(true);
                mergeFound(found);
                statusText.setText(getString(R.string.status_scan_found, found.size()));
            }

            @Override
            public void onError(String message) {
                btnScan.setEnabled(true);
                statusText.setText(R.string.status_initial);
            }
        });
    }

    private void mergeFound(List<TvDevice> found) {
        for (TvDevice tv : found) {
            boolean exists = false;
            for (TvDevice d : devices) {
                if (d.ip != null && d.ip.equals(tv.ip)) {
                    if (!TextUtils.isEmpty(tv.name)) {
                        d.name = tv.name;
                    }
                    if (!TextUtils.isEmpty(tv.id)) {
                        d.id = tv.id;
                    }
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                devices.add(tv);
            }
        }
        adapter.notifyDataSetChanged();
        updateEmptyState();
        repository.save(new ArrayList<>(devices));
    }

    // ------------------------------------------------------------------
    // Manual add
    // ------------------------------------------------------------------

    private void confirmForget(TvDevice device) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.forget_title)
                .setMessage(getString(R.string.forget_message, device.getDisplayName()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.forget_confirm, (d, w) -> {
                    if (connection.getDevice() != null && device.ip != null
                            && device.ip.equals(connection.getDevice().ip)) {
                        connection.disconnect();
                    }
                    devices.remove(device);
                    adapter.notifyDataSetChanged();
                    updateEmptyState();
                    repository.save(new ArrayList<>(devices));
                })
                .show();
    }

    private void showAddTvDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_add_tv, null);
        EditText nameInput = view.findViewById(R.id.inputName);
        EditText ipInput = view.findViewById(R.id.inputIp);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.add_tv)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.add_tv_hint, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String ip = ipInput.getText().toString().trim();
                    if (!isValidIp(ip)) {
                        ipInput.setError(getString(R.string.add_tv_ip));
                        return;
                    }
                    String name = nameInput.getText().toString().trim();
                    TvDevice tv = new TvDevice(null, name.isEmpty() ? null : name, ip);
                    devices.add(tv);
                    adapter.notifyDataSetChanged();
                    updateEmptyState();
                    repository.save(new ArrayList<>(devices));
                    dialog.dismiss();
                    connectToTv(tv);
                }));
        dialog.show();
    }

    private boolean isValidIp(String ip) {
        if (TextUtils.isEmpty(ip)) {
            return false;
        }
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String p : parts) {
            try {
                int v = Integer.parseInt(p);
                if (v < 0 || v > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Connecting / pairing
    // ------------------------------------------------------------------

    private void connectToTv(TvDevice tv) {
        DebugLog.clear();
        DebugLog.d("MainActivity", "connect to " + tv.ip + (tv.clientKey != null && !tv.clientKey.isEmpty() ? " (has key)" : " (no key)"));
        findViewById(R.id.debugPanel).setVisibility(View.GONE);
        adapter.setConnectingIp(tv.ip);
        adapter.setActiveIp(null);
        showProgress(getString(R.string.connecting_to, tv.getDisplayName()));
        connection.connect(tv);
    }

    private void showPairingDialog() {
        if (connection.isPairingPinRequired()) {
            showPinDialog();
        } else {
            showConfirmOnTvDialog();
        }
    }

    private void showConfirmOnTvDialog() {
        pairingDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.pairing_title_confirm)
                .setMessage(R.string.pairing_message_confirm)
                .setNegativeButton(R.string.cancel, (d, w) -> connection.disconnect())
                .setCancelable(false)
                .create();
        pairingDialog.show();
    }

    private void showPinDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_pairing, null);
        EditText pinInput = view.findViewById(R.id.inputPin);

        pairingDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.pairing_title)
                .setView(view)
                .setNegativeButton(R.string.cancel, (d, w) -> connection.disconnect())
                .setPositiveButton(R.string.pairing_confirm, null)
                .setCancelable(false)
                .create();

        pairingDialog.setOnShowListener(d -> pairingDialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String pin = pinInput.getText().toString().trim();
                    if (pin.length() < 4) {
                        pinInput.setError(getString(R.string.pairing_hint));
                        return;
                    }
                    pairingDialog.dismiss();
                    pairingDialog = null;
                    showProgress(getString(R.string.pairing));
                    connection.pair(pin);
                }));
        pairingDialog.show();

        pinInput.requestFocus();
        handler.postDelayed(() -> {
            try {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(pinInput, InputMethodManager.SHOW_IMPLICIT);
                }
            } catch (Exception ignored) {
            }
        }, 250);
    }

    private void onConnected() {
        startActivity(new Intent(this, RemoteActivity.class));
    }

    private void showDebug() {
        if (debugText == null) {
            return;
        }
        String dump = DebugLog.dump();
        if (dump.isEmpty()) {
            dump = getString(R.string.status_initial);
        }
        debugText.setText(dump);
        findViewById(R.id.debugPanel).setVisibility(View.VISIBLE);
        ((ScrollView) findViewById(R.id.debugScroll)).post(() ->
                ((ScrollView) findViewById(R.id.debugScroll)).fullScroll(View.FOCUS_DOWN));
    }

    private void copyDebug() {
        String dump = DebugLog.dump();
        if (dump.isEmpty()) {
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("LG remote debug log", dump));
            Toast.makeText(this, R.string.debug_copied, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------
    // Dialogs
    // ------------------------------------------------------------------

    private void showProgress(String message) {
        dismissProgress();
        progressDialog = new ProgressDialog(this);
        progressDialog.setMessage(message);
        progressDialog.setCancelable(false);
        progressDialog.setIndeterminate(true);
        progressDialog.show();
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    // ------------------------------------------------------------------
    // List helpers
    // ------------------------------------------------------------------

    private void reloadDevices() {
        devices.clear();
        devices.addAll(repository.load());
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void updateEmptyState() {
        boolean empty = devices.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        tvList.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            statusText.setText(R.string.status_none_found);
        }
    }

    // ------------------------------------------------------------------
    // LgTvConnection.Listener
    // ------------------------------------------------------------------

    @Override
    public void onStateChanged(int state) {
        switch (state) {
            case LgTvConnection.STATE_PAIRING:
                dismissProgress();
                showPairingDialog();
                break;
            case LgTvConnection.STATE_CONNECTED:
                dismissProgress();
                if (pairingDialog != null) {
                    pairingDialog.dismiss();
                    pairingDialog = null;
                }
                adapter.setActiveIp(connection.getDevice() != null ? connection.getDevice().ip : null);
                adapter.setConnectingIp(null);
                onConnected();
                break;
            case LgTvConnection.STATE_DISCONNECTED:
                dismissProgress();
                adapter.setActiveIp(null);
                adapter.setConnectingIp(null);
                showDebug();
                break;
            case LgTvConnection.STATE_CONNECTING:
            default:
                break;
        }
    }

    @Override
    public void onVolume(int volume, boolean muted) {
    }

    @Override
    public void onError(String message) {
        dismissProgress();
        Toast.makeText(this, getString(R.string.remote_error, message), Toast.LENGTH_LONG).show();
        adapter.setActiveIp(null);
        adapter.setConnectingIp(null);
        showDebug();
    }

    // ------------------------------------------------------------------
    // TvAdapter.OnConnectListener
    // ------------------------------------------------------------------

    @Override
    public void onConnect(TvDevice device) {
        connectToTv(device);
    }

    @Override
    public void onForget(TvDevice device) {
        confirmForget(device);
    }
}
