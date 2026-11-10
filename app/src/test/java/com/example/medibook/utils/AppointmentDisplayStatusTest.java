package com.example.medibook.utils;

import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentStatus;
import com.google.firebase.Timestamp;

import org.junit.Test;

import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

/** Pure display-state tests; no Android, Firestore network, or current-clock dependency. */
public class AppointmentDisplayStatusTest {
    private static final long END_AT = 1_794_022_200_000L; // 2026-11-07 09:30 Asia/Dhaka
    private static final long MINUTE = 60_000L;

    private static Appointment appointment(AppointmentStatus status) {
        return new Appointment("appointment-1", "patient-1", "Patient Fixture",
                "+8801712345678", "doctor-1", "Doctor Fixture", "Cardiology", 50025L,
                "schedule-1", 6, new Timestamp(1_793_988_000L, 0), 540, 570,
                new Timestamp(END_AT / 1000L - 1800L, 0), new Timestamp(END_AT / 1000L, 0),
                status, new Timestamp(100, 0), new Timestamp(200, 0));
    }

    @Test
    public void confirmedIsActiveBeforeTheEndInstant() {
        assertEquals(AppointmentDisplayStatus.CONFIRMED,
                AppointmentDisplayStatus.of(AppointmentStatus.CONFIRMED, END_AT, END_AT - 1));
        assertEquals(AppointmentDisplayStatus.CONFIRMED,
                AppointmentDisplayStatus.of(AppointmentStatus.CONFIRMED, END_AT, END_AT - MINUTE));
    }

    @Test
    public void confirmedBecomesCompletedAtTheEndInstant() {
        assertEquals(AppointmentDisplayStatus.COMPLETED,
                AppointmentDisplayStatus.of(AppointmentStatus.CONFIRMED, END_AT, END_AT));
    }

    @Test
    public void confirmedStaysCompletedAfterTheEndInstant() {
        assertEquals(AppointmentDisplayStatus.COMPLETED,
                AppointmentDisplayStatus.of(AppointmentStatus.CONFIRMED, END_AT, END_AT + 1));
        assertEquals(AppointmentDisplayStatus.COMPLETED,
                AppointmentDisplayStatus.of(AppointmentStatus.CONFIRMED, END_AT, END_AT + MINUTE));
    }

    @Test
    public void cancelledStaysCancelledAtEveryInstant() {
        for (long now : new long[]{
                END_AT - MINUTE, END_AT - 1, END_AT, END_AT + 1, END_AT + MINUTE}) {
            assertEquals(Long.toString(now), AppointmentDisplayStatus.CANCELLED,
                    AppointmentDisplayStatus.of(AppointmentStatus.CANCELLED, END_AT, now));
        }
    }

    @Test
    public void cancelledIsNeverConvertedToCompleted() {
        for (long now : new long[]{END_AT, END_AT + MINUTE, Long.MAX_VALUE}) {
            assertNotEquals(Long.toString(now), AppointmentDisplayStatus.COMPLETED,
                    AppointmentDisplayStatus.of(AppointmentStatus.CANCELLED, END_AT, now));
        }
    }

    @Test
    public void nullPersistedStatusIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AppointmentDisplayStatus.of(null, END_AT, END_AT));
    }

    @Test
    public void appointmentOverloadUsesTheStoredEndInstant() {
        Appointment confirmed = appointment(AppointmentStatus.CONFIRMED);
        assertEquals(AppointmentDisplayStatus.CONFIRMED,
                AppointmentDisplayStatus.of(confirmed, END_AT - 1));
        assertEquals(AppointmentDisplayStatus.COMPLETED,
                AppointmentDisplayStatus.of(confirmed, END_AT));
        assertEquals(AppointmentDisplayStatus.COMPLETED,
                AppointmentDisplayStatus.of(confirmed, END_AT + 1));

        Appointment cancelled = appointment(AppointmentStatus.CANCELLED);
        assertEquals(AppointmentDisplayStatus.CANCELLED,
                AppointmentDisplayStatus.of(cancelled, END_AT + 1));
    }

    @Test
    public void nullAppointmentIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AppointmentDisplayStatus.of((Appointment) null, END_AT));
    }

    @Test
    public void comparisonIsIndependentOfTheDeviceTimezone() {
        TimeZone previous = TimeZone.getDefault();
        try {
            for (String zone : new String[]{"UTC", "America/Los_Angeles", "Pacific/Kiritimati"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertEquals(zone, AppointmentDisplayStatus.CONFIRMED,
                        AppointmentDisplayStatus.of(AppointmentStatus.CONFIRMED, END_AT, END_AT - 1));
                assertEquals(zone, AppointmentDisplayStatus.COMPLETED,
                        AppointmentDisplayStatus.of(AppointmentStatus.CONFIRMED, END_AT, END_AT));
                assertEquals(zone, AppointmentDisplayStatus.CANCELLED,
                        AppointmentDisplayStatus.of(AppointmentStatus.CANCELLED, END_AT, END_AT));
            }
        } finally {
            TimeZone.setDefault(previous);
        }
    }
}
