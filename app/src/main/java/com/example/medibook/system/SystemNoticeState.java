package com.example.medibook.system;

/** Deduplicates unchanged system events, including stop/start and retained-ViewModel rotations. */
public final class SystemNoticeState {
    private boolean airplane, batteryLow;
    public boolean airplane() { return airplane; }
    public boolean updateAirplane(boolean enabled) {
        boolean changed = airplane != enabled; airplane = enabled; return changed;
    }
    public boolean updateBattery(boolean low) {
        boolean show = low && !batteryLow; batteryLow = low; return show;
    }
}
