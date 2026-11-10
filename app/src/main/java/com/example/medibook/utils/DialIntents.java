package com.example.medibook.utils;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;

import com.example.medibook.R;
import com.example.medibook.local.EmergencyContact;
import com.google.android.material.snackbar.Snackbar;

public final class DialIntents {
    private DialIntents() { }
    public static void hospital(Activity activity) {
        String number = activity.getString(R.string.hospital_phone_number).trim();
        if (EmergencyContact.dialable(number) == null) {
            Snackbar.make(activity.findViewById(android.R.id.content), R.string.hospital_phone_unavailable, Snackbar.LENGTH_LONG).show();
        } else dial(activity, number);
    }
    public static void dial(Activity activity, String number) {
        String phone = EmergencyContact.dialable(number);
        if (phone == null) return;
        try {
            // Launch directly; resolveActivity can incorrectly report no handler under package visibility.
            activity.startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)));
        } catch (ActivityNotFoundException | SecurityException noDialer) {
            Snackbar.make(activity.findViewById(android.R.id.content), R.string.dialer_unavailable, Snackbar.LENGTH_LONG).show();
        }
    }
}
