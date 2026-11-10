package com.example.medibook.utils;

import com.example.medibook.models.UserProfile;

/**
 * Pure launcher/navigation decisions. Kept free of Android types so the "the launcher must never
 * finish into an empty task" rule is unit-testable.
 */
public final class LaunchRouting {

    public enum Destination { LOGIN, PATIENT_HOME, ADMIN_DASHBOARD }

    public enum Action { RESUME_EXISTING_TASK, BUILD_DESTINATION, VERIFY_SESSION }

    private LaunchRouting() { }

    public static Destination destinationFor(UserProfile.Role role) {
        if (role == UserProfile.Role.ADMIN) return Destination.ADMIN_DASHBOARD;
        if (role == UserProfile.Role.PATIENT) return Destination.PATIENT_HOME;
        return Destination.LOGIN;
    }

    /**
     * A verified session always has somewhere to go: reuse the retained task, otherwise build the
     * destination. Finishing without either leaves an empty task, which is what made a launcher tap
     * appear to do nothing.
     */
    public static Action actionFor(boolean verifiedUser, boolean resumableTaskFound) {
        if (!verifiedUser) return Action.VERIFY_SESSION;
        return resumableTaskFound ? Action.RESUME_EXISTING_TASK : Action.BUILD_DESTINATION;
    }

    /** True when a retained task's root activity is the destination this session belongs to. */
    public static boolean taskRootsAt(String appPackage, String rootPackage, String rootClass,
                                      String expectedClass) {
        return appPackage != null && expectedClass != null
                && appPackage.equals(rootPackage) && expectedClass.equals(rootClass);
    }
}
