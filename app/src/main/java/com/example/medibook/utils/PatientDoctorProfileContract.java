package com.example.medibook.utils;

import android.content.Intent;
import com.example.medibook.models.Doctor;
import com.example.medibook.repositories.DoctorMapper;

/** Path identity only. Extras never establish authorization or supply trusted profile fields. */
public final class PatientDoctorProfileContract {
    public static final String EXTRA_OWNER = "com.example.medibook.patient.OWNER";
    public static final String EXTRA_ID = "com.example.medibook.patient.DOCTOR_ID";
    private PatientDoctorProfileContract() { }
    public static void putDoctorContext(Intent intent, String owner, Doctor doctor) {
        if (!DoctorMapper.isValidId(doctor.getId()) || !doctor.isActive()) throw new IllegalArgumentException("Invalid doctor");
        intent.putExtra(EXTRA_OWNER, owner);
        intent.putExtra(EXTRA_ID, doctor.getId());
    }
}
