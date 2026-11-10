package com.example.medibook.utils;

import com.example.medibook.models.DoctorSchedule;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class PatientScheduleDisplayTest {
    private DoctorSchedule schedule(String id, int day, int start) {
        return new DoctorSchedule(id, day, start, start + 30, 15, true);
    }
    @Test public void midnightNoonAndLastMinuteAreWallClockTimes() {
        assertEquals("12:00 AM", PatientScheduleDisplay.time(0));
        assertEquals("9:05 AM", PatientScheduleDisplay.time(545));
        assertEquals("12:00 PM", PatientScheduleDisplay.time(720));
        assertEquals("11:59 PM", PatientScheduleDisplay.time(1439));
    }
    @Test public void deviceTimezoneDoesNotShiftHospitalMinutes() {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            assertEquals("9:00 AM", PatientScheduleDisplay.time(540));
        } finally { TimeZone.setDefault(previous); }
    }
    @Test public void outOfRangeTimesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PatientScheduleDisplay.time(-1));
        assertThrows(IllegalArgumentException.class, () -> PatientScheduleDisplay.time(1440));
    }
    @Test public void isoDayThenTimeThenIdSortIsStableAndInactiveRowsExcluded() {
        DoctorSchedule monday = schedule("a", 1, 600), earlier = schedule("b", 1, 540), sunday = schedule("s", 7, 0);
        List<DoctorSchedule> source = new ArrayList<>(Arrays.asList(sunday, monday, earlier, schedule("hidden", 2, 600).withActive(false)));
        List<DoctorSchedule> result = PatientScheduleDisplay.sorted(source);
        assertEquals(Arrays.asList(earlier, monday, sunday), result);
        source.clear();
        assertEquals(3, result.size());
        assertThrows(UnsupportedOperationException.class, result::clear);
    }
    @Test public void emptyAndInvalidSchedulesAreNotInventedOrDisplayed() {
        assertTrue(PatientScheduleDisplay.sorted(Collections.emptyList()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> PatientScheduleDisplay.sorted(Collections.singletonList(schedule("bad", 8, 0))));
    }
}
