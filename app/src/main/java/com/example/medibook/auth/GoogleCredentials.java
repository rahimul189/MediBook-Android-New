package com.example.medibook.auth;

import android.content.Context;
import android.content.MutableContextWrapper;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.utils.GoogleAuthFlow;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

/** Explicit button flow (also permits adding an account). Never retains or logs credentials. */
public final class GoogleCredentials implements AutoCloseable {
    private final Context application;
    private final MutableContextWrapper context;
    private final AuthRepository repository;
    private CancellationSignal cancellation;
    private int launched = -1;

    public GoogleCredentials(AppCompatActivity activity) {
        application = activity.getApplicationContext();
        context = new MutableContextWrapper(activity);
        repository = AuthRepository.getInstance();
    }

    @android.annotation.SuppressLint("DiscouragedApi") // Optional resource generated only after OAuth setup.
    public void launchIfNeeded(AuthRepository.GoogleState state) {
        if (state.stage != GoogleAuthFlow.Stage.SELECTING || launched == state.request) return;
        launched = state.request;
        int resource = application.getResources().getIdentifier("default_web_client_id", "string", application.getPackageName());
        String client = resource == 0 ? "" : application.getString(resource).trim();
        if (client.isEmpty() || !client.endsWith(".apps.googleusercontent.com")) {
            repository.googleSelectionFailed(launched, AuthRepository.Error.GOOGLE_CONFIGURATION); return;
        }
        cancellation = new CancellationSignal();
        final int request = launched;
        try {
            GetCredentialRequest options = new GetCredentialRequest.Builder().addCredentialOption(
                    new GetSignInWithGoogleOption.Builder(client).build()).build();
            CredentialManager.create(application).getCredentialAsync(context, options, cancellation,
                    ContextCompat.getMainExecutor(application), new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                        @Override public void onResult(GetCredentialResponse result) {
                            try {
                                Credential value = result.getCredential();
                                if (!(value instanceof CustomCredential)
                                        || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(value.getType())) {
                                    repository.googleSelectionFailed(request, AuthRepository.Error.GOOGLE_INVALID_CREDENTIAL); return;
                                }
                                GoogleIdTokenCredential google = GoogleIdTokenCredential.createFrom(value.getData());
                                // Firebase validates signature/audience/expiry; local parsing is not authentication.
                                repository.acceptGoogleCredential(request, google.getIdToken(), google.getId());
                            } catch (Exception invalid) {
                                repository.googleSelectionFailed(request, AuthRepository.Error.GOOGLE_INVALID_CREDENTIAL);
                            }
                        }
                        @Override public void onError(@NonNull GetCredentialException error) {
                            repository.googleSelectionFailed(request, error instanceof GetCredentialCancellationException
                                    ? AuthRepository.Error.GOOGLE_CANCELLED : error instanceof NoCredentialException
                                    ? AuthRepository.Error.GOOGLE_NO_CREDENTIAL : AuthRepository.Error.GOOGLE_UNAVAILABLE);
                        }
                    });
        } catch (RuntimeException unavailable) {
            repository.googleSelectionFailed(request, AuthRepository.Error.GOOGLE_UNAVAILABLE);
        }
    }

    @Override public void close() {
        // Rotation deliberately cancels just the chooser. An already-started Firebase Task survives.
        repository.googleSelectionFailed(launched, AuthRepository.Error.GOOGLE_CANCELLED);
        if (cancellation != null) cancellation.cancel();
        context.setBaseContext(application);
    }

    public static void clearState(Context context) {
        Context application = context.getApplicationContext();
        try {
            CredentialManager.create(application).clearCredentialStateAsync(new ClearCredentialStateRequest(),
                    new CancellationSignal(), ContextCompat.getMainExecutor(application),
                    new CredentialManagerCallback<Void, ClearCredentialException>() {
                        @Override public void onResult(Void result) { }
                        @Override public void onError(@NonNull ClearCredentialException error) { /* Sign-out is still effective. */ }
                    });
        } catch (RuntimeException unavailable) { /* Optional provider/device support; no credential revocation claimed. */ }
    }
}
