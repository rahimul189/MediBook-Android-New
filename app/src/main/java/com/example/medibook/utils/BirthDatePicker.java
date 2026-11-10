package com.example.medibook.utils;

import androidx.fragment.app.FragmentManager;

import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.DateValidatorPointBackward;
import com.google.android.material.datepicker.MaterialDatePicker;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Shared Material date picker for a birth date: no future dates, ISO result. */
public final class BirthDatePicker {

    public interface Listener {
        void onDate(String isoDate);
    }

    private static final String TAG = "birthDatePicker";

    private BirthDatePicker() { }

    public static void show(FragmentManager manager, String currentIso, Listener listener) {
        MaterialDatePicker.Builder<Long> builder = MaterialDatePicker.Builder.datePicker()
                .setTitleText(com.example.medibook.R.string.profile_date_of_birth_label)
                .setCalendarConstraints(new CalendarConstraints.Builder()
                        .setValidator(DateValidatorPointBackward.now()).build());
        builder.setSelection(utcSelection(currentIso));
        MaterialDatePicker<Long> picker = builder.build();
        bind(picker, listener);
        picker.show(manager, TAG);
    }

    /** Restored dialogs lose Activity callbacks; reconnect without creating a second picker. */
    @SuppressWarnings("unchecked")
    public static void reconnect(FragmentManager manager, Listener listener) {
        androidx.fragment.app.Fragment fragment = manager.findFragmentByTag(TAG);
        if (fragment instanceof MaterialDatePicker) bind((MaterialDatePicker<Long>) fragment, listener);
    }

    private static void bind(MaterialDatePicker<Long> picker, Listener listener) {
        picker.clearOnPositiveButtonClickListeners();
        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection == null) return;
            Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            utc.setTimeInMillis(selection);
            listener.onDate(String.format(Locale.US, "%04d-%02d-%02d",
                    utc.get(Calendar.YEAR), utc.get(Calendar.MONTH) + 1, utc.get(Calendar.DAY_OF_MONTH)));
        });
    }

    /** MaterialDatePicker works in UTC midnight millis; existing ISO dates round-trip unchanged. */
    private static long utcSelection(String iso) {
        if (PatientAge.isValidIsoDate(iso)) {
            String[] parts = iso.split("-");
            Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            utc.clear();
            utc.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]));
            return utc.getTimeInMillis();
        }
        return MaterialDatePicker.todayInUtcMilliseconds();
    }
}
