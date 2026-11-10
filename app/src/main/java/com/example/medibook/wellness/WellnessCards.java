package com.example.medibook.wellness;

import com.example.medibook.R;

/**
 * The eleven supplied original cards in archive order. Original card 06 (Hospital Services) and the
 * Blood Pressure / Heart Health cards are intentionally excluded.
 */
public final class WellnessCards {

    private static final int[] DRAWABLES = {
            R.drawable.wellness_stay_hydrated,
            R.drawable.wellness_balanced_diet,
            R.drawable.wellness_physical_activity,
            R.drawable.wellness_quality_sleep,
            R.drawable.wellness_hand_hygiene,
            R.drawable.wellness_meet_our_doctors,
            R.drawable.wellness_book_consultation,
            R.drawable.wellness_find_a_doctor,
            R.drawable.wellness_book_appointment,
            R.drawable.wellness_track_appointments,
            R.drawable.wellness_appointment_reminders,
    };

    private WellnessCards() { }

    private static final int[] DESCRIPTIONS = {
            R.string.wellness_hydrated_text, R.string.wellness_diet_text,
            R.string.wellness_activity_text, R.string.wellness_sleep_text,
            R.string.wellness_hygiene_text, R.string.wellness_doctors_text,
            R.string.wellness_consultation_text, R.string.wellness_find_text,
            R.string.wellness_booking_text, R.string.wellness_track_text,
            R.string.wellness_reminders_text
    };

    public static int description(int position) { return DESCRIPTIONS[position]; }

    public static int[] drawables() { return DRAWABLES.clone(); }

    public static int count() { return DRAWABLES.length; }
}
