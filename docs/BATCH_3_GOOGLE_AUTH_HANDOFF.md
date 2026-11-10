# Batch 3 — Google Authentication and secure account integration

## Status and safeguards

**Overall Batch 3 status: partially complete pending external setup/device sign-off.** Google sign-in,
gated profile completion and explicit password-to-Google linking are implemented locally.
**Console configuration and real Google/device validation remain outstanding.** No Google
OAuth operation, production account mutation, rule deployment or production-data test was performed.
The Database/Dashboard Revision was physically validated and its Rules/index published **as reported
by the user**; its 694 automated tests are historical, not this batch's verification.

Before editing, the dirty working tree was inspected and all existing changes/untracked files were
preserved. A verified, non-overwriting backup of 361 project input/configuration files was created:
`C:\Users\user\AppData\Local\Temp\opencode\MediBook-before-batch3-20261109-112447-758e43c0.zip`.
Generated build/cache directories and Git metadata were excluded. The archive includes private local
configuration: keep it local; do not upload or commit it. No Git commit, push, reset, stash or clean
was run. No paid service or second authentication system was added.

## Dependencies and entry points

The existing Firebase BoM **35.1.0**, Google Services plugin **4.5.0**, Java 11 and minSdk 24 are
retained. The only new libraries are stable AndroidX Credentials **1.6.0**, its Play Services Auth
adapter **1.6.0**, and Google ID **1.2.1**. Versions were checked against official Maven metadata.
No deprecated GoogleSignInClient/One Tap integration or additional Google authorization scopes.

- Login: **Continue with Google**, below the unchanged email/password form. Registration, password
  visibility and Admin password login remain available. Existing-account guidance is shown explicitly.
- Edit Profile: **Connect Google Account**. Already-connected accounts are labelled and cannot submit
  another link. No unlink/account deletion UI is introduced.
- Complete Profile: a non-exported, role-gated screen using existing styles, validators, date picker,
  Male/Female values and Dhaka age calculations.

## Google sign-in flow

1. `AuthRepository` reserves one in-memory operation. Concurrent sign-in/link requests are ignored.
2. `GoogleCredentials` uses Credential Manager's explicit-button `GetSignInWithGoogleOption`, an
   Activity-backed `MutableContextWrapper`, cancellation and main-thread callbacks. This allows an
   account chooser, account addition and reauthentication without silent automatic selection.
3. The optional generated **Web** OAuth client resource is checked. Missing configuration produces a
   clear error; no invented ID or client secret is embedded. A shrinker keep rule preserves this
   generated resource when OAuth configuration is subsequently supplied.
4. Only a parsed Google ID-token custom credential is accepted. The token is passed directly to
   Firebase `signInWithCredential`; Firebase verifies it. Local parsing/nonempty-token checks do NOT
   establish signature, email ownership, claims or a MediBook role.
5. The SDK user is reloaded, its signed token refreshed and `users/{currentFirebaseUid}` read from
   `Source.SERVER`. Existing `SessionPolicy` validates email, exact profile schema and trusted claims.
6. A valid Admin claim/profile pair enters Admin. A complete existing Patient profile enters Patient
   Home unchanged. A verified Google Patient without a profile, or with valid legacy core fields but
   missing current personal details, enters Complete Profile with **no Patient session granted**.
   Corrupt/foreign-email/unknown-role profiles are denied, not overwritten or automatically repaired.

Firebase persists its normal session. On a cold start the same online verification and onboarding
decision are recomputed; no Intent, cached role, Google display name or matching email grants access.
Legacy password-only accounts retain their original optional-personal-details compatibility. Once
Google is attached, missing DOB/current gender must be completed before normal Patient access.

### Provider conflicts and server-side automatic resolution

Firebase can return different collision codes or perform trusted-provider resolution itself. The
app does not enumerate other users by email, disable email-enumeration protection, query profiles by
email, assume one specific collision code, or implement its own merge. All collision variants direct
the user to the original sign-in method and explicit linking in Edit Profile. A sign-in never writes
a Patient profile automatically: onboarding requires explicit validated submission.

**Existing password users should link from their authenticated account first.** Firebase's own
same-email/trusted-provider behavior depends on provider verification and project settings; the app
cannot override that behavior before Firebase authenticates a credential. It does not promise that
every unlinked same-email attempt will return a collision. Test this behavior using isolated test
accounts before release; do not experiment with existing production patient/Admin accounts. If
Firebase resolves an existing UID, the app uses only that UID's verified profile and never copies or
reassigns any records. A different UID, even with the same email, has no access to those records.

## Complete Profile flow

- Full name is prefilled from Google when present and remains editable. Missing names require entry.
- Email is read-only and comes only from the authenticated identity, matched to refreshed signed
  claims. UID always comes from Firebase; neither is accepted from form fields/Intent extras.
- Required name, normalized phone, ISO DOB (1900 through today in Dhaka) and Male/Female are checked
  with existing validators. Age is derived, correctly pluralized and **never stored**.
- Draft fields survive rotation only for the same owner. A restored Material date picker reconnects
  its callback. No credentials/passwords are saved in instance state.
- One Firestore transaction reads the current UID's document: absent → create the existing seven-field
  Patient schema; valid incomplete core → update only four editable fields; already complete (including
  lost-acknowledgement retry/concurrent completion) → no-op. Invalid metadata/role/email → deny.
- Email/role/createdAt are never overwritten on completion. A server-fetched result is verified again
  before Patient navigation. Failed/timed-out writes expose Retry without a second document or account
  deletion. A timed-out write may already have committed; retry reconciles instead of overwriting it.
- Launcher, Login, Registration and protected-screen routing recognize the separate onboarding state.
  Back/sign-out leaves onboarding without bypassing the gate. No normal dashboards/reminders are
  activated for an incomplete profile.

## Explicit account-linking flow

1. The current account must be a verified Patient with a password provider and no Google provider.
2. The user enters their current password in a non-persisted dialog. `reauthenticate` proves the
   existing account; refreshed signed claims and the server profile recheck Patient authorization.
3. Credential Manager obtains a fresh Google credential. The chosen Google email must exactly match
   the existing Firebase email, avoiding changes to the immutable profile email.
4. Call **only `originalFirebaseUser.linkWithCredential`**, never signInWithCredential as a linking
   fallback. The originating UID, current UID and returned UID must all match.
5. Reload/reverify the session and confirm the Google/password providers. Success is displayed only
   after Firebase confirms linking and the session is verified (or required onboarding is opened).

An already-used Google credential, provider conflict, wrong account, cancelled chooser or required
recent login fails safely. The original UID's appointments, contacts, summary and reminders are never
copied, renamed, reassigned or deleted. There is no automatic account merge or unsafe linking workaround.

## Session/lifecycle/data isolation

`GoogleAuthFlow` and the repository fence callbacks by request/stage/owner. No tokens or credentials
are retained in observable state, preferences, Firestore, RTDB, instance state or logs. Auth exception
messages are not logged because they can contain account/credential details.

The chooser has a 90-second deadline; password proof and Firebase exchange have 30-second deadlines;
session verification and profile submission have 20-second deadlines. Leaving/rotating the chooser
cancels that UI stage; the user can retry. Already-started Firebase work survives Activity recreation,
with navigation delivered only to started observers. No Activity is retained in the repository.

Firebase auth-mutating Tasks cannot be cancelled. On timeout the UI exits its loading state, but a
new auth mutation is blocked until the old Task drains; the user sees a clear message rather than
overlapping accounts. Late abandoned sign-in results are discarded/signed out. An unacknowledged link
can have succeeded server-side; a subsequent verified profile refresh shows the real provider state,
not an invented success. Process restart re-verifies whichever SDK session actually persisted.

Logout invalidates request generations, stops the existing screen subscriptions through their role
gates, clears patient caches (including the Next Appointment in-memory pointer), and invokes existing
local reminder/contact epoch invalidation. Credential Manager state is cleared best-effort; this is
**not** Google consent revocation. Loading screens retain content only for the same owner, never a
different account or onboarding session. The chart, dashboard queries/mirrors and reminder mechanics
are otherwise unchanged.

## Rules and data architecture

**No Firestore or RTDB Rules changes are needed in Batch 3.** The already-published complete files
remain unchanged from the pre-Batch-3 backup. New tests use synthetic `firebase.sign_in_provider`
metadata to verify that Google/password providers share UID ownership but confer no Admin powers.
Existing seven-field Patient creation and four-field updates suffice. No token/age/provider fields
or parallel profile collection are introduced. Existing reservation, serial, contact, RTDB ownership
and retired-feature denies remain in place. **Do not republish Rules for this batch.**

## Required manual Firebase / Google Console configuration

Inspection confirmed the existing local JSON selects `medibook-2c3a4` / `com.example.medibook`, but
contains **zero Web and zero Android OAuth client entries**. Provider enablement, registered signing
fingerprints and consent-screen status cannot be established from that file. No external setting was
changed and no client ID/fingerprint was invented or published here.

1. Firebase Console → `medibook-2c3a4` → Authentication → Sign-in method/providers → **Google**:
   verify/enable it, select the appropriate support email and save. Keep Email/Password enabled.
2. Project Settings → General → existing Android app **com.example.medibook** → add the signing
   certificate's **SHA-1 and SHA-256**. Obtain the actual local debug values with:

   ```powershell
   .\gradlew.bat :app:signingReport --console=plain
   ```

   Read the **debug** variant for this workstation. For a release, obtain the actual release certificate
   fingerprints locally; for Play distribution include the Play **App signing certificate** fingerprints
   from Play Console → App integrity, not just the upload certificate. Never share keystores/passwords.
3. Authentication → Settings → User account linking: verify the intended **One account per email**
   configuration. Do not enable duplicate same-email accounts for this integration. If existing project
   settings deliberately allow them, stop for an account-policy review; do not delete/merge users or
   blindly change a production policy to resolve conflicts. Keep enumeration protection enabled.
4. Download the updated `google-services.json` for the existing Android app and replace only
   `app/google-services.json` locally after preserving its old copy. Sync/rebuild. Google Services must
   generate a nonempty `default_web_client_id` from an OAuth client of **Web application / type 3** in
   the same Google Cloud/Firebase project. Do not use an Android OAuth client ID in setServerClientId.
5. Google Cloud Console → same project → Google Auth Platform / OAuth credentials: verify the Android
   client package and SHA-1 match the installed build and that the Web client matches the generated
   resource. Complete required branding/audience/consent settings; add dedicated test users if the
   app is in Testing. Do not request unrelated scopes or put any OAuth client secret/service-account
   credential in the app. Follow any applicable Google branding/verification requirements before release.
6. Rebuild/install on a Google Play-enabled device. Missing/wrong OAuth setup must yield a clear error;
   it is not a reason to weaken Firestore/RTDB rules, API-key restrictions or authorization checks.

The existing appointments index and Database/Dashboard Rules need no new publication/migration.
No paid Firebase upgrade, Cloud Function, production appointment mutation or data cleanup is required.

## Verification

Final fresh verification on **2026-11-09: 747 tests passed, 0 failures/errors, 0 skips**.

| Check | Actual result |
| --- | --- |
| `:app:testDebugUnitTest` | 551 tests / 53 classes, passed |
| `:firestore-rules-tests:test` | 160 tests / 10 classes, passed |
| `:rtdb-rules-tests:test` | 36 tests / 2 classes, passed |
| `:app:assembleDebug` | Successful; `app/build/outputs/apk/debug/app-debug.apk` |
| `:app:lintDebug` | Successful; 0 errors, 15 warnings; no unused-resource warnings |
| `git diff --check` | Passed |
| Backup comparison | 22 modified / 16 added Batch-3 files; zero pre-existing files deleted |
| Protected-file comparison | Rules, index, Firebase JSON and chart implementation byte-identical to pre-Batch-3 backup |
| APK/source cleanup | New Google/onboarding classes and reminder worker/helpers/receivers packaged; retired feature classes/references absent |

The 15 warnings are the existing two older-API accessibility attributes, seven dependency-update
notices, one nested-weight warning, three overdraw warnings and two XML/bitmap launcher-icon warnings.
Lint report: `app/build/reports/lint-results-debug.html`; XML test reports: each module's
`build/test-results` directory. Compared with the historical 694-test revision, this batch adds 42 app
policy tests, 8 Firestore tests and 3 RTDB tests without deleting or weakening existing assertions.

Successful full verification command (bounded to 10 minutes; actual final full pass 1m 36s):

```powershell
.\gradlew.bat :app:testDebugUnitTest :firestore-rules-tests:test :rtdb-rules-tests:test :app:assembleDebug :app:lintDebug --continue --no-parallel --max-workers=2 --console=plain
```

After the final password-reauthentication drain fence was added, the app unit/build/lint tasks were
rerun successfully on the final sources (37s). The Rules, emulator tests and their passing results
were unchanged:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-parallel --max-workers=2 --console=plain
```

Unit tests exercise production selection/routing/validation/write-plan/fencing policies;
Firestore/RTDB integration tests evaluate real Rules with synthetic Firebase ID tokens on owned
loopback `demo-medibook` emulators. These are **not** a real Google account chooser, token exchange or
SDK credential-linking end-to-end test. No physical-device success is claimed for Batch 3.

The first full run found and corrected an API-24 autofill guard, a lint indentation ambiguity and an
incorrect new-test expectation (an authorized missing document returns REST 404, not 200). Concurrent
cold emulator startup/first requests also hit existing HTTP deadlines during the heavy initial build;
the verification rerun limits Gradle worker concurrency rather than weakening tests or timeouts.
An earlier dependency-resolution/compile attempt exceeded its 3-minute command deadline; the daemon
was confirmed idle, not left running a stuck task. Subsequent bounded builds completed successfully.

## Required device/staging sign-off

`adb devices` currently lists no connected device. Use an isolated Firebase test project/accounts for
mutation tests; never change existing live patient/Admin accounts, documents or appointments to test.

- New Google user → missing profile → complete every required field → Patient Home; missing display
  name; invalid/blank name/phone/DOB/gender; automatic age; save failure, retry and lost acknowledgement.
- Kill/restart while incomplete; direct entry to protected screens; confirm no bypass. Rotate with
  partially entered data and with the date picker open; verify owner-scoped restoration.
- Existing complete Google Patient → same UID/profile; no recreation. Existing password Patient →
  password proof → same-email Google link → same UID and both methods usable; verify existing contacts,
  appointments, private summary and reminder settings remain associated with that UID.
- Wrong password; different Google email; already-linked provider; Google credential attached to a
  different test UID; all server collision variants. No merge, account deletion or record reassignment.
- Trusted Admin versus profile-role/email spoof; incomplete/missing Admin profile fails safely.
- Chooser cancellation, no device Google account, expired credential, offline/network interruption,
  rapid double taps, background/resume, rotation and logout during each asynchronous stage.
- Logout followed by two different Patients, Patient/Admin switching and launcher restart: no old
  profile/summary/contact/appointment visibility, stale navigation or reminder delivery.
- Regression: password registration/login; profile editing; doctor CRUD/schedules/search; transactional
  booking/cancellation, slot serial reuse, automatic completion, both dashboards/chart/fallback,
  contact sync/picker, Environment & Health/calling, 11 wellness cards; 24h/6h/custom AM-PM reminders,
  notification-only and alarm/vibration modes, 30-second stop and early Dismiss.

## Batch 3 file manifest

New:
- `app/src/main/java/com/example/medibook/auth/GoogleCredentials.java`
- `app/src/main/java/com/example/medibook/activities/auth/CompleteProfileActivity.java`
- `app/src/main/java/com/example/medibook/utils/GoogleAccountPolicy.java`
- `app/src/main/java/com/example/medibook/utils/GoogleAuthFlow.java`
- `app/src/main/java/com/example/medibook/utils/GoogleProfileCompletion.java`
- `app/src/main/res/layout/activity_complete_profile.xml`
- `app/src/main/res/values/google_auth_strings.xml`, `google_auth_styles.xml`
- `app/src/main/res/drawable/ic_google.xml`, `app/src/main/res/raw/google_auth_keep.xml`
- `app/src/test/java/com/example/medibook/utils/GoogleAccountPolicyTest.java`
- `app/src/test/java/com/example/medibook/utils/GoogleAuthFlowTest.java`
- `app/src/test/java/com/example/medibook/utils/GoogleProfileCompletionTest.java`
- `app/src/test/java/com/example/medibook/utils/AccountTransitionVisibilityTest.java`
- `tests/firestore/src/test/java/com/example/medibook/rules/GoogleIdentityRulesTest.java`
- `docs/BATCH_3_GOOGLE_AUTH_HANDOFF.md`

Modified (relative to the pre-Batch-3 backup, including previously untracked files):
- `app/build.gradle.kts`, `gradle/libs.versions.toml`, `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/example/medibook/repositories/AuthRepository.java`
- `app/src/main/java/com/example/medibook/repositories/PatientDashboardRepository.java` (cache clear only)
- `app/src/main/java/com/example/medibook/activities/auth/LoginActivity.java`, `RegisterActivity.java`
- `app/src/main/java/com/example/medibook/activities/patient/EditProfileActivity.java`
- `app/src/main/java/com/example/medibook/activities/patient/PatientHomeActivity.java`, `DoctorProfileActivity.java` (owner-safe loading only)
- `app/src/main/java/com/example/medibook/activities/AppointmentSessionActivity.java`
- `app/src/main/java/com/example/medibook/utils/AuthNavigation.java`, `AuthErrorMessages.java`, `PatientScreenAccess.java`, `BirthDatePicker.java`
- `app/src/main/res/layout/activity_login.xml`, `activity_edit_profile.xml`
- `tests/firestore/src/test/java/com/example/medibook/rules/RulesClient.java`, `RulesIntegrationSuite.java`
- `tests/rtdb/src/test/java/com/example/medibook/rtdb/RtdbClient.java`, `DashboardRulesTest.java`
- `docs/BATCH_2_HANDOFF.md`

All unrelated pre-existing modifications/untracked files remain present. Hospital Announcements and
the Firestore in-app Notification Center remain removed.

## Official references consulted

- https://firebase.google.com/docs/auth/android/google-signin
- https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation
- https://firebase.google.com/docs/auth/android/account-linking
- https://firebase.google.com/docs/auth/users#verified_email_addresses
- Official Google Maven metadata for AndroidX Credentials and Google ID versions.
