package com.example.medibook.system;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

/** Foreground/dashboard-only academic demonstration of AIRPLANE_MODE_CHANGED and BATTERY_LOW.
 * Both are protected system broadcasts; there is no manifest receiver or background service. */
public final class PatientSystemNotices {
    public interface Listener { void onAirplaneState(boolean enabled); void onBatteryLow(); }
    private final Context context;
    private final SystemNoticeState state;
    private final Listener listener;
    private boolean registered;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (intent == null) return;
            if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(intent.getAction())) refreshAirplane();
            else if (Intent.ACTION_BATTERY_LOW.equals(intent.getAction())) battery(true);
            else if (Intent.ACTION_BATTERY_OKAY.equals(intent.getAction())) battery(false);
        }
    };
    public PatientSystemNotices(Context context, SystemNoticeState state, Listener listener) {
        this.context = context; this.state = state; this.listener = listener;
    }
    public void start() {
        if (registered) return;
        IntentFilter filter = new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(Intent.ACTION_BATTERY_LOW); filter.addAction(Intent.ACTION_BATTERY_OKAY);
        // EXPORTED accepts system senders on modern Android. These actions cannot be sent by apps.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
        registered = true;
        refreshAirplane();
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            boolean low = Build.VERSION.SDK_INT >= 28 ? battery.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false)
                    : scale > 0 && level >= 0 && level * 100 / scale <= 15 && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0;
            battery(low);
        }
    }
    public void stop() {
        if (!registered) return;
        context.unregisterReceiver(receiver); registered = false;
    }
    private void refreshAirplane() {
        state.updateAirplane(Settings.Global.getInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0);
        listener.onAirplaneState(state.airplane());
    }
    private void battery(boolean low) { if (state.updateBattery(low)) listener.onBatteryLow(); }
}
