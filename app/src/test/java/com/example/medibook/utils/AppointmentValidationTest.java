package com.example.medibook.utils;

import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentStatus;
import com.google.firebase.Timestamp;

import org.junit.Test;

import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Pure validation tests; no Android, Firestore network, or current-clock dependency. */
public class AppointmentValidationTest {
    private static final long MIDNIGHT = 1_793_988_000_000L; // 2026-11-07 00:00 Asia/Dhaka
    private static final long START_AT = MIDNIGHT + 540L * 60_000L;
    private static final long END_AT = MIDNIGHT + 570L * 60_000L;
    private static final Timestamp DATE = timestamp(MIDNIGHT);
    private static final Timestamp START = timestamp(START_AT);
    private static final Timestamp END = timestamp(END_AT);
    private static final Timestamp CREATED = new Timestamp(100, 0);
    private static final Timestamp UPDATED = new Timestamp(200, 0);

    private static final class Fixture {
        String id = "appointment-1";
        String patientUid = "patient-1";
        String patientName = "Patient Fixture";
        String patientPhone = "+8801712345678";
        String doctorId = "doctor-1";
        String doctorName = "Doctor Fixture";
        String doctorSpecialization = "Cardiology";
        long consultationFee = 50025L;
        String scheduleId = "schedule-1";
        int dayOfWeek = 6;
        Timestamp appointmentDate = DATE;
        int startMinute = 540;
        int endMinute = 570;
        Timestamp startAt = START;
        Timestamp endAt = END;
        AppointmentStatus status = AppointmentStatus.CONFIRMED;
        Timestamp createdAt = CREATED;
        Timestamp updatedAt = UPDATED;

        Appointment build() {
            return new Appointment(id, patientUid, patientName, patientPhone, doctorId,
                    doctorName, doctorSpecialization, consultationFee, scheduleId, dayOfWeek,
                    appointmentDate, startMinute, endMinute, startAt, endAt, status,
                    createdAt, updatedAt);
        }
    }

    private static Timestamp timestamp(long millis) {
        return new Timestamp(millis / 1000L, (int) ((millis % 1000L) * 1_000_000L));
    }

    private static boolean valid(Fixture fixture) {
        return AppointmentValidation.isValid(fixture.build());
    }

    private static void assertInvalid(Fixture fixture) {
        assertFalse(fixture.build().toString(), valid(fixture));
    }

    private static Fixture fixtureForMinutes(int startMinute, int endMinute) {
        Fixture fixture = new Fixture();
        fixture.startMinute = startMinute;
        fixture.endMinute = endMinute;
        fixture.startAt = timestamp(MIDNIGHT + startMinute * 60_000L);
        fixture.endAt = timestamp(MIDNIGHT + endMinute * 60_000L);
        return fixture;
    }

    private static void setId(Fixture fixture, int field, String value) {
        switch (field) {
            case 0: fixture.id = value; break;
            case 1: fixture.patientUid = value; break;
            case 2: fixture.doctorId = value; break;
            default: fixture.scheduleId = value; break;
        }
    }

    @Test
    public void acceptsTheCompleteValidAppointmentAndAllStatuses() {
        Fixture fixture = new Fixture();
        assertTrue(valid(fixture));
        AppointmentValidation.requireValid(fixture.build());
        for (AppointmentStatus status : AppointmentStatus.values()) {
            Fixture withStatus = new Fixture();
            withStatus.status = status;
            assertTrue(status.name(), valid(withStatus));
        }
    }

    @Test
    public void rejectsNullAppointment() {
        assertFalse(AppointmentValidation.isValid(null));
        assertThrows(IllegalArgumentException.class,
                () -> AppointmentValidation.requireValid(null));
    }

    @Test
    public void validatesDocumentAndRelationshipIdentifiers() {
        for (int field = 0; field < 4; field++) {
            for (String invalid : new String[]{"", "bad/id", "bad id", "dot.", "x".repeat(129)}) {
                Fixture fixture = new Fixture();
                setId(fixture, field, invalid);
                assertInvalid(fixture);
            }
            Fixture maximum = new Fixture();
            setId(maximum, field, "x".repeat(128));
            assertTrue(valid(maximum));
        }
    }

    @Test
    public void validatesPatientNameAndPhoneAgainstTheProfileLimits() {
        for (String invalid : new String[]{"", "   ", "x".repeat(101)}) {
            Fixture fixture = new Fixture();
            fixture.patientName = invalid;
            assertInvalid(fixture);
        }
        Fixture longestName = new Fixture();
        longestName.patientName = "x".repeat(100);
        assertTrue(valid(longestName));

        for (String invalid : new String[]{"", "123456", "1234567890123456", "+1 2345678",
                "017-1234567", "+0123456789012345", "phone"}) {
            Fixture fixture = new Fixture();
            fixture.patientPhone = invalid;
            assertInvalid(fixture);
        }
        for (String phone : new String[]{"1234567", "123456789012345", "+8801712345678"}) {
            Fixture fixture = new Fixture();
            fixture.patientPhone = phone;
            assertTrue(phone, valid(fixture));
        }
    }

    @Test
    public void validatesStoredDoctorTextAndSpecializationLengths() {
        String[] invalidTexts = {"", "   ", "\u00a0", "x".repeat(101), "line\nbreak",
                "tab\tvalue", "nul\u0000", "separator\u2028value"};
        for (String invalid : invalidTexts) {
            Fixture name = new Fixture();
            name.doctorName = invalid;
            assertInvalid(name);
            Fixture specialization = new Fixture();
            specialization.doctorSpecialization = invalid;
            assertInvalid(specialization);
        }
        Fixture maximum = new Fixture();
        maximum.doctorName = "x".repeat(100);
        maximum.doctorSpecialization = "x".repeat(100);
        assertTrue(valid(maximum));
    }

    @Test
    public void enforcesConsultationFeeBoundsInIntegerHundredths() {
        assertEquals(99_999_999L, AppointmentValidation.MAX_CONSULTATION_FEE_HUNDREDTHS);
        for (long fee : new long[]{0L, 1L, 50025L, 99_999_999L}) {
            Fixture fixture = new Fixture();
            fixture.consultationFee = fee;
            assertTrue(Long.toString(fee), valid(fixture));
        }
        for (long fee : new long[]{-1L, 100_000_000L, Long.MAX_VALUE, Long.MIN_VALUE}) {
            Fixture fixture = new Fixture();
            fixture.consultationFee = fee;
            assertInvalid(fixture);
        }
    }

    @Test
    public void enforcesIsoWeekdayAndTheActualDhakaWeekday() {
        for (int day : new int[]{0, 8, -1, 100}) {
            Fixture fixture = new Fixture();
            fixture.dayOfWeek = day;
            assertInvalid(fixture);
        }
        // 2026-11-02 is Monday (1) through 2026-11-08 Sunday (7).
        for (int day = 1; day <= 7; day++) {
            long midnight = DhakaDateTime.localDateAtMidnight(2026, 11, day + 1);
            Fixture fixture = new Fixture();
            fixture.dayOfWeek = day;
            fixture.appointmentDate = timestamp(midnight);
            fixture.startAt = timestamp(midnight + 540L * 60_000L);
            fixture.endAt = timestamp(midnight + 570L * 60_000L);
            assertTrue(Integer.toString(day), valid(fixture));
        }
        Fixture wrongDay = new Fixture();
        wrongDay.dayOfWeek = 5; // Friday, but 2026-11-07 is Saturday.
        assertInvalid(wrongDay);
    }

    @Test
    public void enforcesMinuteRangesAndPositiveSameDayDuration() {
        for (int start : new int[]{0, 1, 540, 1439}) {
            Fixture fixture = fixtureForMinutes(start, Math.min(start + 1, 1439));
            if (fixture.endMinute > fixture.startMinute) {
                assertTrue(start + "-" + fixture.endMinute, valid(fixture));
            }
        }
        for (int[] range : new int[][]{{-1, 30}, {1440, 1441}, {1441, 1500},
                {0, 0}, {540, 539}, {570, 540}, {0, 1440},
                {540, 1440}, {540, 1441}}) {
            assertInvalid(fixtureForMinutes(range[0], range[1]));
        }
    }

    @Test
    public void endMinuteCannotCrossIntoTheNextDhakaCalendarDay() {
        // Step 84 slots can represent an ending boundary of minute 1440. A stored appointment
        // must stay within one Dhaka day, so minute 1440 is rejected here.
        Fixture crossing = fixtureForMinutes(540, 1440);
        assertEquals(timestamp(MIDNIGHT + 1440L * 60_000L), crossing.endAt);
        assertInvalid(crossing);
    }

    @Test
    public void appointmentDateMustBeExactDhakaMidnightWithZeroNanoseconds() {
        Fixture oneMinuteLate = new Fixture();
        oneMinuteLate.appointmentDate = new Timestamp(DATE.getSeconds() + 60, 0);
        assertInvalid(oneMinuteLate);

        Fixture oneSecondLate = new Fixture();
        oneSecondLate.appointmentDate = new Timestamp(DATE.getSeconds() + 1, 0);
        assertInvalid(oneSecondLate);

        Fixture fractional = new Fixture();
        fractional.appointmentDate = new Timestamp(DATE.getSeconds(), 1);
        assertInvalid(fractional);

        Fixture previousDay = new Fixture();
        previousDay.appointmentDate = new Timestamp(DATE.getSeconds() - 86_400, 0);
        assertInvalid(previousDay);
    }

    @Test
    public void startAndEndInstantsMustMatchTheStoredMinutesExactly() {
        Fixture startLater = new Fixture();
        startLater.startAt = new Timestamp(START.getSeconds() + 60, 0);
        assertInvalid(startLater);

        Fixture startEarlier = new Fixture();
        startEarlier.startAt = new Timestamp(START.getSeconds() - 1, 0);
        assertInvalid(startEarlier);

        Fixture startFractional = new Fixture();
        startFractional.startAt = new Timestamp(START.getSeconds(), 1);
        assertInvalid(startFractional);

        Fixture endLater = new Fixture();
        endLater.endAt = new Timestamp(END.getSeconds() + 60, 0);
        assertInvalid(endLater);

        Fixture endEarlier = new Fixture();
        endEarlier.endAt = new Timestamp(END.getSeconds() - 60, 0);
        assertInvalid(endEarlier);

        Fixture endFractional = new Fixture();
        endFractional.endAt = new Timestamp(END.getSeconds(), 1);
        assertInvalid(endFractional);
    }

    @Test
    public void enforcesCreatedAtAndUpdatedAtChronology() {
        Fixture reversed = new Fixture();
        reversed.updatedAt = new Timestamp(CREATED.getSeconds() - 1, 0);
        assertInvalid(reversed);

        Fixture equal = new Fixture();
        equal.updatedAt = CREATED;
        assertTrue(valid(equal));

        Fixture later = new Fixture();
        later.updatedAt = new Timestamp(CREATED.getSeconds(), 1);
        assertTrue(valid(later));
    }

    @Test
    public void acceptsHistoricalAppointmentsWithoutComparingTheCurrentClock() {
        // 2020-01-15 is a Wednesday (ISO 3), long before the current date.
        long midnight = 1_579_024_800_000L;
        Fixture historical = new Fixture();
        historical.appointmentDate = timestamp(midnight);
        historical.dayOfWeek = 3;
        historical.startMinute = 600;
        historical.endMinute = 630;
        historical.startAt = timestamp(midnight + 600L * 60_000L);
        historical.endAt = timestamp(midnight + 630L * 60_000L);
        assertTrue(valid(historical));
    }

    @Test
    public void validationUsesDhakaRatherThanTheDeviceTimezone() {
        TimeZone previous = TimeZone.getDefault();
        try {
            for (String zone : new String[]{"UTC", "America/Los_Angeles", "Pacific/Kiritimati"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertTrue(zone, valid(new Fixture()));
            }
        } finally {
            TimeZone.setDefault(previous);
        }
    }
}
