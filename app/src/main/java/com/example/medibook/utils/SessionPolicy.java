package com.example.medibook.utils;

import com.example.medibook.models.UserProfile;
import com.google.firebase.Timestamp;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic session validation, separate from network/navigation for local unit testing.
 * Call only with refreshed Firebase token claims and the owner's server-fetched profile.
 */
public final class SessionPolicy {
    private static final Set<String> PROFILE_FIELDS = new HashSet<>(
            Arrays.asList("fullName", "email", "phone", "role", "createdAt"));
    /** Optional personal details: legacy accounts remain valid without them. */
    private static final Set<String> OPTIONAL_PROFILE_FIELDS = new HashSet<>(
            Arrays.asList("dateOfBirth", "gender"));

    public enum Failure { NONE, INVALID_PROFILE, ROLE_MISMATCH }

    public static final class Result {
        public final UserProfile user;
        public final Failure failure;

        private Result(UserProfile user, Failure failure) {
            this.user = user;
            this.failure = failure;
        }
    }

    private SessionPolicy() {
    }

    public static Result evaluate(Map<String, Object> claims, Map<String, Object> profile,
                                  String authenticatedEmail) {
        if (claims == null || profile == null) {
            return denied(Failure.INVALID_PROFILE);
        }
        Set<String> requiredFields = new HashSet<>(profile.keySet());
        requiredFields.removeAll(OPTIONAL_PROFILE_FIELDS);
        if (!PROFILE_FIELDS.equals(requiredFields)) {
            return denied(Failure.INVALID_PROFILE);
        }
        Object name = profile.get("fullName");
        Object email = profile.get("email");
        Object phone = profile.get("phone");
        if (!(name instanceof String) || !PatientProfileValidation.isValidName((String) name)
                || !(email instanceof String) || ((String) email).trim().isEmpty()
                || ((String) email).length() > 254 || !email.equals(authenticatedEmail)
                || !(phone instanceof String) || !PatientProfileValidation.isValidPhone((String) phone)
                || !(profile.get("createdAt") instanceof Timestamp)) {
            return denied(Failure.INVALID_PROFILE);
        }
        Object dateOfBirth = profile.get("dateOfBirth");
        if (dateOfBirth != null
                && !(dateOfBirth instanceof String && PatientAge.isValidIsoDate((String) dateOfBirth))) {
            return denied(Failure.INVALID_PROFILE);
        }
        Object gender = profile.get("gender");
        // Legacy values stay readable; new writes are restricted to Male/Female in the UI and rules.
        if (gender != null && !(gender instanceof String && PatientGender.isReadable((String) gender))) {
            return denied(Failure.INVALID_PROFILE);
        }

        UserProfile.Role role;
        if (Boolean.TRUE.equals(claims.get("admin"))
                && UserProfile.Role.ADMIN.getProfileValue().equals(profile.get("role"))) {
            role = UserProfile.Role.ADMIN;
        } else if (!claims.containsKey("admin")
                && UserProfile.Role.PATIENT.getProfileValue().equals(profile.get("role"))) {
            role = UserProfile.Role.PATIENT;
        } else {
            // A profile role alone, or an explicit malformed/false Admin claim, grants nothing.
            return denied(Failure.ROLE_MISMATCH);
        }
        return new Result(new UserProfile((String) name, (String) email, (String) phone, role,
                dateOfBirth == null ? null : (String) dateOfBirth,
                gender == null ? null : (String) gender), Failure.NONE);
    }

    public static Result evaluatePatientRegistration(Map<String, Object> claims,
                                                     Map<String, Object> profile,
                                                     String authenticatedEmail) {
        Result result = evaluate(claims, profile, authenticatedEmail);
        if (result.user != null && result.user.getRole() != UserProfile.Role.PATIENT) {
            return denied(Failure.ROLE_MISMATCH);
        }
        return result;
    }

    private static Result denied(Failure failure) {
        return new Result(null, failure);
    }
}
