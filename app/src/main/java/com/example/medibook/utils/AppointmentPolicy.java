package com.example.medibook.utils;

import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentStatus;
import com.example.medibook.models.DoctorSchedule;
import com.example.medibook.models.Doctor;
import com.example.medibook.models.InventorySlot;
import com.example.medibook.models.UserProfile;

import java.util.Locale;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** Small offline policy shared by repository, screens and tests. Rules remain authoritative. */
public final class AppointmentPolicy {
    private AppointmentPolicy() { }

    public static boolean fits(InventorySlot slot, DoctorSchedule schedule) {
        return slot != null && schedule != null && schedule.isActive() && slot.scheduleId.equals(schedule.getId())
                && slot.dayOfWeek == schedule.getDayOfWeek() && slot.startMinute >= schedule.getStartMinute()
                && slot.endMinute <= schedule.getEndMinute()
                && slot.endMinute - slot.startMinute == schedule.getSlotMinutes()
                && (slot.startMinute - schedule.getStartMinute()) % schedule.getSlotMinutes() == 0;
    }

    public static boolean canCancel(Appointment appointment, long nowMillis) {
        return appointment != null && appointment.getStatus() == AppointmentStatus.CONFIRMED
                && appointment.getStartAt().toDate().getTime() > nowMillis;
    }

    /**
     * Reminders are patient-private: only the verified Patient who owns this eligible future
     * appointment may see or change them. A verified Admin (or any other role/owner mismatch)
     * never qualifies, regardless of the local session epoch.
     */
    public static boolean canCustomRemind(Appointment appointment, UserProfile.Role role, String viewerUid,
                                          long nowMillis) {
        return role == UserProfile.Role.PATIENT && appointment != null && viewerUid != null
                && viewerUid.equals(appointment.getPatientUid()) && canCancel(appointment, nowMillis);
    }

    public static boolean sameQuote(Doctor shown, Doctor current) {
        return shown != null && current != null && current.isActive()
                && shown.getId().equals(current.getId()) && shown.getName().equals(current.getName())
                && shown.getSpecialization().equals(current.getSpecialization())
                && shown.getConsultationFee().compareTo(current.getConsultationFee()) == 0;
    }

    public static boolean hasScheduleOn(List<DoctorSchedule> schedules, long midnight) {
        Calendar date = Calendar.getInstance(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
        date.setTimeInMillis(midnight);
        int weekday = DhakaDateTime.isoDayOfWeek(date.get(Calendar.YEAR), date.get(Calendar.MONTH) + 1, date.get(Calendar.DAY_OF_MONTH));
        for (DoctorSchedule schedule : schedules) if (schedule.isActive() && schedule.getDayOfWeek() == weekday) return true;
        return false;
    }

    /** Suggest a matching future calendar date, instead of publishing today's wrong weekday. */
    public static long nextBookingDate(List<DoctorSchedule> schedules, long now) {
        Calendar date = Calendar.getInstance(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
        date.setTimeInMillis(now);
        for (int offset = 1; offset <= 7; offset++) {
            date.add(Calendar.DAY_OF_MONTH, 1);
            long midnight = DhakaDateTime.localDateAtMidnight(date.get(Calendar.YEAR), date.get(Calendar.MONTH) + 1, date.get(Calendar.DAY_OF_MONTH));
            if (hasScheduleOn(schedules, midnight)) return midnight;
        }
        return -1;
    }

    /** A read of an unchanged Confirmed appointment is NOT successful cancellation evidence. */
    public static boolean reconciles(Appointment appointment, boolean cancellation) {
        return appointment != null && (!cancellation || appointment.getStatus() == AppointmentStatus.CANCELLED);
    }

    /** Stable time-order ordinal, not a dense patient count. Rebooking the same slot keeps it. */
    public static String queueToken(int startMinute) {
        if (startMinute < 0 || startMinute > 1439) throw new IllegalArgumentException("Invalid minute");
        return String.format(Locale.ROOT, "Q%04d", startMinute + 1);
    }
}
