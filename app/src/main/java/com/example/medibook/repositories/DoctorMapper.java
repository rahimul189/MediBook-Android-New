package com.example.medibook.repositories;

import com.example.medibook.models.Doctor;
import com.example.medibook.utils.DoctorFormValidation;
import com.example.medibook.utils.DoctorImageUrl;
import com.google.firebase.Timestamp;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Explicit, unit-testable mapping. Firestore stores the fee as integer hundredths, not a double. */
public final class DoctorMapper {
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList("name", "specialization",
            "qualifications", "consultationFee", "biography", "active", "createdAt", "updatedAt"));

    private DoctorMapper() { }

    public static boolean isValidId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,128}");
    }

    public static Doctor fromMap(String id, Map<String, Object> data) {
        if (!isValidId(id) || data == null || !data.keySet().containsAll(FIELDS)
                || data.keySet().stream().anyMatch(key -> !FIELDS.contains(key) && !"imageUrl".equals(key))
                || !(data.get("name") instanceof String) || !(data.get("specialization") instanceof String)
                || !(data.get("qualifications") instanceof String) || !(data.get("biography") instanceof String)
                || !(data.get("active") instanceof Boolean) || !(data.get("createdAt") instanceof Timestamp)
                || !(data.get("updatedAt") instanceof Timestamp)) throw invalid();
        Object storedFee = data.get("consultationFee");
        if (!(storedFee instanceof Long) && !(storedFee instanceof Integer)) throw invalid();
        long hundredths = ((Number) storedFee).longValue();
        if (hundredths < 0 || hundredths > 99999999L) throw invalid();
        BigDecimal fee = BigDecimal.valueOf(hundredths, 2);
        Timestamp createdAt = (Timestamp) data.get("createdAt");
        Timestamp updatedAt = (Timestamp) data.get("updatedAt");
        if (updatedAt.compareTo(createdAt) < 0) throw invalid();
        DoctorFormValidation.Result validation = DoctorFormValidation.validate((String) data.get("name"),
                (String) data.get("specialization"), (String) data.get("qualifications"),
                fee.toPlainString(), (String) data.get("biography"));
        if (!validation.isValid()) throw invalid();
        // Bad legacy URLs affect only the avatar, never the availability of a Doctor profile.
        String image = data.get("imageUrl") instanceof String ? (String) data.get("imageUrl") : "";
        if (!DoctorImageUrl.isValid(image)) image = "";
        return new Doctor(id, (String) data.get("name"), (String) data.get("specialization"),
                (String) data.get("qualifications"), fee, (String) data.get("biography"),
                (Boolean) data.get("active"), image, createdAt, updatedAt);
    }

    public static Map<String, Object> createMap(Doctor draft, Object serverTimestamp) {
        Map<String, Object> data = updateMap(draft, serverTimestamp);
        data.put("createdAt", serverTimestamp);
        return data;
    }

    /** Deliberately excludes createdAt and ID, even when the form/model includes them. */
    public static Map<String, Object> updateMap(Doctor draft, Object serverTimestamp) {
        if (!isValidId(draft.getId()) || !DoctorFormValidation.validate(draft.getName(),
                draft.getSpecialization(), draft.getQualifications(), draft.getConsultationFee().toPlainString(),
                draft.getBiography()).isValid() || !DoctorImageUrl.isValid(draft.getImageUrl())) throw invalid();
        Map<String, Object> data = new HashMap<>();
        data.put("name", draft.getName());
        data.put("specialization", draft.getSpecialization());
        data.put("qualifications", draft.getQualifications());
        data.put("consultationFee", draft.getConsultationFee().movePointRight(2).longValueExact());
        data.put("biography", draft.getBiography());
        data.put("imageUrl", draft.getImageUrl());
        data.put("active", draft.isActive());
        data.put("updatedAt", serverTimestamp);
        return data;
    }

    public static Map<String, Object> statusMap(boolean active, Object serverTimestamp) {
        Map<String, Object> data = new HashMap<>();
        data.put("active", active);
        data.put("updatedAt", serverTimestamp);
        return data;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid doctor data");
    }
}
