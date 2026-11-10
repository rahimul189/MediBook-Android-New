package com.example.medibook.repositories;

import com.example.medibook.models.Appointment;
import com.example.medibook.models.UserProfile;
import com.example.medibook.utils.Diagnostics;
import com.google.android.gms.tasks.Task;
import java.util.List;

/** Foreground screen reads only. Booking/cancellation/reconciliation continue using their own reads. */
public final class AppointmentReadRepository {
    private static final SingleFlightReads READS = new SingleFlightReads();
    private final AppointmentRepository source = new AppointmentRepository();

    public Task<List<Appointment>> list(String owner, boolean admin) {
        return READS.run(owner, "list/" + admin, () -> {
            Diagnostics.event("Read", "appointment list started admin=" + admin);
            return source.list(owner, admin);
        });
    }
    public Task<Appointment> get(String owner, String id, UserProfile.Role role) {
        return READS.run(owner, "get/" + role.name() + "/" + id, () -> {
            Diagnostics.event("Read", "appointment details started");
            return source.get(owner, id);
        });
    }
}
