# Patient Doctor Directory and Doctor Profile

This document describes the Patient Home/directory implemented in Step 75 and connected to
Cloud Firestore in Step 78. Admin Doctor/Schedule CRUD, authentication, Security Rules and the
Realtime Database configuration are unchanged.

## Live Firestore behavior

- Patient Home loads active doctors with one server-backed snapshot listener:
  `doctors.whereEqualTo("active", true)`. There is no unfiltered Doctor query and no Admin
  repository call. Inactive records are excluded by Firestore, not by a client-side scan.
- Doctor Profile navigates with the stable doctor document ID and the initiating Patient UID.
  It re-queries the doctor by `active == true` and document ID, then re-queries weekly schedules
  with `schedules.whereEqualTo("active", true)` under that doctor.
- No profile field is trusted from the navigation Intent. The Intent supplies only path identity;
  name, specialization, qualifications, fee, biography and schedules are read from Firestore.
- Firestore stores `consultationFee` as integer hundredths. `50000` maps to the UI value `500.00`
  through `DoctorMapper` and explicit `BigDecimal` scaling.
- `dayOfWeek` is ISO (Monday `1` through Sunday `7`). `startTime`/`endTime` are integer minutes
  after midnight in `Asia/Dhaka`. The profile shows day and wall-clock time without adding dates,
  UTC conversion or inferred appointment slots.
- The `active` filters are required by Security Rules, which are not filters. If a doctor or
  schedule becomes unavailable, Rules can return `PERMISSION_DENIED`; the UI clears the affected
  content, shows an honest unavailable/error state and offers Retry.
- The disabled **Book appointment — coming soon** button has no click handler. No booking, slot
  reservation or appointment history is implemented.

## Search, filter and presentation

- `PatientDoctorDirectoryViewModel` retains only UI criteria and immutable snapshots for the
  verified Patient owner. It exposes `LOADING`, `READY`, `ERROR`, `PERMISSION` and unused
  `NOT_CONNECTED` states; only server-confirmed snapshots become `READY`.
- Search is a trimmed, case-insensitive doctor-name substring limited to 100 characters.
  Specialization filtering is derived from the currently loaded active doctors and combined with
  search using AND. Live snapshot updates reapply both filters and remove an obsolete
  specialization selection instead of hiding every row silently.
- `PatientDoctorDirectory` rejects malformed or duplicate records, keeps active records only,
  sorts by name then ID, and returns immutable snapshots. Specializations are trimmed,
  case-insensitive, deduplicated and sorted.
- Patient Home keeps one RecyclerView with a `ConcatAdapter`: a stable header adapter for greeting,
  search and specialization, plus the result/state adapter. The results adapter publishes only
  per-row `DiffUtil` updates; it must not call `notifyDataSetChanged()`, because ConcatAdapter
  promotes a nested full invalidation to the whole list and detaches the focused search header
  (closing the keyboard on each typed character).
- Result cards show name, specialization, qualifications, fee and View profile. They are separate
  from the Admin adapter and expose no management actions. There are no sample records, fabricated
  specialties or simulated success messages.

## Session, lifecycle and authorization

Both Activities independently call `AuthRepository.checkSession()` on every `onStart` and observe
its online verified state. Content stays hidden while verification runs and after `onStop`.
`PatientScreenAccess` requires a verified Patient role and a current authenticated UID. Admin,
missing-user and loading states cannot expose Patient content. Unauthorized sessions use the
existing `AuthNavigation` clear-task routing.

Logout remains in each Patient toolbar. Directory/profile listeners detach on stop, and each
request has a generation token so a superseded or stopped screen cannot publish late results.
Changing accounts cancels the previous listener and clears search criteria. Saved state contains
only bounded search/filter text plus its owner, never doctor records, credentials or an accepted
role; restoration occurs only after a fresh same-owner Patient check. Profile extras cannot grant
authorization for another account.

`PatientDoctorRepository` is read-only. It rejects cache-only or pending-write snapshots as
authorized results, waits for the first server confirmation during initial loading, and clears
previously verified data if server confirmation is lost. A 12-second request timeout prevents an
indefinite loading state. It has no booking, write or Admin operation.

## Relevant files

- `activities/patient/PatientHomeActivity.java`: verified session, live directory listener,
  search/filter callbacks and ID-only profile navigation.
- `activities/patient/DoctorProfileActivity.java`: live doctor and active-schedule listeners,
  owner gate, retry/error/loading states and disabled booking.
- `repositories/PatientDoctorRepository.java`: read-only active-only doctor and schedule queries.
- `viewmodels/PatientDoctorDirectoryViewModel.java`: bounded criteria and presentation states.
- `adapters/PatientHomeHeaderAdapter.java`, `adapters/PatientDoctorAdapter.java`: stable header and
  read-only cards/states.
- `utils/PatientDoctorDirectory.java`: validation, filtering, sorting and fee text.
- `utils/PatientDoctorProfileContract.java`: bounded path identity (`doctorId` + initiating UID).
- `utils/PatientScheduleDisplay.java`: ISO/day/time ordering and wall-clock time text.
- `utils/PatientScreenAccess.java`: Patient-role and UID ownership gate.
- `res/layout/activity_patient_home.xml`, `activity_doctor_profile.xml`,
  `item_patient_directory_header.xml`, `item_patient_directory_state.xml`,
  `item_patient_doctor.xml`, `view_patient_session_loading.xml`; `res/values/strings.xml`;
  `res/menu/menu_patient.xml`; `AndroidManifest.xml` (non-exported profile Activity).
- Tests: `PatientDoctorDirectoryTest`, `PatientScreenAccessTest`,
  `PatientDoctorDirectoryViewModelTest`, `PatientScheduleDisplayTest`, `DoctorMapperTest`.
- Java emulator suite: `tests/firestore/` (see its README and COVERAGE map).

Step 75's earlier layout-preview route and its placeholder strings were removed when live data was
connected. The profile no longer claims doctor details are unverified preview data.

## Limits

- Emulator/unit tests do not exercise real production ID-token signatures, actual Android Activity
  rotation/Bundle restoration, Firestore offline persistence or device rendering. They do not
  perform production Firebase operations.
- `PatientDoctorRepository` and its listeners are not exercised by JVM tests because they require
  Firebase/Android runtime. Mapping, filtering, schedule formatting and state transitions are
  covered separately.
- Security Rules cannot enforce global schedule-window overlap exclusion. This step does not add
  booking or claim concurrency safety.
- Doctor fee currency is not stored in the existing schema. The UI shows the exact amount and an
  explicit currency-not-configured note.
- A deleted or inactive exact-ID doctor query may return `PERMISSION_DENIED` rather than an empty
  result. This is expected Rules behavior and is handled as unavailable, not as a successful empty
  profile. See the Step 78 emulator regression in `tests/firestore/README.md`.
- No Firebase rules deployment, production data change, account/claim change, Realtime Database
  change, commit or push was performed.

## Validation and physical-device checklist

From the repository root:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :firestore-rules-tests:test --console=plain
git diff --check
```

Physical review on a Patient account connected to the configured Firebase project:

1. Verify the session spinner appears before content. Confirm that Admin accounts are routed away
   and never see directory/profile content.
2. Confirm only active doctors appear, with real name, specialization, qualifications and fee
   (`50000` displayed as `500.00`). Change a doctor in Admin and verify the Patient list updates
   without a manual refresh.
3. Search by partial name in different letter cases and keep typing several characters without
   reopening the keyboard; verify the cursor stays at the end and the typed query is preserved.
   Select and clear each real specialization; combine both filters; verify no fake specialties
   and no stale rows. Rotate and confirm filters survive only for the same Patient.
4. Open a doctor by ID and verify current profile details and only active weekly schedules.
   Check ISO day mapping and Dhaka wall-clock times, including 12:00 AM and 12:00 PM.
5. Deactivate the doctor or schedule in Admin while the profile is open. Verify content clears,
   schedules stop updating and an error/retry state appears. Deleted/missing/inactive profiles must
   never show fabricated details.
6. Background/return, rotate and press system Back from the profile. Verify no duplicate listeners,
   no stale flash and a fresh Patient session check. Log out and verify Back cannot reopen content.
7. Verify small screens, landscape, large fonts, TalkBack and keyboard scrolling. Confirm Book
   appointment is visibly disabled and labelled coming soon.
