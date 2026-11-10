package com.example.medibook.utils;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import com.example.medibook.activities.admin.AdminDashboardActivity;
import com.example.medibook.activities.auth.LoginActivity;
import com.example.medibook.activities.auth.CompleteProfileActivity;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.activities.patient.PatientHomeActivity;
import com.example.medibook.models.UserProfile;

import java.util.List;

/** Navigation only: each protected Activity must independently recheck the repository session. */
public final class AuthNavigation {
    private AuthNavigation() {
    }

    public static void openDestination(Activity activity, UserProfile verifiedUser) {
        openDestination(activity, verifiedUser, true);
    }

    /**
     * {@code clearTask} is true when the task must be rebuilt (login/logout/role change). It is
     * false only when the destination replaces this router inside the launcher's own task.
     */
    public static void openDestination(Activity activity, UserProfile verifiedUser, boolean clearTask) {
        Class<?> destination = verifiedUser == null && AuthRepository.getInstance().pendingProfile() != null
                ? CompleteProfileActivity.class : destinationClass(LaunchRouting.destinationFor(
                verifiedUser == null ? null : verifiedUser.getRole()));
        Intent intent = new Intent(activity, destination);
        if (clearTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finish();
    }

    /**
     * Finds this app's retained task rooted at the destination this session belongs to, without
     * changing the task stack. The task keeps the exact screen the patient left (Doctor Profile,
     * My Appointments, ...). Returns null when there is nothing to return to.
     */
    public static ActivityManager.AppTask findDestinationTask(Activity activity, UserProfile verifiedUser) {
        if (activity == null || verifiedUser == null) return null;
        Class<?> expected = destinationClass(LaunchRouting.destinationFor(verifiedUser.getRole()));
        if (expected == LoginActivity.class) return null;
        ActivityManager manager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return null;
        List<ActivityManager.AppTask> tasks;
        try {
            tasks = manager.getAppTasks();
        } catch (RuntimeException unavailable) {
            return null;
        }
        if (tasks == null) return null;
        for (ActivityManager.AppTask task : tasks) {
            ActivityManager.RecentTaskInfo info;
            try {
                info = task.getTaskInfo();
            } catch (RuntimeException unavailable) {
                continue;
            }
            if (info == null || info.id == activity.getTaskId() || info.baseIntent == null) continue;
            ComponentName root = info.baseIntent.getComponent();
            if (root == null) continue;
            if (!LaunchRouting.taskRootsAt(activity.getPackageName(), root.getPackageName(),
                    root.getClassName(), expected.getName())) continue;
            return task;
        }
        return null;
    }

    /** Brings a previously found task to the front. False means the caller must build a destination. */
    public static boolean resumeTask(ActivityManager.AppTask task) {
        if (task == null) return false;
        try {
            task.moveToFront();
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static Class<?> destinationClass(LaunchRouting.Destination destination) {
        switch (destination) {
            case ADMIN_DASHBOARD:
                return AdminDashboardActivity.class;
            case PATIENT_HOME:
                return PatientHomeActivity.class;
            case LOGIN:
            default:
                return LoginActivity.class;
        }
    }
}
