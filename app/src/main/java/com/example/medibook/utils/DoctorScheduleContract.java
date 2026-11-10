package com.example.medibook.utils;

import android.content.Intent;
import com.example.medibook.repositories.DoctorMapper;

/** ID-only, doctor/account-scoped navigation. Records are always fetched from the server. */
public final class DoctorScheduleContract {
    public static final String EXTRA_DOCTOR_ID = "com.example.medibook.schedule.DOCTOR_ID";
    public static final String EXTRA_DOCTOR_NAME = "com.example.medibook.schedule.DOCTOR_NAME";
    public static final String EXTRA_OWNER = "com.example.medibook.schedule.OWNER";
    public static final String EXTRA_SCHEDULE_ID = "com.example.medibook.schedule.ID";
    public static final String EXTRA_CHANGE = "com.example.medibook.schedule.CHANGE";
    public static final int ADDED = 1;
    public static final int EDITED = 2;

    private DoctorScheduleContract() { }

    public static void putContext(Intent intent, String doctorId, String doctorName, String owner) {
        intent.putExtra(EXTRA_DOCTOR_ID, doctorId);
        intent.putExtra(EXTRA_DOCTOR_NAME, doctorName);
        intent.putExtra(EXTRA_OWNER, owner);
    }

    public static boolean validContext(String id, String name, String owner) {
        return DoctorMapper.isValidId(id) && name != null && name.length() <= 100
                && name.matches(".*\\S.*") && owner != null && !owner.isEmpty();
    }

}
