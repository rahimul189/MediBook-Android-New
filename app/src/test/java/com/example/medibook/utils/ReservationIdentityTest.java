package com.example.medibook.utils;

import com.example.medibook.models.AppointmentSlot;
import org.junit.Test;

import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Deterministic slot/appointment identity and half-open interval boundaries. */
public class ReservationIdentityTest {
    private static final long MONDAY_2026_11_09_MIDNIGHT_MILLIS = 1_794_160_800_000L;
    private static final long MONDAY_0900_MILLIS = 1_794_193_200_000L;
    private static final long MONDAY_0915_MILLIS = 1_794_194_100_000L;
    private static final long TUESDAY_2026_11_10_MIDNIGHT_MILLIS = 1_794_247_200_000L;
    private static final long TUESDAY_0900_MILLIS = 1_794_279_600_000L;
    private static final long DEC_31_2026_MIDNIGHT_MILLIS = 1_798_653_600_000L;
    private static final long JAN_1_2027_MIDNIGHT_MILLIS = 1_798_740_000_000L;
    private static final long FEB_29_2024_MIDNIGHT_MILLIS = 1_709_143_200_000L;

    @Test
    public void slotIdUsesExactMinuteInstants() {
        String slotId = ReservationIdentity.slotId(MONDAY_0900_MILLIS, MONDAY_0915_MILLIS);
        assertEquals("s1794193200000-1794194100000", slotId);
        assertEquals(slotId, ReservationIdentity.slotId(
                MONDAY_0900_MILLIS, MONDAY_0915_MILLIS));

        AppointmentSlot slot = new AppointmentSlot(
                "monday-1", 540, 555, MONDAY_0900_MILLIS, MONDAY_0915_MILLIS);
        assertEquals(slotId, ReservationIdentity.slotId(slot));
    }

    @Test
    public void sameClockMinuteOnDifferentDatesHasDifferentIdentity() {
        String monday = ReservationIdentity.slotId(MONDAY_0900_MILLIS, MONDAY_0915_MILLIS);
        String tuesday = ReservationIdentity.slotId(
                TUESDAY_0900_MILLIS, TUESDAY_0900_MILLIS + 15 * 60_000L);
        assertNotEquals(monday, tuesday);

        String mondayKey = ReservationIdentity.reservationKey(
                "doctor-a", ReservationIdentity.dateKey(2026, 11, 9), monday, 1);
        String tuesdayKey = ReservationIdentity.reservationKey(
                "doctor-a", ReservationIdentity.dateKey(2026, 11, 10), tuesday, 1);
        assertNotEquals(mondayKey, tuesdayKey);
    }

    @Test
    public void differentDoctorsDoNotShareReservationKeys() {
        String slotId = ReservationIdentity.slotId(MONDAY_0900_MILLIS, MONDAY_0915_MILLIS);
        String dateKey = ReservationIdentity.dateKey(2026, 11, 9);
        String doctorA = ReservationIdentity.reservationKey("doctor-a", dateKey, slotId, 1);
        String doctorB = ReservationIdentity.reservationKey("doctor-b", dateKey, slotId, 1);
        assertNotEquals(doctorA, doctorB);
        assertEquals(doctorA,
                ReservationIdentity.reservationKey("doctor-a", dateKey, slotId, 1));
    }

    @Test
    public void bookingLockIdIsOneBasedAndGenerationSpecific() {
        assertEquals("g1", ReservationIdentity.bookingLockId(1));
        assertEquals("g2", ReservationIdentity.bookingLockId(2));
        assertEquals("g2147483647", ReservationIdentity.bookingLockId(Integer.MAX_VALUE));
        assertNotEquals(ReservationIdentity.bookingLockId(1),
                ReservationIdentity.bookingLockId(2));
        assertThrows(IllegalArgumentException.class,
                () -> ReservationIdentity.bookingLockId(0));
        assertThrows(IllegalArgumentException.class,
                () -> ReservationIdentity.bookingLockId(-1));
    }

    @Test
    public void differentGenerationsOfOneSlotHaveDifferentReservationKeys() {
        String slotId = ReservationIdentity.slotId(MONDAY_0900_MILLIS, MONDAY_0915_MILLIS);
        String dateKey = ReservationIdentity.dateKey(2026, 11, 9);
        String first = ReservationIdentity.reservationKey("doctor-a", dateKey, slotId, 1);
        String second = ReservationIdentity.reservationKey("doctor-a", dateKey, slotId, 2);
        assertNotEquals(first, second);
        assertTrue(first.endsWith("/g1"));
        assertTrue(second.endsWith("/g2"));
    }

    @Test
    public void dateKeyIsUtcMillisOfDhakaMidnight() {
        assertEquals("1794160800000", ReservationIdentity.dateKey(2026, 11, 9));
        assertEquals("1798653600000", ReservationIdentity.dateKey(2026, 12, 31));
        assertEquals("1798740000000", ReservationIdentity.dateKey(2027, 1, 1));
        assertEquals("1709143200000", ReservationIdentity.dateKey(2024, 2, 29));
        assertEquals(DEC_31_2026_MIDNIGHT_MILLIS,
                Long.parseLong(ReservationIdentity.dateKey(2026, 12, 31)));
        assertEquals(JAN_1_2027_MIDNIGHT_MILLIS,
                Long.parseLong(ReservationIdentity.dateKey(2027, 1, 1)));
        assertEquals(FEB_29_2024_MIDNIGHT_MILLIS,
                Long.parseLong(ReservationIdentity.dateKey(2024, 2, 29)));
    }

    @Test
    public void dateKeyRejectsInvalidCalendarDates() {
        assertThrows(IllegalArgumentException.class,
                () -> ReservationIdentity.dateKey(2023, 2, 29));
        assertThrows(IllegalArgumentException.class,
                () -> ReservationIdentity.dateKey(2026, 13, 1));
        assertThrows(IllegalArgumentException.class,
                () -> ReservationIdentity.dateKey(2026, 0, 1));
    }

    @Test
    public void dateKeyDoesNotDependOnDeviceTimezone() {
        TimeZone previous = TimeZone.getDefault();
        try {
            for (String zoneId : new String[]{
                    "America/Los_Angeles", "Pacific/Kiritimati", "UTC"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
                assertEquals(zoneId, "1794160800000",
                        ReservationIdentity.dateKey(2026, 11, 9));
                assertEquals(zoneId, "1798740000000",
                        ReservationIdentity.dateKey(2027, 1, 1));
            }
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    public void overlapIsHalfOpenAndAdjacencyIsAllowed() {
        assertFalse(ReservationIdentity.overlaps(540, 570, 570, 600));
        assertFalse(ReservationIdentity.overlaps(570, 600, 540, 570));
        assertTrue(ReservationIdentity.overlaps(540, 570, 569, 600));
        assertTrue(ReservationIdentity.overlaps(540, 600, 555, 570));
        assertTrue(ReservationIdentity.overlaps(540, 600, 540, 600));
        assertFalse(ReservationIdentity.overlaps(540, 570, 600, 630));
    }

    @Test
    public void invalidIntervalsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ReservationIdentity.overlaps(540, 540, 600, 630));
        assertThrows(IllegalArgumentException.class,
                () -> ReservationIdentity.overlaps(540, 570, 600, 600));
        assertThrows(IllegalArgumentException.class,
                () -> ReservationIdentity.slotId(540_000L, 540_000L));
        assertThrows(IllegalArgumentException.class,
                () -> ReservationIdentity.slotId(-60_000L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> ReservationIdentity.slotId(1_000L, 61_000L));
    }

    @Test
    public void cancellationIsStrictlyBeforeStart() {
        long startAt = MONDAY_0900_MILLIS;
        assertTrue(ReservationIdentity.isCancellable(startAt, startAt - 1));
        assertFalse(ReservationIdentity.isCancellable(startAt, startAt));
        assertFalse(ReservationIdentity.isCancellable(startAt, startAt + 1));

        long nextMidnight = JAN_1_2027_MIDNIGHT_MILLIS;
        assertTrue(ReservationIdentity.isCancellable(nextMidnight, nextMidnight - 1));
        assertFalse(ReservationIdentity.isCancellable(nextMidnight, nextMidnight));
    }

    @Test
    public void reservationKeyRejectsMalformedInputs() {
        String slotId = ReservationIdentity.slotId(MONDAY_0900_MILLIS, MONDAY_0915_MILLIS);
        String dateKey = ReservationIdentity.dateKey(2026, 11, 9);
        assertThrows(IllegalArgumentException.class,
                () -> ReservationIdentity.reservationKey(null, dateKey, slotId, 1));
        assertThrows(IllegalArgumentException.class,
                () -> ReservationIdentity.reservationKey("bad/id", dateKey, slotId, 1));
        assertThrows(IllegalArgumentException.class,
                () -> ReservationIdentity.reservationKey("doctor-a", null, slotId, 1));
        assertThrows(IllegalArgumentException.class,
                () -> ReservationIdentity.reservationKey("doctor-a", "not-a-date", slotId, 1));
        assertThrows(IllegalArgumentException.class,
                () -> ReservationIdentity.reservationKey("doctor-a", dateKey, "bad/id", 1));
        assertThrows(IllegalArgumentException.class,
                () -> ReservationIdentity.reservationKey("doctor-a", dateKey, slotId, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ReservationIdentity.slotId(null));
    }

    @Test
    public void knownDateKeyConstantsMatchDhakaDateTime() {
        assertEquals(MONDAY_2026_11_09_MIDNIGHT_MILLIS,
                DhakaDateTime.localDateAtMidnight(2026, 11, 9));
        assertEquals(TUESDAY_2026_11_10_MIDNIGHT_MILLIS,
                DhakaDateTime.localDateAtMidnight(2026, 11, 10));
        assertEquals(DEC_31_2026_MIDNIGHT_MILLIS,
                DhakaDateTime.localDateAtMidnight(2026, 12, 31));
        assertEquals(JAN_1_2027_MIDNIGHT_MILLIS,
                DhakaDateTime.localDateAtMidnight(2027, 1, 1));
    }
}
