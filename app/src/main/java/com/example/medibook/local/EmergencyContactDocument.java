package com.example.medibook.local;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Firestore representation of the whole contact list: one private document with at most ten
 * fixed slots. Fixed slots let Security Rules prove the 10-contact limit and reject duplicate
 * canonical numbers without trusting the client.
 */
public final class EmergencyContactDocument {
    public static final String FIELD = "contacts";
    public static final String INDEX = "phoneKeys";
    public static final String UPDATED_AT = "updatedAt";
    public static final String SLOT_PREFIX = "c";

    private EmergencyContactDocument() { }

    /** Deduplicated, bounded and order-preserving; malformed entries are dropped. */
    public static List<EmergencyContact> normalize(List<EmergencyContact> contacts) {
        if (contacts == null) return Collections.emptyList();
        List<EmergencyContact> result = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (EmergencyContact contact : contacts) {
            if (result.size() == EmergencyContacts.MAX) break;
            if (contact == null) continue;
            String key = EmergencyContacts.canonical(contact.phone);
            if (key == null || !keys.add(key)) continue;
            EmergencyContact clean = EmergencyContact.from(contact.name, EmergencyContact.dialable(contact.phone));
            if (clean != null) result.add(clean);
        }
        return result;
    }

    /** Firestore map value for a full replacement write. Empty slots are omitted. */
    public static Map<String, Object> slots(List<EmergencyContact> contacts) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<EmergencyContact> clean = normalize(contacts);
        for (int index = 0; index < clean.size(); index++) {
            EmergencyContact contact = clean.get(index);
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("name", contact.name);
            slot.put("phone", contact.phone);
            slot.put("phoneKey", EmergencyContacts.canonical(contact.phone));
            values.put(SLOT_PREFIX + index, slot);
        }
        return values;
    }

    /**
     * Canonical number to slot index. Security Rules require this map to be a bijection with the
     * contact slots, which makes duplicate numbers impossible without pairwise slot comparisons.
     */
    public static Map<String, Object> phoneKeys(List<EmergencyContact> contacts) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<EmergencyContact> clean = normalize(contacts);
        for (int index = 0; index < clean.size(); index++) {
            values.put(EmergencyContacts.canonical(clean.get(index).phone), SLOT_PREFIX + index);
        }
        return values;
    }

    /** Reads current or legacy data without ever throwing on a malformed remote document. */
    public static List<EmergencyContact> read(Object raw) {
        if (!(raw instanceof Map)) return Collections.emptyList();
        Object contactsValue = ((Map<?, ?>) raw).get(FIELD);
        if (!(contactsValue instanceof Map)) return Collections.emptyList();
        Map<?, ?> slots = (Map<?, ?>) contactsValue;
        List<EmergencyContact> result = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (int index = 0; index < EmergencyContacts.MAX; index++) {
            Object value = slots.get(SLOT_PREFIX + index);
            if (!(value instanceof Map)) continue;
            Map<?, ?> slot = (Map<?, ?>) value;
            Object name = slot.get("name");
            Object phone = slot.get("phone");
            Object key = slot.get("phoneKey");
            if (!(name instanceof String) || !(phone instanceof String) || !(key instanceof String)) continue;
            EmergencyContact contact = EmergencyContact.from((String) name, (String) phone);
            if (contact == null) continue;
            String canonical = EmergencyContacts.canonical(contact.phone);
            if (canonical == null || !canonical.equals(key) || !keys.add(canonical)) continue;
            result.add(contact);
        }
        return result;
    }
}
