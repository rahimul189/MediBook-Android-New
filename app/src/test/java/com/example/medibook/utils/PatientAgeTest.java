package com.example.medibook.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/** Age is always derived; no profile stores an age integer. */
public class PatientAgeTest {

    private static long dhaka(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
        calendar.clear();
        calendar.set(year, month - 1, day, 12, 0, 0);
        return calendar.getTimeInMillis();
    }

    @Test public void rejectsMalformedAndImpossibleDates() {
        assertFalse(PatientAge.isValidIsoDate("1990-3-12"));
        assertFalse(PatientAge.isValidIsoDate("1990-02-30"));
        assertFalse(PatientAge.isValidIsoDate("not-a-date"));
        assertFalse(PatientAge.isValidIsoDate(null));
        assertTrue(PatientAge.isValidIsoDate("1990-03-12"));
    }

    @Test public void rejectsFutureAndImplausiblyOldDates() {
        long now = dhaka(2026, 11, 7);
        assertFalse(PatientAge.isValidBirthDate("2026-11-08", now));
        assertFalse(PatientAge.isValidBirthDate("1899-12-31", now));
        assertTrue(PatientAge.isValidBirthDate("1900-01-01", now));
        assertTrue(PatientAge.isValidBirthDate("2026-11-07", now));
    }

    @Test public void computesCompletedYearsInHospitalTimezone() {
        long now = dhaka(2026, 11, 7);
        assertEquals(Integer.valueOf(36), PatientAge.years("1990-03-12", now));
        assertEquals(Integer.valueOf(35), PatientAge.years("1990-12-12", now));
        assertEquals(Integer.valueOf(36), PatientAge.years("1990-11-07", now));
    }

    @Test public void handlesLeapDayBirth() {
        assertEquals(Integer.valueOf(36), PatientAge.years("1992-02-29", dhaka(2029, 2, 28)));
        assertEquals(Integer.valueOf(37), PatientAge.years("1992-02-29", dhaka(2029, 3, 1)));
    }

    @Test public void missingOrInvalidValueHasNoAge() {
        assertNull(PatientAge.years(null, dhaka(2026, 11, 7)));
        assertNull(PatientAge.years("1990-02-30", dhaka(2026, 11, 7)));
    }

    @Test public void genderWritesAcceptOnlyMaleAndFemale() {
        assertTrue(PatientGender.isWireValue("Male"));
        assertTrue(PatientGender.isWireValue("Female"));
        assertFalse(PatientGender.isWireValue("Other"));
        assertFalse(PatientGender.isWireValue("Prefer not to say"));
        assertFalse(PatientGender.isWireValue("male"));
        assertFalse(PatientGender.isWireValue(""));
        assertFalse(PatientGender.isWireValue(null));
        assertEquals(2, PatientGender.OPTIONS.length);
    }

    @Test public void legacyGenderValuesRemainReadableButAreNeverDisplayed() {
        assertTrue(PatientGender.isReadable("Other"));
        assertTrue(PatientGender.isReadable("Prefer not to say"));
        assertTrue(PatientGender.isReadable("Male"));
        assertNull(PatientGender.display("Other"));
        assertNull(PatientGender.display("Prefer not to say"));
        assertEquals("Female", PatientGender.display("Female"));
        assertNull(PatientGender.display(null));
    }
}
