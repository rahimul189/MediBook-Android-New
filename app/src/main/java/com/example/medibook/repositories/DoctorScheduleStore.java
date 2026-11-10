package com.example.medibook.repositories;

import com.example.medibook.models.DoctorSchedule;
import com.google.android.gms.tasks.Task;

/** Small test seam: production uses Firestore; unit tests use manually completed Tasks. */
public interface DoctorScheduleStore {
    boolean isCurrentUser(String owner);
    String newScheduleId(String doctorId);
    Task<DoctorScheduleRepository.Directory> load(String owner, String doctorId);
    Task<Void> add(String owner, String doctorId, DoctorSchedule draft);
    Task<Void> edit(String owner, String doctorId, DoctorSchedule original, DoctorSchedule draft);
    Task<Void> setActive(String owner, String doctorId, DoctorSchedule original, boolean active);
}
