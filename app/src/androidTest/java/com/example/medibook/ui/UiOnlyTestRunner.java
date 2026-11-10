package com.example.medibook.ui;

import android.app.Application;
import android.content.Context;
import androidx.test.runner.AndroidJUnitRunner;

/** No MediBookApplication/LocalPatientServices, accounts, listeners or reminder scheduling. */
public final class UiOnlyTestRunner extends AndroidJUnitRunner {
    @Override public Application newApplication(ClassLoader loader, String className, Context context)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return super.newApplication(loader, Application.class.getName(), context);
    }
}
