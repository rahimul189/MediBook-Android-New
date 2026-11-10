package com.example.medibook.repositories;

import com.example.medibook.models.DoctorSchedule;
import com.google.firebase.Timestamp;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.Assert.*;

/** Pure offline mapping; no Firebase app, accounts or production data. */
public class DoctorScheduleMapperTest {
    private Map<String, Object> data() {
        Map<String, Object> data = new HashMap<>();
        data.put("dayOfWeek", 1L);
        data.put("startTime", 540L);
        data.put("endTime", 660L);
        data.put("slotDurationMinutes", 20L);
        data.put("active", true);
        data.put("updatedAt", new Timestamp(100, 123));
        return data;
    }

    private void invalid(Map<String, Object> values) {
        assertThrows(IllegalArgumentException.class, () -> DoctorScheduleMapper.fromMap("schedule-1", values));
    }

    @Test public void mapsAllSixFieldsAndPathIdentity() {
        DoctorSchedule schedule = DoctorScheduleMapper.fromMap("schedule-1", data());
        assertEquals("schedule-1", schedule.getId());
        assertEquals(1, schedule.getDayOfWeek());
        assertEquals(540, schedule.getStartMinute());
        assertEquals(660, schedule.getEndMinute());
        assertEquals(20, schedule.getSlotMinutes());
        assertTrue(schedule.isActive());
        assertEquals(new Timestamp(100, 123), schedule.getUpdatedAt());
    }

    @Test public void acceptsIntegerAndLongButNotOtherNumericTypes() {
        for (String field : new String[]{"dayOfWeek", "startTime", "endTime", "slotDurationMinutes"}) {
            Map<String, Object> values = data();
            long number = (Long) values.get(field);
            values.put(field, (int) number);
            DoctorScheduleMapper.fromMap("schedule-1", values);
            for (Object wrong : new Object[]{(double) number, (float) number, Long.toString(number), true,
                    Long.MAX_VALUE, Long.MIN_VALUE, Double.NaN, Double.POSITIVE_INFINITY}) {
                values.put(field, wrong);
                invalid(values);
            }
        }
    }

    @Test public void rejectsEveryMissingField() {
        for (String field : data().keySet()) {
            Map<String, Object> values = data();
            values.remove(field);
            invalid(values);
        }
    }

    @Test public void rejectsExtraFieldsIncludingMutableIdentifiers() {
        for (String field : new String[]{"id", "doctorId", "scheduleId", "createdAt", "timezone", "role"}) {
            Map<String, Object> values = data();
            values.put(field, "unexpected");
            invalid(values);
        }
    }

    @Test public void rejectsNullDataAndNullOrWrongFieldTypes() {
        invalid(null);
        for (String field : data().keySet()) {
            Map<String, Object> values = data();
            values.put(field, null);
            invalid(values);
            values.put(field, new Object());
            invalid(values);
        }
    }

    @Test public void rejectsMalformedAndOverlongPathIds() {
        for (String id : new String[]{null, "", "bad/id", "..", "x".repeat(129)}) {
            assertThrows(IllegalArgumentException.class, () -> DoctorScheduleMapper.fromMap(id, data()));
        }
        assertEquals("x".repeat(128), DoctorScheduleMapper.fromMap("x".repeat(128), data()).getId());
    }

    @Test public void validatesIsoWeekdays() {
        for (long day : new long[]{1, 7}) {
            Map<String, Object> values = data();
            values.put("dayOfWeek", day);
            assertEquals(day, DoctorScheduleMapper.fromMap("schedule-1", values).getDayOfWeek());
        }
        for (long day : new long[]{0, 8, -1}) {
            Map<String, Object> values = data();
            values.put("dayOfWeek", day);
            invalid(values);
        }
    }

    @Test public void enforcesSameDayTimeOrderingAndMinuteBounds() {
        for (long[] pair : new long[][]{{-1, 600}, {540, 1440}, {600, 600}, {1380, 60}, {600, -1}}) {
            Map<String, Object> values = data();
            values.put("startTime", pair[0]);
            values.put("endTime", pair[1]);
            invalid(values);
        }
        Map<String, Object> values = data();
        values.put("startTime", 0L);
        values.put("endTime", 1439L);
        assertEquals(1439, DoctorScheduleMapper.fromMap("schedule-1", values).getEndMinute());
    }

    @Test public void validatesDurationPolicyAndWindowFit() {
        for (long slot : new long[]{0, 4, 7, 125, 181, 185}) {
            Map<String, Object> values = data();
            values.put("slotDurationMinutes", slot);
            invalid(values);
        }
        Map<String, Object> values = data();
        values.put("endTime", 605L);
        values.put("slotDurationMinutes", 30L);
        assertEquals(30, DoctorScheduleMapper.fromMap("schedule-1", values).getSlotMinutes());
    }

    @Test public void activeMustBeBooleanAndUpdatedAtMustBeResolvedTimestamp() {
        for (Object value : new Object[]{"true", 1L}) {
            Map<String, Object> values = data();
            values.put("active", value);
            invalid(values);
        }
        for (Object value : new Object[]{100L, "serverTimestamp", new Object()}) {
            Map<String, Object> values = data();
            values.put("updatedAt", value);
            invalid(values);
        }
    }

    @Test public void writingUsesExactlySixFieldsAndProvidedServerSentinel() {
        Object sentinel = new Object();
        Map<String, Object> written = DoctorScheduleMapper.writeMap(DoctorScheduleMapper.fromMap("schedule-1", data()), sentinel);
        assertEquals(data().keySet(), written.keySet());
        assertSame(sentinel, written.get("updatedAt"));
        assertEquals(540L, written.get("startTime"));
        assertFalse(written.containsKey("id"));
    }

    @Test public void statusUpdateOnlyWritesActiveAndServerTimestamp() {
        Object sentinel = new Object();
        Map<String, Object> written = DoctorScheduleMapper.statusMap(false, sentinel);
        assertEquals(new HashSet<>(Arrays.asList("active", "updatedAt")), written.keySet());
        assertEquals(false, written.get("active"));
        assertSame(sentinel, written.get("updatedAt"));
    }

    @Test public void versionComparisonUsesIdAndFullTimestampPrecision() {
        DoctorSchedule original = DoctorScheduleMapper.fromMap("schedule-1", data());
        assertTrue(DoctorScheduleMapper.sameVersion(original, original.withActive(false)));
        assertFalse(DoctorScheduleMapper.sameVersion(original,
                new DoctorSchedule("schedule-1", 1, 540, 660, 20, true, new Timestamp(100, 124))));
        assertFalse(DoctorScheduleMapper.sameVersion(original,
                new DoctorSchedule("other", 1, 540, 660, 20, true, original.getUpdatedAt())));
        assertFalse(DoctorScheduleMapper.sameVersion(original, new DoctorSchedule("schedule-1", 1, 540, 660, 20, true)));
        assertFalse(DoctorScheduleMapper.sameVersion(null, original));
    }

    @Test public void invalidDraftCannotBeWritten() {
        assertThrows(IllegalArgumentException.class, () -> DoctorScheduleMapper.writeMap(null, new Object()));
        assertThrows(IllegalArgumentException.class, () -> DoctorScheduleMapper.writeMap(
                new DoctorSchedule("schedule-1", 1, 600, 540, 15, true), new Object()));
    }
}
