package com.example.medibook.utils;

/**
 * Current gender wire values are Male and Female only. Two legacy values remain readable so
 * existing profiles still verify after an app update; they are never offered or written again.
 */
public final class PatientGender {

    public static final String MALE = "Male";
    public static final String FEMALE = "Female";

    /** Legacy values stored by earlier app versions. Read-only compatibility only. */
    public static final String LEGACY_OTHER = "Other";
    public static final String LEGACY_PREFER_NOT_TO_SAY = "Prefer not to say";

    /** Exactly the values a new registration or profile edit may save. */
    public static final String[] OPTIONS = {MALE, FEMALE};

    private PatientGender() { }

    /** True only for a value that may be written by this version of the app. */
    public static boolean isWireValue(String value) {
        return MALE.equals(value) || FEMALE.equals(value);
    }

    /** True when a stored profile value can still be read without rejecting the session. */
    public static boolean isReadable(String value) {
        return isWireValue(value) || LEGACY_OTHER.equals(value) || LEGACY_PREFER_NOT_TO_SAY.equals(value);
    }

    /** A current value suitable for display; legacy values deliberately map to "not provided". */
    public static String display(String value) {
        return isWireValue(value) ? value : null;
    }
}
