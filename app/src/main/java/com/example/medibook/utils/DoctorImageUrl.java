package com.example.medibook.utils;

/** Small shared URL policy: optional, public HTTPS URL, no credentials, port, fragment or spaces. */
public final class DoctorImageUrl {
    public static final int MAX_LENGTH = 2048;
    private DoctorImageUrl() { }
    public static boolean isValid(String value) {
        return value != null && value.length() <= MAX_LENGTH && (value.isEmpty()
                || value.matches("https://[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9](/[A-Za-z0-9._~!$&()*+,;=:@%/?-]*)?"));
    }
}
