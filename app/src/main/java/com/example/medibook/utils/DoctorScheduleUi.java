package com.example.medibook.utils;

import android.content.Context;
import android.text.format.DateFormat;

import com.example.medibook.R;

import java.util.Calendar;
import java.util.TimeZone;

/** Display helpers keep weekly wall-clock times in the configured hospital timezone. */
public final class DoctorScheduleUi {
    private static final int[] DAYS = { R.string.schedule_monday, R.string.schedule_tuesday,
            R.string.schedule_wednesday, R.string.schedule_thursday, R.string.schedule_friday,
            R.string.schedule_saturday, R.string.schedule_sunday };

    private DoctorScheduleUi() { }

    public static String[] days(Context context) {
        String[] names = new String[DAYS.length];
        for (int index = 0; index < DAYS.length; index++) names[index] = context.getString(DAYS[index]);
        return names;
    }

    public static String day(Context context, int isoDay) { return context.getString(DAYS[isoDay - 1]); }

    public static String time(Context context, int minute) {
        TimeZone zone = TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID);
        Calendar clock = Calendar.getInstance(zone);
        clock.clear();
        clock.set(2000, Calendar.JANUARY, 1, minute / 60, minute % 60);
        java.text.DateFormat format = DateFormat.getTimeFormat(context);
        format.setTimeZone(zone);
        return format.format(clock.getTime());
    }
}
