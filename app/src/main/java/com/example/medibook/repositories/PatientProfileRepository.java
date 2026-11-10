package com.example.medibook.repositories;

import com.example.medibook.models.UserProfile;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;

/** Server-side read of one patient profile for an authorized appointment detail view. */
public final class PatientProfileRepository {

    public interface Listener {
        /** Null when the profile is missing, unreadable or malformed. */
        void onProfile(UserProfile profile);
    }

    private final FirebaseFirestore firestore = FirebaseFirestore.getInstance();

    public void get(String uid, Listener listener) {
        if (uid == null || uid.isEmpty()) {
            listener.onProfile(null);
            return;
        }
        firestore.collection("users").document(uid).get(Source.SERVER)
                .addOnCompleteListener(task ->
                        listener.onProfile(task.isSuccessful() ? map(task.getResult()) : null));
    }

    private static UserProfile map(DocumentSnapshot document) {
        if (document == null || !document.exists()) return null;
        String name = document.getString("fullName");
        String email = document.getString("email");
        String phone = document.getString("phone");
        String role = document.getString("role");
        if (name == null || email == null || phone == null || role == null) return null;
        UserProfile.Role parsed;
        if (UserProfile.Role.ADMIN.getProfileValue().equals(role)) parsed = UserProfile.Role.ADMIN;
        else if (UserProfile.Role.PATIENT.getProfileValue().equals(role)) parsed = UserProfile.Role.PATIENT;
        else return null;
        return new UserProfile(name, email, phone, parsed,
                document.getString("dateOfBirth"), document.getString("gender"));
    }
}
