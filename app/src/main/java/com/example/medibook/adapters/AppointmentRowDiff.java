package com.example.medibook.adapters;

import androidx.recyclerview.widget.DiffUtil;
import com.example.medibook.models.Appointment;
import com.example.medibook.utils.AppointmentDisplayStatus;
import java.util.List;

/** Compare what a row actually displays, including time-derived status at each submission. */
final class AppointmentRowDiff extends DiffUtil.Callback {
    private final List<Appointment> before, after;
    private final boolean oldAdmin, newAdmin;
    private final long oldTime, newTime;
    AppointmentRowDiff(List<Appointment> before, List<Appointment> after, boolean oldAdmin,
                       boolean newAdmin, long oldTime, long newTime) {
        this.before = before; this.after = after; this.oldAdmin = oldAdmin; this.newAdmin = newAdmin;
        this.oldTime = oldTime; this.newTime = newTime;
    }
    @Override public int getOldListSize() { return before.size(); }
    @Override public int getNewListSize() { return after.size(); }
    @Override public boolean areItemsTheSame(int oldPosition, int newPosition) {
        return before.get(oldPosition).getId().equals(after.get(newPosition).getId());
    }
    @Override public boolean areContentsTheSame(int oldPosition, int newPosition) {
        Appointment a = before.get(oldPosition), b = after.get(newPosition);
        return oldAdmin == newAdmin && a.getPatientUid().equals(b.getPatientUid())
                && a.getDoctorName().equals(b.getDoctorName())
                && (!newAdmin || a.getPatientName().equals(b.getPatientName()))
                && a.getAppointmentDate().equals(b.getAppointmentDate())
                && a.getStartMinute() == b.getStartMinute() && a.getEndMinute() == b.getEndMinute()
                && a.getSlotSerial() == b.getSlotSerial()
                && AppointmentDisplayStatus.of(a, oldTime) == AppointmentDisplayStatus.of(b, newTime);
    }
}
