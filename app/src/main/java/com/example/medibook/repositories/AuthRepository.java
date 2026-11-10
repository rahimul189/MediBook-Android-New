package com.example.medibook.repositories;

import android.os.Handler;
import android.os.Looper;

import com.example.medibook.models.UserProfile;
import com.example.medibook.utils.Diagnostics;
import com.example.medibook.utils.PatientGender;
import com.example.medibook.utils.SessionPolicy;
import com.example.medibook.utils.VerifiedSessionLease;
import com.example.medibook.utils.GoogleAccountPolicy;
import com.example.medibook.utils.GoogleAuthFlow;
import com.example.medibook.utils.GoogleProfileCompletion;
import com.example.medibook.utils.PatientProfileValidation;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.auth.UserInfo;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One in-process authentication operation at a time. Activities observe immutable state,
 * so a rotation does not repeat a request or abandon profile creation/cleanup.
 * Activities detach listeners onStop; SDK callbacks do not capture Activity references.
 * Public operations and listeners run on the main thread. Passwords are not retained in state.
 */
public final class AuthRepository {

    public enum Error {
        NONE, NETWORK, TOO_MANY_REQUESTS, EMAIL_IN_USE, WEAK_PASSWORD,
        INVALID_CREDENTIALS, AUTH_UNAVAILABLE, PERMISSION_DENIED, PROFILE_MISSING,
        INVALID_PROFILE, ROLE_MISMATCH, PROFILE_FAILED_ACCOUNT_REMOVED,
        PROFILE_FAILED_ACCOUNT_REMAINS, SESSION_EXPIRED, UNKNOWN,
        GOOGLE_CONFIGURATION, GOOGLE_CANCELLED, GOOGLE_NO_CREDENTIAL, GOOGLE_INVALID_CREDENTIAL,
        GOOGLE_UNAVAILABLE, GOOGLE_CONFLICT, GOOGLE_EMAIL_MISMATCH, GOOGLE_ALREADY_LINKED,
        REAUTH_REQUIRED, AUTH_IN_PROGRESS, ACCOUNT_DISABLED, AUTH_CONFIGURATION, AUTH_REJECTED
    }

    public static final class State {
        public final boolean loading;
        public final UserProfile user;
        public final Error error;
        public final PendingProfile pendingProfile;

        private State(boolean loading, UserProfile user, Error error) {
            this(loading, user, error, null);
        }
        private State(boolean loading, UserProfile user, Error error, PendingProfile pending) {
            this.loading = loading;
            this.user = user;
            this.error = error;
            this.pendingProfile = pending;
        }
    }

    /** Server-verified onboarding identity, never a role or an Intent-supplied UID/email. */
    public static final class PendingProfile {
        public final String uid, email, fullName, phone, dateOfBirth, gender;
        private PendingProfile(FirebaseUser identity, UserProfile existing) {
            uid = identity.getUid(); email = identity.getEmail();
            fullName = existing == null ? identity.getDisplayName() : existing.getFullName();
            phone = existing == null ? "" : existing.getPhone();
            dateOfBirth = existing == null ? null : existing.getDateOfBirth();
            gender = existing == null ? null : existing.getGender();
        }
    }

    public static final class GoogleState {
        public final int request;
        public final GoogleAuthFlow.Mode mode;
        public final GoogleAuthFlow.Stage stage;
        public final boolean busy;
        public final Error error;
        private GoogleState(GoogleAuthFlow flow, Error error) {
            request = flow.request(); mode = flow.mode(); stage = flow.stage(); busy = flow.busy(); this.error = error;
        }
    }
    public interface GoogleListener { void onGoogleStateChanged(GoogleState state); }

    public interface Listener {
        void onAuthStateChanged(State state);
    }

    public interface ProfileUpdateListener {
        void onProfileUpdate(boolean success, Error error);
    }

    private static AuthRepository instance;

    private final FirebaseAuth auth = FirebaseAuth.getInstance();
    private final FirebaseFirestore firestore = FirebaseFirestore.getInstance();
    private final Set<Listener> listeners = new HashSet<>();
    private State state = new State(false, null, Error.NONE);
    private volatile int operationId;
    private String verifiedUid, observedUid, profileUid, checkingUid;
    private long verifiedAt = -1, tokenExpiresAt;
    private boolean changingAccount;
    private boolean checkingExisting;
    /** A warm-resume revalidation that must not replace verified content with a loader. */
    private boolean quietChecking;
    /** A verification that never answers must not leave a screen permanently on the loader. */
    private static final long VERIFY_TIMEOUT_MS = 20_000L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable verificationWatchdog;
    private final GoogleAuthFlow googleFlow = new GoogleAuthFlow();
    private final Set<GoogleListener> googleListeners = new HashSet<>();
    private Error googleError = Error.NONE;
    private Runnable googleWatchdog;
    // Firebase sign-in/link Tasks cannot be cancelled. After a UI deadline, drain the old Task
    // before another authentication mutation can start; discard its late session, never merge.
    private boolean authTransportBusy;

    private AuthRepository() {
        observedUid = currentUid();
        auth.addAuthStateListener(firebase -> {
            String uid = currentUid();
            if (!java.util.Objects.equals(observedUid, uid)) {
                observedUid = uid;
                // SDK state notifications can arrive after the sign-in Task and even its profile
                // read. Never cancel our own already-verified/current-UID operation as an account swap.
                if (uid != null && uid.equals(profileUid) && (state.user != null || state.pendingProfile != null)) return;
                com.example.medibook.cache.PatientDataCache.get().clear();
                PatientDashboardRepository.clearCachedSummary();
                // Only a real sign-out may erase the private per-account store. A sign-in's UID event
                // is already bound by LocalPatientServices' own auth listener, which clears the
                // previous account's data, alarms and work before binding the new one; erasing here
                // would wipe the account it just bound and leave no epoch for contacts/reminders.
                if (uid == null) com.example.medibook.local.LocalPatientServices.signOutBestEffort();
                invalidateVerifiedSession();
                if (!state.loading || (!changingAccount && !java.util.Objects.equals(checkingUid, uid))) {
                    operationId++;
                    cancelGoogleWatchdog(); googleFlow.clear(); googleError = Error.NONE; publishGoogle();
                    profileUid = null;
                    publish(new State(false, null, uid == null && (state.user != null || checkingExisting) ? Error.SESSION_EXPIRED : Error.NONE));
                }
            }
        });
        // Do not invalidate the lease with an IdTokenListener. verifySession() forces a token
        // refresh with getIdToken(true); FirebaseAuth notifies IdTokenListeners for that refresh
        // *after* the verified lease is installed, which made the very next screen run a redundant
        // full revalidation (and risked a needless "Signing you in..." card on slow networks). The
        // lease is bounded by VerifiedSessionLease.MAX_AGE_MILLIS and the token expiry, and every
        // Firestore/RTDB read and write still re-evaluates the server-side claims.
    }

    private String currentUid() { return auth.getCurrentUser() == null ? null : auth.getCurrentUser().getUid(); }
    public void invalidateVerifiedSession() { verifiedUid = null; verifiedAt = -1; tokenExpiresAt = 0; }

    public static synchronized AuthRepository getInstance() {
        if (instance == null) {
            instance = new AuthRepository();
        }
        return instance;
    }

    public void addListener(Listener listener) {
        if ((state.user != null || state.pendingProfile != null) && !java.util.Objects.equals(profileUid, currentUid())) {
            state = new State(false, null, Error.NONE);
        }
        listeners.add(listener);
        listener.onAuthStateChanged(state);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void signIn(String email, String password) {
        if (operationBlocked()) return;
        int operation = beginOperation();
        checkingExisting = false;
        changingAccount = true;
        authTransportBusy = true;
        scheduleVerificationWatchdog(operation);
        auth.signInWithEmailAndPassword(email, password).addOnCompleteListener(task -> {
            authTransportBusy = false;
            changingAccount = false;
            if (operation != operationId) { discardLateSession(task.isSuccessful() ? task.getResult().getUser() : null); return; }
            if (!task.isSuccessful()) {
                fail(operation, mapError(task.getException()));
                return;
            }
            verifySession(auth.getCurrentUser(), operation, false);
        });
    }

    public void register(String fullName, String email, String phone, String dateOfBirth,
                         String gender, String password) {
        if (operationBlocked()) return;
        int operation = beginOperation();
        if (!PatientGender.isWireValue(gender)) {
            fail(operation, Error.INVALID_PROFILE);
            return;
        }
        checkingExisting = false;
        changingAccount = true;
        authTransportBusy = true;
        scheduleVerificationWatchdog(operation);
        auth.createUserWithEmailAndPassword(email, password).addOnCompleteListener(task -> {
            authTransportBusy = false;
            changingAccount = false;
            if (operation != operationId) { discardLateSession(task.isSuccessful() ? task.getResult().getUser() : null); return; }
            if (!task.isSuccessful()) {
                fail(operation, mapError(task.getException()));
                return;
            }
            FirebaseUser user = auth.getCurrentUser();
            if (user == null) {
                fail(operation, Error.UNKNOWN);
                return;
            }
            checkingUid = user.getUid(); // Own Auth callback may arrive during the profile write.

            Map<String, Object> profile = new HashMap<>();
            profile.put("fullName", fullName);
            profile.put("email", user.getEmail());
            profile.put("phone", phone);
            profile.put("dateOfBirth", dateOfBirth);
            profile.put("gender", gender);
            profile.put("role", UserProfile.Role.PATIENT.getProfileValue());
            profile.put("createdAt", FieldValue.serverTimestamp());

            // Completion means the server accepted the write, not just the local cache.
            firestore.collection("users").document(user.getUid()).set(profile)
                    .addOnCompleteListener(profileTask -> {
                        if (operation != operationId) {
                            return;
                        }
                        if (profileTask.isSuccessful()) {
                            verifySession(user, operation, true);
                        } else {
                            rollbackNewAccount(user, operation);
                        }
                    });
        });
    }

    /** True only for an in-memory verification of the account Firebase currently reports. */
    public boolean hasVerifiedUser() {
        String uid = currentUid();
        return !state.loading && state.user != null && uid != null && uid.equals(profileUid);
    }

    /** The verified in-memory profile, or null. Never read from disk or a cached snapshot. */
    public UserProfile verifiedUser() {
        return hasVerifiedUser() ? state.user : null;
    }

    public PendingProfile pendingProfile() {
        return state.pendingProfile != null && state.pendingProfile.uid.equals(currentUid()) ? state.pendingProfile : null;
    }

    public Set<String> providers() {
        Set<String> result = new HashSet<>();
        FirebaseUser identity = auth.getCurrentUser();
        if (identity != null) for (UserInfo provider : identity.getProviderData()) result.add(provider.getProviderId());
        return result;
    }

    public void addGoogleListener(GoogleListener listener) {
        googleListeners.add(listener); listener.onGoogleStateChanged(new GoogleState(googleFlow, googleError));
    }
    public void removeGoogleListener(GoogleListener listener) { googleListeners.remove(listener); }
    private void publishGoogle() {
        GoogleState value = new GoogleState(googleFlow, googleError);
        for (GoogleListener listener : new ArrayList<>(googleListeners)) listener.onGoogleStateChanged(value);
    }
    private void discardLateSession(FirebaseUser returned) {
        if (returned != null && returned.getUid().equals(currentUid()) && state.user == null && state.pendingProfile == null) auth.signOut();
    }
    private boolean operationBlocked() {
        if (state.loading || googleFlow.busy()) return true;
        if (authTransportBusy) {
            publish(new State(false, state.user, Error.AUTH_IN_PROGRESS, state.pendingProfile)); return true;
        }
        return false;
    }

    public void beginGoogleSignIn() {
        if (operationBlocked()) return;
        if (currentUid() != null) { checkSession(); return; }
        checkingExisting = false; quietChecking = false;
        int request = beginOperation();
        googleFlow.start(request, GoogleAuthFlow.Mode.SIGN_IN, null); googleError = Error.NONE;
        scheduleGoogleWatchdog(request, 90_000L); publishGoogle();
    }

    /** Always prove the existing password account before asking to attach a Google credential. */
    public void beginGoogleLink(String password) {
        if (operationBlocked()) return;
        FirebaseUser identity = auth.getCurrentUser();
        UserProfile verified = verifiedUser();
        if (identity == null || !GoogleAccountPolicy.canLink(verified, providers())) {
            googleError = providers().contains("google.com") ? Error.GOOGLE_ALREADY_LINKED : Error.PERMISSION_DENIED;
            publishGoogle(); return;
        }
        if (password == null || password.isEmpty()) { googleError = Error.REAUTH_REQUIRED; publishGoogle(); return; }
        cancelVerificationWatchdog(); quietChecking = false;
        int request = ++operationId;
        googleFlow.start(request, GoogleAuthFlow.Mode.LINK, identity.getUid()); googleError = Error.NONE;
        scheduleGoogleWatchdog(request, 30_000L); publishGoogle();
        authTransportBusy = true;
        identity.reauthenticate(EmailAuthProvider.getCredential(identity.getEmail(), password)).addOnCompleteListener(task -> {
            authTransportBusy = false;
            if (!validGoogle(request, GoogleAuthFlow.Stage.REAUTHENTICATING)) {
                discardLateSession(task.isSuccessful() ? identity : null); return;
            }
            if (!task.isSuccessful()) { googleFailure(request, mapGoogleError(task.getException())); return; }
            // Re-check signed role and the original server profile, not just a cached screen.
            identity.getIdToken(true).addOnCompleteListener(token -> {
                if (!validGoogle(request, GoogleAuthFlow.Stage.REAUTHENTICATING)) return;
                if (!token.isSuccessful()) { googleFailure(request, mapGoogleError(token.getException())); return; }
                firestore.collection("users").document(identity.getUid()).get(Source.SERVER).addOnCompleteListener(profile -> {
                    if (!validGoogle(request, GoogleAuthFlow.Stage.REAUTHENTICATING)) return;
                    if (!profile.isSuccessful()) { googleFailure(request, mapGoogleError(profile.getException())); return; }
                    SessionPolicy.Result result = SessionPolicy.evaluate(token.getResult().getClaims(), profile.getResult().getData(), identity.getEmail());
                    if (result.user == null || result.user.getRole() != UserProfile.Role.PATIENT) {
                        googleFailure(request, Error.PERMISSION_DENIED); invalidateVerifiedSession(); checkSession(); return;
                    }
                    if (!GoogleAccountPolicy.canLink(result.user, providers())) {
                        googleFailure(request, providers().contains("google.com") ? Error.GOOGLE_ALREADY_LINKED : Error.PERMISSION_DENIED); return;
                    }
                    googleFlow.advance(request, GoogleAuthFlow.Stage.REAUTHENTICATING, GoogleAuthFlow.Stage.SELECTING, currentUid());
                    scheduleGoogleWatchdog(request, 90_000L); publishGoogle();
                });
            });
        });
    }

    private boolean validGoogle(int request, GoogleAuthFlow.Stage stage) {
        return request == operationId && googleFlow.accepts(request, stage, currentUid());
    }
    public void googleSelectionFailed(int request, Error error) {
        if (validGoogle(request, GoogleAuthFlow.Stage.SELECTING)) googleFailure(request, error);
    }

    public void acceptGoogleCredential(int request, String idToken, String selectedEmail) {
        if (!validGoogle(request, GoogleAuthFlow.Stage.SELECTING)) return;
        if (!GoogleAccountPolicy.hasIdToken(idToken)) { googleFailure(request, Error.GOOGLE_INVALID_CREDENTIAL); return; }
        boolean link = googleFlow.mode() == GoogleAuthFlow.Mode.LINK;
        FirebaseUser original = auth.getCurrentUser();
        if ((!link && original != null) || (link && (original == null
                || !GoogleAccountPolicy.sameEmail(original.getEmail(), selectedEmail)))) {
            googleFailure(request, link ? Error.GOOGLE_EMAIL_MISMATCH : Error.SESSION_EXPIRED); return;
        }
        googleFlow.advance(request, GoogleAuthFlow.Stage.SELECTING, GoogleAuthFlow.Stage.EXCHANGING, currentUid());
        scheduleGoogleWatchdog(request, 30_000L); publishGoogle();
        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
        authTransportBusy = true;
        if (!link) changingAccount = true;
        com.google.android.gms.tasks.Task<com.google.firebase.auth.AuthResult> exchange = link
                ? original.linkWithCredential(credential) : auth.signInWithCredential(credential);
        exchange.addOnCompleteListener(task -> {
            authTransportBusy = false;
            if (!link) changingAccount = false;
            FirebaseUser returned = task.isSuccessful() ? task.getResult().getUser() : null;
            if (request != operationId || !googleFlow.busy()) {
                // No later auth mutation was allowed while this Task drained. Do not admit its session.
                discardLateSession(returned);
                return;
            }
            if (!task.isSuccessful()) { googleFailure(request, mapGoogleError(task.getException())); return; }
            if (returned == null || (link && !GoogleAccountPolicy.preservedUid(googleFlow.owner(), currentUid(), returned.getUid()))
                    || !returned.getUid().equals(currentUid())) {
                googleFailure(request, Error.SESSION_EXPIRED); signOut(); return;
            }
            checkingUid = returned.getUid();
            googleFlow.advance(request, GoogleAuthFlow.Stage.EXCHANGING, GoogleAuthFlow.Stage.VERIFYING, currentUid());
            scheduleGoogleWatchdog(request, 25_000L); publishGoogle();
            verifySession(returned, request, false);
        });
    }

    private void scheduleGoogleWatchdog(int request, long delay) {
        cancelGoogleWatchdog();
        googleWatchdog = () -> {
            if (request != operationId || !googleFlow.busy()) return;
            googleFailure(request, googleFlow.stage() == GoogleAuthFlow.Stage.SELECTING ? Error.GOOGLE_CANCELLED : Error.NETWORK);
        };
        mainHandler.postDelayed(googleWatchdog, delay);
    }
    private void cancelGoogleWatchdog() {
        if (googleWatchdog != null) mainHandler.removeCallbacks(googleWatchdog);
        googleWatchdog = null;
    }
    private void googleFailure(int request, Error error) {
        if (request != operationId || !googleFlow.busy()) return;
        boolean link = googleFlow.mode() == GoogleAuthFlow.Mode.LINK;
        cancelGoogleWatchdog(); cancelVerificationWatchdog();
        googleFlow.finish(false); googleError = error;
        if (!link) fail(request, error == Error.GOOGLE_CANCELLED ? Error.NONE : error);
        else operationId++; // Invalidate timed-out/duplicate/abandoned callbacks, including a late chooser.
        publishGoogle();
    }

    public void cancelPendingGoogleLink() {
        if (googleFlow.mode() == GoogleAuthFlow.Mode.LINK && (googleFlow.stage() == GoogleAuthFlow.Stage.REAUTHENTICATING
                || googleFlow.stage() == GoogleAuthFlow.Stage.SELECTING)) googleFailure(googleFlow.request(), Error.GOOGLE_CANCELLED);
    }

    /** Transactional create-or-complete at the authenticated UID; retries never overwrite a profile. */
    public void completeGoogleProfile(String fullName, String phone, String dob, String gender) {
        if (operationBlocked()) return;
        PendingProfile pending = pendingProfile();
        FirebaseUser identity = auth.getCurrentUser();
        String name = fullName == null ? "" : fullName.trim();
        String number = PatientProfileValidation.normalizePhone(phone);
        if (pending == null || identity == null) return;
        if (GoogleProfileCompletion.validate(name, number, dob, gender, System.currentTimeMillis()) != GoogleProfileCompletion.Invalid.NONE) {
            publish(new State(false, null, Error.INVALID_PROFILE, pending)); return;
        }
        int request = ++operationId;
        publish(new State(true, null, Error.NONE, pending));
        cancelVerificationWatchdog();
        verificationWatchdog = () -> {
            if (request != operationId) return;
            operationId++; publish(new State(false, null, Error.NETWORK, pending));
        };
        mainHandler.postDelayed(verificationWatchdog, VERIFY_TIMEOUT_MS);
        identity.getIdToken(true).addOnCompleteListener(token -> {
            if (request != operationId || !pending.uid.equals(currentUid())) return;
            if (!token.isSuccessful()) { profileCompletionFailed(request, pending, mapError(token.getException())); return; }
            Map<String, Object> claims = token.getResult().getClaims();
            if (claims.containsKey("admin") || !identity.isEmailVerified() || !Boolean.TRUE.equals(claims.get("email_verified"))
                    || !pending.email.equals(claims.get("email")) || !providers().contains("google.com")
                    || !pending.email.equals(identity.getEmail())) { profileCompletionFailed(request, pending, Error.PERMISSION_DENIED); return; }
            com.google.firebase.firestore.DocumentReference ref = firestore.collection("users").document(identity.getUid());
            firestore.runTransaction(transaction -> {
                if (request != operationId || !pending.uid.equals(currentUid())) throw new IllegalStateException("Account changed");
                DocumentSnapshot existing = transaction.get(ref);
                GoogleProfileCompletion.Plan plan = GoogleProfileCompletion.plan(claims, existing.getData(), identity.getEmail(), System.currentTimeMillis());
                if (plan == GoogleProfileCompletion.Plan.DENY) {
                    throw new IllegalStateException("Unverifiable profile");
                }
                if (plan == GoogleProfileCompletion.Plan.ALREADY_COMPLETE) return null;
                Map<String, Object> fields = GoogleProfileCompletion.details(name, number, dob, gender, System.currentTimeMillis());
                if (plan == GoogleProfileCompletion.Plan.UPDATE_DETAILS) transaction.update(ref, fields);
                else {
                    fields.put("email", identity.getEmail()); fields.put("role", "Patient"); fields.put("createdAt", FieldValue.serverTimestamp());
                    transaction.set(ref, fields);
                }
                return null;
            }).addOnCompleteListener(saved -> {
                if (request != operationId || !pending.uid.equals(currentUid())) return;
                if (!saved.isSuccessful()) { profileCompletionFailed(request, pending, mapError(saved.getException())); return; }
                verifySession(identity, request, true);
            });
        });
    }

    private void profileCompletionFailed(int request, PendingProfile pending, Error error) {
        if (request != operationId || !pending.uid.equals(currentUid())) return;
        cancelVerificationWatchdog(); publish(new State(false, null, error, pending));
    }

    /**
     * A Patient may correct only their own display details. Role, email, createdAt and Admin
     * claims are never part of this update, and the write is validated again by firestore.rules.
     */
    public void updatePatientProfile(String fullName, String phone, String dateOfBirth,
                                     String gender, ProfileUpdateListener listener) {
        FirebaseUser user = auth.getCurrentUser();
        UserProfile verified = verifiedUser();
        if (state.loading || googleFlow.busy() || authTransportBusy || user == null || verified == null
                || verified.getRole() != UserProfile.Role.PATIENT) {
            listener.onProfileUpdate(false, Error.PERMISSION_DENIED);
            return;
        }
        if (!PatientGender.isWireValue(gender)) {
            listener.onProfileUpdate(false, Error.INVALID_PROFILE);
            return;
        }
        Map<String, Object> update = new HashMap<>();
        update.put("fullName", fullName);
        update.put("phone", phone);
        update.put("dateOfBirth", dateOfBirth);
        update.put("gender", gender);
        final int request = operationId;
        firestore.collection("users").document(user.getUid()).update(update)
                .addOnCompleteListener(task -> {
                    if (request != operationId || !user.getUid().equals(currentUid())) { listener.onProfileUpdate(false, Error.SESSION_EXPIRED); return; }
                    if (!task.isSuccessful()) {
                        listener.onProfileUpdate(false, mapError(task.getException()));
                        return;
                    }
                    // Refresh the verified profile so every screen shows the new name immediately.
                    verifySession(user, ++operationId, false);
                    listener.onProfileUpdate(true, Error.NONE);
                });
    }

    public void checkSession() {
        if (state.loading || googleFlow.busy() || authTransportBusy) return;
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            boolean hadSession = state.user != null || quietChecking;
            quietChecking = false;
            invalidateVerifiedSession();
            if (hadSession || state.error != Error.NONE) publish(new State(false, null, Error.NONE));
            return;
        }
        if ((state.user != null || state.pendingProfile != null) && VerifiedSessionLease.reusable(verifiedUid, user.getUid(), verifiedAt,
                android.os.SystemClock.elapsedRealtime(), tokenExpiresAt, System.currentTimeMillis())) return;
        if (state.user != null && profileUid != null && profileUid.equals(user.getUid())) {
            // Warm resume: revalidate claims and profile online, but keep the current screen and its
            // already-loaded data visible instead of publishing a loading state.
            if (quietChecking) return;
            quietChecking = true;
            verifySession(user, ++operationId, false);
            return;
        }
        checkingExisting = true;
        quietChecking = false;
        verifySession(user, beginOperation(), false);
    }

    public void signOut() {
        // Late session-check callbacks must not reopen either logged-out protected screen.
        cancelVerificationWatchdog();
        cancelGoogleWatchdog(); googleFlow.clear(); googleError = Error.NONE;
        operationId++;
        changingAccount = false;
        checkingExisting = false;
        quietChecking = false;
        com.example.medibook.cache.PatientDataCache.get().clear();
        PatientDashboardRepository.clearCachedSummary();
        profileUid = null; checkingUid = null;
        invalidateVerifiedSession();
        com.example.medibook.local.LocalPatientServices.signOutBestEffort();
        auth.signOut();
        com.example.medibook.auth.GoogleCredentials.clearState(FirebaseApp.getInstance().getApplicationContext());
        observedUid = currentUid();
        publish(new State(false, null, Error.NONE));
        publishGoogle();
    }

    private int beginOperation() {
        cancelVerificationWatchdog();
        int operation = ++operationId;
        checkingUid = null;
        publish(new State(true, null, Error.NONE));
        return operation;
    }

    private void verifySession(FirebaseUser user, int operation, boolean patientRegistration) {
        if (user == null) {
            fail(operation, Error.INVALID_CREDENTIALS);
            return;
        }
        checkingUid = user.getUid();
        Diagnostics.event("Auth", "verify quiet=" + quietChecking + " registration=" + patientRegistration);
        // The SDK can retry an offline request for minutes. A bounded verification keeps every
        // screen out of a permanent "Signing you in..." state.
        scheduleVerificationWatchdog(operation);
        // Cold/expired leases still reload the account, refresh signed claims and fetch the profile.
        user.reload().addOnCompleteListener(reloadTask -> {
            if (operation != operationId || !user.getUid().equals(currentUid())) return;
            if (!reloadTask.isSuccessful()) {
                fail(operation, mapError(reloadTask.getException()));
                return;
            }
            user.getIdToken(true).addOnCompleteListener(tokenTask -> {
                if (operation != operationId || !user.getUid().equals(currentUid())) return;
                if (!tokenTask.isSuccessful()) {
                    fail(operation, mapError(tokenTask.getException()));
                    return;
                }
                Map<String, Object> claims = tokenTask.getResult().getClaims();
                firestore.collection("users").document(user.getUid()).get(Source.SERVER)
                        .addOnCompleteListener(profileTask -> {
                            if (operation != operationId || !user.getUid().equals(currentUid())) return;
                            if (!profileTask.isSuccessful()) {
                                fail(operation, mapError(profileTask.getException()));
                                return;
                            }
                            acceptProfile(profileTask.getResult(), claims, user.getEmail(),
                                    operation, patientRegistration, tokenTask.getResult().getExpirationTimestamp() * 1000L);
                        });
            });
        });
    }

    private void acceptProfile(DocumentSnapshot document, Map<String, Object> claims,
                               String authenticatedEmail, int operation, boolean patientRegistration, long expiresAt) {
        if (operation != operationId || !document.getId().equals(currentUid())) return;
        SessionPolicy.Result result = patientRegistration
                ? SessionPolicy.evaluatePatientRegistration(claims, document.getData(), authenticatedEmail)
                : SessionPolicy.evaluate(claims, document.getData(), authenticatedEmail);
        FirebaseUser identity = auth.getCurrentUser();
        boolean google = providers().contains("google.com");
        if (googleFlow.busy() && googleFlow.mode() == GoogleAuthFlow.Mode.LINK
                && !GoogleAccountPolicy.linkConfirmed(googleFlow.owner(), currentUid(), document.getId(), providers())) {
            fail(operation, Error.GOOGLE_CONFLICT); return;
        }
        GoogleAccountPolicy.Destination destination = GoogleAccountPolicy.destination(google,
                identity != null && authenticatedEmail != null && !authenticatedEmail.trim().isEmpty()
                        && authenticatedEmail.equals(claims.get("email")) && identity.isEmailVerified()
                        && Boolean.TRUE.equals(claims.get("email_verified")),
                claims, document.exists(), result, System.currentTimeMillis());
        if (destination == GoogleAccountPolicy.Destination.COMPLETE_PROFILE && identity != null) {
            cancelVerificationWatchdog(); cancelGoogleWatchdog();
            profileUid = document.getId(); verifiedUid = profileUid;
            verifiedAt = android.os.SystemClock.elapsedRealtime(); tokenExpiresAt = expiresAt;
            quietChecking = false;
            com.example.medibook.cache.PatientDataCache.get().clear();
            PatientDashboardRepository.clearCachedSummary();
            com.example.medibook.local.LocalPatientServices.signOutBestEffort();
            if (googleFlow.busy()) { googleFlow.finish(true); googleError = Error.NONE; publishGoogle(); }
            publish(new State(false, null, Error.NONE, new PendingProfile(identity, result.user)));
            return;
        }
        if (!document.exists()) { fail(operation, Error.PROFILE_MISSING); return; }
        if (result.failure == SessionPolicy.Failure.ROLE_MISMATCH) {
            fail(operation, Error.ROLE_MISMATCH);
            return;
        }
        if (result.failure != SessionPolicy.Failure.NONE || destination == GoogleAccountPolicy.Destination.DENIED) {
            fail(operation, Error.INVALID_PROFILE);
            return;
        }
        cancelVerificationWatchdog();
        cancelGoogleWatchdog();
        profileUid = document.getId(); verifiedUid = profileUid;
        verifiedAt = android.os.SystemClock.elapsedRealtime(); tokenExpiresAt = expiresAt;
        quietChecking = false;
        checkingExisting = false;
        Diagnostics.event("Auth", "verified role=" + result.user.getRole().name()
                + " adminClaim=" + Boolean.TRUE.equals(claims.get("admin")));
        com.example.medibook.cache.PatientDataCache.get().bindOwner(profileUid);
        com.example.medibook.local.LocalPatientServices.verifiedBestEffort(profileUid, result.user.getRole());
        if (googleFlow.busy()) {
            googleFlow.finish(true); googleError = Error.NONE; publishGoogle();
        }
        publish(new State(false, result.user, Error.NONE));
    }

    private void rollbackNewAccount(FirebaseUser user, int operation) {
        // Auth and Firestore cannot share a transaction. Only delete the NEW account whose
        // profile write was rejected. Never delete an existing account on a login/read failure.
        changingAccount = true; // This intentional account removal must not invalidate its callback.
        user.delete().addOnCompleteListener(deleteTask -> {
            if (operation != operationId) return;
            changingAccount = false;
            fail(operation, deleteTask.isSuccessful()
                    ? Error.PROFILE_FAILED_ACCOUNT_REMOVED : Error.PROFILE_FAILED_ACCOUNT_REMAINS);
        });
    }

    private void fail(int operation, Error error) {
        if (operation != operationId) return;
        cancelVerificationWatchdog();
        if (googleFlow.busy()) {
            cancelGoogleWatchdog(); googleFlow.finish(false); googleError = error; publishGoogle();
        }
        PendingProfile pending = pendingProfile();
        if (pending != null && (error == Error.NETWORK || error == Error.UNKNOWN || error == Error.TOO_MANY_REQUESTS)) {
            operationId++; publish(new State(false, null, error, pending)); return;
        }
        if (checkingExisting && error == Error.INVALID_CREDENTIALS) error = Error.SESSION_EXPIRED;
        boolean quiet = quietChecking;
        quietChecking = false;
        Diagnostics.event("Auth", "verification failed " + error.name() + " quiet=" + quiet);
        UserProfile verified = state.user;
        if (revalidationKeepsSession(quiet, verified != null,
                profileUid != null && profileUid.equals(currentUid()), error)) {
            // A background revalidation of an already-verified account never discards that account.
            // The server still authorizes every read and write, so a stale screen cannot gain access
            // to anything; it simply stops a transient rules/network blip from signing the user out
            // in the middle of an operation.
            operationId++; publish(new State(false, verified, Error.NONE));
            return;
        }
        invalidateVerifiedSession();
        com.example.medibook.cache.PatientDataCache.get().clear();
        PatientDashboardRepository.clearCachedSummary();
        profileUid = null; checkingUid = null;
        com.example.medibook.local.LocalPatientServices.signOutBestEffort();
        auth.signOut();
        observedUid = currentUid(); changingAccount = false;
        checkingExisting = false;
        operationId++;
        publish(new State(false, null, error));
    }

    /**
     * Decides whether an already-verified screen survives a failed revalidation.
     *
     * <p>Only a quiet revalidation of the account that is still signed in may keep the session, and
     * only for failures that do not prove the account itself is unusable. The server still
     * authorizes every read and write, so a retained screen cannot gain access to anything it would
     * not otherwise have; it simply stops a transient network/rules blip from signing a valid Admin
     * out in the middle of an operation.</p>
     */
    public static boolean revalidationKeepsSession(boolean quiet, boolean verifiedUserPresent,
                                                   boolean sameSignedInUid, Error error) {
        if (!quiet || !verifiedUserPresent || !sameSignedInUid || error == null) return false;
        switch (error) {
            case INVALID_CREDENTIALS:
            case ACCOUNT_DISABLED:
            case AUTH_CONFIGURATION:
            case AUTH_REJECTED:
            case SESSION_EXPIRED:
            case PROFILE_MISSING:
            case INVALID_PROFILE:
            case ROLE_MISMATCH:
                return false;
            // A denied profile read is a rules/propagation problem, not a revoked account: the
            // server still denies every operation the rules deny, so ending a freshly verified
            // Admin session (and tearing the task down) would only lose the Admin's work.
            default:
                return true;
        }
    }

    private void scheduleVerificationWatchdog(int operation) {
        cancelVerificationWatchdog();
        verificationWatchdog = () -> {
            if (operation != operationId) return;
            Diagnostics.event("Auth", "verification timed out");
            boolean quiet = quietChecking;
            quietChecking = false;
            UserProfile verified = state.user;
            if (quiet && verified != null && profileUid != null && profileUid.equals(currentUid())) {
                operationId++; publish(new State(false, verified, Error.NONE));
                return;
            }
            fail(operation, Error.NETWORK);
        };
        mainHandler.postDelayed(verificationWatchdog, VERIFY_TIMEOUT_MS);
    }

    private void cancelVerificationWatchdog() {
        if (verificationWatchdog != null) mainHandler.removeCallbacks(verificationWatchdog);
        verificationWatchdog = null;
    }

    private void publish(State newState) {
        state = newState;
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onAuthStateChanged(newState);
        }
    }

    private Error mapError(Exception exception) {
        // Auth/provider exception messages may contain an email or credential detail. Never log them.
        if (exception instanceof FirebaseNetworkException) return Error.NETWORK;
        if (exception instanceof FirebaseTooManyRequestsException) return Error.TOO_MANY_REQUESTS;
        if (exception instanceof FirebaseAuthUserCollisionException) return Error.EMAIL_IN_USE;
        if (exception instanceof FirebaseAuthWeakPasswordException) return Error.WEAK_PASSWORD;
        if (exception instanceof FirebaseFirestoreException) {
            FirebaseFirestoreException.Code code = ((FirebaseFirestoreException) exception).getCode();
            if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED) return Error.PERMISSION_DENIED;
            if (code == FirebaseFirestoreException.Code.UNAVAILABLE
                    || code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED) return Error.NETWORK;
        }
        if (exception instanceof FirebaseAuthException) {
            String code = ((FirebaseAuthException) exception).getErrorCode();
            // Display distinctions only; all rejected credentials retain the same session denial.
            return com.example.medibook.utils.AuthFailurePresentation.forCode(code);
        }
        return Error.UNKNOWN;
    }

    private Error mapGoogleError(Exception exception) {
        if (exception instanceof FirebaseAuthUserCollisionException) return Error.GOOGLE_CONFLICT;
        if (exception instanceof FirebaseAuthException) {
            String code = ((FirebaseAuthException) exception).getErrorCode();
            if (GoogleAccountPolicy.conflictCode(code)) return Error.GOOGLE_CONFLICT;
            if ("ERROR_PROVIDER_ALREADY_LINKED".equals(code)) return Error.GOOGLE_ALREADY_LINKED;
            if ("ERROR_REQUIRES_RECENT_LOGIN".equals(code)) return Error.REAUTH_REQUIRED;
            if ("ERROR_OPERATION_NOT_ALLOWED".equals(code) || "ERROR_INVALID_OAUTH_CLIENT_ID".equals(code)) return Error.GOOGLE_CONFIGURATION;
            if (googleFlow.stage() != GoogleAuthFlow.Stage.REAUTHENTICATING && ("ERROR_INVALID_CREDENTIAL".equals(code)
                    || "ERROR_INVALID_IDP_RESPONSE".equals(code) || "ERROR_INVALID_OAUTH_RESPONSE".equals(code)
                    || "ERROR_INVALID_ID_TOKEN".equals(code))) return Error.GOOGLE_INVALID_CREDENTIAL;
        }
        return mapError(exception);
    }
}
