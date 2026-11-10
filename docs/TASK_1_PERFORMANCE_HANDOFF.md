# Task 1 — Performance handoff

## Scope and confirmed bottlenecks

This delivery changes read/caching/presentation paths only. It preserves the Appointments
LayoutManager ordering fix, bounded authentication lease, numeric slot labels and modernized UI.

- Admin discarded its current counts on every return. Patient cached a nonempty next appointment,
  but could not distinguish a verified empty result from an unknown result.
- Doctor Profile could not reuse a doctor already fetched by the directory. Schedule listening
  was serialized behind another doctor query, even when that doctor was already verified.
- Repeated authentication state publications could restart appointment screen reads. Concurrent
  screen instances did not share a pending server read.
- Appointment rows were all marked changed on each render/30-second tick. The static Patient
  header also received RecyclerView change notifications for unchanged content.
- Weekly analytics re-counted documents on metadata/timer emissions on the main thread, and the
  chart/text fields were rebound even when the output was identical.
- RTDB watched the projection parent, including revision reservations rather than summary changes.

## Changes

- Added a UID-scoped, 120-second dashboard display cache (including verified empty results),
  invalidated on logout/account change and acknowledged appointment-write invalidation. Admin
  snapshots also require the current Dhaka day/week. Saved values remain CACHED until live
  verification; no raw/unverified RTDB data authorizes or replaces Firestore truth.
- Kept the existing RTDB revision-reservation / subsequent server Firestore read / conditional
  publication protocol. Listen to the compact summary child, suppress equal summaries and coalesce
  identical pending refresh requests. All RTDB projections reuse one database root/client.
- Moved weekly aggregation to a serial background executor. Reuse counts for unchanged document
  content only until the next completion or Dhaka midnight. Generation/data-version, account and
  clock checks reject stale results; errors do not turn an old snapshot into verified data.
- Reuse directory doctor data immediately and start doctor/schedule refreshes in parallel. Live
  permission loss/deactivation still clears the profile. Metadata-only doctor emissions no longer
  remap/rebind identical records.
- Added single-flight foreground appointment reads keyed by UID and read/role scope. Completed
  values never replace a new server refresh. Duplicate auth publications do not restart these reads.
- Own-Patient detail snapshots can render immediately, explicitly as saved information. They are
  separate from the server-confirmed action model: cancel/reminder actions never use this display
  cache. Admin/foreign-patient detail snapshots are not used as an own cached detail.
- Appointment diffing now updates changed/inserted/removed rows only, including time-derived status
  changes and serial arrivals. Static header updates preserve search focus. Identical text/chart
  content is not rewritten; warm appointment refreshes do not insert a spinner above cached rows.

## Verification

- Android unit tests: **594 passed, 0 failures/errors/skips**.
- API 37 offline instrumentation: **12 passed** (6 read/update performance checks, 4 slot-display
  regressions, 2 appointment header/crash regressions).
- Authenticated API 37 instrumentation: **3 passed**. Includes Patient/Admin list/filter/details/
  Back/recreate paths, updated Patient Next Appointment and weekly totals after background refresh,
  verified live RTDB analytics then forced RTDB disconnection with Firestore fallback, and Doctor
  Profile rendering from the directory cache while Firestore networking is disabled.
- Counted work, not an unmeasured speed percentage: 2 concurrent identical reads start 1 source;
  100 identical label emissions cause 1 TextView write instead of 100; unchanged appointment
  refresh/ticks cause 0 row changes; 10 directory emissions cause 0 static-header invalidations.
- Final authenticated Logcat: **3 cold verifications, 0 quiet revalidations, 0 invalid-credential
  warnings, 0 fatal exceptions**. Doctor and schedule listeners start in parallel.
- `:app:assembleDebug`, test APK assembly and `git diff --check` passed. The debug APK was installed
  and normal launcher startup reached Login on the emulator.
- SHA-256 comparison: **22 protected authentication, booking, publishing, reminder/local-service
  and rule files unchanged from this task's starting tree**. No production Firebase data/rules,
  OAuth configuration, slot allocation or transaction semantics were changed.

The new RTDB E2E initially exposed an emulator-client setup problem: SDK `useEmulator` mutates the
RepoInfo cache key, so repeated `getInstance(originalUrl)` could select a second client. Shared root
reuse plus test-only priming/identity/loopback assertions fixed the harness. Auth/Firestore/RTDB
fixtures all use explicit local emulator endpoints; no owner bypass is used for app assertions.

### Commands

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest
# Run AuthenticatedAppointmentsTest with androidx.test.runner.AndroidJUnitRunner.
# Auth 9099, Firestore 8080 and RTDB 9000 must be local and adb-reversed.
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest '-Pmedibook.uiTestRunner=com.example.medibook.ui.UiOnlyTestRunner'
# Run ReadPerformanceTest, AppointmentSlotDisplayTest, AppointmentListStructureTest with UiOnlyTestRunner.
git diff --check
```

APK: `D:\AndroidStudioProjects\MediBook\app\build\outputs\apk\debug\app-debug.apk`

## Task 1 files

Under `app/src/main/java/com/example/medibook/`:

- `cache/DashboardCache.java`, `cache/PatientDataCache.java`
- `repositories/SingleFlightReads.java`, `repositories/AppointmentReadRepository.java`
- `repositories/AdminDashboardRepository.java`, `repositories/PatientDashboardRepository.java`
- `repositories/DashboardMirror.java`, `repositories/RealtimeDisplayRepository.java`
- `repositories/WeeklyAnalyticsRepository.java`, `repositories/PatientDoctorRepository.java`
- `activities/AppointmentsActivity.java`, `activities/AppointmentDetailsActivity.java`
- `activities/admin/AdminDashboardActivity.java`, `activities/patient/DoctorProfileActivity.java`
- `adapters/AppointmentRowDiff.java`, `adapters/AppointmentAdapter.java`, `adapters/PatientHomeHeaderAdapter.java`
- `utils/ViewUpdates.java`, `utils/AppointmentPresentation.java`, `views/WeeklyBarChartView.java`
- `viewmodels/PatientDoctorDirectoryViewModel.java`

Tests under `app/src/test/java/com/example/medibook/`:

- `cache/DashboardCacheTest.java`, `cache/PatientDataCacheTest.java`
- `adapters/AppointmentRowDiffTest.java`, `viewmodels/PatientDoctorDirectoryViewModelTest.java`

Instrumentation under `app/src/androidTest/java/com/example/medibook/`:

- `ui/ReadPerformanceTest.java`, `ui/AuthenticatedAppointmentsTest.java`
- `repositories/LocalDatabaseHarness.java`

## Remaining checks / limits (not implemented)

- Measure navigation latency, frame pacing and memory on the OnePlus Nord CE5. Emulator/count-based
  evidence does not establish physical-device speed or FPS improvements.
- Confirm warm/cold navigation, IME/search continuity, weak-network reconnection, RTDB fallback,
  account switching and day/week rollover on the physical device with approved test accounts.
- First uncached loads still require network verification. Large appointment histories still use
  the original unpaginated queries; server-maintained aggregates/pagination need separate design.
- Task 2 booking, publishing, cancellation and reminder work is deliberately not included.

All changes remain uncommitted. No commit, push, reset, stash, clean or Firebase deployment was run.
