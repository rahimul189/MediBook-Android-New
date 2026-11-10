package com.example.medibook.utils;

import com.example.medibook.models.Doctor;
import com.example.medibook.repositories.DoctorMapper;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

/** Pure presentation operations on supplied records. No repository calls, fixtures or persistence. */
public final class PatientDoctorDirectory {
    public static final int MAX_QUERY_LENGTH = 100;

    private PatientDoctorDirectory() { }

    public static boolean validDoctor(Doctor doctor) {
        return doctor != null && DoctorMapper.isValidId(doctor.getId())
                && DoctorFormValidation.validate(doctor.getName(), doctor.getSpecialization(),
                doctor.getQualifications(), doctor.getConsultationFee().toPlainString(), doctor.getBiography()).isValid();
    }

    public static List<Doctor> activeRecords(List<Doctor> supplied) {
        if (supplied == null) throw new IllegalArgumentException("Missing directory input");
        List<Doctor> active = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Doctor doctor : supplied) {
            if (!validDoctor(doctor) || !ids.add(doctor.getId())) throw new IllegalArgumentException("Invalid directory input");
            if (doctor.isActive()) active.add(doctor);
        }
        active.sort(Comparator.comparing(Doctor::getName, String.CASE_INSENSITIVE_ORDER).thenComparing(Doctor::getId));
        return Collections.unmodifiableList(active);
    }

    public static List<String> specializations(List<Doctor> active) {
        TreeMap<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Doctor doctor : active) {
            if (doctor.isActive()) {
                String specialization = doctor.getSpecialization().trim();
                values.putIfAbsent(specialization, specialization);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(values.values()));
    }

    public static List<Doctor> filter(List<Doctor> active, String query, String specialization) {
        String name = normalize(query);
        String selected = specialization == null ? "" : specialization.trim();
        List<Doctor> visible = new ArrayList<>();
        for (Doctor doctor : active) {
            if (doctor.isActive() && normalize(doctor.getName()).contains(name)
                    && (selected.isEmpty() || doctor.getSpecialization().trim().equalsIgnoreCase(selected))) visible.add(doctor);
        }
        return Collections.unmodifiableList(visible);
    }

    public static String boundedQuery(String query) {
        if (query == null) return "";
        return query.substring(0, Math.min(query.length(), MAX_QUERY_LENGTH));
    }

    public static String feeText(Doctor doctor) {
        NumberFormat format = NumberFormat.getNumberInstance();
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(doctor.getConsultationFee());
    }

    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
}
