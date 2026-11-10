package com.example.medibook.system;

import org.junit.Test;
import static org.junit.Assert.*;

public class SystemNoticeStateTest {
    @Test public void airplaneOnIsPersistentNotRepeatedPopupState() {
        SystemNoticeState state = new SystemNoticeState(); assertTrue(state.updateAirplane(true));
        assertFalse(state.updateAirplane(true)); assertTrue(state.airplane());
    }
    @Test public void airplaneOffRemovesWarningAndDuplicateOffIsIgnored() {
        SystemNoticeState state = new SystemNoticeState(); state.updateAirplane(true);
        assertTrue(state.updateAirplane(false)); assertFalse(state.airplane()); assertFalse(state.updateAirplane(false));
    }
    @Test public void initialBatteryOkayDoesNotShowNotice() { assertFalse(new SystemNoticeState().updateBattery(false)); }
    @Test public void repeatedBatteryLowDoesNotRepeatNotice() {
        SystemNoticeState state = new SystemNoticeState(); assertTrue(state.updateBattery(true)); assertFalse(state.updateBattery(true));
    }
    @Test public void batteryRecoveryAllowsANewLowEvent() {
        SystemNoticeState state = new SystemNoticeState(); state.updateBattery(true); assertFalse(state.updateBattery(false)); assertTrue(state.updateBattery(true));
    }
    @Test public void airplaneAndBatteryAreIndependentNotInternetStatus() {
        SystemNoticeState state = new SystemNoticeState(); state.updateAirplane(true);
        assertTrue(state.updateBattery(true)); state.updateAirplane(false); assertFalse(state.updateBattery(true));
    }
}
