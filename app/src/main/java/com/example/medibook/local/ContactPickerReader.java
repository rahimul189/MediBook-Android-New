package com.example.medibook.local;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

/** Reads ONLY the user-granted phone row; never enumerates the address book. */
public final class ContactPickerReader {
    private ContactPickerReader() { }
    public static ContactSelection read(ContentResolver resolver, boolean success, String selectedUri) {
        return ContactSelection.read(success, selectedUri, () -> {
            String[] columns = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER};
            try (Cursor cursor = resolver.query(Uri.parse(selectedUri), columns, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) return null;
                int name = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                int number = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                return number < 0 ? null : EmergencyContact.from(name < 0 ? null : cursor.getString(name), cursor.getString(number));
            }
        });
    }
}
