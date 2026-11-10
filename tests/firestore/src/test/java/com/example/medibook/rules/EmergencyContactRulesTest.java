package com.example.medibook.rules;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.example.medibook.rules.RulesClient.*;
import static org.junit.Assert.*;

/**
 * Emergency contacts are private to one Patient and one list document. These assertions exercise
 * the actual Rules, including the client-side 10-slot contract, duplicate-number blocking and the
 * ownership boundary that keeps one account's contacts invisible to every other account.
 */
public class EmergencyContactRulesTest extends RulesFixtures {
    private static final String OWNS = "users/patient-a/emergencyContacts/list";
    private static final String OTHER = "users/patient-b/emergencyContacts/list";

    private static Map<String, Object> slot(String name, String phone, String key) {
        return map("name", name, "phone", phone, "phoneKey", key);
    }

    private static Map<String, Object> slot(int index) {
        return slot("Contact " + index, "017120000" + String.format("%02d", index),
                "017120000" + String.format("%02d", index));
    }

    private static Map<String, Object> list(Object... slots) {
        Map<String, Object> contacts = new LinkedHashMap<>();
        Map<String, Object> phoneKeys = new LinkedHashMap<>();
        for (int index = 0; index < slots.length; index++) {
            String key = "c" + index;
            contacts.put(key, slots[index]);
            if (slots[index] instanceof Map) {
                Object phoneKey = ((Map<?, ?>) slots[index]).get("phoneKey");
                if (phoneKey instanceof String) phoneKeys.putIfAbsent((String) phoneKey, key);
            }
        }
        return map("contacts", contacts, "phoneKeys", phoneKeys, "updatedAt", Special.SERVER_TIME);
    }

    private static Map<String, Object> document(Map<String, Object> contacts,
                                                Map<String, Object> phoneKeys) {
        return map("contacts", contacts, "phoneKeys", phoneKeys, "updatedAt", Special.SERVER_TIME);
    }

    private static Map<String, Object> multiple(int count) {
        Object[] slots = new Object[count];
        for (int index = 0; index < count; index++) slots[index] = slot(index);
        return list(slots);
    }

    @Test public void patientCreatesReadsAndReplacesOwnList() {
        RulesClient patient = patient();
        allowed(patient.set(OWNS, multiple(10)));
        JsonObject fields = fields(patient.get(OWNS));
        assertEquals(10, fields.getAsJsonObject("contacts").getAsJsonObject("mapValue").getAsJsonObject("fields").size());
        // A full replacement with fewer contacts is how Remove is persisted.
        allowed(patient.set(OWNS, multiple(1)));
        assertEquals(1, fields(patient.get(OWNS)).getAsJsonObject("contacts").getAsJsonObject("mapValue").getAsJsonObject("fields").size());
    }

    @Test public void emptyListIsValid() {
        allowed(patient().set(OWNS, list()));
    }

    @Test public void ownerTransactionUsesTheSameSingleDocumentReadWriteShapeAsSync() {
        RulesClient client = patient();
        String transaction = allowed(client.beginTransaction()).getAsJsonObject().get("transaction").getAsString();
        allowed(client.transactionRead(transaction, OWNS));
        allowed(client.transactionCommit(transaction, write(OWNS, multiple(10), false)));
        String remove = allowed(client.beginTransaction()).getAsJsonObject().get("transaction").getAsString();
        allowed(client.transactionRead(remove, OWNS));
        allowed(client.transactionCommit(remove, write(OWNS, list(), false)));
        JsonObject empty = fields(client.get(OWNS)).getAsJsonObject("contacts").getAsJsonObject("mapValue");
        assertTrue(!empty.has("fields") || empty.getAsJsonObject("fields").isEmpty());
    }

    @Test public void elevenContactsAreDenied() {
        denied(patient().set(OWNS, multiple(11)));
    }

    @Test public void duplicateCanonicalNumberIsDenied() {
        denied(patient().set(OWNS, list(
                slot("Home", "01712345678", "01712345678"),
                slot("Same", "+01712345678", "01712345678"))));
    }

    @Test public void distinctNumbersAreAccepted() {
        allowed(patient().set(OWNS, list(
                slot("Home", "01712345678", "01712345678"),
                slot("Work", "+8801812345678", "8801812345678"))));
    }

    @Test public void malformedSlotShapeIsDenied() {
        denied(patient().set(OWNS, document(map("c0", map("name", "Missing key", "phone", "01712345678")),
                map("01712345678", "c0"))));
        denied(patient().set(OWNS, document(map("c0", map("name", "Extra", "phone", "01712345678",
                "phoneKey", "01712345678", "uri", "content://contacts/1")),
                map("01712345678", "c0"))));
        denied(patient().set(OWNS, list(merge(slot("A", "01712345678", "01712345678"),
                map("phoneKey", 1712345678L)))));
    }

    @Test public void invalidPhoneOrKeyIsDenied() {
        denied(patient().set(OWNS, list(slot("Bad phone", "01712#345678", "01712345678"))));
        denied(patient().set(OWNS, list(slot("Bad key", "01712345678", "01712-345678"))));
        denied(patient().set(OWNS, list(slot("Short key", "01712345678", "12"))));
        denied(patient().set(OWNS, document(map("c0", slot("Empty phone", "", "")), map("123", "c0"))));
        denied(patient().set(OWNS, list(slot("Forged key", "01712345678", "01812345678"))));
        denied(patient().set(OWNS, list(slot("A", "01712345678", "01712345678"),
                slot("Same with forged key", "+01712-345678", "01812345678"))));
    }

    @Test public void oversizedOrMultilineNameIsDenied() {
        denied(patient().set(OWNS, list(slot("x".repeat(201), "01712345678", "01712345678"))));
        denied(patient().set(OWNS, list(slot("First\nSecond", "01712345678", "01712345678"))));
        denied(patient().set(OWNS, list(slot("Bell\u0007", "01712345678", "01712345678"))));
        allowed(patient().set(OWNS, list(slot("x".repeat(200), "01712345678", "01712345678"))));
        allowed(patient().set(OWNS, list(slot("", "01712345678", "01712345678"))));
    }

    @Test public void clientTimestampAndExtraFieldsAreDenied() {
        denied(patient().set(OWNS, merge(multiple(1), map("updatedAt", OLD_TIME))));
        denied(patient().set(OWNS, merge(multiple(1), map("note", "unexpected"))));
    }

    @Test public void missingOrMismatchedPhoneIndexIsDenied() {
        denied(patient().set(OWNS, map("contacts", map("c0", slot(0)),
                "updatedAt", Special.SERVER_TIME)));
        denied(patient().set(OWNS, map("contacts", map("c0", slot(0)),
                "phoneKeys", map("01712000000", "c0", "01812000000", "c0"),
                "updatedAt", Special.SERVER_TIME)));
        denied(patient().set(OWNS, map("contacts", map("c0", slot(0)),
                "phoneKeys", map("01712000000", "c1"),
                "updatedAt", Special.SERVER_TIME)));
    }

    @Test public void onlyTheOwningPatientCanReadOrWrite() {
        seed(OWNS, multiple(1));
        seed("users/patient-b", merge(profile(), map("email", "other@example.invalid")));
        allowed(patient().get(OWNS));
        denied(patient().get(OTHER));
        RulesClient other = authenticated("patient-b", map("email", "other@example.invalid"));
        allowed(other.set(OTHER, multiple(1)));
        denied(other.set(OWNS, multiple(1)));
        denied(patient().set(OTHER, multiple(1)));
        denied(anonymous().set(OWNS, multiple(1)));
        denied(anonymous().get(OWNS));
        denied(admin().set(OWNS, multiple(1)));
        denied(admin().get(OWNS));
    }

    @Test public void inheritedReadsAndDeletesAreDenied() {
        seed(OWNS, multiple(2));
        denied(patient().get("users/patient-a/emergencyContacts"));
        denied(patient().query("users", false));
        denied(patient().query("users/patient-a", "emergencyContacts", null, false));
        denied(patient().delete(OWNS));
    }

    @Test public void malformedAdminClaimsCannotBypassOwnershipOrPatientRole() {
        for (Object claim : new Object[]{false, "true", 1}) {
            RulesClient client = authenticated("patient-a", map("admin", claim));
            denied(client.get(OWNS)); denied(client.set(OWNS, multiple(1)));
        }
        denied(authenticated("missing-profile", map()).set("users/missing-profile/emergencyContacts/list", multiple(1)));
    }
}
