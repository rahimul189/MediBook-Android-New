# Final RTDB dashboard handoff

**Batch 3 continuation:** see [BATCH_3_GOOGLE_AUTH_HANDOFF.md](BATCH_3_GOOGLE_AUTH_HANDOFF.md) for the
current Google sign-in/profile-completion/account-linking implementation and manual OAuth setup.
The user reports the Database/Dashboard Revision was physically validated, both complete Rules files
published and the appointments composite index enabled after this historical verification. No repeat
Rules/index publication is needed for Batch 3, which retains both files unchanged.

## Current development status — supersedes every archived section below

The Hospital Announcements feature and Firestore in-app Notification Center are permanently removed.
Their screens, dashboard entries/bells, listeners, models, repositories, resources, navigation and
feature-only tests are gone. Existing production data is NOT deleted. Local Rules now deny all client
access to both announcement branches and `users/{uid}/notifications/{id}`. Android appointment
notifications/reminders are a separate feature and remain intact.

The original `WeeklyBarChartView` implementation, dimensions, Saturday–Friday labels and appearance
are unchanged. The Admin dashboard keeps Doctor/Appointment Management and now has four matching
cards: Today's Total, This Week's Total, Confirmed Today and Cancelled Today. The patient dashboard
has Next Appointment near the top; the original 11 wellness cards and carousel implementation are
unchanged. All other booking/profile/contact/reminder/environment/calling work is retained.

### Final database architecture

| Store/path | Purpose and authority |
| --- | --- |
| Firestore `appointments` | Authoritative booking identity, patient ownership, scheduled dates/times, status and immutable snapshots |
| Firestore `doctorSlotInventory/.../serials/...` | Authoritative, immutable slot numbers, including reuse; never calculated from row position or RTDB token |
| Firestore profiles/doctors/schedules/emergency contacts | Existing ownership, validation, transactional reservation/release and contact-sync contracts unchanged |
| RTDB `dashboards/patients/{uid}` | Private `{revision, summary}`; summary has appointment ID/start/end plus revision/checkedAt only; no name, phone or medical details |
| RTDB `dashboards/admin` | Admin-only `{revision, summary}`; current Dhaka day/week, seven daily counts, totals, confirmed/cancelled/completed counters and checkedAt |
| RTDB `display/bookings`, `display/status`, `display/queues` | Existing hidden best-effort booking/token mirror and all permissions retained |
| Old announcements / Firestore notification history | Retired, client reads/writes denied, retained data untouched |

### Patient selection and verification

`PatientDashboardRepository` listens to one bounded, owner-scoped query: patientUid == current UID,
status == Confirmed, startAt > the query's current instant, ascending startAt, limit 20. Selection is
earliest valid future booking, never last-created/last-booked. Cancellation promotes the next result;
start-time expiry rebinds the query. One selected serial sidecar listener supplies the true `#05`
number; legacy/unavailable assignments use the existing explicit time-slot identifier, never a fake
rank. Missing doctor display text has safe placeholders; identity/time/status are never inferred.
Details navigation opens the existing server-verifying Appointment Details screen.

The private RTDB pointer is compared to the *nearest* authoritative query result, not merely checked
for the existence of an arbitrary ID. The validated pointer resolves doctor/date/status/serial from
Firestore, without copying sensitive appointment records into RTDB. A missing, malformed, stale,
unreachable or mismatched pointer uses Firestore directly. Cache-first values are explicitly saved/
awaiting verification, and a cached status is not labelled current Confirmed. An unknown empty offline
cache is NOT shown as a verified “no appointments” result. Initial verification has an 8-second UI
deadline with Retry; listeners can still recover afterwards. Account and request generations guard
callbacks; listeners/timers close on stop, retry and account changes.
If the bounded 20-record prefix contains only malformed/expired records, the result is unverifiable
with Retry, not proof of an empty future schedule.

### Admin counts and chart

`WeeklyAnalyticsRepository` remains the Firestore reconciliation/fallback source, queried only within
the current Saturday midnight → following Saturday midnight `appointmentDate` range. It uses the
existing `WeeklyAppointmentStats` logic, now including status counters. All doctors count; creation
dates do not. Cancelled contributes only to Cancelled Today. Non-cancelled Completed contributes to
today/week/seven-day totals. Confirmed Today's counter follows the app's current display lifecycle:
Confirmed records with endAt <= now are Completed, not still Confirmed. Legacy persisted Completed
records remain readable for aggregates; this task does NOT change the booking status enum or persist
completion. Today's Total = Confirmed Today + Completed Today; Week Total = sum of the seven bars.

`AdminDashboardRepository` subscribes to the compact RTDB summary, validates its current period,
schema, freshness and equality with Firestore-calculated values, then supplies its counts to the
unchanged chart. Otherwise it renders the Firestore result. Saved/offline, unknown and fallback states
are labelled; unavailable counts show a dash, not invented zeros. Foreground clocks recompute
completion and Dhaka day/week boundaries even when no database document changes.

### Synchronization and consistency limits

`DashboardMirror` is a foreground/short-lived client projection, not a backend service. It reserves
a monotonic RTDB revision in a transaction **before** a fresh `Source.SERVER` Firestore read, then
publishes only if that reservation is still current. Competing publishers and stale async callbacks
cannot overwrite a newer reservation/publication. Rules additionally reject revision rollback,
same-revision payload mutation, stale publication, wrong periods and inconsistent aggregate totals.
Equivalent recent projections skip writes; concurrent refreshes coalesce. RTDB transactions suppress
speculative local events. A 20-second publication deadline invalidates late work; auth/lifecycle
guards prevent later callbacks from writing under a changed account.

Booking/cancellation success still depends solely on the existing Firestore transaction and server
reconciliation. The retained best-effort mirror hook separately starts a bounded projection refresh:
a Patient updates only their private pointer, an Admin only global analytics. Dashboard load/resume,
live Firestore changes and freshness clocks reconcile missed writes. An Admin cannot write another
Patient's dashboard pointer; its owner repairs it on the next active dashboard session.

There is NO cross-database atomic commit, Cloud Function, paid service, guaranteed background update
or always-running server. Without an active trusted Admin, global RTDB counts can lag patient writes;
the Admin dashboard's Firestore listener catches up and reconciles. RTDB Rules cannot prove a
Firestore booking or the truth of an Admin count. A Patient can fabricate only their own private
pointer, which never grants booking access and is rejected by app verification. Admin projections
are not more authoritative than Firestore. Device clock errors can conservatively force fallback;
server-time writes and Rules enforce publication dates and timestamps.
Authoritative verification keeps the scoped Firestore listeners active; RTDB does not eliminate
Firestore reads or guarantee a reduction in billed reads.

### Database/Dashboard Console setup — reported completed; reference only

The user reports these steps were manually completed and physically validated. Retain them for
environment recreation only; they are not new Batch 3 publication instructions.

Project: **medibook-2c3a4**.

1. Firestore → Rules: publish the **complete** local `firestore.rules`. This retires in-app history
   access; profile, doctor, appointment, slot/serial and emergency-contact rules remain present.
2. Realtime Database → the configured existing instance → Rules: publish the **complete** local
   `database.rules.json`. It adds the private/global dashboards, denies obsolete announcements, and
   retains every existing `display` branch. Never grant reads/writes at root or `dashboards` parents.
3. Firestore → Indexes → Composite → Create: collection **appointments**, query scope **Collection**,
    fields **patientUid Ascending**, **status Ascending**, **startAt Ascending**. Wait until Enabled.
    The full definition is `firestore.indexes.json`, referenced by local Firebase configurations.
    Retain all existing indexes; this is an addition, not a replacement of the production index set.
   Emulators do not prove production composite-index availability. Until the index is ready, the
   patient card reports inability to verify rather than presenting an incorrect empty state.
4. The Database/Dashboard Revision needs no data cleanup, claims migration, Google sign-in setup,
   paid service or appointment test mutation. Batch 3 OAuth setup is documented separately above.
   Do not delete archived announcement/notification data as part of setup.

### Verification and remaining development

Final automated pass: **2026-11-09 — successful**. Tests use only owned loopback Firestore/RTDB
emulators and synthetic `demo-medibook` users, never production data.

| Verification | Final result |
| --- | --- |
| App unit tests | 509 tests / 49 classes; 0 failures/errors, 0 skipped |
| Firestore Emulator security/query tests | 152 tests / 9 classes; 0 failures/errors, 0 skipped |
| RTDB Emulator security/CAS/realtime tests | 33 tests / 2 classes; 0 failures/errors, 0 skipped |
| `:app:assembleDebug` | Successful; APK: `app/build/outputs/apk/debug/app-debug.apk` |
| `:app:lintDebug` | Successful; 0 errors, 15 warnings; no unused-resource warnings |
| `git diff --check` | Passed |
| Removed-feature cleanup | No retired-feature references in `app/src`; built APK has no retired classes/resources |

The final combined command was:

```powershell
.\gradlew.bat :app:testDebugUnitTest :firestore-rules-tests:test :rtdb-rules-tests:test :app:assembleDebug :app:lintDebug --continue --console=plain
```

All **694 tests** passed. The 15 non-fatal lint warnings are two older-API accessibility attributes,
seven dependency-update notices, one nested-weight layout warning, three overdraw warnings and two
XML/PNG launcher-icon warnings. Reports are under `app/build/reports/lint-results-debug.html` and
each module's `build/test-results` directory. The APK scan also confirmed the appointment reminder
worker, notification helper and alarm/dismiss/system receivers remain packaged.

`adb devices` found **no attached physical device**; build/emulator results are not device sign-off.

Historical pre-device checklist (subsequently reported passed by the user; retain for regression):
index-ready first load; multiple future bookings; cancellation
promoting the next record; slot sidecar lookup/reuse; offline/reconnect and denied RTDB fallback;
two-account isolation; Admin counts versus the unchanged chart; completion, Dhaka midnight and
Saturday rollover; competing Admin sessions; rotation/resume; native contact picker and local
24h/6h/custom AM/PM reminders with 30-second sound/vibration and early Dismiss.

Google Authentication is implemented in the Batch 3 continuation linked above; its Console OAuth
configuration and real Google/device sign-off remain pending. The 694-test results above belong to
the Database/Dashboard Revision only. Existing password authentication and Admin custom-claim
authorization remain preserved. No commits, pushes, resets, stashes, cleans, deployments or
production data changes were performed by either local implementation.

---

## Archived Batch 2 history (not current instructions)

**Everything below describes removed features and historical investigations. It is retained only as
project history. Its announcement/Notification Center publication and device steps are obsolete;
use the current manual setup and checklist above instead.**

# Batch 2 handoff — announcements, notifications, analytics (archived)

Status: implemented and verified locally on 2026-11-08. **Nothing is committed, pushed or deployed.**
No Firebase rules, production data or claims were changed by this session; the one manual step is the
Firestore Rules republication in section 6.

## 1. Hospital announcements — root cause and fixes

The RTDB rules were **not** the cause. The app's exact Admin write (create `publishedAnnouncements/<id>`
plus a sibling delete under `draftAnnouncements/`) was reproduced against the database emulator and is
accepted (`AnnouncementRulesTest.androidClientCreatePayloadWithSiblingDeleteIsAccepted`). The real
failure was invisible state:

- RTDB queues writes while offline, so the completion listener could stay silent forever: there was no
  timeout, no `.info/connected` check, and one generic message for unconfigured / permission / denial /
  network.
- The shared announcement session verification set `session = null` during a quiet revalidation, so a
  click on an already-visible form returned silently.
- The editor showed no progress and no result on success, so a working write looked like nothing happened.

`AnnouncementRepository` now exposes a typed `Error` model (NONE, UNCONFIGURED, NOT_ADMIN, PERMISSION,
NETWORK, TIMEOUT, INVALID, OFFLINE), probes `.info/connected` for 5 s, bounds each write to 20 s, keeps
stable IDs, moves a record between branches atomically only when editing, and preserves `publishedAt`
for an edit that stays published. Editor/list/detail map errors to specific text, show progress,
connection notices, retry and confirmation snackbars; entered text survives a failed save and rotation.
Draft/Published states are visually distinct.

Admin lifecycle: create, save draft, publish, list (draft + published), edit, unpublish, republish,
delete with confirmation. Patient: newest-first bounded list (3 on the dashboard, 20 per page with
View All), detail screen, live updates, Asia/Dhaka times, drafts never readable, empty/error states.

## 2. Firestore Notification Center

**Architecture.** `users/{uid}/notifications/{type_referenceId}` only — Cloud Firestore, never RTDB, and
no Cloud Function. Each signed-in client derives its own events from data it may already read and
creates only missing documents:

| Source | Patient | Admin |
| --- | --- | --- |
| Appointment documents | own bookings (`list(owner, false)`) | bounded 20 most recently updated |
| Announcement branch | public `publishedAnnouncements`, bounded 20 | — |

`NotificationPlanner` is pure and derives `booking` (Confirmed record), `cancellation` (persisted
Cancelled status) and `completion` (existing time-derived state; event time is the scheduled `endAt`).
Drafts are never planned. The document ID is `<type>_<referenceId>`, so reconciliation is idempotent;
a rejected create is verified with a server read before it is treated as a failure. Local reminder
alarms are **not** added to Firestore history.

**UI.** Bell + unread badge on both dashboards (Patient toolbar action view, Admin dashboard row),
`NotificationsActivity` with newest-first rows, read/unread distinction, tap-to-open the existing
Appointment Details or Announcement Detail screen, Mark all as read, and loading/empty/error states.
The badge is one bounded `read == false` query (≤20 documents), never a scan of appointment history.

**Security.** The collection path itself isolates accounts. Rules allow only the owner to `get`/`list`/
`create`, allow only `read: false → true` with `readAt == request.time` afterwards, and deny deletes.
A create must contain exactly `type, referenceId, title, body, createdAt, read`, with
`createdAt == request.time`, plain single-line bounded text, and an ID equal to `type_referenceId`.
Appointment-backed types additionally require the referenced appointment to exist and agree:
`booking` ⇒ `status == 'Confirmed'`, `cancellation` ⇒ `'Cancelled'`, `completion` ⇒ `'Confirmed'` and
`endAt <= request.time`; the appointment must belong to the caller unless the token has `admin=true`.
A patient therefore cannot write into an Admin collection, forge a status, or reference a foreign
appointment. `announcement` events are accepted only for non-Admin tokens in the caller's own
collection (Firestore Rules cannot read RTDB) — see limitations.

## 3. Admin weekly analytics

`WeeklyAppointmentStats` builds exactly seven Dhaka day buckets starting at the Saturday midnight of the
current week; `WeeklyAnalyticsRepository` fetches only that bounded `appointmentDate` range in one query
(`whereGreaterThanOrEqualTo` / `whereLessThan` on Dhaka midnights) and reuses it for the chart and both
totals. Counts come from the scheduled date, never the booking time; Confirmed and already-finished
appointments are included, Cancelled is excluded at every instant; all doctors count. Cached data is
shown first (`MetadataChanges.INCLUDE`) and refreshed quietly; the listener re-subscribes when the week
rolls over at Dhaka midnight. `WeeklyBarChartView` draws the seven bars in plain Canvas — no charting
dependency. Failure shows a message plus Retry; zero counts show a clean empty state; counts are never
hardcoded.

## 4. Removed dashboard components and preserved RTDB dependencies

- Removed the obsolete visible RTDB live-status / booking-alert card from `activity_admin_dashboard.xml`
  and `item_patient_directory_header.xml`, with their dashboard/adapter code (`startLive`, `stopLive`,
  live-display rendering, retry). No `liveUpdates`, `view_live_updates`, `startLive(` or `stopLive(`
  references remain in `app/src/main`.
- Preserved: Firestore remains the only booking store; atomic reservation/release, retained history,
  fixed serial numbers and slot reuse, both cancellation flows, and the hidden best-effort RTDB mirror
  (`RealtimeDisplayRepository.publishBestEffort`) still called from booking and cancellation. No RTDB
  task gates booking success.
- Consequence to be aware of: the visible cards are gone, so no Batch 2 screen renders the RTDB display
  branches and the Admin dashboard's former 20-record display bridge listener was removed with its card.
  The mirror keeps recording the acting client's own writes for inspection, not for UI.

## 5. Verification results (actual)

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest` | **513 tests, 0 failures** (23 new: planner ×8, weekly stats ×8, notification model ×7) |
| `:firestore-rules-tests:test` | **155 tests, 0 failures** — includes new `NotificationRulesTest` ×7 |
| `:rtdb-rules-tests:test` | **26 tests, 0 failures** (`AnnouncementRulesTest` ×8 incl. the payload probe, `RealtimeRulesTest` ×18) |
| `:app:assembleDebug` | BUILD SUCCESSFUL |
| `:app:lintDebug` | **0 errors, 27 warnings** |
| `git diff --check` | clean |

Run everything with:

```powershell
.\gradlew.bat :app:testDebugUnitTest :firestore-rules-tests:test :rtdb-rules-tests:test :app:assembleDebug :app:lintDebug --console=plain
```

Firebase tests use only the owned loopback emulators and the synthetic `demo-medibook` project. No device
or emulator UI run was performed for Batch 2, so no on-device behaviour is claimed.

## 6. Manual requirement — publish updated Firestore Rules

1. Firebase Console → **medibook-2c3a4** → Firestore → Rules: paste and publish the **entire** local
   `firestore.rules`. It gains `appointmentBackedNotification`, `validNotificationEvent` and
   `match /users/{uid}/notifications/{notificationId}`. Until published, notification creation fails
   with permission-denied (the UI shows the error rather than a false success).
2. `database.rules.json` is **unchanged** in Batch 2 — no RTDB republication is needed. The already
   published announcement rules are correct and emulator-verified.
3. No new Firestore composite indexes are required: every new query is single-field (`createdAt` desc,
   `read == false`, `appointmentDate` range, `updatedAt` desc on a bounded limit) or a per-document get.
4. The Admin `admin=true` custom claim already in use is unchanged; no new claims or secrets.

## 7. Physical-device checklist (not yet performed)

1. Admin → Announcements: create, save draft, publish; confirm the progress indicator, success snackbar
   and the Published/Draft chip. Repeat while offline and after denying the write (network error text,
   entered text retained, retry works).
2. Admin: edit a published notice (patients see the new text), unpublish and republish it, delete it
   after confirmation. Patient dashboard and View All update without a restart; no draft ever appears.
3. Patient: open the bell, confirm badge count, tap a row (opens the correct appointment or notice and
   marks it read), Mark all as read, confirm the badge clears.
4. Log out and sign in as the other account: no previous account's notifications are visible, and the
   badge reflects only the new account.
5. Book an appointment and cancel another (as Patient, then as Admin): confirm one notification per
   event, no duplicates after repeated resumes, and correct behaviour after rotation and a cold start.
6. Admin dashboard: compare Today / This week / chart against Firestore `appointmentDate` values for a
   real Saturday–Friday week, including a Cancelled exclusion and a completed past appointment.
7. Re-check regressions: login/roles, directory search/filter, booking with serial numbers, both
   cancellations, emergency contacts, reminders, air quality and the 11-card wellness carousel.

## 8. Limitations and security notes

- **No guaranteed background delivery.** Without Cloud Functions or a backend on the Spark plan, events
  are materialised when a signed-in client runs. A completion notification appears the next time the app
  is used after the scheduled end time, and an account that never opens the app receives nothing.
- **Admin history is bounded** to the 20 most recently updated appointments, so older bookings are not
  turned into notifications. Patients see their own complete appointment list.
- **Announcement authenticity is not rules-verifiable.** Firestore Rules cannot read the RTDB branch;
  they permit an `announcement` event only in the caller's own collection for non-Admin tokens, and the
  app verifies published state before rendering or opening, hiding unpublished/deleted references. A
  patient could still manufacture a notice-shaped row in their own history; it can never affect another
  account or grant access to a draft.
- **Badge caps** at 9+ and its query is bounded to 20 unread documents.
- Rules are not rate limits: notification writes are per-user and bounded by the client, but Spark quotas
  and unbounded stored history still need manual monitoring. Nothing is deleted automatically.
- The RTDB mirror is best-effort and non-authoritative; Firestore appointment documents remain the single
  source of truth for bookings, analytics and notifications.

## Remaining Batch 3 scope

The Batch 3 Android lab features (contact picker, system notices, reminders, air quality) are implemented
as described in [batch3-android-lab.md](batch3-android-lab.md); Batch 2 added none of them. What remains
there is manual confirmation, not new code: supplying a verified hospital number in
`batch3_strings.xml`, a real allow/deny plus reminder-delivery run on Android 13+, genuine Airplane
Mode/Battery Low events, and the full native regression sign-off listed in that document. Guaranteed
cross-user/background notification delivery would require a trusted backend and is deliberately out of
scope for Batches 2 and 3.

---

# Batch 2.1 — runtime bug fix session (2026-11-08, new session)

Status: fixed locally, **not committed, not deployed**, no rules changed. A physical device could not be
attached during this session (`adb devices` returned no device), so on-device verification of both
workflows is still pending; every claim below is code/test based and is labelled as such.

## A. "Signing you in…" after Save draft / Publish

`checking_session` (`Signing you in…`) is rendered only by `@layout/view_patient_session_loading`, the
Admin dashboard loading card and the launcher router. Tracing the Admin flow
(`AdminDashboard → AnnouncementsActivity → AnnouncementEditorActivity`) showed the announcement write
itself is never the trigger: a rejected or unanswered RTDB write reports a specific snackbar and stays on
the editor. The screen was replaced by the session loader through the **session gate**:

1. `AuthRepository` publishes `State(loading = true)` from `beginOperation()` whenever a screen starts
   while the in-memory verification is absent, and `AppointmentSessionActivity`/the dashboard render
   that as `Signing you in…`. `AnnouncementsActivity`/`AnnouncementEditorActivity` call `checkSession()`
   on **every** `onStart`, and the previous screen's `onStop` clears `session`, so any cold in-memory
   state after a save drops the Admin straight onto the loader.
2. A *quiet* revalidation failure used to be tolerated only for `NETWORK/UNKNOWN/TOO_MANY_REQUESTS`.
   Any other outcome — `PERMISSION_DENIED` while the newly published rules were still propagating, an
   unreadable profile (`PROFILE_MISSING`/`INVALID_PROFILE`) — signed the Admin out and
   `AppointmentSessionActivity` answered with `AuthNavigation.openDestination(..., clearTask = true)`.
   That rebuilds the task and lands on the Admin dashboard/login, which then cold-verifies and shows
   `Signing you in…`: the form is gone and the write looks like it never happened.
3. A verification that never answered (offline `user.reload()`/token refresh/`users/{uid}` server read)
   left the published state `loading` with no terminal publish, i.e. a permanent loader.
4. The editor's inputs use `saveEnabled="false"` and had no instance-state backup, so a process kill
   during that window silently discarded the typed notice.

Fixes: `AuthRepository.revalidationKeepsSession(...)` keeps a **verified** session through quiet
revalidation except for `INVALID_CREDENTIALS / SESSION_EXPIRED / PROFILE_MISSING / INVALID_PROFILE /
ROLE_MISMATCH` (the server still authorizes every operation, so a kept screen grants nothing); the
verified state is re-published so no listener is left on a null user; every verification now has a
20-second watchdog that can never leave `loading` stranded; `AppointmentSessionActivity` latches
`verifiedOnce` so a verified screen never falls back to the loader and never redirects while the same
Firebase account is still signed in; the Admin dashboard no longer flashes its loader on a warm resume;
the editor restores the typed title/body from instance state; an accepted write is confirmed and the
editor closed even if the session re-verified while the request was in flight; the announcement list
confirms a returned save without requiring `canAccess()` at that instant; and a new notice reuses one
stable id across a retry (`AnnouncementEditorViewModel.newId`), so a lost acknowledgement cannot create a
duplicate draft/publication.

## B. Empty Notification Center

Root cause candidates were reduced to the reconciliation pipeline, and two real defects were found:

1. **Planner/rules mismatch (deterministic).** `NotificationPlanner` emitted a `booking` event for every
   appointment, including `Cancelled` ones, while `firestore.rules` requires
   `booking ⇒ status == 'Confirmed'`. One cancelled appointment in the source page therefore produced a
   permanently denied create, and `NotificationRepository.createMissing` failed the **whole** pass
   (`whenAllSuccess` + throw) as soon as one document was rejected. Both accounts in this project have
   cancellations (analytics testing), so reconciliation reported a failure on every run.
2. **Failure rendered as "No notifications yet."** `NotificationsActivity.sync()` wrote the error into
   the shared message view and then immediately called `render()`, which only knew about the feed
   error — so a permission/network/rejection failure was overwritten with the empty-state text. An
   empty history and a broken pipeline were indistinguishable on screen (and the Retry button was
   hidden).

Fixes: the planner plans `cancellation` only for `Cancelled` and `booking`/`completion` only for
`Confirmed` (exactly what the rules accept); `createMissing` now reports per-event outcomes
(`CreateResult{created, rejected, error}`), keeps accepted documents and never hides them behind one
rejection; the announcement read used for patient events is bounded to 8 s so an unreachable RTDB cannot
block appointment events; `NotificationsActivity` tracks `syncError` separately from the feed error and
shows a specific message plus Retry (`notifications_permission` now names the rules-publication step).
No Firestore rule was weakened or changed.

## Diagnostics (debug builds only)

`utils/Diagnostics` gains `event(area, detail)` gated by `ApplicationInfo.FLAG_DEBUGGABLE`; the existing
`failure(...)` now logs a stack trace only in a debug build. Emitted events (counts/enum names only — no
tokens, emails, phones, UIDs or notification text):

- `Auth: verify quiet=<bool> registration=<bool>`, `Auth: verified role=… adminClaim=…`,
  `Auth: verification failed <ERROR> quiet=<bool>`, `Auth: verification timed out`.
- `Announcements: publish pressed …`, `Announcements: write dispatch <KIND> …`,
  `Announcements: admin write preflight root=<bool> signedIn=<bool>`,
  `Announcements: database write acknowledged|rejected <ERROR>|unanswered`, `Announcements: write accepted|failed <ERROR>`,
  `Announcements: feed rows=… published=… error=…`.
- `Notifications: source appointments=… announcements=… planned=… admin=…`,
  `Notifications: existing=… missing=…`, `Notifications: created=… rejected=… first=…`,
  `Notifications: feed rows=… cached=… error=…`, `Notifications: sync created=… error=…`.

Filter Logcat by tag `MediBook` on a debug build.

## Verification actually run in this session

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest` | **526 tests, 0 failures** (13 new: `AnnouncementActionTest` ×4, `AuthSessionPolicyTest` ×3, `NotificationCreateResultTest` ×4, planner ×2) |
| `:firestore-rules-tests:test` | **155 tests, 0 failures** (emulators, `demo-medibook`) |
| `:rtdb-rules-tests:test` | **26 tests, 0 failures** |
| `:app:assembleDebug` | BUILD SUCCESSFUL |
| `:app:lintDebug` | **0 errors, 27 warnings** |
| `git diff --check` | clean |

No device or emulator UI run: `adb devices` listed no attached device, so neither workflow is claimed as
fixed on hardware.

## Physical-device retest for these two bugs

1. Admin → Manage announcements → New: type a title/body, press **Save draft**. Expect the progress bar,
   then the editor closes and the list shows the draft chip with a "Draft saved." snackbar. **No
   "Signing you in…" screen at any point.**
2. Open that draft → **Publish** → Published chip + "Announcement published."; Patient dashboard/View All
   shows it within seconds; a Patient tapping it opens the detail.
3. Edit a published notice → **Save changes** (patients see the new text) → **Unpublish** (it becomes a
   draft and disappears for patients) → **Publish** again → **Delete** with confirmation.
4. Repeat 1 while the phone is in Airplane Mode: expect "Not connected. A change is sent when the
   connection returns." (or the timeout text), the typed text retained, and a retry that does not create
   two notices.
5. Patient: bell → Notifications is either populated or shows an explicit error + Retry (never a silent
   empty list). Book one appointment and cancel another as that Patient, reopen Notifications: one
   "Appointment booked" per confirmed booking and one "Appointment cancelled" per cancellation, no
   duplicates after repeated resumes, badge matches the unread rows.
6. Admin: bell -> recent bookings/cancellations appear with patient names; tap one -> the Admin
   Appointment Details screen; Mark all as read clears the badge.
7. Account isolation: sign out, sign in as the other account; no rows from the previous account.
8. With a debug build attached: `adb logcat -s MediBook` during steps 1-6 shows the `Announcements:` and
   `Notifications:` events above. If notification events report `rejected=1 first=PERMISSION`, the
   Firestore Rules published in the Console do not contain the notifications block yet.
