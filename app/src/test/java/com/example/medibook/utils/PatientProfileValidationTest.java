package com.example.medibook.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PatientProfileValidationTest {

    @Test
    public void nameMustBeNonblankAndBounded() {
        assertFalse(PatientProfileValidation.isValidName(null));
        assertFalse(PatientProfileValidation.isValidName("  "));
        assertFalse(PatientProfileValidation.isValidName("\t\n"));
        assertTrue(PatientProfileValidation.isValidName("Patient Name"));
        assertTrue(PatientProfileValidation.isValidName("A".repeat(100)));
        assertFalse(PatientProfileValidation.isValidName("A".repeat(101)));
    }

    @Test
    public void phoneFormattingIsNormalizedWithoutChangingCountryCode() {
        assertEquals("+8801712345678", PatientProfileValidation.normalizePhone(" +880 (1712) 345-678 "));
        assertEquals("01712345678", PatientProfileValidation.normalizePhone("01712 345678"));
        assertEquals("", PatientProfileValidation.normalizePhone(null));
    }

    @Test
    public void phoneMustHaveSevenToFifteenDigitsAndOptionalLeadingPlus() {
        assertTrue(PatientProfileValidation.isValidPhone("+8801712345678"));
        assertTrue(PatientProfileValidation.isValidPhone("01712345678"));
        assertTrue(PatientProfileValidation.isValidPhone("1234567"));
        assertTrue(PatientProfileValidation.isValidPhone("123456789012345"));
        assertFalse(PatientProfileValidation.isValidPhone(null));
        assertFalse(PatientProfileValidation.isValidPhone(""));
        assertFalse(PatientProfileValidation.isValidPhone("123456"));
        assertFalse(PatientProfileValidation.isValidPhone("1234567890123456"));
        assertFalse(PatientProfileValidation.isValidPhone("++8801712345678"));
        assertFalse(PatientProfileValidation.isValidPhone("01712ABC678"));
    }
}
