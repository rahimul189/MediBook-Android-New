package com.example.medibook.local;

import java.util.Collections;
import java.util.List;

/** Pure rules for the private, account-scoped emergency-contact list. */
public final class EmergencyContacts {
    public static final int MAX = 10;

    public enum AddResult { ADDED, DUPLICATE, LIMIT, INVALID, ACCOUNT_CHANGED, SYNC_PENDING }

    private EmergencyContacts() { }

    /** Formatting-insensitive comparison key; only normalized ASCII digits are retained. */
    public static String canonical(String phone) {
        String dialable = EmergencyContact.dialable(phone);
        return dialable == null ? null : dialable.replace("+", "");
    }

    public static AddResult canAdd(List<EmergencyContact> contacts, EmergencyContact candidate) {
        if (candidate == null || candidate.phone == null) return AddResult.INVALID;
        if (contacts == null) contacts = Collections.emptyList();
        if (contacts.size() >= MAX) return AddResult.LIMIT;
        String key = canonical(candidate.phone);
        if (key == null) return AddResult.INVALID;
        for (EmergencyContact contact : contacts) {
            if (contact != null && key.equals(canonical(contact.phone))) return AddResult.DUPLICATE;
        }
        return AddResult.ADDED;
    }
}
