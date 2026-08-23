package com.example.lgremote.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.lgremote.R;
import com.example.lgremote.data.TvDevice;

import java.util.List;

public class TvAdapter extends BaseAdapter {

    public interface OnConnectListener {
        void onConnect(TvDevice device);
    }

    private final List<TvDevice> items;
    private final LayoutInflater inflater;
    private OnConnectListener connectListener;
    private String activeIp;

    public TvAdapter(LayoutInflater inflater, List<TvDevice> items) {
        this.inflater = inflater;
        this.items = items;
    }

    public void setConnectListener(OnConnectListener l) {
        this.connectListener = l;
    }

    public void setActiveIp(String ip) {
        this.activeIp = ip;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public TvDevice getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = inflater.inflate(R.layout.item_tv, parent, false);
        }

        TvDevice device = getItem(position);

        ImageView tvIcon = v.findViewById(R.id.tvIcon);
        TextView name = v.findViewById(R.id.tvName);
        TextView status = v.findViewById(R.id.tvStatus);
        TextView connect = v.findViewById(R.id.btnConnect);

        name.setText(device.getDisplayName());

        StringBuilder sb = new StringBuilder();
        sb.append(device.ip);
        if (device.isPaired()) {
            sb.append("  ·  ").append("Paired");
        }
        status.setText(sb.toString());

        boolean isActive = device.ip != null && device.ip.equals(activeIp);
        if (isActive) {
            connect.setText("Connected");
            tvIcon.setAlpha(1f);
        } else {
            connect.setText(R.string.connect);
            tvIcon.setAlpha(0.7f);
        }

        connect.setOnClickListener(v1 -> {
            if (connectListener != null && !isActive) {
                connectListener.onConnect(device);
            }
        });

        v.setOnClickListener(v12 -> {
            if (connectListener != null && !isActive) {
                connectListener.onConnect(device);
            }
        });

        return v;
    }
}
