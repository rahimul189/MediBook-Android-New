package com.example.medibook.utils;

import com.example.medibook.models.Doctor;

import org.junit.Test;

import java.math.BigDecimal;

import static com.example.medibook.utils.DoctorFormValidation.Error.*;
import static org.junit.Assert.*;

/** Synthetic form values only. Never initializes Firebase or seeds the application's doctor list. */
public class DoctorFormValidationTest {
    private DoctorFormValidation.Result validWithFee(String fee) {
        return DoctorFormValidation.validate("Preview Name", "Preview specialty", "Preview qualification", fee, "");
    }

    @Test
    public void validFormCreatesNormalizedPreviewWithoutCurrencyRounding() {
        DoctorFormValidation.Result result = DoctorFormValidation.validate(
                "  Preview Name  ", " Preview specialty ", " Preview qualification ", " 12.5 ", " Preview bio ");
        assertTrue(result.isValid());
        Doctor preview = result.toDoctor("preview-1", false);
        assertEquals("Preview Name", preview.getName());
        assertEquals("Preview specialty", preview.getSpecialization());
        assertEquals("Preview qualification", preview.getQualifications());
        assertEquals("Preview bio", preview.getBiography());
        assertEquals(new BigDecimal("12.50"), preview.getConsultationFee());
        assertFalse(preview.isActive());
    }

    @Test
    public void nullRequiredFieldsAreRejectedButBiographyIsOptional() {
        DoctorFormValidation.Result result = DoctorFormValidation.validate(null, null, null, null, null);
        assertEquals(REQUIRED, result.nameError);
        assertEquals(REQUIRED, result.specializationError);
        assertEquals(REQUIRED, result.qualificationsError);
        assertEquals(REQUIRED, result.feeError);
        assertEquals(NONE, result.biographyError);
        assertFalse(result.isValid());
    }

    @Test
    public void unicodeWhitespaceDoesNotSatisfyRequiredFields() {
        DoctorFormValidation.Result result = DoctorFormValidation.validate("\u00a0\u2003", "\t\n", "  ", " ", "\u00a0");
        assertEquals(REQUIRED, result.nameError);
        assertEquals(REQUIRED, result.specializationError);
        assertEquals(REQUIRED, result.qualificationsError);
        assertEquals(REQUIRED, result.feeError);
        assertEquals(NONE, result.biographyError);
    }

    @Test
    public void textLengthsAcceptTheirBoundaries() {
        assertTrue(DoctorFormValidation.validate("N".repeat(100), "S".repeat(100),
                "Q".repeat(200), "1", "B".repeat(1000)).isValid());
    }

    @Test
    public void textLengthsRejectValuesAboveTheirBoundaries() {
        DoctorFormValidation.Result result = DoctorFormValidation.validate("N".repeat(101), "S".repeat(101),
                "Q".repeat(201), "1", "B".repeat(1001));
        assertEquals(TOO_LONG, result.nameError);
        assertEquals(TOO_LONG, result.specializationError);
        assertEquals(TOO_LONG, result.qualificationsError);
        assertEquals(TOO_LONG, result.biographyError);
    }

    @Test
    public void singleLineFieldsRejectEmbeddedControlCharacters() {
        DoctorFormValidation.Result result = DoctorFormValidation.validate("Preview\nName", "Preview\tSpecialty",
                "Preview\u0000Qualification", "1", "");
        assertEquals(INVALID_TEXT, result.nameError);
        assertEquals(INVALID_TEXT, result.specializationError);
        assertEquals(INVALID_TEXT, result.qualificationsError);
    }

    @Test
    public void unicodeLineSeparatorsAreNotAcceptedInSingleLineFields() {
        assertEquals(INVALID_TEXT, DoctorFormValidation.validate("Preview\u2028Name", "Specialty",
                "Qualification", "1", "").nameError);
    }

    @Test
    public void biographyAllowsNormalParagraphBreaks() {
        DoctorFormValidation.Result result = DoctorFormValidation.validate("Preview", "Specialty", "Qualification",
                "1", "First paragraph.\r\n\nSecond paragraph.");
        assertTrue(result.isValid());
    }

    @Test
    public void biographyRejectsNonprintingControlCharacters() {
        assertEquals(INVALID_TEXT, DoctorFormValidation.validate("Preview", "Specialty", "Qualification",
                "1", "Text\u0007text").biographyError);
    }

    @Test
    public void namesAllowInternationalTextAndPunctuation() {
        assertTrue(DoctorFormValidation.validate("Preview O’Name-ডেমো", "Specialty", "Q. (Preview)", "0", "").isValid());
    }

    @Test
    public void zeroFeeIsValidRatherThanMissing() {
        for (String fee : new String[]{"0", "0.0", "0.00"}) {
            assertEquals(new BigDecimal("0.00"), validWithFee(fee).toDoctor("preview-1", true).getConsultationFee());
        }
    }

    @Test
    public void wholeAndTwoDecimalFeesRemainExact() {
        assertEquals(new BigDecimal("500.00"), validWithFee("500").toDoctor("preview-1", true).getConsultationFee());
        assertEquals(new BigDecimal("0.10"), validWithFee("0.10").toDoctor("preview-1", true).getConsultationFee());
        assertEquals(new BigDecimal("999999.99"), validWithFee("999999.99").toDoctor("preview-1", true).getConsultationFee());
    }

    @Test
    public void excessPrecisionIsRejectedRatherThanSilentlyRounded() {
        assertEquals(INVALID_AMOUNT, validWithFee("12.345").feeError);
        assertEquals(INVALID_AMOUNT, validWithFee("0.001").feeError);
    }

    @Test
    public void negativeAndSignedFeesAreRejected() {
        for (String fee : new String[]{"-1", "-0.00", "+1"}) assertEquals(INVALID_AMOUNT, validWithFee(fee).feeError);
    }

    @Test
    public void unsupportedNumericFormatsAreRejected() {
        for (String fee : new String[]{"NaN", "Infinity", "1e3", "1E+3", "1,000", "12,50", ".50", "1.", "1 2", "$10"}) {
            assertEquals(fee, INVALID_AMOUNT, validWithFee(fee).feeError);
        }
    }

    @Test
    public void overLimitAndUnboundedFeesAreRejected() {
        assertEquals(INVALID_AMOUNT, validWithFee("1000000").feeError);
        assertEquals(INVALID_AMOUNT, validWithFee("9".repeat(1000)).feeError);
    }

    @Test
    public void leadingZerosNormalizeWithoutChangingTheAmount() {
        assertEquals(new BigDecimal("5.50"), validWithFee("0005.50").toDoctor("preview-1", true).getConsultationFee());
    }

    @Test(expected = IllegalStateException.class)
    public void invalidFormCannotProduceADoctorPreview() {
        validWithFee("-1").toDoctor("preview-1", false);
    }
}
