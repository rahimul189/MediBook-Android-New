package com.example.medibook.local;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class PrivatePatientStoreTest {
    @Test public void serverReplacementPreservesMigrationAcrossRestartAndNeverMergesDeletedContacts() {
        Memory disk = new Memory();
        PrivatePatientStore store = new PrivatePatientStore(disk); store.bindAccount("a");
        String token = store.epoch(); store.addContact("a", token, contact("Legacy", "999"));
        store.markContactsMigrated();
        assertTrue(store.replaceContacts("a", token, java.util.Collections.emptyList()));
        assertTrue(store.contactsMigrated()); assertTrue(store.contacts("a").isEmpty());
        PrivatePatientStore restored = new PrivatePatientStore(disk);
        assertTrue(restored.contactsMigrated()); assertTrue(restored.contacts("a").isEmpty());
        assertFalse(restored.replaceContacts("b", token, java.util.Collections.singletonList(contact("Other", "111"))));
        restored.bindAccount("b"); assertFalse(restored.contactsMigrated());
    }
    private static final class Memory implements PrivatePatientStore.Storage {
        Map<String, String> disk = new HashMap<>();
        @Override public Map<String, String> read() { return new HashMap<>(disk); }
        @Override public void replace(Map<String, String> values) { disk = new HashMap<>(values); }
    }

    private static EmergencyContact contact(String name, String phone) {
        return EmergencyContact.from(name, phone);
    }

    @Test public void storageStartsEmptyAndNoAnonymousContactCanBeSaved() {
        PrivatePatientStore store = new PrivatePatientStore(new Memory());
        assertNull(store.owner());
        assertTrue(store.contacts(null).isEmpty());
        assertEquals(EmergencyContacts.AddResult.ACCOUNT_CHANGED,
                store.addContact(null, null, contact("Family", "999")));
        store.enableReminders(true); store.putReminder("anonymous", "123:100:scheduled");
        assertFalse(store.remindersEnabled()); assertTrue(store.reminderRecords().isEmpty());
    }

    @Test public void onlyCurrentOwnerAndEpochCanAddOrRemove() {
        PrivatePatientStore store = new PrivatePatientStore(new Memory()); store.bindAccount("a");
        EmergencyContact candidate = contact("A", "999");
        assertEquals(EmergencyContacts.AddResult.ACCOUNT_CHANGED, store.addContact("b", store.epoch(), candidate));
        assertEquals(EmergencyContacts.AddResult.ACCOUNT_CHANGED, store.addContact("a", "stale", candidate));
        assertEquals(EmergencyContacts.AddResult.ADDED, store.addContact("a", store.epoch(), candidate));
        assertTrue(store.contacts("b").isEmpty()); assertEquals("A", store.contacts("a").get(0).name);
        assertFalse(store.removeContact("b", store.epoch(), candidate));
        assertFalse(store.removeContact("a", "stale", candidate));
        assertTrue(store.removeContact("a", store.epoch(), candidate));
    }

    @Test public void sameUidReverificationDoesNotDeleteContactsOrChangeEpoch() {
        PrivatePatientStore store = new PrivatePatientStore(new Memory()); store.bindAccount("a"); String token = store.epoch();
        store.addContact("a", token, contact("A", "999"));
        assertFalse(store.bindAccount("a")); assertEquals(token, store.epoch()); assertEquals(1, store.contacts("a").size());
    }

    @Test public void accountSwitchClearsContactsRemindersConsentAndLedger() {
        PrivatePatientStore store = new PrivatePatientStore(new Memory()); store.bindAccount("a"); String old = store.epoch();
        store.addContact("a", old, contact("A", "999")); store.enableReminders(true); store.putReminder("booking", "123:100:scheduled");
        store.bindAccount("b"); assertTrue(store.contacts("a").isEmpty()); assertTrue(store.contacts("b").isEmpty());
        assertFalse(store.remindersEnabled()); assertTrue(store.reminderRecords().isEmpty()); assertNotEquals(old, store.epoch());
    }

    @Test public void logoutActuallyErasesPrivateFieldsFromStorage() {
        Memory memory = new Memory(); PrivatePatientStore store = new PrivatePatientStore(memory); store.bindAccount("a");
        store.addContact("a", store.epoch(), contact("A", "999")); store.enableReminders(true);
        store.bindAccount(null); assertTrue(memory.disk.isEmpty()); assertNull(store.epoch());
    }

    @Test public void sameAccountReloginCannotAcceptOldPickerOrNotificationToken() {
        PrivatePatientStore store = new PrivatePatientStore(new Memory()); store.bindAccount("a"); String old = store.epoch();
        store.bindAccount(null); store.bindAccount("a"); assertFalse(store.matches("a", old));
        assertEquals(EmergencyContacts.AddResult.ACCOUNT_CHANGED,
                store.addContact("a", old, contact("Late result", "999")));
    }

    @Test public void restartKeepsOnlyTheCurrentAccountsMinimalContacts() {
        Memory memory = new Memory(); PrivatePatientStore first = new PrivatePatientStore(memory); first.bindAccount("a");
        first.addContact("a", first.epoch(), contact("A", "999"));
        PrivatePatientStore restarted = new PrivatePatientStore(memory); assertFalse(restarted.bindAccount("a"));
        assertEquals("999", restarted.contacts("a").get(0).phone); assertEquals(first.epoch(), restarted.epoch());
        assertFalse(memory.disk.containsKey("uri")); assertFalse(memory.disk.containsKey("photo"));
    }

    @Test public void coldStartUnderDifferentUidErasesPersistedContacts() {
        Memory memory = new Memory(); PrivatePatientStore old = new PrivatePatientStore(memory); old.bindAccount("a");
        old.addContact("a", old.epoch(), contact("A", "999"));
        PrivatePatientStore restarted = new PrivatePatientStore(memory); restarted.bindAccount("b"); assertTrue(restarted.contacts("b").isEmpty());
        assertFalse(memory.disk.containsKey("contacts.count"));
        assertFalse(memory.disk.containsKey("contacts.0.phone"));
    }

    @Test public void legacySingleContactIsMigratedOnceAndRetainsBothFields() {
        Memory memory = new Memory();
        memory.disk.put("owner", "a"); memory.disk.put("epoch", "epoch-a");
        memory.disk.put("name", "Family"); memory.disk.put("phone", "+880 1712-345678");
        PrivatePatientStore store = new PrivatePatientStore(memory);
        List<EmergencyContact> contacts = store.contacts("a");
        assertEquals(1, contacts.size()); assertEquals("Family", contacts.get(0).name);
        assertEquals("+880 1712-345678", contacts.get(0).phone);
        assertFalse(store.contacts("a").isEmpty());
        assertFalse(memory.disk.containsKey("name")); assertFalse(memory.disk.containsKey("phone"));
        PrivatePatientStore restarted = new PrivatePatientStore(memory);
        assertEquals(1, restarted.contacts("a").size());
    }

    @Test public void legacyMigrationNeverCreatesAnInvalidOrAnonymousContact() {
        Memory memory = new Memory(); memory.disk.put("name", "Family"); memory.disk.put("phone", "12");
        PrivatePatientStore store = new PrivatePatientStore(memory);
        assertTrue(store.contacts(null).isEmpty()); assertFalse(memory.disk.containsKey("name"));
        assertFalse(memory.disk.containsKey("phone"));
    }

    @Test public void duplicateNumbersAreRejectedIgnoringFormatting() {
        PrivatePatientStore store = new PrivatePatientStore(new Memory()); store.bindAccount("a");
        assertEquals(EmergencyContacts.AddResult.ADDED,
                store.addContact("a", store.epoch(), contact("A", "+880 (1712) 345-678")));
        assertEquals(EmergencyContacts.AddResult.DUPLICATE,
                store.addContact("a", store.epoch(), contact("Duplicate", "+8801712345678")));
        assertEquals(EmergencyContacts.AddResult.DUPLICATE,
                store.addContact("a", store.epoch(), contact("Duplicate", " +880 1712 345 678 ")));
        assertEquals(1, store.contacts("a").size());
    }

    @Test public void atMostTenContactsCanBeStored() {
        PrivatePatientStore store = new PrivatePatientStore(new Memory()); store.bindAccount("a");
        for (int index = 0; index < EmergencyContacts.MAX; index++) {
            assertEquals(EmergencyContacts.AddResult.ADDED,
                    store.addContact("a", store.epoch(), contact("Contact " + index, "900000" + index)));
        }
        assertEquals(EmergencyContacts.AddResult.LIMIT,
                store.addContact("a", store.epoch(), contact("Eleventh", "999999")));
        assertEquals(EmergencyContacts.MAX, store.contacts("a").size());
    }

    @Test public void removingOneContactCompactsTheRemainingOrderedList() {
        PrivatePatientStore store = new PrivatePatientStore(new Memory()); store.bindAccount("a");
        EmergencyContact first = contact("First", "1111111"), second = contact("Second", "2222222"), third = contact("Third", "3333333");
        store.addContact("a", store.epoch(), first); store.addContact("a", store.epoch(), second); store.addContact("a", store.epoch(), third);
        assertTrue(store.removeContact("a", store.epoch(), second));
        List<EmergencyContact> contacts = store.contacts("a");
        assertEquals(2, contacts.size()); assertEquals("First", contacts.get(0).name); assertEquals("Third", contacts.get(1).name);
        assertFalse(store.removeContact("a", store.epoch(), second));
    }

    @Test public void removingContactLeavesReminderPreferenceIntact() {
        PrivatePatientStore store = new PrivatePatientStore(new Memory()); store.bindAccount("a"); store.enableReminders(true);
        EmergencyContact candidate = contact("A", "999");
        store.addContact("a", store.epoch(), candidate);
        store.removeContact("b", store.epoch(), candidate); assertEquals(1, store.contacts("a").size());
        store.removeContact("a", store.epoch(), candidate); assertTrue(store.contacts("a").isEmpty()); assertTrue(store.remindersEnabled());
    }

    @Test public void togglingRemindersRetainsSentAndCancelledTombstones() {
        PrivatePatientStore store = new PrivatePatientStore(new Memory()); store.bindAccount("a");
        store.putReminder("booking", "123:100:sent"); store.enableReminders(false); store.enableReminders(true);
        assertEquals("123:100:sent", store.reminderRecords().get("booking"));
    }

    @Test public void defaultAndCustomReminderLedgersNeverSurviveAnAccountSwitch() {
        PrivatePatientStore store = new PrivatePatientStore(new Memory()); store.bindAccount("a");
        store.putEntry("defaults.booking", "123:100:200:0:0:0");
        store.putEntry("custom.booking", "123:100:4:0:scheduled");
        assertNotNull(store.entry("defaults.booking")); assertNotNull(store.entry("custom.booking"));
        store.bindAccount("b");
        assertNull(store.entry("defaults.booking")); assertNull(store.entry("custom.booking"));
    }

    @Test public void ledgersRequireAnOwnerAndSurviveRepeatedPendingWork() {
        PrivatePatientStore store = new PrivatePatientStore(new Memory());
        store.putEntry("defaults.booking", "123:100:200:0:0:0");
        assertNull(store.entry("defaults.booking"));
        store.bindAccount("a");
        store.putEntry("defaults.booking", "123:100:200:0:0:0");
        store.putEntry("defaults.booking", "123:100:200:0:0:0");
        assertEquals(1, store.entries("defaults.").size());
    }

    @Test public void ledgerSnapshotsAreNotMutableStorageAliases() {
        PrivatePatientStore store = new PrivatePatientStore(new Memory()); store.bindAccount("a"); store.putReminder("id", "x");
        store.reminderRecords().clear(); assertEquals("x", store.reminderRecords().get("id"));
    }
}
