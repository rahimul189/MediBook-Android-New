package com.example.medibook.local;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Single-account, private local state. No Firebase/network dependency; temporary auth loading is
 * deliberately NOT an account transition. Call bindAccount only with the actual Firebase UID. */
public final class PrivatePatientStore {
    public static final String FILE = "patient_private";
    private static final String CONTACT_COUNT = "contacts.count";
    private static final String CONTACT_PREFIX = "contacts.";
    private static final String CONTACT_MIGRATED = "contacts.migrated";

    public interface Storage {
        Map<String, String> read();
        void replace(Map<String, String> values);
    }

    private final Storage storage;
    private final Map<String, String> values;

    public PrivatePatientStore(Storage storage) {
        this.storage = storage;
        values = new HashMap<>(storage.read());
        migrateLegacyContact();
    }

    /** The old single-contact keys become one list entry; malformed legacy values are discarded. */
    private void migrateLegacyContact() {
        if (!values.containsKey("name") && !values.containsKey("phone")) return;
        String owner = values.get("owner");
        EmergencyContact legacy = EmergencyContact.from(values.get("name"), values.get("phone"));
        values.remove("name");
        values.remove("phone");
        if (owner != null && legacy != null) {
            List<EmergencyContact> contacts = readContacts();
            if (EmergencyContacts.canAdd(contacts, legacy) == EmergencyContacts.AddResult.ADDED) {
                contacts.add(legacy);
                writeContacts(contacts);
            }
        }
        persist();
    }

    public String owner() { return values.get("owner"); }
    public String epoch() { return values.get("epoch"); }
    public boolean matches(String uid, String token) {
        return uid != null && token != null && uid.equals(owner()) && token.equals(epoch());
    }
    public boolean bindAccount(String uid) {
        if (Objects.equals(uid, owner())) return false;
        values.clear();
        if (uid != null) { values.put("owner", uid); values.put("epoch", UUID.randomUUID().toString()); }
        persist(); return true;
    }

    public List<EmergencyContact> contacts(String uid) {
        if (uid == null || !uid.equals(owner())) return Collections.emptyList();
        return Collections.unmodifiableList(readContacts());
    }

    public EmergencyContacts.AddResult addContact(String uid, String token, EmergencyContact contact) {
        if (!matches(uid, token)) return EmergencyContacts.AddResult.ACCOUNT_CHANGED;
        List<EmergencyContact> contacts = readContacts();
        EmergencyContacts.AddResult result = EmergencyContacts.canAdd(contacts, contact);
        if (result != EmergencyContacts.AddResult.ADDED) return result;
        contacts.add(contact);
        writeContacts(contacts);
        persist();
        return EmergencyContacts.AddResult.ADDED;
    }

    public boolean removeContact(String uid, String token, EmergencyContact contact) {
        if (!matches(uid, token) || contact == null) return false;
        String key = EmergencyContacts.canonical(contact.phone);
        if (key == null) return false;
        List<EmergencyContact> contacts = readContacts();
        boolean removed = contacts.removeIf(existing -> key.equals(EmergencyContacts.canonical(existing.phone)));
        if (removed) { writeContacts(contacts); persist(); }
        return removed;
    }

    /** Replaces the cache with the server-confirmed full list, preserving a stable order. */
    public boolean replaceContacts(String uid, String token, List<EmergencyContact> contacts) {
        if (!matches(uid, token)) return false;
        writeContacts(EmergencyContactDocument.normalize(contacts));
        persist();
        return true;
    }

    /** True after this account's local list has been reconciled with Firestore once. */
    public boolean contactsMigrated() { return "true".equals(values.get(CONTACT_MIGRATED)); }

    public void markContactsMigrated() {
        if (owner() == null) return;
        values.put(CONTACT_MIGRATED, "true");
        persist();
    }

    public void clearContacts(String uid) {
        if (uid == null || !uid.equals(owner())) return;
        clearContactValues();
        values.remove(CONTACT_MIGRATED);
        persist();
    }

    /** Generic per-account entries used by the default and custom reminder ledgers. */
    public String entry(String key) {
        if (key == null) return null;
        return values.get(key);
    }
    public void putEntry(String key, String value) {
        if (owner() == null || key == null) return;
        if (value == null) values.remove(key); else values.put(key, value);
        persist();
    }
    public Map<String, String> entries(String prefix) {
        Map<String, String> selected = new HashMap<>();
        if (prefix == null) return selected;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().startsWith(prefix)) selected.put(entry.getKey().substring(prefix.length()), entry.getValue());
        }
        return selected;
    }
    /** Defaults are on unless the patient explicitly turned reminders off on this device. */
    public boolean defaultsOptOut() { return "true".equals(values.get("defaultsOff")); }
    public void setDefaultsOptOut(boolean optOut) {
        if (owner() == null) return;
        values.put("defaultsOff", Boolean.toString(optOut));
        persist();
    }
    public boolean remindersEnabled() { return "true".equals(values.get("reminders")); }
    public void enableReminders(boolean enabled) {
        if (owner() == null) return;
        values.put("reminders", Boolean.toString(enabled));
        // Retain sent/cancelled tombstones when toggling, preventing duplicate reminders.
        persist();
    }
    public Map<String, String> reminderRecords() {
        Map<String, String> records = new HashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().startsWith("reminder.")) records.put(entry.getKey().substring(9), entry.getValue());
        }
        return records;
    }
    public void putReminder(String id, String record) {
        if (owner() == null) return;
        if (record == null) values.remove("reminder." + id); else values.put("reminder." + id, record);
        persist();
    }

    private List<EmergencyContact> readContacts() {
        List<EmergencyContact> contacts = new ArrayList<>();
        int count = contactCount();
        for (int index = 0; index < count; index++) {
            EmergencyContact contact = EmergencyContact.from(
                    values.get(CONTACT_PREFIX + index + ".name"),
                    values.get(CONTACT_PREFIX + index + ".phone"));
            if (contact != null) contacts.add(contact);
        }
        return contacts;
    }

    private int contactCount() {
        try {
            int count = Integer.parseInt(values.getOrDefault(CONTACT_COUNT, "0"));
            return Math.max(0, Math.min(EmergencyContacts.MAX, count));
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    private void writeContacts(List<EmergencyContact> contacts) {
        clearContactValues();
        values.put(CONTACT_COUNT, Integer.toString(contacts.size()));
        for (int index = 0; index < contacts.size(); index++) {
            values.put(CONTACT_PREFIX + index + ".name", contacts.get(index).name);
            values.put(CONTACT_PREFIX + index + ".phone", contacts.get(index).phone);
        }
    }

    private void clearContactValues() {
        List<String> keys = new ArrayList<>();
        for (String key : values.keySet()) {
            if (key.equals(CONTACT_COUNT) || (key.startsWith(CONTACT_PREFIX) && !key.equals(CONTACT_MIGRATED))) keys.add(key);
        }
        for (String key : keys) values.remove(key);
    }

    private void persist() { storage.replace(new HashMap<>(values)); }
}
