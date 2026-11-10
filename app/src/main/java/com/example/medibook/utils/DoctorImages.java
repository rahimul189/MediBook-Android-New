package com.example.medibook.utils;

import android.widget.ImageView;
import com.bumptech.glide.Glide;
import com.example.medibook.R;

public final class DoctorImages {
    private DoctorImages() { }
    public static void load(ImageView view, String url) {
        Glide.with(view).load(DoctorImageUrl.isValid(url) && !url.isEmpty() ? url : null)
                .placeholder(R.drawable.avatar_doctor).fallback(R.drawable.avatar_doctor)
                .error(R.drawable.avatar_doctor).circleCrop().into(view);
    }
    public static void clear(ImageView view) {
        Glide.with(view).clear(view);
        view.setImageResource(R.drawable.avatar_doctor);
    }
}
