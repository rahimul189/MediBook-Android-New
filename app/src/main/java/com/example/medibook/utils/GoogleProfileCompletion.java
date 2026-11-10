package com.example.medibook.utils;

import com.example.medibook.models.UserProfile;
import java.util.HashMap;
import java.util.Map;

/** Pure create/complete/no-op plan, used inside the server transaction to handle retry/races. */
public final class GoogleProfileCompletion {
    public enum Plan { CREATE, UPDATE_DETAILS, ALREADY_COMPLETE, DENY }
    public enum Invalid { NONE, NAME, PHONE, BIRTH_DATE, GENDER }
    private GoogleProfileCompletion() { }

    public static Invalid validate(String name, String phone, String dob, String gender, long now) {
        if (!PatientProfileValidation.isValidName(name == null ? null : name.trim())) return Invalid.NAME;
        if (!PatientProfileValidation.isValidPhone(PatientProfileValidation.normalizePhone(phone))) return Invalid.PHONE;
        if (!PatientAge.isValidIsoDate(dob) || !PatientAge.isValidBirthDate(dob, now)) return Invalid.BIRTH_DATE;
        if (!PatientGender.isWireValue(gender)) return Invalid.GENDER;
        return Invalid.NONE;
    }

    public static Plan plan(Map<String, Object> claims, Map<String, Object> existing, String authenticatedEmail, long now) {
        if (claims == null || claims.containsKey("admin") || authenticatedEmail == null || authenticatedEmail.trim().isEmpty()) return Plan.DENY;
        if (existing == null) return Plan.CREATE;
        SessionPolicy.Result result = SessionPolicy.evaluate(claims, existing, authenticatedEmail);
        if (result.user == null || result.user.getRole() != UserProfile.Role.PATIENT) return Plan.DENY;
        return GoogleAccountPolicy.complete(result.user, now) ? Plan.ALREADY_COMPLETE : Plan.UPDATE_DETAILS;
    }

    public static Map<String, Object> details(String name, String phone, String dob, String gender, long now) {
        if (validate(name, phone, dob, gender, now) != Invalid.NONE) throw new IllegalArgumentException("Invalid patient details");
        Map<String, Object> fields = new HashMap<>();
        fields.put("fullName", name.trim()); fields.put("phone", PatientProfileValidation.normalizePhone(phone));
        fields.put("dateOfBirth", dob); fields.put("gender", gender); return fields;
    }
}
