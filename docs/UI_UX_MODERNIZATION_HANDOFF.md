# MediBook — UI/UX modernization and quality handoff

## Status and scope

**Implementation and automated quality verification are complete. Physical-device/TalkBack,
authenticated end-to-end navigation and Android Profiler acceptance remain pending.**

Implementation follows the historical UI audit and the 14-item Cross-Screen Component Consistency
Audit in [the existing report](ui-professionalization-batch25.md#cross-screen-component-consistency-audit).
Both sections were read before application changes. No separate second audit document was found.
The audit's planning-only restrictions describe that earlier task; the subsequent user instruction
authorizes this implementation. The earlier audit/history has not been replaced.

The app remains Java/XML/View Binding/Material 1.10.0, light theme, `sans`/Roboto and the existing
medical blue/teal/white identity. There is no Compose migration, new dependency, feature redesign,
Firebase deployment, production-data/account operation or Git commit/push/reset/stash/clean.

### Verified backup and preservation

Before substantial edits, **377 project inputs** were archived and every archive entry SHA-256
verified against its source:

`C:\Users\user\AppData\Local\Temp\opencode\MediBook-ui-modernization-aa6b8473156048e7b79428dcd0821e19\project-inputs.zip`

The adjacent `baseline.json` and `git-status-before.txt` distinguish this task from the extensive
pre-existing uncommitted work. Build/cache/Git metadata are excluded. The archive contains private
local configuration: keep it local; do not upload or commit it. No pre-existing file is deleted.

## 1. Unified design system

- **Palette:** existing blue `#1565C0`, bright teal `#00BFA5`, dark teal `#005C59`, white,
  main text `#163340`, supporting text `#526671` and outline `#73888F` retained. Success aliases
  existing teal; warning uses coordinated dark amber/pale amber; cancellation/error uses existing
  red plus a light red container. Status is always written in text, not conveyed only by color.
- **Geometry:** named spacing/gutter/content/radius/touch/progress dimensions in `values/dimens.xml`.
  Existing editable-input/Login dimensions retain their values. Outlined controls use 12dp corners;
  auth/list cards retain 24dp, feature cards 16dp, zero elevation and quiet borders. Original
  wellness 18dp shape/artwork remains unchanged. No blanket padding/font-padding hack or shadows.
- **Typography:** reusable section/card-title/body-strong/label/error roles supplement the existing
  Heading and Body styles. Admin doctor/schedule titles now match the patient/list card hierarchy;
  fee and slot labels have deliberate emphasis. Auth/doctor-profile hero roles remain distinct.
- **Actions:** filled primary for affirmative actions; outlined picker/secondary for choosing values;
  error-colored outlined/text actions and scoped destructive-confirm buttons for cancellation/removal/
  discard. Neutral Cancel/Keep editing remains neutral. Text/Google buttons have disabled-state
  foreground lists; Google's 18dp untinted logo and branded 48dp/24dp-radius variant remain intact.
- **Fields:** editable Material outlined inputs remain the Login reference; non-editable selectors
  use a dedicated exposed-dropdown style. Input types, normalization, helpers/counters, password
  toggles, wire values and read-only account email remain unchanged. Profile IME Next/Done is explicit.
- **Progress:** shared session 24dp/32dp, action 28dp/32dp and page 40dp/48dp indicator/host roles,
  3dp track and explicit zero indicator inset. All circular indicators use these roles. Existing
  linear publication/environment refresh indicators remain appropriate. Adjacent text owns speech.

Not every literal XML dimension is mechanically replaced: existing placement values that already
fit their role are retained to avoid a noisy global reflow. Shared widget styles retain their
existing top margins; newly structured content uses explicit spacing tokens.

## 2. Mandatory Login error change — implemented

For relevant invalid Email/Password credentials, Login's **existing red `statusMessage` TextView**
now displays exactly:

> Unable to sign in. Please check your email and password. You can also try Continue with Google if your account supports Google sign-in.

`AuthErrorMessages.forLoginError()` applies this copy only to `INVALID_CREDENTIALS`; registration
and Google reauthentication/linking retain their original context-specific copy. Wrong password,
unknown email and generic invalid-credential SDK codes deliberately share the same response.
No email/account enumeration or automatic linking promise is introduced; the existing explicit
linking guidance and Continue with Google branding remain visible.

Source inspection found that the old Firebase error mapper grouped almost every Auth exception
under invalid credentials. `AuthFailurePresentation` now distinguishes disabled account,
configuration/provider unavailability, token expiration and other rejected Auth codes for **display**.
Network/rate-limit/Google-specific messages remain separate. New rejected categories retain the
same session denial as their former invalid-credentials category. Authentication calls, credential
exchange, linking, claims, verification deadlines, role routing and authorization are not rewritten.

## 3. Gender root cause and solution

The old generic outlined TextInputLayout applied a Material `editTextStyle` overlay to a
`MaterialAutoCompleteTextView`, without the autocomplete-aware exposed-dropdown parent. Adding an
arrow alone did not install that child's style. Material 1.10.0's AAR confirmed this mismatch.

`Widget.MediBook.ExposedDropdown` now explicitly derives from
`Widget.Material3.TextInputLayout.OutlinedBox.ExposedDropdownMenu`, sharing the existing 12dp
corners/spacing and native state behavior. It is applied to Register/Edit/Complete Profile Gender,
schedule weekday, directory specialization and the migrated appointment-status filter. No spaces
are prepended to values, no popup padding is used to move selected text and no left-margin patch
is applied. All five original selector callbacks/options/wire values are preserved.
The autocomplete-aware overlay also selects a Material-derived child style with the same enabled/
disabled medical text colors as editable fields. This removes the dependency's different default
selected-value foreground without changing internal padding or native dropdown metrics.

## 4. Screen/component coverage

| Screen / component | Implemented improvement / intentional preservation |
| --- | --- |
| Launcher | Consistent page-progress geometry; routing unchanged. |
| Login | Exact mandatory error copy, progress geometry and disabled-button colors; liked overall composition, illustration, card, colors and typography preserved. |
| Registration | Correct exposed Gender dropdown and shared progress; original validation/autofill/IME/password behavior preserved. |
| Complete Profile | Dropdown, profile keyboard actions and operation-appropriate loading label; owner-scoped draft/onboarding gate unchanged. |
| Edit Profile | Dropdown/IME/error feedback; outlined XML password dialog replaces an unstyled programmatic input, preserving password clearing, non-persistence and linking checks. |
| Patient Dashboard | Consistent textual next-status badge, slot emphasis, error/cached severity and adaptive quick-action columns; one directory scroll/cache/owner logic and original carousel unchanged. |
| Doctor directory / Admin cards | Shared card-title/fee/status/secondary-action roles, spacing tokens, progress geometry and coherent density; no new doctor actions or data changes. |
| Doctor Profile | Shared card/action design tokens, consistent section spacing; spacious hero, grouping, schedule data and booking entry retained rather than redesigned for novelty. |
| Doctor Add/Edit | Shared input/action/progress system; existing discard confirmation gets scoped destructive emphasis; validation/reload/write guards untouched. |
| Weekly Schedule editor | Correct weekday dropdown, shared outlined time controls/progress, destructive discard emphasis; existing Material time picker and system 12/24-hour policy preserved. |
| Manage Schedule / Publish Slots | Outlined date selector, explicit session progress and existing linear operation feedback; publication date, serial allocation and retry logic unchanged. |
| Booking | Outlined calendar selector, readable available-times heading/legend, rounded native radio rows with checked glyph + outline/background, selected time/slot summary, clear operation label and duplicate-dialog guard. |
| My/Admin Appointments | Scrollable filter/state header in a ConcatAdapter instead of fixed controls consuming result height; exposed status dropdown with original four values/defaults/saved-index behavior; structured cards/status/slot, error severity and Patient empty-state Find a doctor action. |
| Appointment Details | Selectable doctor/specialty/date/time/slot/fee summary card, textual status badge, authorized patient/reminder groups retained, destructive cancellation styling and confirmation. |
| Emergency Contacts | Existing native phone picker and 48dp name-labelled actions retained; explicit scoped remove confirmation with owner/epoch/recheck guards; error/sync text distinction. |
| Environment & Health | Consistent metric-card title, warning freshness and error feedback; location/permission/estimate/advice/source logic unchanged. |
| Custom reminders | Scrollable dialog, full-width outlined date/time controls, 48dp radio targets, existing preview/error/help and explicit remove confirmation; alarm/notification mechanisms unchanged. |
| Wellness | Eleven image text equivalents transcribed from actual existing artwork; informative accessibility content, same order/images/ratio/non-clickable behavior and eight-second lifecycle timer. |

### Booking/status boundaries

Only server-confirmed **available** inventory is returned by the existing booking API. The legend
explains that booked/unavailable times are not shown. Checked state, not color alone, identifies
selection. There is deliberately no fabricated disabled/booked-slot grid, guessed slot number,
new inventory query or booking-policy change.

Success keeps the existing immediate navigation to My Appointments. A native Snackbar with a
checkmark and doctor/date/slot summary is shown from the destination's **authorized appointment
list**, not an Intent-provided patient snapshot or new query. The Intent contains only a display
hint ID; it cannot authorize a read. Confirmation, transaction/idempotency, failures persisting in
a dialog after availability refresh, durable serials/time fallback and cancelled/completed history
remain intact. The summary can be shown before a serial sidecar resolves and truthfully uses the
existing explicit time-slot fallback in that case.
The pending hint survives same-account initial session verification, but is cleared on account loss/
change and consumed only after a verified own-list returns. No authenticated E2E claim is made.

## 5. Accessibility, responsive layout and motion

- Native Material fields/radios/buttons/dialogs retain focus/checked/error semantics and ripples.
  New headings use AndroidX accessibility support for API 24+, not API-28-only XML attributes.
- Textual status badges distinguish Confirmed/Completed/Cancelled; cached next status is explicitly
  “Checking current status…” rather than presented as verified. Error/warning/success text remains
  readable and accompanied by meaningful copy.
- `AdaptiveColumnsLayout` is a small native LinearLayout measurement variant for metric/action
  pairs: columns stack when actual available width and font scale cannot support readable columns.
  It does not listen to data, animate layout or add dependencies. Existing avatars/long text wrap.
- Appointment filter controls scroll with results without nesting a second vertical scrolling list.
  Reminder dialog content scrolls and its date/time values no longer compete in narrow half-widths.
- Password content remains non-persisted, account email remains read-only and contact action labels
  retain the contact's name. No real patient/credential data is used in review fixtures.
- All eleven wellness descriptions contain the original card title/advice, not invented medical
  recommendations. The original artwork and carousel scheduling/drag/lifecycle behavior are intact.
- Native checked/ripple/dialog/Snackbar transitions provide purposeful feedback. A separate animated
  checkmark, success screen, artificial delay, shimmer, confetti or per-row animation loop was **not**
  added: immediate success navigation and a clear summary are preferable here. No animation library.

System-bar/cutout/IME padding remains owned by the existing `AuthUi`; the chart already supports
scaled text and count descriptions. No global font-padding removal, font shrinking to fit,
chart replacement or root-inset overwrite is used. True TalkBack focus/timing, OEM/system reduced
motion and physical-device interactions still require manual acceptance.

## 6. Performance changes, evidence and limits

1. **Slot selection:** source previously notified every slot; it now notifies only the old/new
   checked rows (at most two). Busy state and selected IDs remain model-controlled. An offline
   adapter observer regression checks notification counts, selected serial label and busy taps.
2. **Appointment list structure:** one recyclable result list plus a single scrolling controls
   header replaces a fixed filter pane. This prevents filters from permanently consuming the
   result viewport at large fonts and keeps result-row recycling. No unbounded nested result list.
3. **Existing infrastructure deliberately retained:** dashboard subscriptions close on stop and
   fence owner/generation callbacks; Patient summary caches are owner-scoped; existing Glide
   requests are View-owned and cleared on recycle; environmental fetch uses its existing executor
   and fresh-cache path; carousel has one retimed lifecycle-controlled Runnable. No evidence justified
   rewriting these systems. Booking/schedule outer-scroll structures and small Admin adapters are
   retained rather than making speculative infrastructure changes.

These are bounded **source-level work reductions/layout fixes**, not measured FPS, cold-start,
memory or Firebase billing improvements. Android Profiler/real-device traces have not established
before/after performance numbers. No additional Firebase listeners/queries, retries, delays,
image assets, large dependencies or loading layers are introduced. The weekly chart's calculations
and Saturday–Friday labels, RTDB reconciliation and reminder mechanics remain unchanged.

## 7. Verification

### Audit-item disposition

| Finding | Implementation / acceptance boundary |
| --- | --- |
| CSC-01 | Material exposed-dropdown parent on every equivalent field; emulator checks editable/autocomplete inset and text-size parity at normal/large fonts. Popup/physical-device checks remain. |
| CSC-02 | Shared outlined form/compact picker entry controls. Existing Material/platform picker families retained deliberately to preserve DOB/Dhaka/AM-PM semantics. |
| CSC-03 | Destructive cancellation/removal/discard styling; existing confirmations retained, explicit contact/custom-reminder removal confirmations added as authorized. |
| CSC-04 | Spinner replaced with exposed status dropdown; original four choices/defaults/filter-index restoration retained; header now scrolls. |
| CSC-05 | Styled XML password content with same clearing/autofill/non-persistence and reauthentication callback. |
| CSC-06 | Shared title/fee/status/action/density roles and adaptive dashboard metric/action pairs; existing spacious versus compact variants retained. |
| CSC-07 | Structured selectable appointment details, clearer list/next status and slot emphasis, existing status/serial policies reused. |
| CSC-08 | All circular indicators use explicit session/action/page geometry; operation-appropriate Complete Profile label; existing linear progress/cached content retained. |
| CSC-09 | Typed error/cached/warning/success presentation; persistent booking-failure dialog and existing field validation retained rather than replacing all feedback with one mechanism. |
| CSC-10 | Edit/Complete Profile IME roles made explicit; existing registration keyboard/autofill behavior retained. |
| CSC-11 | All eleven original artwork titles/advice transcribed and mapped one-to-one for accessibility; no new advice or carousel redesign. |
| CSC-12 | Emulator resource/geometry/font/contrast checks plus windowed fixtures; full physical-device, TalkBack, popup/IME/navigation acceptance remains pending. |
| CSC-13 | Shared dimensions/semantic colors/styles/disabled ColorStateLists added without global margin or Login redesign. |
| CSC-14 | At-most-two row selection updates and single-scroll appointment header; no speculative database/adapter/chart rewrite or unmeasured speed claim. |

### Automated results

Baseline app unit tests passed before application edits. Incremental app unit/build checks passed
after shared components and booking/list changes. Final automated counts/results are recorded below;
physical-device acceptance remains separate.

| Check | Result |
| --- | --- |
| Android unit tests | **560 tests / 54 classes**, zero failures/errors/skips; includes 9 new presentation regressions. |
| Firestore Emulator | **160 tests / 10 classes**, zero failures/errors/skips; local `demo-medibook`, unchanged Rules. |
| RTDB Emulator | **36 tests / 2 classes**, zero failures/errors/skips; local `demo-medibook`, unchanged Rules. |
| Debug APK | Build successful: `app/build/outputs/apk/debug/app-debug.apk`. |
| Android test APK | Build successful: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`. |
| Android lint | **0 errors / 14 warnings**, no unused-resource warnings. |
| `git diff --check` | Passed with the existing Windows LF/CRLF notices; no rules/file normalization performed. |
| Source XML/resource review | All **91 source XML files** parse; all **37 layout definitions** are included in the offline resource matrix. Resource linking and View Binding generation passed in the build. |
| Offline Android UI checks | **13 tests passed** on the read-only API 37 emulator; 444 inflation/measurement combinations plus targeted attached-window form/dialog checks. |
| Release manifest merge | `:app:processReleaseMainManifest` successful; debug-only fixture absent from the merged release manifest. |

The 14 lint warnings are two pre-existing API-28 accessibility XML attributes in Login/Register,
seven dependency-update notices, three pre-existing overdraw warnings and two launcher XML/bitmap
warnings. No dependency upgrade was made to silence them. New headings use AndroidX support.
Reports are under each module's `build/test-results/` and `app/build/reports/lint-results-debug.html`.

Full verification command (PowerShell property argument must be quoted):

```powershell
.\gradlew.bat :app:testDebugUnitTest :firestore-rules-tests:test :rtdb-rules-tests:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug '-Pmedibook.uiTestRunner=com.example.medibook.ui.UiOnlyTestRunner' --continue --no-parallel --max-workers=2 --console=plain
```

This completed successfully. The emulator suites launch/stop their own loopback Java processes and
refuse non-demo/non-loopback targets; no Firebase CLI deployment or real account operation is used.

After installing the two debug APKs on the owned, offline emulator, UI checks run with:

```powershell
adb -s emulator-5580 shell am instrument -w -r -e class com.example.medibook.ui.UiResourceTest com.example.medibook.test/com.example.medibook.ui.UiOnlyTestRunner
```

The first attempted custom runner was not registered because Android Gradle controls the generated
instrumentation manifest. The opt-in runner property fixes that test-harness configuration; the
quoted PowerShell argument above is required. The subsequent installed-runner UI suite passed.
The final installed APK rerun passed **13/13 tests in 12.639 seconds**, including the selected-text
color-parity assertion. The owned read-only `emulator-5580` was then stopped; its underlying AVD,
the private backup and review artifacts were retained. No attached physical device was used.

### Offline Android UI harness

`UiOnlyTestRunner` uses plain `Application`, not `MediBookApplication`/local account services.
An opt-in Gradle property changes **only the instrumentation runner**; the default runner and
production application/main manifest remain unchanged. A non-exported debug-only `UiFixtureActivity`
hosts attached synthetic views/dialogs for windowed checks; it is absent from release builds and
all app navigation, and calls no account/backend/reminder services. The read-only API 37 AVD uses blocked DNS,
airplane mode and disabled mobile/Wi-Fi; no live sign-in/backend operation is performed.

`UiResourceTest` checks all 37 current layout definitions at 320/411/600dp and 100/130/150/200%
fonts (444 inflation/measurement combinations), dropdown insets/text metrics/states, radio targets,
adaptive columns, password field configuration, exact Login copy/wrapping, actual artwork text,
indicator geometry, source color-pair contrast and slot-update behavior. Synthetic XML-rendered
review images are labelled as fixtures, **not authenticated screen screenshots**. Targeted attached
Edit Profile/reminder-dialog fixtures also exercise real windows at 200% font. This is not an
end-to-end navigation/picker-popup test, Android Profiler run or physical TalkBack pass.
Unattached inflation previews do not bind data adapters (for example wellness) or establish settled
floating-label/icon animation placement; attached-window fixtures are used for the targeted form/
dialog checks instead. Unbound placeholder content is not an application-data/visual finding.
The attached Edit Profile capture shows the floating Name/Phone/Gender labels and selected “Male”
aligned normally at 200% font; the custom-reminder capture shows wrapping full-width date/time
controls and stacked native dialog actions. Insets/text-size/color parity is asserted separately
across all three patient form resources. Physical/OEM and popup/keyboard acceptance still remains.
Review fixtures are retained privately beside the backup under `ui-review/`; they contain synthetic
data only, not real credentials/patient records. They are not part of the source-file inventory.

## 8. Functional preservation and remaining device acceptance

No rule/index/Firebase configuration, production account/data, appointment write schema, serial
allocator, schedule policy, completion policy, authorization, network/cache/ViewModel infrastructure
or reminder engine was changed. AuthRepository's narrowly scoped edit is safe error presentation
classification with equivalent rejected-session denial, described above. Existing functions remain:
Email/Password and Google/linking, role routing, profiles, doctor/schedule workflows, search,
transactional booking/cancellation/release/fixed serials, automatic completion, dashboards/analytics,
contacts, calling, Environment & Health, wellness and local reminders.

Physical device acceptance is still required for:

- Both roles: Login exact invalid-credential message, distinguishable network/disabled/configuration
  errors, Google chooser/linking, password dialog clearing, role-aware routes and account switching.
- Name/Phone/Gender selected/empty/focus/error/disabled/popup/keyboard/RTL, normal and 200% fonts.
  Registration Phone already had Next at baseline; the audit's statement that it lacked one was
  inaccurate. This implementation adds the missing **Edit/Complete Profile** IME configuration.
- Long names/qualifications/fees, scrolling filters and selection restoration, metrics stacking,
  compact/landscape/split-window layouts, system bars/cutout/IME and full windowed dialogs.
- Booking confirmation/success/failure, duplicate taps, serial lookup/fallback, cancellation/history/
  release, publication retry and automatic completion. No transaction semantics are relaxed.
- TalkBack traversal/announcements, wellness advice and auto-advance focus stability, chart counts,
  checked/error/disabled cues, contact removal and actual composed contrast. Emulator source-pair
  contrast checks are not a full rendered accessibility certification.
- **Dhaka AM/PM custom reminder dates/times, both modes, default 24h/6h reminders, permission and
  exact-alarm fallback, 30-second sound/vibration and early Dismiss.** No scheduling/audio code changed.
- Real-device smoothness, cold/warm/offline transitions, memory/listener lifetime and system motion
  preferences. Do not infer these from test counts or synthetic XML previews.

Known retained risks: existing Register/Edit DOB picker restoration wiring differs from Complete
Profile; the shared picker/date semantics were not silently rewritten. Full picker lifecycle and
carousel accessibility-focus behavior remain device checks. Optional universal picker migrations,
new booked-slot visualization, global density rewrites and speculative Firebase/adapter refactors
are intentionally not implemented.

## 9. Task-specific file inventory

The final inventory below is relative to the verified task-start backup, **not Git HEAD**. It must
not be confused with earlier uncommitted Batch 2/3 work. Generated build/test/APK/previews and
private backup files are not source deliverables.

**55 modified, 19 added, zero removed: 74 task-specific files.** Prefixes in the grouped inventory
are literal repository directories, not package-name shortcuts.

Modified:

```text
app/build.gradle.kts
app/src/main/java/com/example/medibook/activities/AppointmentDetailsActivity.java
app/src/main/java/com/example/medibook/activities/AppointmentsActivity.java
app/src/main/java/com/example/medibook/activities/admin/AddEditDoctorActivity.java
app/src/main/java/com/example/medibook/activities/admin/AddEditDoctorScheduleActivity.java
app/src/main/java/com/example/medibook/activities/admin/AdminDashboardActivity.java
app/src/main/java/com/example/medibook/activities/auth/CompleteProfileActivity.java
app/src/main/java/com/example/medibook/activities/auth/LoginActivity.java
app/src/main/java/com/example/medibook/activities/patient/AirQualityActivity.java
app/src/main/java/com/example/medibook/activities/patient/BookAppointmentActivity.java
app/src/main/java/com/example/medibook/activities/patient/EditProfileActivity.java
app/src/main/java/com/example/medibook/activities/patient/EmergencyContactActivity.java
app/src/main/java/com/example/medibook/adapters/AirQualityAdapter.java
app/src/main/java/com/example/medibook/adapters/AppointmentAdapter.java
app/src/main/java/com/example/medibook/adapters/AvailableSlotAdapter.java
app/src/main/java/com/example/medibook/adapters/PatientHomeHeaderAdapter.java
app/src/main/java/com/example/medibook/adapters/WellnessCarouselAdapter.java
app/src/main/java/com/example/medibook/repositories/AuthRepository.java
app/src/main/java/com/example/medibook/utils/AuthErrorMessages.java
app/src/main/java/com/example/medibook/wellness/WellnessCards.java
app/src/main/res/layout/activity_add_edit_doctor.xml
app/src/main/res/layout/activity_add_edit_doctor_schedule.xml
app/src/main/res/layout/activity_admin_dashboard.xml
app/src/main/res/layout/activity_appointment_details.xml
app/src/main/res/layout/activity_appointments.xml
app/src/main/res/layout/activity_book_appointment.xml
app/src/main/res/layout/activity_complete_profile.xml
app/src/main/res/layout/activity_doctor_profile.xml
app/src/main/res/layout/activity_edit_profile.xml
app/src/main/res/layout/activity_login.xml
app/src/main/res/layout/activity_main.xml
app/src/main/res/layout/activity_manage_doctor_schedule.xml
app/src/main/res/layout/activity_manage_doctors.xml
app/src/main/res/layout/activity_register.xml
app/src/main/res/layout/dialog_custom_reminder.xml
app/src/main/res/layout/item_air_quality_metric.xml
app/src/main/res/layout/item_appointment.xml
app/src/main/res/layout/item_available_slot.xml
app/src/main/res/layout/item_doctor.xml
app/src/main/res/layout/item_doctor_list_header.xml
app/src/main/res/layout/item_doctor_list_state.xml
app/src/main/res/layout/item_doctor_schedule.xml
app/src/main/res/layout/item_doctor_schedule_header.xml
app/src/main/res/layout/item_doctor_schedule_state.xml
app/src/main/res/layout/item_patient_directory_header.xml
app/src/main/res/layout/item_patient_directory_state.xml
app/src/main/res/layout/item_patient_doctor.xml
app/src/main/res/layout/view_patient_session_loading.xml
app/src/main/res/values/colors.xml
app/src/main/res/values/dashboard_strings.xml
app/src/main/res/values/google_auth_styles.xml
app/src/main/res/values/patient_wellness_strings.xml
app/src/main/res/values/styles.xml
app/src/main/res/values/themes.xml
docs/ui-professionalization-batch25.md
```

Added:

```text
app/src/androidTest/java/com/example/medibook/ui/UiOnlyTestRunner.java
app/src/androidTest/java/com/example/medibook/ui/UiResourceTest.java
app/src/debug/AndroidManifest.xml
app/src/debug/java/com/example/medibook/ui/UiFixtureActivity.java
app/src/main/java/com/example/medibook/utils/AppointmentPresentation.java
app/src/main/java/com/example/medibook/utils/AuthFailurePresentation.java
app/src/main/java/com/example/medibook/views/AdaptiveColumnsLayout.java
app/src/main/res/color/action_text.xml
app/src/main/res/color/destructive_text.xml
app/src/main/res/color/google_button_text.xml
app/src/main/res/color/input_text.xml
app/src/main/res/color/slot_text.xml
app/src/main/res/drawable/slot_background.xml
app/src/main/res/layout/dialog_confirm_password.xml
app/src/main/res/layout/item_appointment_list_header.xml
app/src/main/res/values/dimens.xml
app/src/main/res/values/ui_strings.xml
app/src/test/java/com/example/medibook/utils/UiPresentationTest.java
docs/UI_UX_MODERNIZATION_HANDOFF.md
```

The main Android manifest, Firebase JSON/rules/index/deployment files, dependency versions,
all eleven wellness bitmap assets, chart and carousel timer remain byte-identical to the backup.
The sole Gradle edit is opt-in test-runner selection. Fifty-four existing domain/network/cache/
reminder/ViewModel source files (excluding the explicitly reviewed AuthRepository error mapper)
were hash-checked unchanged. AuthRepository was additionally compared against the backup with only
its three intended error-presentation edits permitted.
