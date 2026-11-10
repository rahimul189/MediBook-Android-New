# Batch 1 — Core appointments

## Device workflow fix / manual deployment check

- Admin Schedule now has a visible **Choose date** control and **Publish Slots** button; the menu
  is optional. A matching future weekday is suggested. Wrong-weekday/elapsed schedules are errors,
  not a misleading successful zero publication. Results and failures remain on the screen.
- Patient slots render in a selectable RecyclerView. Unpublished, fully booked, stale/no-matching-
  schedule states are distinguished. Both screens show the actual runtime Firebase project and
  epoch-millisecond inventory path. Failures include SDK code and operation; Logcat tag is
  `MediBookAppointments` (no tokens or patient bodies logged).
- Production Rules for **medibook-2c3a4** could not be inspected: Console requires sign-in. An
  authorized operator must open Firebase Console → medibook-2c3a4 → Firestore → `(default)` → Rules,
  compare against the entire local `firestore.rules`, and manually publish it **if different**.
  Required matches include `appointments`, `doctorSlotInventory`/`bookings`, and `doctorDateBlocks`.
  Do not replace them with open/test rules. Admin needs Boolean `admin=true`; sign out/in after a
  trusted claim change. Retain default single-field indexes; no new composite index is required.
- Exact device check: Admin → Doctor Management → that doctor’s Schedules → choose a future date
  with an enabled weekly schedule → Publish Slots → confirm success and the inventory path. Then
  Patient → same doctor → Book Appointment → **same date** → select a row → Confirm → My Appointments.
  If publishing shows `PERMISSION_DENIED` at `Read date block`/inventory, resolve deployed Rules or
  authorization first; a client code change cannot safely bypass this. Verify the runtime project
  label is `medibook-2c3a4`. No ADB device was connected, so this remains **not device-verified**.
  Install the freshly built `app/build/outputs/apk/debug/app-debug.apk` first (Android Studio Run,
  or `adb install -r app/build/outputs/apk/debug/app-debug.apk` after connecting/authorizing the phone).
- Fix verification: `:app:testDebugUnitTest :firestore-rules-tests:test :app:assembleDebug --rerun-tasks`
  passed with **324 Android tests / 29 classes** and **82 emulator tests / 5 classes**, zero
  failures/errors/skips. Seven narrow regressions cover wrong dates, empty-state causes, shared
  inventory paths and diagnostic codes. This does not establish production deployment or device success.

## App flow

- Patient: Doctor Profile → Book Appointment → date → published slot → confirmation → My Appointments.
- My Appointments has All, Upcoming/in-progress, Completed and Cancelled views. Tap a row for details
  and eligible future cancellation. Completion is derived when `now >= endAt`, never written.
- Admin: dashboard → Manage Appointments. Filter by doctor name, Dhaka date and display status;
  open details for embedded patient name/phone and individual future cancellation. No user directory.
- Existing Schedule screen → **Choose date → Publish Slots**. Select a date after saving active weekly
  schedules. No unavailable-date management, bulk cancellation or live queue controls were added.

## Safety and limitations

- Rules and the 17/12/6-field appointment/inventory/lock schemas are unchanged from Step 89.
  Booking reads current server inventory, Doctor, Schedule and own profile, then commits exactly
  three companion writes. The displayed Doctor quote must still match at confirmation.
- Reads use `Source.SERVER`; transaction completion and a server appointment read establish success.
  Local cache/pending writes never count as success. Transactions do not queue offline writes.
- A booking ID is retained in a ViewModel/saved instance state and reused after an unknown result.
  A successful read of that ID reconciles a prior commit. Missing own appointments can return
  permission-denied, not 404. After closing/restarting a flow, check My Appointments before rebooking
  an operation whose outcome was unknown.
- Cancellation reads only the appointment and patches the appointment plus slot. It does not need
  a Patient-readable lock, generation field, or inactive-doctor inventory read. It tries an available
  release, then (only after permission denial) a blocked release. Existing date blocks remain enforced
  and private. An unchanged Confirmed read is not reported as successful cancellation.
- Publication reserves **every** existing interval, regardless of booked/available/blocked status,
  and creates only missing non-overlapping intervals. It never overwrites, deletes or reopens slots.
  Admin checks existing date blocks before publication and before each new slot write.
  Booked history survives schedule changes. Available slots whose schedules no longer fit are hidden
  and rejected at booking; old intervals can leave gaps that publication intentionally does not fill.
- Publication is serialized within this app process. Use **one Admin publisher/device per doctor**.
  Rules cannot prove sibling non-overlap or coordinate two different Admin devices adding different
  IDs. Existing overlapping inventory is rejected by this publisher and availability reader; it is
  not silently repaired. A malicious Admin can still bypass the app, as documented in Step 89.
- Queue token = `Q` + zero-padded `(startMinute + 1)` for that doctor/date, e.g. 09:00 → `Q0541`.
  This is a stable scheduled-time ordinal, **not** a dense patient count or currently-serving number.
  Cancellations leave gaps; rebooking the same slot inherits its token. No new field/allocator is used.
- Appointment lists use the Patient UID single-field query or an Admin collection query, then filter
  and sort in memory. No composite index is needed. This small-lab implementation is not paginated;
  large histories will increase reads. Display status updates locally every 30 seconds and on refresh.
- Patients cannot pre-read private date blocks; an available-looking slot on an already blocked date
  can be rejected at confirmation with a refresh/history message. Rules were not weakened for UI.
- No RTDB sync, external API, paid backend, manual approval or manual completion was introduced.

## Manual setup / device smoke test

No Firebase deployment or production-data operation was performed by this batch. Before using
the app against a live project, separately review/deploy the **existing local `firestore.rules`**,
retain default single-field indexes, and provision an Admin through the existing trusted custom-
claim process. Do not grant Admin by editing a profile alone.

1. Sign in as Admin. Create/enable a Doctor and matching weekly schedule. On Schedule screen publish
   a future matching date. Repeat publication: it should add zero slots and preserve reservations.
2. Sign in as Patient. Open that Doctor, choose that date and slot, confirm, then open My Appointments.
3. Race the same slot from another client: only one reservation may commit; refresh after rejection.
4. Cancel the future appointment; verify retained Cancelled history and successful next-generation
   rebooking. Try cancellation after disabling the Doctor/Schedule: history/cancellation still work.
5. Sign in as Admin, filter the appointment list, inspect embedded patient details and cancel one
   future booking. Confirm no user-directory navigation, approval or Mark Completed control exists.
6. Check rotation, offline/reconnect, refresh and logout/account-switch behavior on a device.

No Android device/AVD was connected during this session, so this device walkthrough remains manual.
Automated validation uses JVM domain tests and the isolated Java Firestore emulator, including real
REST read/write transactions with the repository’s read set and minimal cancellation write masks.

## Batch file inventory

New Java files (under `app/src/main/java/com/example/medibook/`):

- `activities/AppointmentSessionActivity.java`, `activities/AppointmentsActivity.java`,
  `activities/AppointmentDetailsActivity.java`, `activities/patient/BookAppointmentActivity.java`.
- `adapters/AppointmentAdapter.java`, `viewmodels/AppointmentOperationViewModel.java`.
- `models/InventorySlot.java`, `models/BookingLock.java`.
- `repositories/AppointmentRepository.java`, `repositories/AppointmentWrites.java`,
  `repositories/SlotInventoryPublisher.java`, `repositories/InventorySlotMapper.java`,
  `repositories/BookingLockMapper.java`.
- `utils/AppointmentPolicy.java`, `utils/AppointmentUi.java`.

New resources: `activity_book_appointment.xml`, `activity_appointments.xml`,
`activity_appointment_details.xml`, `item_appointment.xml` under `res/layout/`;
`res/menu/menu_admin_schedules.xml`; `res/values/strings_appointments.xml`.
New tests: `repositories/ReservationMappingTest.java` and `utils/AppointmentPolicyTest.java`
under `app/src/test/java/com/example/medibook/`. This document is also new.

Modified app files: `AndroidManifest.xml`; Patient Home/Profile and Admin Dashboard/Schedule
Activities; `DoctorScheduleListViewModel.java`; the existing Admin dashboard, Doctor profile and
Schedule layouts; `menu_patient.xml`; `strings.xml`. Emulator changes are limited to
`RulesClient.java`, `AppointmentRulesTest.java`, `InventoryRulesTest.java`, README and COVERAGE;
the reservation design now links to this batch. Existing `firestore.rules` is byte-unchanged.

## Verified results — 2026-11-07

```powershell
.\gradlew.bat :firestore-rules-tests:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --rerun-tasks --console=plain
```

- **BUILD SUCCESSFUL**, 55 actionable tasks executed.
- **317 Android tests / 28 classes**, zero failures, errors or skips (17 new focused tests).
- **82 emulator tests / 5 classes**, zero failures, errors or skips: 4 canaries, 16 Doctor/Patient,
  22 Schedule, 31 Appointment, 9 Inventory. Five new cases exercise Batch 1 repository contracts.
- Lint: **0 errors, 13 warnings**. Eleven are pre-existing; two noncritical new warnings concern
  full-list adapter redraw and nested layout weights. These were not suppressed.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, **13,415,123 bytes**.
- No access-limit/expression-limit diagnostics. The static write-path budget estimates remain
  11 for booking, 8 for Patient cancellation and 7 for Admin cancellation. Exact runtime counters
  are not available from the coverage export; real REST transaction tests also passed.
- `git diff --check` passed (LF/CRLF warnings only). No dependency change, deployment, real Firebase
  operation, commit or push. `.idea/appInsightsSettings.xml` and protected prior work are unchanged.

Automated checks do not establish Android Activity rendering or live Auth/Firestore end-to-end
behavior. The device smoke test above remains required; no device/AVD was connected.
