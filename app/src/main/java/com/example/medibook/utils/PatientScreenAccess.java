package com.example.medibook.utils;

import com.example.medibook.models.UserProfile;

/** UI gate applied only to AuthRepository's freshly verified state; extras never authorize a screen. */
public final class PatientScreenAccess {
    private PatientScreenAccess() { }

    public static boolean authorized(boolean checking, UserProfile verifiedUser, String currentUid) {
        return !checking && verifiedUser != null && verifiedUser.getRole() == UserProfile.Role.PATIENT
                && currentUid != null && !currentUid.isEmpty();
    }

    public static boolean sameOwner(String owner, String currentUid) {
        return owner != null && !owner.isEmpty() && owner.equals(currentUid);
    }

    public static boolean keepWhileChecking(boolean visibleOrVerified, String owner, String currentUid, boolean onboarding) {
        return visibleOrVerified && !onboarding && sameOwner(owner, currentUid);
    }
}
