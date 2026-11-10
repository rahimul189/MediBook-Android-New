# Java/JUnit Firestore Security Rules integration tests

## Current profile-security audit (2026-11-08)

**134 tests / 7 classes** pass: the existing 97 tests, 36 focused Patient-profile permission/
validation regressions and one deployment-configuration canary. The profile cases include the
Male/Female write restriction and read-only compatibility for legacy gender values. One old
Admin-profile-get denial assertion was updated for the explicitly intended, Patient-only direct
Admin read; list denial stays.
See [the audit findings, exact policy, verification and manual-only deployment command](../../docs/firestore-profile-security-audit.md).
`firebase.json`/`.firebaserc` configure manual production deployment; this Java test harness still
uses only `firebase.emulators.json`, **demo-medibook** and loopback, never the production project.
Historical counts and results below describe earlier development checkpoints.

Batch 2 adds two optional Doctor-image cases (**84 Firestore tests total**) and verifies the
Admin bridge's recent-`updatedAt` query with the existing ownership canary. See
`docs/batch2-live-display.md` and the separate Java-only `tests/rtdb/` suite for live displays.

## Run without JavaScript, Node or the Firebase CLI

Prerequisites: **Java 21+**, the repository Gradle wrapper, and the pinned official Firestore
emulator JAR **1.22.0**. Gradle resolves only JUnit 4.13.2 and Gson 2.14.0 for this separate Java
test module. It adds no Android application dependencies and needs no Android device/AVD.

From the Android repository root:

```powershell
.\gradlew.bat :firestore-rules-tests:test --console=plain
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

The integration task always runs afresh, starts **its own** Java emulator, runs the serial suite,
exports Rules evaluation coverage and shuts the process down. It refuses an occupied port instead
of attaching to or clearing an unknown emulator. The Firestore section of `firebase.emulators.json` must still
specify `127.0.0.1:8080`, single-project mode, disabled UI and the root `firestore.rules` file.
All data requests are hard-fixed to **`demo-medibook`**, database **`(default)`** on that loopback
endpoint. A different `FIRESTORE_EMULATOR_HOST` is rejected, never silently overridden.

### Provision the Java emulator once

An existing Firebase emulator cache is reused at
`$HOME/.cache/firebase/emulators/cloud-firestore-emulator-v1.22.0.jar`.
Alternatively set `MEDIBOOK_FIRESTORE_EMULATOR_JAR` to a copy outside the repository. Every run
checks its SHA-256 against the official pinned artifact:

```text
9b6498b7f62714d67f48f59b3818883cd682dbcd46b9f59511de81c97bb5166c
```

Official public download (no sign-in/private key/Firebase project access):

```text
https://storage.googleapis.com/firebase-preview-drop/emulator/cloud-firestore-emulator-v1.22.0.jar
```

For example, download to a new external file, verify it, then set its path:

```powershell
# Choose a NEW filename; do not overwrite an existing cache or backup.
$jar = Join-Path $env:TEMP 'medibook-firestore-emulator-v1.22.0.jar'
if (Test-Path -LiteralPath $jar) { throw 'Choose a new file, or verify/reuse the existing file separately.' }
Invoke-WebRequest -Uri 'https://storage.googleapis.com/firebase-preview-drop/emulator/cloud-firestore-emulator-v1.22.0.jar' -OutFile $jar
if ((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne '9B6498B7F62714D67F48F59B3818883CD682DBCD46B9F59511DE81C97BB5166C') { throw 'Checksum mismatch' }
$env:MEDIBOOK_FIRESTORE_EMULATOR_JAR = $jar
```

Missing JAR, wrong checksum, wrong environment/configuration, unavailable port, startup failure or
Rules compilation failure is a **failed** test run, not a skipped/passing integration test.

## Why these requests actually exercise Security Rules

The Firebase Admin/Google Cloud Java server libraries bypass Firestore Rules and are **not**
permission test clients here. Java's `HttpClient` calls the **documented Firestore REST v1 API**.
Firebase documents that Firebase ID-token and unauthenticated REST requests use Security Rules,
unlike service-account/OAuth requests. This is the supported client API path appropriate to a
plain-JVM test runner; it does not require embedding the Android SDK or a JavaScript test SDK.

- Patient/Admin requests carry synthetic, unsigned emulator Firebase ID tokens with explicit UID,
  project and claims. Admin's Boolean `admin=true` is a tested end-user claim, not an IAM/Admin SDK
  bypass. Anonymous requests have **no Authorization header**.
- No Auth emulator accounts, real Authentication accounts, real custom claims, credentials,
  `google-services.json`, ADC, service-account keys or production resources are read/modified.
- Privileged **emulator-only** clear/seed requests use a separate fixture helper with the emulator
  owner credential. They never count as allow/deny evidence and return no assertion response.
- Canary cases fail if anonymous requests bypass Rules, Patient identity is ignored, malformed
  claims grant access or Admin end-user tokens bypass unrelated-collection/delete restrictions.
- Denials require exactly **403 + `PERMISSION_DENIED`**. Transport errors, invalid arguments,
  authentication errors, missing documents/indexes and arbitrary exceptions cannot pass a denial.
- Data transport has no credentials provider, proxy or redirect fallback. It rejects non-loopback,
  different-project/database/port and unexpected targets before sending requests.
- Server timestamps use REST `REQUEST_TIME` field transforms; deletions use update masks. Explicit
  REST field types permit Rules to inspect invalid/null/noninteger/NaN/infinite inputs directly.

References:

- https://firebase.google.com/docs/firestore/use-rest-api#authentication_and_authorization
- https://firebase.google.com/docs/emulator-suite/connect_firestore#cloud_firestore_rest_api
- https://firebase.google.com/docs/firestore/reference/rest/v1/Write

## Coverage and results

See [COVERAGE.md](COVERAGE.md) for the one-for-one map of the preserved baseline cases and the
Step 89/Batch 1 appointment/inventory matrix. The current suite is **82 Java integration tests**:

- **4** `ClientRulesCanaryTest` cases (anonymous, identity, claim and transport canaries).
- **16** `DoctorRulesTest` cases (preserved Doctor/Patient profile and directory policy, plus the
  Step 78 live-query case).
- **22** `ScheduleRulesTest` cases (preserved weekly schedule policy).
- **31** `AppointmentRulesTest` cases (atomic Confirmed booking, partial batches, duplicates,
  forged snapshots, cancellation, date blocks, reads, retries, real concurrent races and the Batch 1
  repository read/write transaction and minimal-release/reconciliation shapes).
- **9** `InventoryRulesTest` cases (Admin inventory publication, schema/paths/roles, slot flips,
  immutable locks, date-block lifecycle, inactive doctors and the explicit overlap limitation).

Coverage includes Patient profile ownership/role protection, Doctor/Schedule permissions, filtered
reads, schema/types/ranges/timestamps/IDs, deletion and atomic-move denial, malformed
profiles/claims, denied unrelated paths/groups, the existing overlapping-new-ID limitation, and
the Step 89 reservation contract: exact slot matching, generation-scoped locks, atomic
booking/cancellation, blocked-date release semantics, role-scoped inventory/lock/block reads,
retry reconciliation and two real concurrent request tests. Values and identities are synthetic;
no sample application data is installed.

Generated files stay under ignored `tests/firestore/build/`:

- `test-results/test/TEST-*.xml`: JUnit test counts/failures.
- `emulator/firestore-emulator.log`: owned-server startup and evaluation diagnostics.
- `emulator/rule-coverage.json`: raw Rules evaluation coverage. The export is streamed directly to
  disk because the format retains distinct evaluated values; the Step 89 export is about 200 MB and
  must not be parsed into the test JVM heap.

HTML test reports are disabled for this module, avoiding generated JavaScript report assets. The
Android/Gradle tools can still produce ignored HTML/JavaScript reports under their normal build
directories; **do not include build outputs, caches or `.git` history in an academic source ZIP**.
Prior Git history is intentionally not rewritten. Until reviewed/committed separately, archiving
the old HEAD would still contain the previous tests and omit uncommitted Patient UI/migration work.

## Limits

These are actual Rules integration tests, not Android SDK/Activity/authentication end-to-end tests.
They do not test real ID-token signature verification, production IAM, mobile offline cache,
repository transaction races, production indexes or every emulator/production limit difference.
The emulator accepts unsigned fake tokens only for local testing. Never use them in an app.

The central reservation guarantee is **per Admin-published inventory slot and generation**: the
three-write booking batch requires the exact slot path, an available generation `G`, a new
create-only lock `g(G+1)` and a Confirmed appointment; duplicate attempts, stale generations,
unlisted intervals and cross-slot releases are denied. It is not a Rules proof of global
non-overlap. Firestore Rules cannot query or iterate sibling slots, so the pairwise non-overlap of
Admin-published inventory remains a trusted-Admin invariant established by `SlotInventory`; a
malicious Admin or publisher bug can still publish overlapping slots and both bookings are
accepted (`InventoryRulesTest.overlappingAdminInventoryAndBookingsRemainPossible`).

Cancellation is two writes and requires `startAt > request.time` for both Patient and Admin, so a
past appointment cannot be cancelled through Rules. If a date block exists, the released slot must
become `blocked`; without a block, a Patient may release only to `available` while Admin may choose
`available` or `blocked`. Deleting a block is an explicit Admin action and never automatically
reopens slots already released to `blocked`. The immutable lock remains after cancellation and the
next booking uses the next generation. Bulk cancellation needs a composite index and remains a
feature excluded from Batch 1. Current repository/UI setup is in `docs/appointment-batch1.md`;
 the original single-field queries require no new composite index. The final dashboard adds one
 owner/status/startAt composite index, documented in `firestore.indexes.json` and the current
 `docs/BATCH_2_HANDOFF.md`; create it manually in Firebase Console before physical-device testing.
Dhaka arithmetic assumes the current fixed UTC+06:00 offset with no DST. Android mapping,
validation and ViewModel tests remain separate. Existing behavior/checklists are in
`docs/admin-doctor-firestore.md`, `docs/admin-doctor-schedule-firestore.md` and
`docs/patient-doctor-directory-ui.md`.

Step 89 changes only the local `firestore.rules`, this Java test module and documentation. No
deployments, real account/claim changes, production data operations, Realtime Database changes,
Cloud Functions, Node.js, commits or pushes are part of this work.

## Batch 1 validation (2026-11-07)

Device-workflow fix revalidation: the unchanged Rules suite still passes **82 tests / 5 classes**;
the Android suite now passes **324 tests / 29 classes**, including seven focused workflow regressions.
Publishing is exposed as a visible Schedule-screen button. Production deployment/device behavior
remains unverified; see the manual check in `docs/appointment-batch1.md`. Results below record the
original Batch 1 verification.

`.\gradlew.bat :firestore-rules-tests:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --rerun-tasks --console=plain`
completed successfully with **82 emulator tests / 5 classes**, **317 Android tests / 28 classes**,
zero test failures/errors/skips, lint **0 errors / 13 warnings**, and a rebuilt debug APK
(13,415,123 bytes). All 55 actionable tasks executed. The five extra emulator cases verify
minimal cancellation patches (including inactive doctors and private date blocks), stable-ID
reconciliation, the repository read/write transaction shape and Admin publication/skip behavior.
The Rules file was not changed by Batch 1; the final log has no access/expression-limit diagnostics.
Android UI/device validation remains manual; see `docs/appointment-batch1.md`.

## Historical Step 89 validation (2026-11-07)

- Full emulator task in the final combined run below: **77 actual tests** passed
  (4 canaries, 16 Doctor/Patient, 22 Schedule, 27 Appointment, 8 Inventory), zero
  failed/errored/skipped. The suite lifecycle entry is not an additional test.
- Booking access counts: appointment create 7, slot update 2, lock create 2, batch 11.
  Patient cancellation: appointment update 4, slot update 4, batch 8. Admin cancellation:
  3 + 4 = 7. Inventory create 2; date-block create 1; Admin unbooked-slot flips and date-block
  updates/deletes 0 external accesses. These are static successful-path estimates without caching,
  not measured runtime call
  counts: the coverage export has evaluated values but no per-request access counters. No 10- or
  20-document access-limit errors occurred in the tested single-reservation batches.
- The first booking rule shape exceeded Firestore's 1000-expression evaluation budget. Step 89
  removed redundant derivation while keeping the projected slot/lock/appointment links and the
  immutable Admin-validated slot definition; the final emulator log has zero expression-limit
  errors.
- Concurrent tests use two real REST commits with a start latch. Same-slot booking and duplicate
  cancellation each assert exactly one success; the loser receives 403 `PERMISSION_DENIED`.
- Continuation review reproduced and fixed integer-valued-double acceptance in appointment
  snapshots and slot updates. Explicit integer guards and regression loops preserve the strict
  schema on create/update/cancellation. Forgery assertions now use coherent companion IDs with a
  valid positive control; block checks also deny an Admin available-release and a Patient's
  combined block-delete/booking attempt. The test-method total remains 77.
- Final forced verification:
  `.\gradlew.bat :firestore-rules-tests:test :app:testDebugUnitTest :app:assembleDebug --rerun-tasks --console=plain`
  — **BUILD SUCCESSFUL**, 46 actionable tasks executed; **77 emulator tests across 5 classes** and
  **300 Android tests across 26 classes**, zero failures/errors/skips. Debug APK
  `app/build/outputs/apk/debug/app-debug.apk`, **13,352,982 bytes**, rebuilt rather than reused.
- `git diff --check`: clean apart from LF/CRLF warnings. All Steps 83–89 work remains uncommitted.
- `tests/firestore/build/emulator/rule-coverage.json` is ignored, varies with evaluated values,
  and is streamed to disk rather than parsed in memory (217,305,593 bytes in the final run).
- Final Rules SHA-256: `17F59C6D9AE60B4578110F436DAFF38C87F0736AA04BF50D5790DBA27BF3C1EB`.
- No Rules deployment, production Firebase operation, Auth/claim change, Realtime Database change,
  commit or push occurred.

## Historical notes

- The 2026-11-06 migration preserved the 37 former JavaScript security cases as 15 Doctor/Patient
  and 22 Schedule Java cases, then added the four canaries; all 41 passed before the legacy Node
  tooling was removed.
- Step 78 added `patientProfileQueryUsesActiveStatusAndStableDocumentId` (the 16th Doctor/Patient
  case). Step 86 added 23 Pending/manual-approval appointment cases for a 65-test suite.
- Step 89 replaced those 23 obsolete cases with the current 27 confirmed-booking/cancellation
  cases and added the 8-case inventory suite. The historical Step 86 rules and counts are not
  evidence for the current contract.

## Module file inventory

Added under `tests/firestore/`:

- `build.gradle.kts`, `.gitignore`, `COVERAGE.md`.
- Under `src/test/java/com/example/medibook/rules/`: `RulesIntegrationSuite.java`,
  `LocalFirestoreEmulator.java`, `RulesClient.java`, `RulesFixtures.java`, `ClientRulesCanaryTest.java`,
  `DoctorRulesTest.java`, `ScheduleRulesTest.java`, `AppointmentRulesTest.java`,
  `InventoryRulesTest.java`.

Modified: root `settings.gradle.kts` (independent Java test module), root `.gitignore` (retired Node
tooling ignores), this README, and testing guidance in `docs/admin-doctor-firestore.md`,
`docs/admin-doctor-schedule-firestore.md`, `docs/firebase-auth-setup.md`.
