package com.example.medibook.activities.patient;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.lifecycle.ViewModelProvider;

import com.example.medibook.R;
import com.example.medibook.activities.AppointmentSessionActivity;
import com.example.medibook.databinding.ActivityEmergencyContactBinding;
import com.example.medibook.databinding.ItemEmergencyContactBinding;
import com.example.medibook.local.ContactSelection;
import com.example.medibook.local.EmergencyContact;
import com.example.medibook.local.EmergencyContacts;
import com.example.medibook.local.LocalPatientServices;
import com.example.medibook.models.UserProfile;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.utils.DialIntents;
import com.example.medibook.viewmodels.EmergencyContactViewModel;

import java.util.List;

public final class EmergencyContactActivity extends AppointmentSessionActivity {
    private ActivityEmergencyContactBinding binding;
    private EmergencyContactViewModel model;
    private String pickerOwner, pickerEpoch;
    private boolean pending, pendingSuccess;
    private String pendingUri;
    private boolean contactsReady, contactsSyncing, contactsError;
    private LocalPatientServices.ContactWatch contactWatch;
    private int pendingContactMessage;
    private androidx.appcompat.app.AlertDialog removeDialog;
    private final ActivityResultLauncher<Intent> picker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        pending = true; pendingSuccess = result.getResultCode() == Activity.RESULT_OK;
        pendingUri = result.getData() == null || result.getData().getData() == null ? null : result.getData().getData().toString();
        consumeResult();
    });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); binding = ActivityEmergencyContactBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot()); binding.toolbar.setNavigationOnClickListener(v -> finish());
        model = new ViewModelProvider(this).get(EmergencyContactViewModel.class);
        if (state != null) { pickerOwner = state.getString("pickerOwner"); pickerEpoch = state.getString("pickerEpoch"); }
        binding.addButton.setOnClickListener(v -> {
            if (!canAccess() || !contactsReady || model.reading || contacts().size() >= EmergencyContacts.MAX) return;
            pickerOwner = owner; pickerEpoch = LocalPatientServices.get().epoch(owner);
            try {
                picker.launch(new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI));
            } catch (ActivityNotFoundException | SecurityException unavailable) { binding.message.setText(R.string.contact_picker_unavailable); }
        });
        model.result().observe(this, status -> { if (canAccess()) { render(); showResult(status); } });
        binding.syncRetryButton.setOnClickListener(v -> {
            if (canAccess()) startContacts();
        });
    }

    @Override protected UserProfile.Role requiredRole() { return UserProfile.Role.PATIENT; }
    @Override protected View protectedContent() { return binding.content; }
    @Override protected View sessionLoading() { return binding.sessionLoading.getRoot(); }
    @Override protected void onVerifiedSession() {
        consumeResult(); render(); startContacts(); showResult(model.result().getValue());
    }

    @Override protected void onStop() {
        stopContacts();
        super.onStop();
    }

    @Override protected void clearSessionViews() {
        stopContacts();
        if (removeDialog != null) removeDialog.dismiss();
        binding.contactList.removeAllViews();
        binding.contactCount.setText(null);
        binding.emptyMessage.setVisibility(View.GONE);
        binding.syncStatus.setVisibility(View.GONE);
        binding.syncRetryButton.setVisibility(View.GONE);
        binding.addButton.setEnabled(false);
        binding.message.setText(null);
    }

    private void startContacts() {
        stopContacts();
        if (!canAccess()) return;
        LocalPatientServices local = LocalPatientServices.get();
        contactsReady = false; contactsSyncing = true; contactsError = false;
        render();
        final String token = local.epoch(owner);
        contactWatch = local.watchContacts(owner, token, (contacts, ready, error) -> {
            if (!canAccess()) return;
            contactsReady = ready; contactsSyncing = !ready && !error; contactsError = error;
            if (error) { pendingContactMessage = 0; binding.message.setText(null); }
            else if (ready && pendingContactMessage != 0) {
                binding.message.setText(pendingContactMessage); pendingContactMessage = 0;
            }
            render();
        });
    }

    private void stopContacts() {
        if (contactWatch != null) contactWatch.close();
        contactWatch = null;
        contactsReady = false; contactsSyncing = false; contactsError = false;
    }

    private List<EmergencyContact> contacts() { return LocalPatientServices.get().contacts(owner); }

    private void consumeResult() {
        if (!pending || !canAccess()) return;
        pending = false;
        if (LocalPatientServices.get().matches(pickerOwner, pickerEpoch) && owner.equals(pickerOwner)) {
            model.add(pickerOwner, pickerEpoch, pendingSuccess, pendingUri);
        }
        pendingUri = null;
    }

    private void render() {
        LocalPatientServices local = LocalPatientServices.get();
        List<EmergencyContact> contacts = contacts();
        binding.contactList.removeAllViews();
        for (EmergencyContact contact : contacts) {
            ItemEmergencyContactBinding row = ItemEmergencyContactBinding.inflate(
                    getLayoutInflater(), binding.contactList, false);
            String displayName = contact.name.isEmpty() ? getString(R.string.contact_unnamed) : contact.name;
            row.contactName.setText(displayName);
            row.contactPhone.setText(contact.phone);
            row.callButton.setContentDescription(getString(R.string.contact_call_description, displayName));
            row.removeButton.setContentDescription(getString(R.string.contact_remove_description, displayName));
            row.removeButton.setEnabled(contactsReady && !model.reading);
            row.callButton.setOnClickListener(v -> {
                if (canAccess()) DialIntents.dial(this, contact.phone);
            });
            row.removeButton.setOnClickListener(v -> {
                if (!canAccess() || !contactsReady || model.reading || (removeDialog != null && removeDialog.isShowing())) return;
                String targetOwner = owner;
                String targetEpoch = local.epoch(owner);
                removeDialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_MediBook_DestructiveDialog)
                        .setTitle(R.string.contact_remove_title).setMessage(getString(R.string.contact_remove_message, displayName))
                        .setNegativeButton(R.string.cancel_button, null)
                        .setPositiveButton(R.string.contact_remove, (dialog, which) -> {
                            if (!canAccess() || !targetOwner.equals(owner) || !local.matches(targetOwner, targetEpoch) || !contactsReady || model.reading) return;
                            if (local.removeContact(owner, targetEpoch, contact)) {
                                render(); pendingContactMessage = R.string.contact_removed;
                                binding.message.setText(R.string.contact_syncing);
                            }
                        }).show();
            });
            binding.contactList.addView(row.getRoot());
        }
        int count = contacts.size();
        binding.contactCount.setText(getString(R.string.contact_count, count, EmergencyContacts.MAX));
        binding.emptyMessage.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        binding.addButton.setEnabled(contactsReady && !model.reading && count < EmergencyContacts.MAX);
        binding.syncStatus.setVisibility(contactsSyncing || contactsError ? View.VISIBLE : View.GONE);
        binding.syncStatus.setText(contactsError ? R.string.contact_sync_error : R.string.contact_syncing);
        binding.syncStatus.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                contactsError ? R.color.medibook_error : R.color.medibook_text_secondary));
        binding.syncRetryButton.setVisibility(contactsError ? View.VISIBLE : View.GONE);
        if (model.reading) binding.message.setText(R.string.contact_reading);
    }

    private void showResult(ContactSelection.Status status) {
        if (status == null || !LocalPatientServices.get().matches(model.resultOwner, model.resultEpoch)) return;
        switch (status) {
            case CANCELLED:
                binding.message.setText(null);
                break;
            case SELECTED:
                if (contactsError) break;
                if (contactsReady) binding.message.setText(R.string.contact_saved);
                else { pendingContactMessage = R.string.contact_saved; binding.message.setText(R.string.contact_syncing); }
                break;
            case DUPLICATE:
                binding.message.setText(R.string.contact_duplicate);
                break;
            case LIMIT:
                binding.message.setText(R.string.contact_limit_reached);
                break;
            case NO_NUMBER:
                binding.message.setText(R.string.contact_no_number);
                break;
            case SYNC_PENDING:
                binding.message.setText(R.string.contact_sync_pending);
                break;
            default:
                binding.message.setText(R.string.contact_unavailable);
                break;
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("pickerOwner", pickerOwner); state.putString("pickerEpoch", pickerEpoch); super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (removeDialog != null) removeDialog.dismiss();
        super.onDestroy();
    }
}
