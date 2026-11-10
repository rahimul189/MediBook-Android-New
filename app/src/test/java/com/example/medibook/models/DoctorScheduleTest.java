package com.example.medibook.models;

import org.junit.Test;
import com.google.firebase.Timestamp;

import static org.junit.Assert.*;

public class DoctorScheduleTest {
    @Test
    public void draftRetainsAllWeeklyFieldsAndHasNoServerVersion() {
        DoctorSchedule entry = new DoctorSchedule("schedule-1", 5, 540, 660, 20, true);
        assertEquals("schedule-1", entry.getId());
        assertNull(entry.getUpdatedAt());
        assertEquals(5, entry.getDayOfWeek());
        assertEquals(540, entry.getStartMinute());
        assertEquals(660, entry.getEndMinute());
        assertEquals(20, entry.getSlotMinutes());
        assertTrue(entry.isActive());
    }

    @Test
    public void disablingIsAnImmutableCopyAndPreservesEveryOtherField() {
        DoctorSchedule original = new DoctorSchedule("schedule-1", 1, 540, 600, 15, true, new Timestamp(100, 42));
        DoctorSchedule disabled = original.withActive(false);
        assertNotSame(original, disabled);
        assertTrue(original.isActive());
        assertFalse(disabled.isActive());
        assertEquals(original.getId(), disabled.getId());
        assertEquals(original.getUpdatedAt(), disabled.getUpdatedAt());
        assertEquals(original.getDayOfWeek(), disabled.getDayOfWeek());
        assertEquals(original.getStartMinute(), disabled.getStartMinute());
        assertEquals(original.getEndMinute(), disabled.getEndMinute());
        assertEquals(original.getSlotMinutes(), disabled.getSlotMinutes());
    }

    @Test
    public void inactiveScheduleCanBeEnabledWithoutChangingOriginal() {
        DoctorSchedule original = new DoctorSchedule("schedule-1", 1, 540, 600, 15, false);
        assertTrue(original.withActive(true).isActive());
        assertFalse(original.isActive());
    }
}
