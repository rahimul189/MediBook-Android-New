package com.example.medibook.utils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Date-of-birth helpers. A profile stores only the ISO calendar date {@code yyyy-MM-dd} in the
 * hospital timezone; age is always derived from the current date and never persisted.
 */
public final class PatientAge {

    public static final int EARLIEST_YEAR = 1900;
    private static final String ISO_PATTERN = "yyyy-MM-dd";
    /** SimpleDateFormat accepts variable-width numbers, so the shape is checked explicitly. */
    private static final Pattern ISO_SHAPE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");

    private PatientAge() { }

    private static SimpleDateFormat isoFormat() {
        SimpleDateFormat format = new SimpleDateFormat(ISO_PATTERN, Locale.US);
        format.setLenient(false);
        format.setTimeZone(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
        return format;
    }

    private static Calendar calendar(String iso) {
        if (iso == null) return null;
        try {
            Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
            calendar.setTime(isoFormat().parse(iso));
            return calendar;
        } catch (ParseException exception) {
            return null;
        }
    }

    /** Shape and real-calendar-date check only; use {@link #isValidBirthDate} for form input. */
    public static boolean isValidIsoDate(String iso) {
        return iso != null && ISO_SHAPE.matcher(iso).matches() && calendar(iso) != null;
    }

    /** A real date between 1900 and today in the hospital timezone. */
    public static boolean isValidBirthDate(String iso, long nowMillis) {
        Calendar birth = calendar(iso);
        if (birth == null || birth.get(Calendar.YEAR) < EARLIEST_YEAR) return false;
        Calendar today = Calendar.getInstance(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
        today.setTimeInMillis(nowMillis);
        return !birth.after(today);
    }

    /** Completed years, or null for a missing/invalid value. */
    public static Integer years(String iso, long nowMillis) {
        if (!isValidIsoDate(iso)) return null;
        Calendar birth = calendar(iso);
        if (birth == null) return null;
        Calendar today = Calendar.getInstance(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
        today.setTimeInMillis(nowMillis);
        int years = today.get(Calendar.YEAR) - birth.get(Calendar.YEAR);
        if (today.get(Calendar.DAY_OF_YEAR) < birth.get(Calendar.DAY_OF_YEAR)) years--;
        return years < 0 || years > 130 ? null : years;
    }

    /** Display form such as "12 Mar 1990", or null when the stored value is unusable. */
    public static String formatDisplay(String iso) {
        if (!isValidIsoDate(iso)) return null;
        Calendar birth = calendar(iso);
        if (birth == null) return null;
        SimpleDateFormat display = new SimpleDateFormat("d MMM yyyy", Locale.getDefault());
        display.setTimeZone(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
        return display.format(new Date(birth.getTimeInMillis()));
    }
}
