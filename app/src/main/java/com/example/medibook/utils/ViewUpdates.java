package com.example.medibook.utils;

import android.text.TextUtils;
import android.widget.TextView;

/** Avoid TextWatcher/accessibility/layout work when a live refresh has not changed the label. */
public final class ViewUpdates {
    private ViewUpdates() { }
    public static void text(TextView view, CharSequence value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }
}
