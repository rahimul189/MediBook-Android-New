package com.example.medibook.local;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentStatus;
import com.example.medibook.models.UserProfile;
import com.example.medibook.reminders.AppointmentReminderWorker;
import com.example.medibook.reminders.ReminderAlarmReceiver;
import com.example.medibook.reminders.ReminderMode;
import com.example.medibook.reminders.ReminderNotifications;
import com.example.medibook.reminders.ReminderPolicy;
import com.example.medibook.reminders.ReminderSchedule;
import com.example.medibook.repositories.AppointmentMapper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Application-only coordinator. Never retains Activities or treats auth loading as logout.
 * One lock serializes account changes, contact writes, scheduling and the last notification
 * eligibility check. Default reminders (24 h + 6 h) are automatic; a custom alarm replaces them
 * for a single appointment.
 */
public final class LocalPatientServices {
    public static final String WORK_TAG = "medibook.appointment.reminders";

    public interface ContactObserver {
        void changed(List<EmergencyContact> contacts, boolean ready, boolean error);
    }

    public interface ContactWatch { void close(); }
    private static final String DEFAULT_PREFIX = "defaults.";
    private static final String CUSTOM_PREFIX = "custom.";
    private static volatile LocalPatientServices instance;
    private final Context context;
    private final PrivatePatientStore store;
    private final Set<ContactObserver> contactObservers = new HashSet<>();
    private boolean contactWriting;
    private long lastSync;

    private LocalPatientServices(Context context) {
        this.context = context.getApplicationContext();
        SharedPreferences preferences = context.getSharedPreferences(PrivatePatientStore.FILE, Context.MODE_PRIVATE);
        store = new PrivatePatientStore(new PrivatePatientStore.Storage() {
            @Override public Map<String, String> read() {
                Map<String, String> values = new HashMap<>();
                for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                    if (entry.getValue() instanceof String) values.put(entry.getKey(), (String) entry.getValue());
                }
                return values;
            }
            @Override public void replace(Map<String, String> values) {
                SharedPreferences.Editor editor = preferences.edit().clear();
                for (Map.Entry<String, String> entry : values.entrySet()) editor.putString(entry.getKey(), entry.getValue());
                editor.apply();
            }
        });
    }

    public static synchronized void initialize(Context context) {
        if (instance != null) return;
        instance = new LocalPatientServices(context);
        instance.bindActualAccount();
        FirebaseAuth.getInstance().addAuthStateListener(auth -> instance.bindActualAccount());
    }

    public static LocalPatientServices get() { return instance; }

    private String actualUid() {
        com.google.firebase.auth.FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user == null ? null : user.getUid();
    }

    private synchronized void bindActualAccount() {
        try { bind(actualUid()); } catch (RuntimeException unavailable) { logFailure(); }
    }

    private void bind(String uid) {
        if (Objects.equals(uid, store.owner())) return;
        String oldEpoch = store.epoch();
        Set<String> oldIds = new HashSet<>(store.entries(DEFAULT_PREFIX).keySet());
        oldIds.addAll(store.entries(CUSTOM_PREFIX).keySet());
        // Cancel the old account's custom alarms before the epoch is replaced.
        if (oldEpoch != null) for (String id : oldIds) cancelCustomAlarm(oldEpoch, id);
        if (!store.bindAccount(uid)) return;
        ReminderNotifications.stopFeedback(context);
        contactWriting = false;
        contactObservers.clear();
        lastSync = 0;
        try {
            if (oldEpoch != null) work().cancelAllWorkByTag(sessionTag(oldEpoch));
        } finally {
            for (String id : oldIds) ReminderNotifications.cancelAppointment(context, id);
        }
    }

    public synchronized void clearForSignOut() { bind(null); }

    public synchronized String epoch(String uid) {
        return uid != null && uid.equals(actualUid()) && uid.equals(store.owner()) ? store.epoch() : null;
    }

    public synchronized boolean matches(String uid, String epoch) {
        return uid != null && uid.equals(actualUid()) && store.matches(uid, epoch);
    }

    public synchronized List<EmergencyContact> contacts(String uid) {
        return epoch(uid) == null ? Collections.emptyList() : store.contacts(uid);
    }

    public synchronized EmergencyContacts.AddResult addContact(String uid, String epoch, EmergencyContact contact) {
        if (!matches(uid, epoch)) return EmergencyContacts.AddResult.ACCOUNT_CHANGED;
        if (!store.contactsMigrated() || contactWriting) return EmergencyContacts.AddResult.SYNC_PENDING;
        EmergencyContacts.AddResult result = EmergencyContacts.canAdd(store.contacts(uid), contact);
        if (result == EmergencyContacts.AddResult.ADDED) {
            mutateContact(uid, epoch, contact, true);
        }
        return result;
    }

    public synchronized boolean removeContact(String uid, String epoch, EmergencyContact contact) {
        if (!matches(uid, epoch) || !store.contactsMigrated() || contactWriting || contact == null) return false;
        mutateContact(uid, epoch, contact, false);
        return true;
    }

    /**
     * Cache-first contact view. A first-ever account reads Firestore from the server before it
     * uploads local contacts, so a remote deletion is never resurrected. After that, a bounded
     * document listener keeps devices in sync while the screen is open.
     */
    public synchronized ContactWatch watchContacts(String uid, String token, ContactObserver observer) {
        if (!matches(uid, token)) {
            observer.changed(Collections.emptyList(), false, true);
            return () -> { };
        }
        final DocumentReference ref = contactRef(uid);
        final boolean[] closed = {false};
        final ListenerRegistration[] registration = {null};
        contactObservers.add(observer);

        Runnable attach = () -> {
            if (closed[0] || !matches(uid, token)) return;
            registration[0] = ref.addSnapshotListener(MetadataChanges.INCLUDE, (snapshot, error) -> {
                synchronized (LocalPatientServices.this) {
                    if (closed[0] || !matches(uid, token)) return;
                    if (error != null || snapshot == null) {
                        observer.changed(store.contacts(uid), false, true);
                        return;
                    }
                    List<EmergencyContact> remote = snapshot.exists()
                            ? EmergencyContactDocument.read(snapshot.getData())
                            : Collections.emptyList();
                    if (!contactWriting) store.replaceContacts(uid, token, remote);
                    observer.changed(store.contacts(uid), !contactWriting && !snapshot.getMetadata().isFromCache()
                            && !snapshot.getMetadata().hasPendingWrites(), false);
                }
            });
        };

        if (store.contactsMigrated()) {
            observer.changed(store.contacts(uid), false, false);
            attach.run();
        } else {
            observer.changed(store.contacts(uid), false, false);
            ref.get(Source.SERVER).addOnCompleteListener(task -> {
                synchronized (LocalPatientServices.this) {
                    if (closed[0] || !matches(uid, token)) return;
                    if (!task.isSuccessful() || task.getResult() == null) {
                        observer.changed(store.contacts(uid), false, true);
                        return;
                    }
                    DocumentSnapshot snapshot = task.getResult();
                    if (snapshot.exists()) {
                        List<EmergencyContact> remote = EmergencyContactDocument.read(snapshot.getData());
                        store.replaceContacts(uid, token, remote);
                        store.markContactsMigrated();
                        observer.changed(remote, true, false);
                        attach.run();
                    } else {
                        // Recheck inside a transaction: another device may have created (or emptied)
                        // the document since the server read. Never merge a stale local list into it.
                        List<EmergencyContact> local = store.contacts(uid);
                        FirebaseFirestore.getInstance().runTransaction(transaction -> {
                            if (!matches(uid, token)) throw new IllegalStateException("Account changed");
                            DocumentSnapshot current = transaction.get(ref);
                            List<EmergencyContact> result = current.exists()
                                    ? EmergencyContactDocument.read(current.getData())
                                    : EmergencyContactDocument.normalize(local);
                            if (!current.exists()) transaction.set(ref, contactValues(result));
                            return result;
                        }).addOnCompleteListener(migration -> {
                            synchronized (LocalPatientServices.this) {
                                if (closed[0] || !matches(uid, token)) return;
                                if (migration.isSuccessful()) {
                                    store.replaceContacts(uid, token, migration.getResult());
                                    store.markContactsMigrated();
                                    observer.changed(store.contacts(uid), true, false);
                                    attach.run();
                                } else {
                                    observer.changed(store.contacts(uid), false, true);
                                }
                            }
                        });
                    }
                }
            });
        }
        return () -> {
            closed[0] = true;
            contactObservers.remove(observer);
            if (registration[0] != null) registration[0].remove();
        };
    }

    private DocumentReference contactRef(String uid) {
        return FirebaseFirestore.getInstance().collection("users").document(uid)
                .collection("emergencyContacts").document("list");
    }

    private void mutateContact(String uid, String token, EmergencyContact contact, boolean add) {
        contactWriting = true;
        notifyContacts(uid, false, false);
        DocumentReference ref = contactRef(uid);
        FirebaseFirestore.getInstance().runTransaction(transaction -> {
            if (!matches(uid, token)) throw new IllegalStateException("Account changed");
            DocumentSnapshot snapshot = transaction.get(ref);
            List<EmergencyContact> next = new ArrayList<>(EmergencyContactDocument.read(snapshot.getData()));
            String key = EmergencyContacts.canonical(contact.phone);
            if (add) {
                EmergencyContacts.AddResult result = EmergencyContacts.canAdd(next, contact);
                if (result == EmergencyContacts.AddResult.ADDED) next.add(contact);
                else if (result != EmergencyContacts.AddResult.DUPLICATE) throw new IllegalStateException("Contact limit reached");
            } else {
                next.removeIf(value -> Objects.equals(key, EmergencyContacts.canonical(value.phone)));
            }
            next = EmergencyContactDocument.normalize(next);
            transaction.set(ref, contactValues(next));
            return next;
        }).addOnCompleteListener(task -> {
            synchronized (LocalPatientServices.this) {
                if (!matches(uid, token)) return;
                contactWriting = false;
                if (task.isSuccessful()) store.replaceContacts(uid, token, task.getResult());
                notifyContacts(uid, task.isSuccessful(), !task.isSuccessful());
            }
        });
    }

    private void notifyContacts(String uid, boolean ready, boolean error) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            synchronized (LocalPatientServices.this) {
                if (epoch(uid) == null) return;
                for (ContactObserver observer : new ArrayList<>(contactObservers)) {
                    observer.changed(store.contacts(uid), ready, error);
                }
            }
        });
    }

    private Map<String, Object> contactValues(List<EmergencyContact> contacts) {
        Map<String, Object> values = new HashMap<>();
        values.put(EmergencyContactDocument.FIELD, EmergencyContactDocument.slots(contacts));
        values.put(EmergencyContactDocument.INDEX, EmergencyContactDocument.phoneKeys(contacts));
        values.put(EmergencyContactDocument.UPDATED_AT, FieldValue.serverTimestamp());
        return values;
    }

    /** Default reminders are on unless the patient opted out on this device and notifications are allowed. */
    public synchronized boolean remindersActive(String uid) {
        return epoch(uid) != null && !store.defaultsOptOut() && ReminderNotifications.allowed(context);
    }

    /** Backwards-compatible name used by the existing screens and worker. */
    public synchronized boolean remindersEnabled(String uid) { return remindersActive(uid); }

    public synchronized void setRemindersActive(String uid, boolean active) {
        String token = epoch(uid);
        if (token == null) return;
        store.setDefaultsOptOut(!active);
        if (!active) {
            work().cancelAllWorkByTag(sessionTag(token));
            cancelAlarmsAndNotifications();
            lastSync = 0;
        } else {
            ReminderNotifications.createChannel(context);
            lastSync = 0;
            verifiedAccount(uid, UserProfile.Role.PATIENT);
        }
    }

    public synchronized void enableReminders(String uid, boolean enabled) { setRemindersActive(uid, enabled); }

    private void cancelAlarmsAndNotifications() {
        String token = store.epoch();
        for (String id : store.entries(CUSTOM_PREFIX).keySet()) cancelCustomAlarm(token, id);
        for (String id : unionKeys()) ReminderNotifications.cancelAppointment(context, id);
    }

    private Set<String> unionKeys() {
        Set<String> ids = new HashSet<>(store.entries(DEFAULT_PREFIX).keySet());
        ids.addAll(store.entries(CUSTOM_PREFIX).keySet());
        return ids;
    }

    /** Called only after the existing server/claims role gate succeeds. */
    public synchronized void verifiedAccount(String uid, UserProfile.Role role) {
        if (epoch(uid) == null) return;
        if (role != UserProfile.Role.PATIENT) {
            store.clearContacts(uid);
            work().cancelAllWorkByTag(sessionTag(store.epoch()));
            cancelAlarmsAndNotifications();
            return;
        }
        if (!remindersActive(uid)) return;
        long elapsed = android.os.SystemClock.elapsedRealtime();
        if (lastSync != 0 && elapsed - lastSync < 60_000) return;
        lastSync = elapsed;
        String token = store.epoch();
        // Startup/login reconciliation. All reads are authoritative; failed reads never mean empty.
        FirebaseFirestore.getInstance().collection("appointments").whereEqualTo("patientUid", uid)
                .get(Source.SERVER).addOnCompleteListener(task -> {
                    if (!task.isSuccessful() || task.getResult().getMetadata().isFromCache()
                            || task.getResult().getMetadata().hasPendingWrites()) return;
                    try {
                        List<Appointment> appointments = new ArrayList<>();
                        for (DocumentSnapshot doc : task.getResult()) appointments.add(AppointmentMapper.fromSnapshot(doc));
                        synchronized (LocalPatientServices.this) {
                            if (matches(uid, token)) reconcile(uid, appointments);
                        }
                    } catch (RuntimeException unavailable) { logFailure(); }
                });
    }

    /** Best effort only: local scheduling can never change a successful booking/cancellation Task. */
    public static void appointmentBestEffort(String uid, Appointment appointment) {
        if (instance == null) return;
        try { instance.update(uid, appointment); } catch (RuntimeException unavailable) { logFailure(); }
    }

    public static void listBestEffort(String uid, List<Appointment> appointments) {
        if (instance == null) return;
        try { instance.reconcile(uid, appointments); } catch (RuntimeException unavailable) { logFailure(); }
    }

    public static void verifiedBestEffort(String uid, UserProfile.Role role) {
        if (instance == null) return;
        try { instance.verifiedAccount(uid, role); } catch (RuntimeException unavailable) { logFailure(); }
    }

    public static void signOutBestEffort() {
        if (instance == null) return;
        try { instance.clearForSignOut(); } catch (RuntimeException unavailable) { logFailure(); }
    }

    /** Re-registers custom alarms after reboot or an exact-alarm permission change. */
    public static void rescheduleBestEffort() {
        if (instance == null) return;
        try { instance.reschedule(); } catch (RuntimeException unavailable) { logFailure(); }
    }

    private static void logFailure() {
        android.util.Log.w("MediBookLocal", "Local reminder operation unavailable");
    }

    private synchronized void reconcile(String uid, List<Appointment> appointments) {
        if (epoch(uid) == null) return;
        for (Appointment a : appointments) update(uid, a);
        long now = System.currentTimeMillis();
        for (Map.Entry<String, String> entry : store.entries(DEFAULT_PREFIX).entrySet()) {
            ReminderSchedule.DefaultRecord record = ReminderSchedule.DefaultRecord.decode(entry.getValue());
            if (record != null && record.start <= now) {
                cancelDefaultWork(uid, entry.getKey());
                store.putEntry(DEFAULT_PREFIX + entry.getKey(), null);
            }
        }
        for (Map.Entry<String, String> entry : store.entries(CUSTOM_PREFIX).entrySet()) {
            ReminderSchedule.CustomRecord record = ReminderSchedule.CustomRecord.decode(entry.getValue());
            if (record != null && record.start <= now) {
                cancelCustomAlarm(store.epoch(), entry.getKey());
                store.putEntry(CUSTOM_PREFIX + entry.getKey(), null);
            }
        }
    }

    private synchronized void update(String uid, Appointment a) {
        String token = epoch(uid);
        if (token == null || a == null || !uid.equals(a.getPatientUid())) return;
        String id = a.getId();
        long now = System.currentTimeMillis();
        long start = a.getStartAt().toDate().getTime();
        ReminderSchedule.DefaultRecord defaults =
                ReminderSchedule.DefaultRecord.decode(store.entry(DEFAULT_PREFIX + id));
        ReminderSchedule.CustomRecord custom =
                ReminderSchedule.CustomRecord.decode(store.entry(CUSTOM_PREFIX + id));

        boolean knownCancelled = custom != null && ReminderSchedule.CANCELLED.equals(custom.state);
        if (a.getStatus() == AppointmentStatus.CANCELLED || knownCancelled) {
            cancelAppointment(uid, id, start);
            return;
        }
        if (!ReminderPolicy.eligible(uid, a, now)) {
            // Past, completed or no longer confirmed: never remind, never reschedule.
            cancelAppointment(uid, id, start);
            return;
        }
        // A custom reminder replaces both defaults for this appointment.
        if (custom != null && custom.start == start) {
            cancelDefaultWork(uid, id);
            ReminderNotifications.cancelDefaults(context, id);
            if (ReminderSchedule.SCHEDULED.equals(custom.state) && remindersActive(uid)) scheduleCustomAlarm(uid, id, custom);
            return;
        }
        if (custom != null && custom.start != start) {
            cancelCustomAlarm(token, id);
            store.putEntry(CUSTOM_PREFIX + id, null);
        }

        boolean firstSent = defaults != null && defaults.start == start && defaults.firstSent;
        boolean secondSent = defaults != null && defaults.start == start && defaults.secondSent;
        ReminderSchedule.Defaults desired = ReminderSchedule.defaults(start, now);
        long first = firstSent ? 0 : desired.first;
        long second = secondSent ? 0 : desired.second;
        ReminderSchedule.DefaultRecord next = new ReminderSchedule.DefaultRecord(
                start, first, second, firstSent, secondSent, false);
        if (!Objects.equals(next.encode(), store.entry(DEFAULT_PREFIX + id))) {
            store.putEntry(DEFAULT_PREFIX + id, next.encode());
        }
        if (defaults != null && defaults.start != start) {
            cancelDefaultWork(uid, id);
            ReminderNotifications.cancelDefaults(context, id);
        }
        if (!remindersActive(uid)) {
            cancelDefaultWork(uid, id);
            return;
        }
        if (first > 0) enqueueDefault(uid, id, ReminderSchedule.Kind.DEFAULT_24, start, first);
        else work().cancelUniqueWork(workName(token, id, ReminderSchedule.Kind.DEFAULT_24));
        if (second > 0) enqueueDefault(uid, id, ReminderSchedule.Kind.DEFAULT_6, start, second);
        else work().cancelUniqueWork(workName(token, id, ReminderSchedule.Kind.DEFAULT_6));
    }

    private void enqueueDefault(String uid, String id, ReminderSchedule.Kind kind, long start, long due) {
        long now = System.currentTimeMillis();
        Data data = new Data.Builder().putString("uid", uid).putString("epoch", store.epoch())
                .putString("appointment", id).putString("kind", kind.wire)
                .putLong("start", start).putLong("due", due).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AppointmentReminderWorker.class)
                .setInputData(data)
                .setInitialDelay(Math.max(0, due - now), TimeUnit.MILLISECONDS)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .addTag(WORK_TAG).addTag(sessionTag(store.epoch())).build();
        work().enqueueUniqueWork(workName(store.epoch(), id, kind), ExistingWorkPolicy.KEEP, request);
    }

    private void cancelAppointment(String uid, String id, long start) {
        if (epoch(uid) == null) return;
        cancelDefaultWork(uid, id);
        cancelCustomAlarm(store.epoch(), id);
        ReminderNotifications.cancelAppointment(context, id);
        // Tombstones keep a slow/stale CONFIRMED read from reviving a cancelled reminder.
        ReminderSchedule.DefaultRecord previous =
                ReminderSchedule.DefaultRecord.decode(store.entry(DEFAULT_PREFIX + id));
        long knownStart = start > 0 ? start : previous == null ? System.currentTimeMillis() : previous.start;
        store.putEntry(DEFAULT_PREFIX + id, ReminderSchedule.DefaultRecord.cancelled(knownStart).encode());
        store.putEntry(CUSTOM_PREFIX + id, null);
    }

    private void cancelDefaultWork(String uid, String id) {
        String token = epoch(uid);
        if (token == null) return;
        for (ReminderSchedule.Kind kind : new ReminderSchedule.Kind[]{
                ReminderSchedule.Kind.DEFAULT_24, ReminderSchedule.Kind.DEFAULT_6}) {
            work().cancelUniqueWork(workName(token, id, kind));
        }
    }

    private WorkManager work() { return WorkManager.getInstance(context); }

    private static String sessionTag(String epoch) { return WORK_TAG + "." + epoch; }

    private static String workName(String epoch, String id, ReminderSchedule.Kind kind) {
        return sessionTag(epoch) + "." + kind.wire + "." + id;
    }

    // ---- Custom reminder API used by the appointment details screen -------------------------

    public synchronized boolean hasCustomReminder(String uid, Appointment a) {
        if (a == null || epoch(uid) == null) return false;
        ReminderSchedule.CustomRecord custom =
                ReminderSchedule.CustomRecord.decode(store.entry(CUSTOM_PREFIX + a.getId()));
        return custom != null && !ReminderSchedule.CANCELLED.equals(custom.state)
                && custom.start == a.getStartAt().toDate().getTime();
    }

    /** Trigger instant of the active custom reminder, or -1 when the defaults apply. */
    public synchronized long customReminderTime(String uid, Appointment a) {
        if (a == null || epoch(uid) == null) return -1;
        ReminderSchedule.CustomRecord custom =
                ReminderSchedule.CustomRecord.decode(store.entry(CUSTOM_PREFIX + a.getId()));
        if (custom == null || ReminderSchedule.CANCELLED.equals(custom.state)
                || custom.start != a.getStartAt().toDate().getTime()) return -1;
        return custom.trigger;
    }

    /** Delivery style of the active custom reminder, or null when the defaults apply. */
    public synchronized ReminderMode customReminderMode(String uid, Appointment a) {
        if (a == null || epoch(uid) == null) return null;
        ReminderSchedule.CustomRecord custom =
                ReminderSchedule.CustomRecord.decode(store.entry(CUSTOM_PREFIX + a.getId()));
        return custom == null || ReminderSchedule.CANCELLED.equals(custom.state)
                || custom.start != a.getStartAt().toDate().getTime() ? null : custom.mode;
    }

    public synchronized boolean saveCustomReminder(String uid, Appointment a, long trigger) {
        return saveCustomReminder(uid, a, trigger, ReminderMode.NOTIFICATION);
    }

    /** True when a custom reminder was stored; callers validate the user-facing message themselves. */
    public synchronized boolean saveCustomReminder(String uid, Appointment a, long trigger, ReminderMode mode) {
        String token = epoch(uid);
        if (token == null || a == null || !uid.equals(a.getPatientUid())) return false;
        long now = System.currentTimeMillis();
        long start = a.getStartAt().toDate().getTime();
        if (!ReminderPolicy.eligible(uid, a, now) || !ReminderSchedule.customTriggerValid(start, trigger, now)) {
            return false;
        }
        // Replace any previous pending custom alarm for this appointment.
        cancelCustomAlarm(token, a.getId());
        ReminderNotifications.cancelAppointment(context, a.getId());
        ReminderSchedule.CustomRecord record = new ReminderSchedule.CustomRecord(
                start, trigger, ReminderSchedule.SCHEDULED, ReminderMode.orDefault(mode));
        store.putEntry(CUSTOM_PREFIX + a.getId(), record.encode());
        cancelDefaultWork(uid, a.getId());
        ReminderNotifications.cancelDefaults(context, a.getId());
        if (remindersActive(uid)) scheduleCustomAlarm(uid, a.getId(), record);
        return true;
    }

    public synchronized boolean removeCustomReminder(String uid, Appointment a) {
        String token = epoch(uid);
        if (token == null || a == null) return false;
        cancelCustomAlarm(token, a.getId());
        store.putEntry(CUSTOM_PREFIX + a.getId(), null);
        ReminderNotifications.cancelAppointment(context, a.getId());
        update(uid, a); // Remaining eligible default reminders come back automatically.
        return true;
    }

    public synchronized boolean exactAlarmsAllowed() {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return manager != null && manager.canScheduleExactAlarms();
    }

    private synchronized void reschedule() {
        String uid = actualUid();
        if (uid == null || epoch(uid) == null || !remindersActive(uid)) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, String> entry : store.entries(CUSTOM_PREFIX).entrySet()) {
            ReminderSchedule.CustomRecord record = ReminderSchedule.CustomRecord.decode(entry.getValue());
            if (record == null || !ReminderSchedule.SCHEDULED.equals(record.state)
                    || record.start <= now || record.trigger <= now) continue;
            scheduleCustomAlarm(uid, entry.getKey(), record);
        }
    }

    private void scheduleCustomAlarm(String uid, String id, ReminderSchedule.CustomRecord record) {
        if (record.trigger <= System.currentTimeMillis()) return;
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        PendingIntent pending = customPendingIntent(id, record);
        if (pending == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) {
                // Exact access is a special permission; explain the inexact fallback in the UI.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, record.trigger, pending);
            } else {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, record.trigger, pending);
            }
        } catch (SecurityException exactDenied) {
            try {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, record.trigger, pending);
            } catch (SecurityException unavailable) {
                logFailure();
            }
        }
    }

    private void cancelCustomAlarm(String token, String id) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        PendingIntent pending = customPendingIntent(token, id, 0, 0);
        if (pending != null) manager.cancel(pending);
    }

    private PendingIntent customPendingIntent(String id, ReminderSchedule.CustomRecord record) {
        return customPendingIntent(store.epoch(), id, record.start, record.trigger);
    }

    private PendingIntent customPendingIntent(String token, String id, long start, long due) {
        if (token == null || id == null) return null;
        Intent intent = new Intent(context, ReminderAlarmReceiver.class)
                .setAction(ReminderAlarmReceiver.ACTION_CUSTOM_ALARM)
                .setData(Uri.parse("medibook://reminder-alarm/" + token + "/" + Uri.encode(id)))
                .putExtra("uid", store.owner()).putExtra("epoch", token).putExtra("appointment", id)
                .putExtra("start", start).putExtra("due", due);
        int requestCode = Math.abs((token + ":" + id).hashCode() % 100_000);
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---- Delivery gate (called on WorkManager / alarm threads) ------------------------------

    public synchronized boolean workerAllowed(String uid, String token, String id, ReminderSchedule.Kind kind,
                                              long start, long due, long now) {
        if (kind == null || !matches(uid, token) || !remindersActive(uid)) return false;
        ReminderSchedule.CustomRecord custom =
                ReminderSchedule.CustomRecord.decode(store.entry(CUSTOM_PREFIX + id));
        ReminderSchedule.DefaultRecord defaults =
                ReminderSchedule.DefaultRecord.decode(store.entry(DEFAULT_PREFIX + id));
        if (kind == ReminderSchedule.Kind.CUSTOM) {
            return custom != null && ReminderSchedule.SCHEDULED.equals(custom.state)
                    && custom.start == start && custom.trigger == due
                    && now >= due && now < start;
        }
        if (custom != null && custom.start == start) return false;
        if (defaults == null || defaults.start != start || defaults.cancelled) return false;
        long expected = kind == ReminderSchedule.Kind.DEFAULT_24 ? defaults.first : defaults.second;
        boolean sent = kind == ReminderSchedule.Kind.DEFAULT_24 ? defaults.firstSent : defaults.secondSent;
        return !sent && expected > 0 && expected == due && now >= due && now < start - 5 * ReminderPolicy.MINUTE;
    }

    /** Called AFTER a fresh Firestore read, on the WorkManager thread. */
    public synchronized void deliver(String uid, String token, Appointment a, ReminderSchedule.Kind kind,
                                     long start, long due) {
        long now = System.currentTimeMillis();
        if (!workerAllowed(uid, token, a.getId(), kind, start, due, now)
                || !ReminderPolicy.eligible(uid, a, now)
                || a.getStartAt().toDate().getTime() != start
                || !ReminderNotifications.allowed(context)) return;
        // Mark first for at-most-once local delivery even if the process dies during notify().
        ReminderMode mode = ReminderMode.NOTIFICATION;
        if (kind == ReminderSchedule.Kind.CUSTOM) {
            ReminderSchedule.CustomRecord custom =
                    ReminderSchedule.CustomRecord.decode(store.entry(CUSTOM_PREFIX + a.getId()));
            if (custom != null) {
                mode = custom.mode;
                store.putEntry(CUSTOM_PREFIX + a.getId(), custom.withState(ReminderSchedule.SENT).encode());
            }
        } else {
            ReminderSchedule.DefaultRecord defaults =
                    ReminderSchedule.DefaultRecord.decode(store.entry(DEFAULT_PREFIX + a.getId()));
            if (defaults != null) store.putEntry(DEFAULT_PREFIX + a.getId(),
                    defaults.markSent(kind).encode());
        }
        ReminderNotifications.show(context, uid, token, a, kind, mode);
    }
}
