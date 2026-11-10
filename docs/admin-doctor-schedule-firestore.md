# Step 69.3 — Firestore Doctor Schedule integration recovery

## Scope and preservation

The two Schedule Activities now use the existing Schedule repository/ViewModels instead of local
preview storage. Add, List, Edit, Enable and Disable operate on
`doctors/{doctorId}/schedules/{scheduleId}`. Disabled records remain stored. There is no delete
action, appointment booking, generated appointment slots, Auth account/claim change, Realtime
Database change, automatic rule deployment, commit or push.

The verified pre-repair source backup is preserved at
`D:\MediBookBackups\MediBook_Step69_PreRepair_20261106_214451_974_fa9f4e76569e`.
Doctor CRUD and authentication implementation/schema are not changed by this recovery. Schedule
transactions read the parent Doctor but never write it. The original Patient/Doctor rule policy
above the Schedule section is preserved. Step 67's UI-only document is historical, not current behavior.

## Stored schema

Exactly these six fields are written; identifiers belong only to the document path:

| Field | Stored type and meaning |
| --- | --- |
| `dayOfWeek` | Integer, ISO Monday `1` through Sunday `7` |
| `startTime` | Integer minutes after midnight, `0..1439`, in `Asia/Dhaka` |
| `endTime` | Integer minutes after midnight, later than start and at most `1439` |
| `slotDurationMinutes` | Integer `5..180`, multiple of five, no longer than the window |
| `active` | Boolean |
| `updatedAt` | Firestore server timestamp on every write |

These are weekly hospital-local wall-clock values, not dates or UTC instants. Overnight windows,
`24:00` end values, equal start/end, HH:mm strings and fractional durations are rejected. A window
need not divide exactly into slots; no partial slots or appointments are generated. Existing doctor
IDs are unchanged; edits/status changes target the original schedule ID, never a new document.
The mapper rejects unsupported data rather than silently converting or repairing it.

## Online operations and UI state

- Every entry into either Activity repeats the existing verified Admin session check. Content is
  hidden during verification and on stop. Navigation/results are scoped to the initiating UID and
  selected doctor, but extras never grant authorization. Non-exported Activities pass schedule IDs,
  not editable serialized records, and independently load server data.
- Reads explicitly use `Source.SERVER` and reject cache/pending-write snapshots. The directory is
  sorted by ISO weekday, start time and ID. Loading, empty, error, retry and refresh states are real
  repository states, not simulated delays. This is refresh-on-entry/manual refresh, not a live listener.
- A new form starts without selected day/times/duration and defaults inactive. Existing form
  validation, hospital-time formatting, accessibility hints, focus/scrolling and discard confirmation
  are retained. Duration/status and confirmed selections are explicitly restored after rotation.
- One Save/status Task is retained by its ViewModel across rotation. Pending writes disable duplicate
  actions; Back/Cancel wait while saving. Success/result events occur only after the transaction Task
  acknowledges success, never from an optimistic row change or a cached snapshot. A status write is
  followed by a fresh server directory read. A read failure can coexist with a confirmed write success.
- Activity observers detach on stop. Session end/clearing invalidates callback generations and clears
  loaded data; callbacks also check the initiating UID. Logout remains available, but **cannot undo or
  cancel a write already submitted to Firestore**. Security Rules recheck authorization on the server.
- Time pickers close on stop or loss of edit permission. Restored picker dialogs are dismissed before
  a fresh Admin check; unconfirmed picker positions are not treated as saved form selections.
- Network/unknown outcomes, version conflicts, permission denials and already-existing IDs require an
  explicit **Reload saved data** before another Save. Reload warns that an existing saved record replaces
  unsaved form values. An overlap rejection permits correcting the day/times or saving inactive.
- After process restoration, no Save is repeated automatically and no success is inferred. The
  restored ID is reconciled through an explicit server read: an absent new target keeps the same ID;
  an existing target becomes an edit using its server version; a missing edit target never becomes an
  upsert. Invalid/missing restored IDs cannot silently allocate another path.
- Stable IDs prevent duplicate submissions within this retained/restored flow, **not global exactly-once
  delivery**. If Android saved state itself is lost, review the server list before starting another Add.

## Concurrency and overlaps — intentionally not a guarantee

The repository freshly reads the doctor's schedules, compares the target's server `updatedAt` version,
and checks active windows as half-open intervals `[start, end)` regardless of slot duration. Adjacent
windows are allowed; inactive windows and the candidate's own ID are ignored. Enabling an inactive
record runs the same active-overlap checks. The online transaction rereads the parent, target and
known sibling schedule documents before writing. Changes to those read documents cause retry or
version/overlap rejection; stale edits are not silently overwritten.

**A different new ID inserted after the outside query is not in that transaction's read set.**
Concurrent new-ID inserts can both pass, and an authorized Admin client can bypass UI checks.
Current Rules validate each record, not collection-wide overlap exclusion. UI notices, repository
comments and an emulator test explicitly expose this limitation. A trusted backend or a separately
reviewed locking/index schema is required for a strict guarantee; neither is introduced here.
The current check reads all schedules and is intended for modest weekly directories, not large-scale
pagination or appointment capacity enforcement.

## Local Security Rules

- Only Boolean `admin = true` grants Schedule Admin read/create/update access. A profile's `role`
  never grants Admin access; malformed claims are rejected.
- Patient schedule reads require an authenticated identity without an Admin claim, a complete valid
  Patient profile whose email matches the token, an existing active Doctor and an active schedule.
  Patient list queries must include `where('active', '==', true)`; Rules are not filters.
- Writes require an existing parent, bounded safe doctor/schedule path IDs, exactly the six fields,
  valid types/ranges and `updatedAt == request.time`. Updates must advance `updatedAt`. Editable
  `id`, `doctorId` or `scheduleId` fields are not allowed. Deletion is denied even to Admin.
- Unrelated collections, other nested collections, appointments and collection-group schedule queries
  remain denied. Original Patient profile and Doctor CRUD policies are unchanged.

Rules are **local and not deployed**. If deployed rules still deny schedules, the app correctly shows
permission denied until the project owner separately reviews/publishes appropriate rules. A successful
build/emulator run does not prove production configuration or physical-device behavior.

## Validation

From the repository root:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
.\gradlew.bat :firestore-rules-tests:test --console=plain
git diff --check
```

Step 77 replaces the original test tooling with Java/JUnit and a pinned Java emulator JAR; Java 21+
is required, with no JavaScript/Node/CLI dependency. The runner verifies `firebase.emulators.json`,
starts its own emulator for `demo-medibook` at `127.0.0.1:8080`, and sends authenticated/anonymous
client REST requests that actually evaluate Rules. Fixture-only bypass is not permission evidence.
No production accounts, service credentials or deployments are used. See `tests/firestore/README.md`
and `tests/firestore/COVERAGE.md` for setup, current test results, the original 37-case replacement
mapping, the Step 78 profile-query regression and the 23 Step 86 appointment cases
(65 current tests). Step 86 changes only the local `firestore.rules`; no deployment is implied.

Java tests cover mapping/strict types, ISO/minute/duration boundaries, immutable copies, half-open
overlaps and different durations, sorted immutable directories, server-version identity, duplicate
Save/status suppression, retained Tasks/observers, explicit reconciliation, error/retry state and
session-loss callback suppression. They use manually completed Tasks, not a live Android Firebase app.
Rules tests exercise authorization/schema with synthetic emulator identities, including a test showing
that valid overlapping new IDs are **not** rejected by Rules. These tests do not execute Android
repository transactions, Activity lifecycle callbacks, real login or device rendering end-to-end.

### Recovery validation results (2026-11-06)

- Requested Gradle command: **passed**, including debug assembly and **144 unit tests** with
  zero failures/errors/skips (76 Schedule tests and 68 existing other tests).
- Lint: **0 errors, 13 existing warnings**: 3 older-API accessibility attributes, 5 available dependency
  updates, 4 potential background overdraws and 1 unused existing color. Dependencies were not upgraded.
- Isolated emulator command: **37 passed**, zero failed/skipped: 15 existing Doctor/Patient regressions
  plus 22 Schedule rules tests. Emulator startup identified `demo-medibook` and shut down after tests.
- Tracked and nonignored/untracked development files were reviewed against the source backup;
  Doctor CRUD/authentication files and the original Patient/Doctor rule prefix are unchanged.
- The 122 source files in the verified backup were rehashed and remain unchanged. The backup was
  never used as an output directory or overwritten. Physical-device review below is still pending.

## Recovery change inventory

Java paths in this list are relative to `app/src/main/java/com/example/medibook/`:

- Wired `activities/admin/ManageDoctorScheduleActivity.java` and
  `activities/admin/AddEditDoctorScheduleActivity.java` to their retained server-backed state.
- Hardened `viewmodels/DoctorScheduleListViewModel.java` and
  `viewmodels/DoctorScheduleEditorViewModel.java`; added the `repositories/DoctorScheduleStore.java`
  offline test seam. `repositories/DoctorScheduleRepository.java` now exposes sorted immutable
  directories/domain failures for that seam and defensively rejects null edit drafts.
- Updated `app/src/main/res/layout/activity_add_edit_doctor_schedule.xml` for explicit form restoration.
- Updated `app/src/test/java/com/example/medibook/models/DoctorScheduleTest.java` and
  `app/src/test/java/com/example/medibook/utils/DoctorScheduleValidationTest.java`; migrated
  `DoctorSchedulePreviewsTest.java` to `DoctorScheduleConflictsTest.java` in that same utils test folder.
  Added `app/src/test/java/com/example/medibook/repositories/DoctorScheduleMapperTest.java` and
  `app/src/test/java/com/example/medibook/viewmodels/DoctorScheduleViewModelTest.java`.
- Added Schedule rule comments in `firestore.rules`; its pre-existing partial Schedule policy passes
  the emulator suite without weakening Patient/Doctor policies. The initial test scaffold was later
  replaced by the Step 77 Java/JUnit suite under `tests/firestore/src/test/java/`; see its README
  for current commands. The recovery results above remain historical milestone results.
- Added this document and marked `docs/admin-doctor-schedule-ui.md` as historical. Partial Step 69
  model/mapper/conflict utilities/contract/adapter/strings are retained and now have compatible consumers.

## Physical-device review still required

1. Recheck Patient/Admin login and Doctor Add/Edit/Deactivate/Reactivate; compare Doctor fields before
   and after Schedule operations. Do not use production writes as part of automated validation.
2. With separately approved rule publication/test data, Add a schedule, reopen the list, edit it, then
   Disable/Enable. Verify stable IDs, server timestamps, retained disabled records and genuine success.
3. Try empty/invalid forms, same-day boundaries, overlapping active windows with different durations,
   adjacent windows and inactive overlaps. Enabling a conflicting window must be rejected by the app.
4. Rotate during loading/Save/status changes and with a picker open. Check retained inputs and exactly
   one submitted Task; background/return must reverify Admin before showing content or success.
5. Test interrupted process restoration, explicit Reload with an absent/present ID, denied/offline reads,
   permission failures and stale edits from a second client. Do not treat timeout as a confirmed failure.
6. Logout/change account while loading/saving; prior Admin data must not reappear, and later callbacks
   must not show success in the new session. A previously submitted write may still complete remotely.
7. Change device timezone/12-hour preference; stored wall-clock minutes must remain `Asia/Dhaka`.
   Review landscape, large fonts, keyboard, TalkBack and all error/retry/discard states.
8. Treat concurrent new-ID overlap tests as a documented limitation, not a booking guarantee. Review
   deployment and concurrency design separately before introducing any patient booking feature.
