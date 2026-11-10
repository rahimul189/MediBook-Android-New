package com.example.medibook.utils;

import android.app.DatePickerDialog;
import android.content.Context;

import com.example.medibook.R;
import com.example.medibook.models.Appointment;
import com.example.medibook.repositories.AppointmentRepository;
import com.google.firebase.FirebaseApp;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class AppointmentUi {
    private AppointmentUi() { }
    public interface DateSelected { void selected(int year, int month, int day); }

    public static String date(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID)); return format.format(new Date(millis));
    }
    public static long today() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
        return DhakaDateTime.localDateAtMidnight(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }
    public static void pickDate(Context context, long initial, DateSelected listener) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
        c.setTimeInMillis(initial);
        new DatePickerDialog(context, (view, year, month, day) -> listener.selected(year, month + 1, day),
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }
    public static String status(Context context, Appointment a) {
        switch (AppointmentDisplayStatus.of(a, System.currentTimeMillis())) {
            case CANCELLED: return context.getString(R.string.appointment_cancelled);
            case COMPLETED: return context.getString(R.string.appointment_completed);
            default: return context.getString(R.string.appointment_confirmed);
        }
    }
    public static String summary(Context context, Appointment a) {
        return context.getString(R.string.appointment_summary, a.getDoctorName(), a.getDoctorSpecialization(),
                date(a.getAppointmentDate().toDate().getTime()), DhakaDateTime.formatTime12Hour(a.getStartMinute()),
                DhakaDateTime.formatTime12Hour(a.getEndMinute()), status(context, a), SlotSerials.label(a.getSlotSerial(), a.getStartMinute(), a.getEndMinute()),
                BigDecimal.valueOf(a.getConsultationFee(), 2).toPlainString());
    }
    public static int error(Exception exception) {
        switch (AppointmentRepository.errorFor(exception)) {
            case SLOT_TAKEN: return R.string.appointment_slot_taken;
            case PERMISSION: return R.string.appointment_permission;
            case NETWORK: return R.string.appointment_network;
            case NOT_ELIGIBLE: return R.string.appointment_not_eligible;
            case SESSION_CHANGED: return R.string.appointment_session_changed;
            case INVALID_DATA: return R.string.appointment_invalid;
            case FAILED_PRECONDITION: return R.string.appointment_precondition;
            case MISSING_DOCUMENT: return R.string.appointment_missing_document;
            case DOCTOR_INACTIVE: return R.string.appointment_doctor_inactive;
            case DATE_BLOCKED: return R.string.appointment_date_blocked;
            case NO_ACTIVE_SCHEDULE: return R.string.appointment_no_schedule;
            case NO_FUTURE_SLOTS: return R.string.appointment_no_future_slots;
            case NO_INVENTORY: return R.string.appointment_unpublished;
            case NO_AVAILABLE_SLOTS: return R.string.appointment_fully_booked;
            default: return R.string.appointment_error;
        }
    }

    public static String projectId() {
        String project = FirebaseApp.getInstance().getOptions().getProjectId();
        return project == null ? "unknown" : project;
    }

    /** Codes/context only: no raw document bodies, phone numbers, ID tokens or credentials. */
    public static String errorText(Context context, Exception exception) {
        AppointmentRepository.OperationFailure location = null;
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
            if (cause instanceof AppointmentRepository.OperationFailure) location = (AppointmentRepository.OperationFailure) cause;
        }
        String diagnostic = AppointmentRepository.errorCode(exception)
                + (location == null ? "" : " · " + location.operation + "\n" + location.path)
                + "\nProject: " + projectId();
        android.util.Log.w("MediBookAppointments", diagnostic, exception);
        return context.getString(error(exception));
    }
}
