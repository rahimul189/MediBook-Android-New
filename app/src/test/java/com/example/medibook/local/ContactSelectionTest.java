package com.example.medibook.local;

import org.junit.Test;
import static org.junit.Assert.*;

public class ContactSelectionTest {
    private static final String URI = "content://com.android.contacts/data/42";
    private ContactSelection select(String name, String phone) {
        return ContactSelection.read(true, URI, () -> EmergencyContact.from(name, phone));
    }
    @Test public void selectedPhoneRowSuppliesBothFields() {
        ContactSelection result = select("  Family Member  ", "+880 1712-345678");
        assertEquals(ContactSelection.Status.SELECTED, result.status);
        assertEquals("Family Member", result.contact.name);
        assertEquals("+880 1712-345678", result.contact.phone);
    }
    @Test public void cancellationNeverQueriesProvider() {
        assertEquals(ContactSelection.Status.CANCELLED, ContactSelection.read(false, URI, () -> { fail("Provider must not be read"); return null; }).status);
    }
    @Test public void nullResultNeverQueriesProvider() {
        assertEquals(ContactSelection.Status.INVALID_RESULT, ContactSelection.read(true, null, () -> { fail(); return null; }).status);
    }
    @Test public void invalidUriSchemesAuthoritiesAndPathsAreRejected() {
        for (String uri : new String[]{"", "file:///contacts/1", "https://example.org/data/1", "content://evil.example/data/1",
                "content://com.android.contacts", "content://com.android.contacts/data", "content://com.android.contacts/data/1#secret", "not a uri"}) {
            assertFalse(uri, ContactSelection.validUri(uri));
        }
    }
    @Test public void androidProfilePrefixedAuthorityIsAccepted() { assertTrue(ContactSelection.validUri("content://10@com.android.contacts/data/42")); }
    @Test public void contactsLookupUriIsAcceptedForProviderValidation() { assertTrue(ContactSelection.validUri("content://com.android.contacts/contacts/lookup/key/42")); }
    @Test public void revokedAccessIsNotAnAppCrash() {
        assertEquals(ContactSelection.Status.UNAVAILABLE, ContactSelection.read(true, URI, () -> { throw new SecurityException("revoked"); }).status);
    }
    @Test public void malformedProviderRowsAreNotAnAppCrash() {
        assertEquals(ContactSelection.Status.UNAVAILABLE, ContactSelection.read(true, URI, () -> { throw new IllegalArgumentException("missing columns"); }).status);
    }
    @Test public void missingOrUnusableNumbersHaveAnExplicitState() {
        for (String phone : new String[]{null, "", "  ", "ab", "12", "*123#", "tel:+8801712345678", "123;456", "123,456"})
            assertEquals(ContactSelection.Status.NO_NUMBER, select("Name", phone).status);
    }
    @Test public void emptyCursorIsNoNumber() { assertEquals(ContactSelection.Status.NO_NUMBER, ContactSelection.read(true, URI, () -> null).status); }
    @Test public void nameIsOptionalAndCannotInjectMultilineUi() {
        assertEquals("", select(null, "999").contact.name);
        assertEquals("One Two", select("One\nTwo", "999").contact.name);
    }
    @Test public void displayNameAndPhoneSizesAreBounded() {
        assertEquals(200, select("x".repeat(300), "999").contact.name.length());
        assertNull(EmergencyContact.from("Name", "1".repeat(129)));
    }
    @Test public void formattedInternationalAndUnicodeDigitsNormalizeForDialer() {
        assertEquals("+8801712345678", EmergencyContact.dialable(" +880 (1712) 345-678 "));
        assertEquals("999", EmergencyContact.dialable("৯৯৯"));
    }
    @Test public void dialerCannotAcceptCommandsOrIntents() {
        for (String phone : new String[]{"+", "1+234", "123#", "*999", "123\n456", "123?x=4", "intent:123", "123x456"}) assertNull(phone, EmergencyContact.dialable(phone));
    }
}
