package com.example.medibook;

import android.app.Application;
import com.example.medibook.local.LocalPatientServices;

public final class MediBookApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        LocalPatientServices.initialize(this);
    }
}
