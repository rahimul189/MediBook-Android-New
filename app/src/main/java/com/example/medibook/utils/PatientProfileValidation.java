package com.example.medibook.utils;

/** Shared, non-Android checks matching the limits in firestore.rules. */
public final class PatientProfileValidation {

    private PatientProfileValidation() {
    }

    public static boolean isValidName(String name) {
        return name != null && name.length() <= 100 && name.matches(".*\\S.*");
    }

    public static String normalizePhone(String phone) {
        return phone == null ? "" : phone.trim().replaceAll("[\\s().-]", "");
    }

    public static boolean isValidPhone(String phone) {
        return phone != null && phone.matches("\\+?[0-9]{7,15}");
    }
}
