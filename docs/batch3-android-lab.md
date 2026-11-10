# Batch 3 — Android lab features

## Navigation and configuration

- **Patient Home → Call Hospital:** `ACTION_DIAL` only. Configure a **verified** number in
  `app/src/main/res/values/batch3_strings.xml`, `hospital_phone_number`. It is intentionally blank;
  the shipped behavior is “Hospital contact number is not available.” No invented hospital number.
- **Patient Home → Emergency Contact:** choose/change a phone row using Android's system picker,
  view the selected name/number, optionally dial or remove it.
- **Patient Home:** subtle Airplane Mode banner; brief Battery Low snackbar. OFF removes the banner.
  Airplane Mode is not treated as a test of internet access: Wi-Fi can still be connected.
- **Patient Home → My Appointments → Enable reminders:** contextual explanation, then Android 13+
  notification permission if needed. “Reminders on · Manage” turns this device's reminders off.
  Denial does not affect booking, cancellation or reading appointments. System-disabled channels
  can be enabled using the offered Android notification-settings action.
- **Patient Home → Health & Air Quality:** predefined **Khulna, Bangladesh**, not phone location.
  Scroll the city, freshness/timestamps, attribution and four measurement cards; Retry / Refresh
  bypasses the cache. Header and metrics use a RecyclerView/Adapter/ViewHolder in one scroll.

Existing Directory, Search/Filter, My Appointments, Live Updates and both cancellation flows remain.
New screens use the existing online verified-Patient session gate. No new Admin features.

## APIs, dependencies and permissions

- Java/XML/View Binding/Material; `Intent.ACTION_DIAL`, Activity Result `ACTION_PICK` with
  `ContactsContract.CommonDataKinds.Phone.CONTENT_URI`, `ContentResolver.query`, try-with-resources
  `Cursor`, `SharedPreferences.MODE_PRIVATE`.
- Context-registered `BroadcastReceiver` for `ACTION_AIRPLANE_MODE_CHANGED`, `ACTION_BATTERY_LOW`
  and recovery `ACTION_BATTERY_OKAY`; initial Settings/sticky battery state, retained deduplication,
  one registration in `onStart`, unregister in `onStop`. No manifest receiver/service for these.
- AndroidX **WorkManager 2.12.0**, unique one-time work with a network constraint and exponential
  retry; `NotificationChannel`, `NotificationCompat`, immutable `PendingIntent`.
- `HttpsURLConnection` GET, platform `JSONObject`, background executors and lifecycle ViewModels.
  `org.json:json:20260814` is **test-only**, to exercise the platform JSON API on the JVM.
- Added explicit `POST_NOTIFICATIONS`; existing `INTERNET` retained. Permission requested only
  through the reminder button. **No CALL_PHONE, READ_CONTACTS, location, exact-alarm permission,
  FCM, custom foreground service or paid backend.** WorkManager merges its normal scheduling
  permissions/receivers (network state, wake lock, boot completed, protected job service).

## Contact privacy and account boundaries

Only the selected display name and phone number are stored; no address book enumeration/upload,
contact ID, URI, photo, persistable URI permission, Firestore or RTDB contact write. Only a recognized
Contacts content URI is queried. Cancelled/null/invalid results, absent rows/columns/numbers and
revoked/provider access failures preserve the old contact without a crash. Provider I/O is off-main.

`patient_private.xml` is private app storage and excluded from **cloud backup and device transfer**
(both legacy and Android 12+ rules). It also holds the current account UID, a random login epoch,
device-only reminder consent and minimal reminder records (IDs/times/states, no appointment names).
Actual Firebase UID transitions/logout clear it, pending work and posted reminder notifications.
An auth repository loading state is **not** logout. Asynchronous picker results/work from an old
epoch cannot write/show data, even after the same UID logs back in. Contact views do not save PII
in Activity view-state bundles and are cleared when leaving the protected screen.

## Reminder policy and limits

- Reminders are opt-in **per current account/device login**, not part of booking success.
- Only own, future **Confirmed** appointments; no persisted Completed status is introduced.
- Default due instant: start minus 24 hours. If that is less than 5 minutes away, use start minus
  one hour; if still too near, use halfway through the remaining interval. New bookings with less
  than 20 minutes remaining have no reminder. This avoids immediate near-booking alerts.
- UTC epoch math uses the existing Firestore `startAt`; notification date/time uses **Asia/Dhaka**.
  It gives an explicit day/date/time, not a potentially incorrect “tomorrow” claim across midnight.
- Stable due time and unique work with `KEEP` avoid refresh duplicates/postponement. Sent records
  enforce at-most-once delivery locally. Cancelled tombstones cannot be reversed by an older
  overlapping Confirmed read. Toggling retains these records; logout destroys the entire epoch.
- Existing authoritative repository **reads** feed best-effort scheduling/cancellation outside
  transactions. Verified login/restart and own appointment list refresh reconcile server data.
  Failed queries never mean empty. Missing IDs from an overlapping query are not inferred deleted;
  workers suppress actual missing documents at delivery.
- A worker checks UID/epoch/consent, permission, due/deadline, then reads the appointment from
  **Firestore `Source.SERVER`** with a 25-second bound. It rejects cached/pending/foreign/cancelled,
  malformed or changed appointments and rechecks local account/ledger immediately before notify.
  Known cancellation cancels work and an already-posted reminder. Patient/Admin writes, locks,
  slot release, immutable serial mapping and security rules are unchanged.
- Connectivity is required, so there is **no unsafe offline fallback** to cached appointment data.
  Transient errors use bounded WorkManager backoff; work is skipped within 5 minutes of start or
  after start. This prefers no reminder over a misleading/late reminder. An unavoidable remote
  cancel-after-server-read race is not an atomic cross-device notification guarantee.
- WorkManager/Doze, battery restrictions, disabled notifications, force-stop, no network and OEM
  background limits can delay/prevent delivery. There is **no exact-to-the-minute guarantee**.
  The default channel respects user settings. Private lock-screen content has a generic public
  version. Notification taps use existing session/role validation **plus owner/epoch validation**.

## Real health API

```text
https://air-quality-api.open-meteo.com/v1/air-quality?latitude=22.8456&longitude=89.5403&current=us_aqi,pm2_5,pm10,uv_index&timezone=Asia%2FDhaka&timeformat=unixtime
```

This actual query was checked and returns `current.time`, `us_aqi`, `pm2_5`, `pm10`, `uv_index`.
Unix seconds are UTC, **not** shifted by `utc_offset_seconds` a second time. Display timezone is
Asia/Dhaka. Values are CAMS **model estimates**, not phone measurements or a hyperlocal sensor.
`Data: Open-Meteo` and CAMS attribution are visible. AQI guidance follows general EPA exposure
categories; UV >=3 uses general sun-protection advice. No diagnosis or treatment recommendation.

10s connection/12s read timeouts, HTTPS-only with redirects refused, 256 KiB response bound and
closed streams. HTTP errors, transport/timeouts, malformed/error JSON, invalid timestamps, null,
non-numeric/out-of-range measurements have separate error/unavailable paths. Missing values are
not fake zeros. A non-personal 15-minute disk cache avoids re-fetching on every navigation; failed
requests preserve it. Every displayed report has both estimate and retrieval timestamps. Old/future
estimates (>3h old or >10m ahead), expired cache and failed-refresh cache are labelled; timestamps
remain visible and a foreground timer refreshes freshness labels. Empty/error/loading/retry states
never use hardcoded success data. The public non-commercial endpoint requires no key or setup.

## Files

- New: `MediBookApplication.java`; `local/` contact selection, provider reader, private store and
  account coordinator; `reminders/` policy/worker/notifications; `system/` state/receiver/ViewModel;
  `airquality/` report/parser/HTTP client/cache; contact/air-quality Activities, ViewModels, adapter;
  four screen/item XML layouts and `batch3_strings.xml`; five JVM test classes; this document.
- Modified: `AuthRepository`, `AppointmentRepository` (post-read hooks only),
  `AppointmentDetailsActivity`, `AppointmentsActivity`, `PatientHomeActivity`,
  `PatientHomeHeaderAdapter`, dashboard/history layouts, manifest, both backup XMLs,
  `app/build.gradle.kts`, `gradle/libs.versions.toml`.

## Verification and remaining manual checks

### Verified results (2026-11-07)

- **424 Android JVM tests / 38 classes** (66 new tests), **97 Firestore emulator tests / 6 classes**,
  **18 RTDB emulator tests / 1 class**: zero failures, errors or skips. The combined Firebase run
  passed; subsequent Android runs also passed after the final hardening/UI changes.
- `:app:assembleDebug` and `:app:lintDebug` passed. Lint: **0 errors / 38 warnings**, matching the
  baseline warning count; no new lint suppression or baseline file was added.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, **14,465,501 bytes**;
  SHA-256 `CFE40A6CEF988484584C0C86603AB540F5C7BD76A79DB58BF45F6213C9D81EA4`.
- The **actual Java HTTP client and parser** also completed a real public HTTPS GET on the host
  (not merely a mocked response). On-device networking was then verified separately below.
- Physical **CPH2719**, Android **API 36**, serial `GMXCW8UC69AUQCO7`: reconnected/authorized;
  installed using **`adb install -r`**, without wiping app data, and cold-launched successfully.
- Observed verified Patient Home, Directory/Search/Filter controls and My Appointments navigation;
  Call Hospital's intended unconfigured-number message; native Google Contacts phone-picker launch
  and a populated emergency-contact card. The private preference file contained selected name/phone
  fields, **not URI/photo**; no contact values are recorded in this document. The selection was left
  untouched. READ_CONTACTS/CALL_PHONE are absent from the installed package.
- Observed **real on-device AQI/PM2.5/PM10/UV values**, exposure guidance and source/timestamps;
  successful Retry / Refresh, then labelled cached data on re-entry. No mock API success UI.
- Opened the contextual reminder explanation and cancelled it, preserving the existing denied/
  unrequested POST_NOTIFICATIONS state. **No runtime allow/deny or real notification-delivery claim.**
- Runtime system-broadcast registration was present on Home and absent away from Home. **Actual
  Airplane Mode/Battery Low events were not generated**; personal connectivity/power state was not
  changed. No new AndroidRuntime error lines were observed for the tested app process.
- No calls, production appointment writes, account switching/logout or notification consent changes
  were performed by the device automation. Temporary UI-hierarchy test files were removed; no
  screenshots were created. Native edge cases below are still manual, not implied passed.
- Firebase rules/indexes and booking/cancellation transaction bodies are unchanged. Git remains
  `main` at `3e127f9ed84cfeb089af1fceb2578551cea2278d`: **14 modified / 34 new files**, unstaged,
  uncommitted and unpushed. `git diff --check` passes.

### Commands and manual sign-off

```powershell
.\gradlew.bat :app:testDebugUnitTest :firestore-rules-tests:test :rtdb-rules-tests:test :app:assembleDebug :app:lintDebug --console=plain
```

JVM coverage: picker success/cancel/null/URI/number/revocation boundaries, local account/restart/
logout isolation, independent/deduplicated broadcast state, reminder timing/Dhaka midnight/
cancellation/duplicate/sent/stale work/account switching, real-shape JSON/null/empty/error/HTTP/
timeouts/oversize/closed-body/cache freshness. These are not claims of native-system UI delivery.
Firebase regressions use only existing Java emulator harnesses and demo data, never production.

Remaining manual/native checklist (including the regression pass, not all performed):

1. Supply a verified hospital number, then confirm a native dialer (not an automatic call); test no
   number/no dialer. Remove the demo setting if it is not a real hospital number.
2. Verify additional phone rows (including multiple-number contacts); change/remove/cancel, revoke
   access/rotate, and log out/switch accounts while picker is open. No broad contacts permission.
3. Turn Airplane Mode on/off (with Wi-Fi both on/off); warning clears, no offline assumption.
   Observe a genuine battery-low/recovery event and no repeated unchanged-state snackbars.
4. On Android 13+, allow/deny notifications contextually. Book a future eligible appointment,
   inspect scheduled work, let a real reminder run, tap it, cancel as Patient and as Admin, then
   test stale taps/account switching, restart, disabled channel and OEM battery restrictions.
5. Confirm actual model values/timestamps on the device; Retry with/without connectivity, navigate
   back for cached data, and test unavailable/malformed cases using the JVM tests.
6. Recheck existing login/roles/directory/booking/slot serial/history/RTDB alerts and both cancellation
   flows. Do not alter production records merely for automated device testing.

No rules deployments, production migration, commit/push, screenshots, DOCX, video or Batch 4 work.
