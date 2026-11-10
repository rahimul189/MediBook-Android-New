package com.example.medibook.repositories;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.example.medibook.utils.DashboardPayload;
import com.example.medibook.utils.Diagnostics;
import com.google.android.gms.tasks.Task;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;
import java.util.HashMap;
import java.util.Map;

/**
 * Small, foreground-only RTDB projection. Reserve a revision BEFORE the server Firestore read,
 * then compare-and-set that revision. A late request cannot replace a newer client's publication.
 * No transaction here ever participates in booking success. RTDB is never a booking authority.
 */
final class DashboardMirror implements AutoCloseable {
    interface Source { Task<Map<String, Object>> read(); }
    interface Observer { void changed(); }
    private final String owner;
    private final DatabaseReference ref, summaryRef, connection;
    private final Source source;
    private final Observer observer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ValueEventListener dataListener, connectionListener;
    private volatile boolean closed, connected;
    private boolean busy, dirty, oneShot;
    private volatile int request;
    private Runnable timeout;
    private Map<String, Object> summary, expected, publishing;

    DashboardMirror(String owner, String path, Source source, Observer observer) {
        this.owner = owner; this.source = source; this.observer = observer;
        DatabaseReference root = RealtimeDisplayRepository.root();
        ref = root == null ? null : root.child(path);
        summaryRef = ref == null ? null : ref.child("summary");
        connection = root == null ? null : root.child(".info/connected");
    }
    boolean active() { return !closed && DoctorRepository.sameUser(owner); }
    static void once(String owner, String path, Source source) {
        DashboardMirror mirror = new DashboardMirror(owner, path, source, () -> { });
        mirror.oneShot = true;
        mirror.start(); mirror.reconcile(new HashMap<>());
        mirror.handler.postDelayed(mirror::close, 22_000L);
    }
    Map<String, Object> summary() { return summary; }
    boolean connected() { return connected; }
    boolean usable(Map<String, Object> truth) {
        return connected && DashboardPayload.fresh(summary, System.currentTimeMillis()) && DashboardPayload.matches(truth, summary);
    }
    void start() {
        if (ref == null || !active()) return;
        dataListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!active()) return;
                Map<String, Object> next = map(snapshot.getValue());
                if (java.util.Objects.equals(summary, next)) return;
                summary = next; observer.changed();
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                if (!active()) return; summary = null; observer.changed();
            }
        };
        connectionListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!active()) return;
                connected = Boolean.TRUE.equals(snapshot.getValue()); observer.changed();
                if (connected && expected != null) reconcile(expected);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                if (active()) { connected = false; observer.changed(); }
            }
        };
        // Revision reservation is not a content change. Listen only to the compact summary child.
        summaryRef.addValueEventListener(dataListener); connection.addValueEventListener(connectionListener);
    }
    void reconcile(Map<String, Object> truth) {
        expected = truth;
        if (!active() || ref == null || !connected || usable(truth)) return;
        if (busy) { dirty = !java.util.Objects.equals(publishing, truth); return; }
        publishing = new HashMap<>(truth);
        busy = true; dirty = false; final int token = ++request;
        timeout = () -> { if (active() && token == request) { request++; busy = false; observer.changed(); } };
        handler.postDelayed(timeout, 20_000L);
        ref.runTransaction(new Transaction.Handler() {
            @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData data) {
                if (!valid(token)) return Transaction.abort();
                Object raw = data.child("revision").getValue();
                long previous = raw instanceof Long ? (Long) raw : 0;
                if (previous < 0 || previous >= 1_000_000_000L) return Transaction.abort();
                data.child("revision").setValue(previous + 1);
                return Transaction.success(data);
            }
            @Override public void onComplete(@Nullable DatabaseError error, boolean committed, @Nullable DataSnapshot snapshot) {
                if (!valid(token)) return;
                if (error != null || !committed || snapshot == null) { finish(token); return; }
                Long reserved = snapshot.child("revision").getValue(Long.class);
                if (reserved == null) { finish(token); return; }
                long revision = reserved;
                // This read begins AFTER the revision reservation, never from an earlier cached event.
                Diagnostics.event("Read", "RTDB summary reconciliation server read");
                source.read().addOnCompleteListener(task -> {
                    if (!valid(token)) return;
                    if (!task.isSuccessful() || task.getResult() == null) { finish(token); return; }
                    Map<String, Object> payload = new HashMap<>(task.getResult());
                    payload.put("revision", revision); payload.put("checkedAt", ServerValue.TIMESTAMP);
                    ref.runTransaction(new Transaction.Handler() {
                        @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData current) {
                            if (!valid(token) || !Long.valueOf(revision).equals(current.child("revision").getValue())) return Transaction.abort();
                            current.child("summary").setValue(payload); return Transaction.success(current);
                        }
                        @Override public void onComplete(@Nullable DatabaseError failure, boolean accepted, @Nullable DataSnapshot result) { finish(token); }
                    }, false);
                });
            }
        }, false);
    }
    private boolean valid(int token) { return active() && connected && token == request; }
    private void finish(int token) {
        if (token != request) return;
        handler.removeCallbacks(timeout); busy = false;
        if (oneShot) { close(); return; }
        if (active() && dirty) { dirty = false; reconcile(expected); }
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) {
        return value instanceof Map ? new HashMap<>((Map<String, Object>) value) : null;
    }
    @Override public void close() {
        closed = true; request++;
        if (timeout != null) handler.removeCallbacks(timeout);
        if (summaryRef != null && dataListener != null) summaryRef.removeEventListener(dataListener);
        if (connection != null && connectionListener != null) connection.removeEventListener(connectionListener);
        summary = null; expected = null;
    }
}
