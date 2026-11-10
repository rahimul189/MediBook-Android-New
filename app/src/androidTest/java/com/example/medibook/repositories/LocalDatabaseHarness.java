package com.example.medibook.repositories;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

/** Test-only package access: prime the shared client before SDK emulator URL-key mutation. */
public final class LocalDatabaseHarness {
    private LocalDatabaseHarness() { }
    public static void prime(FirebaseDatabase expected) {
        DatabaseReference root = RealtimeDisplayRepository.root();
        if (root == null || root.getDatabase() != expected || !root.toString().startsWith("http://127.0.0.1:9000")) {
            throw new AssertionError("Refusing an RTDB client outside the local emulator");
        }
        if (RealtimeDisplayRepository.root() != root) throw new AssertionError("RTDB root must be reused");
    }
}
