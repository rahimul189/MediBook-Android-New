package com.example.medibook.models;

/**
 * Display data for a verified session, not an independent source of Admin authorization.
 * Date of birth is an ISO {@code yyyy-MM-dd} calendar date and gender is one of the
 * {@code PatientGender} wire values; both are optional for legacy accounts.
 */
public final class UserProfile {

    public enum Role {
        PATIENT("Patient"), ADMIN("Admin");

        private final String profileValue;

        Role(String profileValue) {
            this.profileValue = profileValue;
        }

        public String getProfileValue() {
            return profileValue;
        }
    }

    private final String fullName;
    private final String email;
    private final String phone;
    private final Role role;
    private final String dateOfBirth;
    private final String gender;

    public UserProfile(String fullName, String email, String phone, Role role) {
        this(fullName, email, phone, role, null, null);
    }

    public UserProfile(String fullName, String email, String phone, Role role,
                       String dateOfBirth, String gender) {
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.role = role;
        this.dateOfBirth = dateOfBirth;
        this.gender = gender;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public Role getRole() {
        return role;
    }

    /** ISO {@code yyyy-MM-dd}, or null when the patient has not provided it. */
    public String getDateOfBirth() {
        return dateOfBirth;
    }

    /** One of the allowed gender wire values, or null when not provided. */
    public String getGender() {
        return gender;
    }

    public boolean hasPersonalDetails() {
        return dateOfBirth != null && gender != null;
    }
}
