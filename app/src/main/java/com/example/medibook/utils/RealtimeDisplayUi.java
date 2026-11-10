package com.example.medibook.utils;

import android.content.Context;
import com.example.medibook.R;
import com.example.medibook.models.Appointment;

public final class RealtimeDisplayUi {
    private RealtimeDisplayUi() { }
    public static String dashboard(Context context, int status, Appointment a, boolean admin) {
        if (a == null) return context.getString(status);
        String token = SlotSerials.label(a.getSlotSerial(), a.getStartMinute(), a.getEndMinute());
        String text = admin ? context.getString(R.string.live_booking_alert, a.getDoctorName(), token,
                AppointmentUi.date(a.getAppointmentDate().toDate().getTime()), DhakaDateTime.formatTime12Hour(a.getStartMinute()))
                : context.getString(R.string.live_patient_alert, AppointmentUi.status(context, a), token,
                a.getDoctorName(), AppointmentUi.date(a.getAppointmentDate().toDate().getTime()));
        return status == R.string.live_offline ? text + "\n" + context.getString(status) : text;
    }
}
