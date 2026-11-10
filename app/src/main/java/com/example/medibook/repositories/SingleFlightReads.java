package com.example.medibook.repositories;

import com.google.android.gms.tasks.Task;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Shares pending READS only; completed values are never substituted for a new server refresh. */
public final class SingleFlightReads {
    private String owner;
    private final Map<String, Task<?>> pending = new HashMap<>();

    @SuppressWarnings("unchecked")
    public synchronized <T> Task<T> run(String uid, String key, Supplier<Task<T>> source) {
        if (uid == null || uid.isEmpty()) throw new IllegalArgumentException("Missing read owner");
        if (!uid.equals(owner)) { pending.clear(); owner = uid; }
        Task<?> existing = pending.get(key);
        if (existing != null && !existing.isComplete()) return (Task<T>) existing;
        Task<T> started = source.get();
        if (!started.isComplete()) {
            pending.put(key, started);
            started.addOnCompleteListener(Runnable::run, complete -> {
                synchronized (SingleFlightReads.this) {
                    if (uid.equals(owner) && pending.get(key) == complete) pending.remove(key);
                }
            });
        }
        return started;
    }
}
