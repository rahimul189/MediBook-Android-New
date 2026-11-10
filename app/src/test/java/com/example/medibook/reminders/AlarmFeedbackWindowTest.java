package com.example.medibook.reminders;

import org.junit.Test;
import static org.junit.Assert.*;

public class AlarmFeedbackWindowTest {
    @Test public void soundAndVibrationShareExactlyThirtySeconds() {
        AlarmFeedbackWindow window = new AlarmFeedbackWindow();
        window.begin("a", 1000);
        assertTrue(window.active(1000)); assertTrue(window.active(30_999));
        assertEquals(30_000L, window.remaining("a", 1000));
        assertEquals(0L, window.remaining("other", 1000));
        assertEquals(0L, window.remaining("a", 31_000));
        assertFalse(window.active(31_000)); assertFalse(window.active(31_001));
    }
    @Test public void dismissStopsImmediatelyAndIsIdempotent() {
        AlarmFeedbackWindow window = new AlarmFeedbackWindow(); window.begin("a", 0);
        assertTrue(window.stop("a")); assertFalse(window.active(1));
        assertFalse(window.stop("a")); assertFalse(window.active(100));
    }
    @Test public void olderNotificationCannotStopNewerAlarmButLogoutCan() {
        AlarmFeedbackWindow window = new AlarmFeedbackWindow();
        window.begin("a", 0); window.begin("b", 5000);
        assertFalse(window.stop("a")); assertTrue(window.active(34_999));
        assertTrue(window.stop(null)); assertFalse(window.active(6000));
    }
}
