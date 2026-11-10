package com.example.medibook.utils;

import com.example.medibook.models.Doctor;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Local form checks only. These are not Firebase Security Rules or server validation. */
public final class DoctorFormValidation {
    public enum Error { NONE, REQUIRED, TOO_LONG, INVALID_TEXT, INVALID_AMOUNT }

    private static final BigDecimal MAX_FEE = new BigDecimal("999999.99");

    public static final class Result {
        public final Error nameError;
        public final Error specializationError;
        public final Error qualificationsError;
        public final Error feeError;
        public final Error biographyError;
        private final String name;
        private final String specialization;
        private final String qualifications;
        private final String biography;
        private final BigDecimal fee;

        private Result(String name, String specialization, String qualifications,
                       String feeText, String biography) {
            this.name = name;
            this.specialization = specialization;
            this.qualifications = qualifications;
            this.biography = biography;
            nameError = validateText(name, 100, true, false);
            specializationError = validateText(specialization, 100, true, false);
            qualificationsError = validateText(qualifications, 200, true, false);
            biographyError = validateText(biography, 1000, false, true);
            feeError = validateFee(feeText);
            fee = feeError == Error.NONE
                    ? new BigDecimal(feeText).setScale(2, RoundingMode.UNNECESSARY) : null;
        }

        public boolean isValid() {
            return nameError == Error.NONE && specializationError == Error.NONE
                    && qualificationsError == Error.NONE && feeError == Error.NONE
                    && biographyError == Error.NONE;
        }

        public Doctor toDoctor(String doctorId, boolean active) {
            if (!isValid()) throw new IllegalStateException("Invalid doctor form");
            return new Doctor(doctorId, name, specialization, qualifications, fee, biography, active);
        }
    }

    private DoctorFormValidation() { }

    public static Result validate(String name, String specialization, String qualifications,
                                  String fee, String biography) {
        return new Result(normalize(name), normalize(specialization), normalize(qualifications),
                normalize(fee), normalize(biography));
    }

    /** Trim Unicode spacing at the edges without changing names or multiline biographies. */
    private static String normalize(String value) {
        if (value == null) return "";
        int start = 0;
        int end = value.length();
        while (start < end && isSpace(value.charAt(start))) start++;
        while (end > start && isSpace(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean isSpace(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character);
    }

    private static Error validateText(String value, int maxLength, boolean required, boolean multiline) {
        if (required && value.isEmpty()) return Error.REQUIRED;
        if (value.length() > maxLength) return Error.TOO_LONG;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean allowedLineBreak = multiline && (character == '\n' || character == '\r');
            if ((Character.isISOControl(character) && !allowedLineBreak)
                    || character == '\u2028' || character == '\u2029') return Error.INVALID_TEXT;
        }
        return Error.NONE;
    }

    private static Error validateFee(String value) {
        if (value.isEmpty()) return Error.REQUIRED;
        // Avoid floating-point rounding, signs, exponents, grouping separators and unbounded input.
        if (value.length() > 12 || !value.matches("[0-9]+(?:\\.[0-9]{1,2})?")) {
            return Error.INVALID_AMOUNT;
        }
        return new BigDecimal(value).compareTo(MAX_FEE) <= 0 ? Error.NONE : Error.INVALID_AMOUNT;
    }
}
