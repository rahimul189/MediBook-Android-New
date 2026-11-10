package com.example.medibook.utils;

import com.example.medibook.models.UserProfile;
import java.util.Map;
import java.util.Set;

/** Provider-neutral authorization stays in SessionPolicy. Google never grants a role. */
public final class GoogleAccountPolicy {
    public enum Destination { EXISTING_SESSION, COMPLETE_PROFILE, DENIED }
    private GoogleAccountPolicy() { }

    public static boolean complete(UserProfile profile, long now) {
        return profile != null && PatientProfileValidation.isValidName(profile.getFullName())
                && PatientProfileValidation.isValidPhone(profile.getPhone())
                && PatientAge.isValidIsoDate(profile.getDateOfBirth())
                && PatientAge.isValidBirthDate(profile.getDateOfBirth(), now)
                && PatientGender.isWireValue(profile.getGender());
    }

    public static Destination destination(boolean googleProvider, boolean verifiedEmail,
                                          Map<String, Object> claims, boolean profileExists,
                                          SessionPolicy.Result result, long now) {
        if (claims == null) return Destination.DENIED;
        if (!profileExists) {
            return googleProvider && verifiedEmail && !claims.containsKey("admin")
                    ? Destination.COMPLETE_PROFILE : Destination.DENIED;
        }
        if (result == null || result.failure != SessionPolicy.Failure.NONE) return Destination.DENIED;
        if (result.user.getRole() == UserProfile.Role.ADMIN) return Destination.EXISTING_SESSION;
        if (googleProvider && !verifiedEmail) return Destination.DENIED;
        return googleProvider && !complete(result.user, now)
                ? Destination.COMPLETE_PROFILE : Destination.EXISTING_SESSION;
    }

    public static boolean canLink(UserProfile profile, Set<String> providers) {
        return profile != null && profile.getRole() == UserProfile.Role.PATIENT
                && providers != null && providers.contains("password") && !providers.contains("google.com");
    }

    /** Intentionally same-email only: linking must not change the immutable profile email. */
    public static boolean sameEmail(String authenticatedEmail, String selectedEmail) {
        return authenticatedEmail != null && !authenticatedEmail.isEmpty()
                && authenticatedEmail.equals(selectedEmail);
    }

    public static boolean preservedUid(String original, String current, String returned) {
        return original != null && original.equals(current) && original.equals(returned);
    }

    public static boolean linkConfirmed(String original, String current, String returned, Set<String> providers) {
        return preservedUid(original, current, returned) && providers != null
                && providers.contains("password") && providers.contains("google.com");
    }

    public static boolean hasIdToken(String token) { return token != null && !token.trim().isEmpty(); }

    public static boolean conflictCode(String code) {
        return "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL".equals(code)
                || "ERROR_EMAIL_ALREADY_IN_USE".equals(code)
                || "ERROR_CREDENTIAL_ALREADY_IN_USE".equals(code);
    }
}
