package com.example.lgremote;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.lgremote.data.TvDevice;
import com.example.lgremote.net.LgTvConnection;
import com.example.lgremote.ui.TouchpadView;

public class RemoteActivity extends AppCompatActivity implements LgTvConnection.Listener {

    private LgTvConnection connection;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView connStatus;
    private TextView volumeText;
    private ProgressBar volumeBar;
    private com.google.android.material.button.MaterialButton btnMute;

    private boolean muted = false;

    private final Runnable volumePoller = new Runnable() {
        @Override
        public void run() {
            if (connection.isConnected()) {
                connection.getVolume();
            }
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote);

        connection = LgTvConnection.get(this);

        TextView tvName = findViewById(R.id.tvName);
        connStatus = findViewById(R.id.connStatus);
        volumeText = findViewById(R.id.volumeText);
        volumeBar = findViewById(R.id.volumeBar);
        btnMute = findViewById(R.id.btnMute);

        TvDevice device = connection.getDevice();
        if (device == null) {
            finish();
            return;
        }
        tvName.setText(device.getDisplayName());

        setupButtons();
        setupTouchpad();
    }

    @Override
    protected void onResume() {
        super.onResume();
        connection.setListener(this);
        if (connection.isConnected()) {
            setConnectedUi(true);
            connection.getVolume();
        }
        handler.postDelayed(volumePoller, 3000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(volumePoller);
        connection.setListener(null);
        super.onPause();
    }

    private void setupButtons() {
        findViewById(R.id.btnBackToList).setOnClickListener(v -> finish());
        findViewById(R.id.btnDisconnect).setOnClickListener(v -> {
            connection.disconnect();
            finish();
        });
        findViewById(R.id.btnDebug).setOnClickListener(v -> showDebugDialog());

        findViewById(R.id.btnVolumeDown).setOnClickListener(v -> {
            connection.volumeDown();
            refreshVolumeSoon();
        });
        findViewById(R.id.btnVolumeUp).setOnClickListener(v -> {
            connection.volumeUp();
            refreshVolumeSoon();
        });

        btnMute.setOnClickListener(v -> {
            connection.setMute(!muted);
            handler.postDelayed(() -> {
                if (connection.isConnected()) {
                    connection.getVolume();
                }
            }, 300);
        });

        findViewById(R.id.btnChannelDown).setOnClickListener(v -> connection.channelDown());
        findViewById(R.id.btnChannelUp).setOnClickListener(v -> connection.channelUp());

        findViewById(R.id.btnUp).setOnClickListener(v -> connection.navigate("UP"));
        findViewById(R.id.btnDown).setOnClickListener(v -> connection.navigate("DOWN"));
        findViewById(R.id.btnLeft).setOnClickListener(v -> connection.navigate("LEFT"));
        findViewById(R.id.btnRight).setOnClickListener(v -> connection.navigate("RIGHT"));
        findViewById(R.id.btnOk).setOnClickListener(v -> connection.navigate("OK"));

        findViewById(R.id.btnHome).setOnClickListener(v -> connection.home());
        findViewById(R.id.btnBack).setOnClickListener(v -> connection.back());

        findViewById(R.id.btnPower).setOnClickListener(v -> confirmPowerOff());
    }

    private void setupTouchpad() {
        TouchpadView touchpad = findViewById(R.id.touchpad);
        touchpad.setListener(new TouchpadView.Listener() {
            @Override
            public void onMove(int dx, int dy) {
                connection.pointerMove(dx, dy);
            }

            @Override
            public void onScroll(int dy) {
                connection.pointerScroll(dy);
            }

            @Override
            public void onClick() {
                connection.pointerClick();
            }
        });
    }

    private void showDebugDialog() {
        String raw = com.example.lgremote.net.DebugLog.dump();
        final String dump = raw.isEmpty() ? getString(R.string.status_initial) : raw;
        TextView text = new TextView(this);
        text.setTextIsSelectable(true);
        text.setText(dump);
        text.setTextSize(10f);
        text.setTypeface(Typeface.MONOSPACE);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle(R.string.debug_title)
                .setView(text)
                .setPositiveButton(R.string.debug_copy, (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("LG remote debug log", dump));
                        Toast.makeText(this, R.string.debug_copied, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.debug_clear, (d, w) -> {
                    com.example.lgremote.net.DebugLog.clear();
                    d.dismiss();
                })
                .show();
    }

    private void refreshVolumeSoon() {
        handler.postDelayed(() -> {
            if (connection.isConnected()) {
                connection.getVolume();
            }
        }, 250);
    }

    private void confirmPowerOff() {
        TvDevice device = connection.getDevice();
        String name = device != null ? device.getDisplayName() : "";
        new AlertDialog.Builder(this)
                .setTitle(R.string.power_confirm_title)
                .setMessage(getString(R.string.power_confirm_message, name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.turn_off, (d, w) -> {
                    connection.turnOff();
                    finish();
                })
                .show();
    }

    private void setConnectedUi(boolean connected) {
        if (connected) {
            connStatus.setText(R.string.status_connected);
            findViewById(R.id.connDot).setBackgroundResource(R.drawable.bg_status_dot_online);
        } else {
            connStatus.setText(R.string.remote_disconnected);
            findViewById(R.id.connDot).setBackgroundResource(R.drawable.bg_status_dot_warning);
        }
    }

    // ------------------------------------------------------------------
    // LgTvConnection.Listener
    // ------------------------------------------------------------------

    @Override
    public void onStateChanged(int state) {
        if (state == LgTvConnection.STATE_CONNECTED) {
            setConnectedUi(true);
            connection.getVolume();
        } else {
            setConnectedUi(false);
        }
    }

    @Override
    public void onVolume(int volume, boolean isMuted) {
        this.muted = isMuted;
        if (volume >= 0) {
            volumeText.setText(getString(R.string.volume_percent, volume));
            volumeBar.setProgress(volume);
        }
        btnMute.setText(isMuted ? R.string.unmute : R.string.mute);
    }

    @Override
    public void onError(String message) {
        Toast.makeText(this, getString(R.string.remote_error, message), Toast.LENGTH_LONG).show();
    }
}
