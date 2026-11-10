# Batch 2.5 — usability and fixed slot numbers

## Audit and UI

Source audit covered all 13 concrete Activities and their session base, 27 layouts, adapters,
menus, resource strings, confirmations/loading/empty/error states and navigation. Findings fixed:
duplicate Schedule publishing/Patient appointments toolbar actions, redundant Find Doctor card,
repeated profile Back buttons, oversized welcome/technical cards, developer diagnostics in normal
copy, incorrect “currency not configured” wording, persistent architecture commentary and filter
state lost on return/rotation. Forms remain scrollable; no new screen, asset or Batch 3 component.

The Patient header has one My Appointments card and accessible directory search/filter controls.
Alert cards are compact and hidden when empty/unconfigured; successful live updates hide their
manual retry button. Booking shows Doctor/date/fee/available times/number/confirmation. Patient
self-cancel and Admin individual cancel, retained history and eligible release remain intact.
Technical paths, SDK failures and verification details stay in Logcat, not normal UI messages.

## Repeated session loading

Cause: every protected Activity called `checkSession()` onStart, which always reloaded Auth,
forced a token refresh and fetched the profile, publishing a null-user loading state. Directory
ViewModels also refreshed on every re-entry. The shared appointment loader used match_parent
height inside a vertical layout, potentially consuming all available content space.

Fix: a maximum **2-minute in-process lease** for the same UID and a non-near-expiry SDK token,
established ONLY after refreshed signed claims + server profile pass the existing SessionPolicy.
Cold/expired/token-changed sessions still reverify. Account changes/logout invalidate the lease;
generation + UID checks reject late callbacks. SDK notification ordering is guarded during own
sign-in/registration/rollback. No Admin privilege is persisted to preferences, intents or disk.
Cold checks use a compact inline indicator. Admin directory reads reuse up to 30 seconds of
same-owner state; explicit refresh and confirmed editor changes still reload. Server Rules and
online booking/cancellation checks remain authoritative. This is not immediate claim revocation.

## Durable slot numbers (Firestore only)

- Existing inventory/appointment/lock schemas remain **12 / 17 / 6 fields**. No reservation or
  cancellation write shape changed. Transient model numbers are not written into appointments.
- Counter: `doctorSlotInventory/{doctorId}/dates/{dateKey}` with exactly `lastSerial`, `lastSlotId`,
  `updatedAt`. Immutable map: the same date's `serials/{slotId}` with `number`, `createdAt`.
- After publishing inventory, Admin assigns missing mappings in chronological order. Every
  allocation transaction reads mapping/counter/slot and atomically creates the map + advances the
  counter by exactly one. Rules require both writes and a real inventory slot; prevent duplicate,
  skipped/reset/reused numbers; and forbid mapping updates/deletes and Patient writes.
- First publication is chronological. Later additions, even earlier clock times, APPEND numbers.
  Cancellation/rebooking/completion/restart never renumber existing mappings; gaps are acceptable.
  Different doctors/dates have independent sequences. Concurrent disjoint publishes can interleave
  assignment order, but cannot duplicate or change an assigned number. Existing overlap prevention
  remains a client publisher responsibility; numbering does not claim to eliminate that boundary.
- Publication is two-phase. If interrupted, some inventory/mappings may already exist; the error
  tells the Admin to retry the same date. Retry preserves all assignments and reservations.
- Legacy appointments remain readable. Re-publish an existing future date with the new APK to
  assign missing maps (including booked slots) without altering appointments/locks. Unassigned past
  dates, old-app publications or failed lookups show an explicit **Time slot start–end** identifier,
  never a fabricated #rank. Once a #number exists it is durable.
- Normal UI uses **Appointment Slot No. #01**. RTDB keeps its old Q-based wire schema/keys for
  compatibility; UI joins Firestore mappings for display. RTDB never allocates/calculates numbers.

## Manual setup and verification

Manually redeploy the ENTIRE updated `firestore.rules` in `medibook-2c3a4` before new publication
can finish numbering. No RTDB Rules redeployment or data reset is required for this batch.
No production data, rules or claims were changed by the development agent.

Run Android unit tests, the Firestore and RTDB Java emulator suites, assembleDebug and lintDebug.
New regressions cover lease ownership/expiry, numbering/legacy presentation, strict allocation
schema/atomicity, concurrent writes, different doctors/dates, incremental earlier slots, booked
legacy migration, both cancellations/rebooking and unchanged appointment/slot/lock field counts.

Device pending (no ADB device): sign in both roles; navigate/rotate/back; test >2-minute lease and
offline/error/retry/logout; publish then incrementally add an earlier slot; book/cancel/rebook the
same slot and verify its #number after restart; check historical fallback, photo and live alerts
on a small display/large font. No screenshot/report/video preparation is part of this batch.

---

## Cross-Screen Component Consistency Audit

**Audit addendum: 9 November 2026 — recommendations for review; not implemented.**

The Batch 2.5 sections above are preserved as historical implementation notes, not a statement
that the current application has passed this audit. Their 13-Activity/27-layout inventory is
superseded by the inventory below. This document is the existing UI audit/history entry found in
the repository; no separate comprehensive UI/UX audit report was found.

### 1. Scope, evidence and safeguards

This is a source-based comparison of all current screen layouts, reusable rows/includes, shared
styles, palette/theme qualifiers, drawable backgrounds, menus, UI adapters and Java-configured
inputs, dialogs, pickers, validation, loading and selection states.

- **Source-confirmed (S):** directly supported by XML/Java or the cached Material dependency.
- **User-reported (U):** the selected Gender value, particularly “Male”, appears too close to the
  left border beside correctly spaced Name/Phone fields. This is accepted as a reported visual
  problem, not described as a new measurement made by this audit.
- **Device verification required (D):** actual rendered insets, baselines, clipping, popup states,
  screen-reader traversal, disabled-state appearance, animation and scrolling performance.
  No audit screenshots, runtime hierarchy inspection or physical-device UI tests were performed.

The application currently has **17 concrete Activities/screens, one abstract session Activity,
and 35 layout XML files: 17 screen layouts, 15 row/header/state layouts, one dialog and two
includes**. Counts describe source definitions, not simultaneous runtime instances. There are
28 XML `TextInputLayout`s (23 `TextInputEditText` children and five autocomplete children), one
Spinner, 62 Material buttons, 31 Material cards, three Material radio buttons, two switches,
12 toolbars, 20 circular progress indicators and two linear progress indicators.

The existing `Theme.Material3.Light.NoActionBar`, `sans`/Roboto identity and medical
blue/teal/white palette are the reference. Material is **1.10.0**
(`gradle/libs.versions.toml:9`), not a newer library inferred from current documentation.
`values-night/themes.xml` intentionally leaves the light theme in place; the v27/v29 qualifiers
retain light navigation bars and disable force-dark on supported versions. This is not a request
for dark mode, Compose, a library upgrade or a replacement design framework.

**Scope boundary:** documentation only. Preserve all pre-existing modified/untracked files.
No application/resource/configuration changes, new features/dependencies, Firebase/authentication
or authorization changes, booking/cancellation/publication changes, reminder changes, commits,
pushes, resets, stashes, cleaning or deployment are authorized by this audit. Recommendations
below require review before any implementation. Previous build/test results are not evidence of
visual or accessibility acceptance for these proposals.

### 2. Screen and component inventory

All screen filenames in this table are under **`app/src/main/res/layout/`**. Row/include filenames
in the following table have the same directory. Throughout this addendum, shortened `layout/...`
and `values.../...` references are relative to **`app/src/main/res/`**; shortened `activities/...`,
`adapters/...` and `utils/...` references are relative to
**`app/src/main/java/com/example/medibook/`**. A bare filename beside a full path uses that same
directory. Java owners in the screen table use this Java root.

| Screen(s) | Current XML | Comparable UI patterns / Java owner |
| --- | --- | --- |
| Launcher/router | `activity_main.xml` | Centered session progress and message; `MainActivity.java` |
| Login | `activity_login.xml` | Brand, illustration, auth card, email/password, password toggle, primary/Google/text buttons, inline errors/progress; `activities/auth/LoginActivity.java` |
| Registration | `activity_register.xml` | Name/email/phone/password/confirmation, DOB field, Gender dropdown, validation, primary/text actions; `activities/auth/RegisterActivity.java` |
| Complete Profile | `activity_complete_profile.xml` | Name/phone/DOB/Gender, read-only email and age, save/sign-out, onboarding state; `activities/auth/CompleteProfileActivity.java` |
| Edit Profile | `activity_edit_profile.xml` | Same patient fields, read-only email, save, branded Google linking and programmatic password-confirmation input; `activities/patient/EditProfileActivity.java` |
| Patient Home | `activity_patient_home.xml` | One scrolling directory with header/next-appointment/wellness/navigation/search/specialization and result-state rows; `activities/patient/PatientHomeActivity.java` |
| Admin Dashboard | `activity_admin_dashboard.xml` | Auth-style enclosing card, feature/navigation cards, paired analytics metrics, native custom-drawn weekly chart, state/retry/logout; `activities/admin/AdminDashboardActivity.java` |
| Manage Doctors | `activity_manage_doctors.xml` | Scrolling header, doctor cards, status chips, edit/schedule/status actions and state rows; `activities/admin/ManageDoctorsActivity.java` |
| Doctor editor | `activity_add_edit_doctor.xml` | Name, free-text specialization, qualifications, decimal fee, multiline biography/image URL, counters/helpers, active switch, save/cancel; `activities/admin/AddEditDoctorActivity.java` |
| Manage Schedule / Publish Slots | `activity_manage_doctor_schedule.xml` | Date selector, publish action/progress, schedule header/cards and state rows inside one outer scroll; `activities/admin/ManageDoctorScheduleActivity.java` |
| Schedule editor | `activity_add_edit_doctor_schedule.xml` | Weekday dropdown, start/end time buttons, inline errors, numeric duration, active switch, save/cancel; `activities/admin/AddEditDoctorScheduleActivity.java` |
| Patient-facing Doctor Profile | `activity_doctor_profile.xml` | Doctor identity/avatar, status, qualifications/fee/about/schedule cards, Book Appointment and error/retry; `activities/patient/DoctorProfileActivity.java` |
| My/Admin Appointments | `activity_appointments.xml` | Status Spinner, Admin-only name/date filters, reminder permission action, appointment/history cards and loading/empty/error text; `activities/AppointmentsActivity.java` |
| Book Appointment | `activity_book_appointment.xml` | Doctor/fee, date selector, available-slot radio rows, confirm dialog/action, progress/messages and live queue text; `activities/patient/BookAppointmentActivity.java` |
| Appointment Details | `activity_appointment_details.xml` | Selectable summary text, patient/reminder cards, custom reminder dialog, remove/cancel/refresh actions; `activities/AppointmentDetailsActivity.java` |
| Emergency Contacts | `activity_emergency_contact.xml` | Native phone-contact picker, count/list/empty/sync state, add/call/remove actions; `activities/patient/EmergencyContactActivity.java` |
| Air Quality | `activity_air_quality.xml` | Scrolling location/freshness header and four metric cards, linear refresh progress, contextual permission/error/retry text; `activities/patient/AirQualityActivity.java` |

| Component family | Shared/similar definitions | Current reuse / important distinction |
| --- | --- | --- |
| Text/password/numeric/multiline inputs | `values/styles.xml`; Login, Register, Complete/Edit Profile, doctor/schedule editors, directory search and appointment doctor filter | All XML containers reuse `Widget.MediBook.Input`. Input types, counters, multiline rules and password-toggle variants are legitimate differences. |
| Non-editable dropdowns | Register/Edit/Complete Profile Gender; schedule weekday; directory specialization in `item_patient_directory_header.xml` | Five autocomplete fields share the same incomplete container integration; see CSC-01. Admin doctor specialization is **free text**, not a dropdown to be silently converted. |
| DOB/date/time selection | Three patient DOB fields; booking/list/publication date buttons; schedule time buttons; `dialog_custom_reminder.xml` | Shared `utils/BirthDatePicker.java`, `utils/AppointmentUi.java`, `utils/DoctorScheduleUi.java`; Material and platform picker families coexist. |
| Buttons / action hierarchy | `values/styles.xml`, `values/google_auth_styles.xml`, screen/row buttons and native dialog buttons | Filled primary, text, locally configured outlined and branded Google variants; icon-only contacts use native ripple backgrounds. |
| Doctor/schedule cards | `item_doctor.xml`, `item_patient_doctor.xml`, `item_doctor_schedule.xml`; Doctor Profile | Common auth-card shell, but different title roles/density/avatar roles and action sets. |
| Appointment cards/details | `item_appointment.xml`, next-appointment block in `item_patient_directory_header.xml`, Appointment Details | History uses a teal card and formatted summary; next appointment uses individual fields; details uses one large multiline summary plus separate patient/reminder cards. |
| Dashboard/metric cards | Admin Dashboard, Patient Home feature cards, `item_air_quality_metric.xml` | Existing `FeatureCard` is the compact 16dp-corner variant; analytics pairs use 12dp content padding. |
| List headers/states | `item_doctor_list_header.xml`, `item_doctor_schedule_header.xml`, `item_patient_directory_header.xml`; corresponding three `*_state.xml` files | Material card/title/body/retry patterns already exist; state icon sizes and progress configuration vary. |
| Selection / status | `item_available_slot.xml`, `dialog_custom_reminder.xml`, doctor/schedule chips and switches | Available slots are selectable radio rows. Status chips are informational, not selectable. No separate visual grid of booked/unavailable slots exists. |
| Contacts / environmental data | `item_emergency_contact.xml`, `item_air_quality_header.xml`, `item_air_quality_metric.xml` | Contact rows have 48dp icon targets and name-specific accessibility labels; Air Quality has textual guidance and freshness, not a source-defined colored risk scale. |
| Brand / session / wellness | `view_auth_brand.xml`, `view_patient_session_loading.xml`, `item_wellness_card.xml` | Shared auth identity, reusable compact loader, original 11-card image carousel. |
| Dialogs / system surfaces | Java `MaterialAlertDialogBuilder` calls; `dialog_custom_reminder.xml`; DOB/schedule/platform pickers; four XML menu resources | Confirmations already use Material dialogs. System contact/permission/settings surfaces should remain system-owned, not visually cloned. |

**Dialog/feedback inventory:** the following Java paths are relative to the Java root above.
These existing Material confirmations are reuse candidates, not missing components to recreate.

| Existing interaction | Responsible source | Reuse / review decision |
| --- | --- | --- |
| Booking confirmation and persistent booking-failure dialog | `activities/patient/BookAppointmentActivity.java:124–135,147–153` | Keep Material layout, fee/date/time/slot copy and persistent failure; success currently uses a Toast before navigation. |
| Appointment cancellation | `activities/AppointmentDetailsActivity.java:66–74` | Keep confirmation and neutral dismissal; review destructive positive-action styling. |
| Doctor activate/deactivate; schedule enable/disable | `activities/admin/ManageDoctorsActivity.java:162–171`; `activities/admin/ManageDoctorScheduleActivity.java:294–304` | Keep Material confirmations; reversible availability changes are not deletion. |
| Doctor editor reload/discard; schedule editor reload/discard | `activities/admin/AddEditDoctorActivity.java:70–81,223–235`; `activities/admin/AddEditDoctorScheduleActivity.java:261–272,354–367` | Preserve unsaved-work warnings, Keep editing and pending-write guards; scoped destructive-confirm styling may cover existing Discard actions if approved. |
| Publish Slots confirmation | `activities/admin/ManageDoctorScheduleActivity.java:192–209` | Preserve the selected doctor/date and affirmative action; publication progress/result remains inline. |
| Enable/disable reminders and notification-settings explanation | `activities/AppointmentsActivity.java:113–153` | Keep native Material confirmation plus existing Snackbar/settings recovery; no permission-flow redesign. |
| Custom reminder form | `activities/AppointmentDetailsActivity.java:142–232`; `app/src/main/res/layout/dialog_custom_reminder.xml` | Keep inline preview/error/mode help, validation before dismissal and platform date/time picker semantics. |
| Google-link password confirmation | `activities/patient/EditProfileActivity.java:130–151` | Keep Material shell and sensitive-flow behavior; style the input content as CSC-05 recommends. |
| DOB / appointment-date / schedule-time pickers | `utils/BirthDatePicker.java:24–45`; `utils/AppointmentUi.java:30–35`; `activities/admin/AddEditDoctorScheduleActivity.java:275–295` | Preserve each domain contract; inspect appearance before proposing a picker migration. |

Field validation is already inline through Material `setError`; save/status/retry messages also
use inline text and Snackbars. There is no universal success/error modal to replace. Emergency
Contacts selects a phone entry through the native `ACTION_PICK` picker rather than a custom form;
contact/reminder removal currently has no confirmation (see CSC-03).

### 3. Findings and priorities

**Severity:** Critical = a demonstrated blocking/safety-critical usability failure; High = a
significant core-flow consistency or accessibility problem; Medium = meaningful hierarchy,
component or interaction inconsistency; Optional = maintainability/polish or measurement-led
optimization. **No Critical issue is established by the inspected source.** A High validation
gate is not a claim that an unobserved rendering failure has occurred.

Regression risk is separate from severity: it estimates implementation risk **if approved**,
not risk caused by this documentation change.

| ID | Finding / decision | Severity | Evidence | Regression risk | Suggested phase |
| --- | --- | --- | --- | --- | --- |
| CSC-01 | Gender and other exposed-dropdown integration mismatch | High | S + U; rendered values D | Low–Medium: XML styling, selection/focus must survive | 1 |
| CSC-02 | Date/time selectors have inconsistent action emphasis and sizing | Medium | S; appearance D | Medium: retain picker entry points and labels | 2 |
| CSC-03 | Appointment cancellation shares affirmative primary styling | High | S; device state review D | Medium: styling only, policy/confirmation untouched | 2 |
| CSC-04 | Status filter uses fixed-height Spinner instead of shared selection pattern | Medium | S; large-font failure unverified | Medium: filtering indexes/defaults/restoration | 2 |
| CSC-05 | Google-link password prompt bypasses the explicit input style | Medium | S; actual default appearance D | High: sensitive reauthentication flow | 4 |
| CSC-06 | Card density, gutters and title hierarchy lack explicit role specifications | Medium | S; not every difference is a defect | Low–Medium: layout/scroll regressions | 3 |
| CSC-07 | Appointment information hierarchy differs between next/list/details | Medium | S | Medium: preserve every value, status and selectable text | 3 |
| CSC-08 | Repeated loading/state implementations and inconsistent configurations | Medium | S; announcement/layout behavior D | Medium: binding/session visibility | 2–3 |
| CSC-09 | Error/status presentation has different visual severity treatment | Medium | S; effectiveness D | Medium: preserve persistent warnings and recovery copy | 2–3 |
| CSC-10 | Profile input IME configuration differs from registration | Medium | S; keyboard behavior D | Medium: focus, picker and autofill behavior | 3 |
| CSC-11 | Wellness image content has no app-supplied textual equivalent | High | S; TalkBack traversal D | Low for metadata; Medium for any timer/focus change | 3 |
| CSC-12 | Small-screen/large-font accessibility acceptance is not established | High validation gate | S risk locations; failures D | Medium–High if reflow/picker work is needed | 0 + every phase |
| CSC-13 | Tokens and semantic component roles are incomplete | Optional | S | Medium if global styles are changed indiscriminately | Incremental |
| CSC-14 | Nested-list/rebinding performance warrants measurement, not speculative rewrites | Optional | S structure; jank D | Medium–High for adapter/scroll changes | 5, only if measured |

#### CSC-01 — Gender alignment: confirmed integration mismatch, likely visual cause

**Reported symptom:** “Male” sits closer to the left border than adjacent Name/Phone values in
Registration/Edit Profile. The three patient Gender fields use the same construction:

- `app/src/main/res/layout/activity_register.xml:120–136`
- `app/src/main/res/layout/activity_edit_profile.xml:58–66`
- `app/src/main/res/layout/activity_complete_profile.xml:44–50`

The same construction also appears at
`app/src/main/res/layout/activity_add_edit_doctor_schedule.xml:159–175` and
`app/src/main/res/layout/item_patient_directory_header.xml:123–131`.

**Source facts and dependency evidence:**

1. `Widget.MediBook.Input` inherits `Widget.Material3.TextInputLayout.OutlinedBox`
   (`app/src/main/res/values/styles.xml:2–8`). It supplies four 12dp corners and a 16dp top margin;
   it does not define a dedicated autocomplete child style, inset, gravity or text appearance.
2. Name/Phone children are `TextInputEditText`; Gender is `MaterialAutoCompleteTextView` with
   `inputType="none"`. Setting only `endIconMode="dropdown_menu"` on the container supplies the
   dropdown interaction, not the exposed-dropdown style's complete theme configuration.
3. Inspection of **Material 1.10.0's actual AAR `res/values/values.xml`** confirms that generic
   `Widget.Material3.TextInputLayout.OutlinedBox` installs
   `ThemeOverlay.Material3.TextInputEditText.OutlinedBox`, which selects `editTextStyle`.
   `Widget.Material3.TextInputLayout.OutlinedBox.ExposedDropdownMenu` instead installs
   `ThemeOverlay.Material3.AutoCompleteTextView.OutlinedBox`, which selects
   `Widget.Material3.AutoCompleteTextView.OutlinedBox` through `autoCompleteTextViewStyle`.
   The latter also supplies the Material 3 simple-item layout and selected-row colors.
4. Java uses ordinary `setSimpleItems(PatientGender.OPTIONS)` and selected text, not a Gender
   drawable/padding workaround: `activities/auth/RegisterActivity.java:42`,
   `activities/patient/EditProfileActivity.java:53,154–161`, and
   `activities/auth/CompleteProfileActivity.java:30,59–63`, under the Java directory above.
   No Gender-specific custom background, padding or gravity override was found in these paths.
5. Parent outline/corner/margin intent is already common. **Child style resolution differs.**
   This is a confirmed structural mismatch and a likely cause of the reported inset discrepancy;
   exact resolved pixel padding, text size, baseline and label position remain unmeasured.
   Do not describe a missing XML padding attribute as proof of a zero runtime inset.

**Recommended Material-compatible correction, not yet applied:**

- Add one `Widget.MediBook.ExposedDropdown` variant with explicit parent
  `Widget.Material3.TextInputLayout.OutlinedBox.ExposedDropdownMenu`. Reuse the same field radius,
  stroke/state tokens and spacing as `Widget.MediBook.Input`; do not merely inherit the generic
  input and add the arrow again. Keep the editable input style/Login unchanged.
- Use its autocomplete-aware overlay with `MaterialAutoCompleteTextView`; retain non-editable
  input, labels, existing options, values, callbacks and state ownership. Apply to all five
  equivalent fields after testing Gender first. The specialization field's start icon warrants
  the normal **icon-aware** inset, not a forced baseline identical to an icon-free field.
- Let Material supply child background/insets/text metrics first. Compare the resolved editable
  and autocomplete styles. If an explicit child style is needed, derive it from the matching
  `Widget.Material3.AutoCompleteTextView.OutlinedBox`, not from a platform Spinner/EditText.
- Do not add a left margin, insert spaces into “Male”, edit popup-row padding to move the selected
  field text, suppress font padding globally or assign a device-specific fixed height.
- Keep `wrap_content`, natural vertical metrics and an appropriate field minimum. Verify selected
  text and floating labels at normal/large font, empty/selected/focused/error/disabled states and
  after reopening the popup, keyboard navigation and rotation. Check start/end insets in RTL too.

**Acceptance:** Gender's selected text follows the same icon-free content-start rule as Name/Phone;
labels, outlines and baselines are coherent without clipping. All options and existing wire values
remain unchanged. The popup still exposes selection and focus correctly. Screenshots/device checks
are required to confirm that the user-reported appearance is resolved.

#### CSC-02 to CSC-05 — Selection controls and action hierarchy

**CSC-02 — Selector styling drift.** Booking's `dateButton` has no explicit style, so it follows the
theme's filled Material button rather than the app's primary or picker specification
(`app/src/main/res/layout/activity_book_appointment.xml:14–15`). Publication and appointment-list
date selectors use `TextButton` (`activity_manage_doctor_schedule.xml:61–63`,
`activity_appointments.xml:19–25`); schedule time selectors independently specify outlined,
56dp minimum/12dp corners (`activity_add_edit_doctor_schedule.xml:177–215`); reminder date/time
selectors use outlined buttons with a 48dp minimum (`dialog_custom_reminder.xml:15–41`). All paths
are in `app/src/main/res/layout/`.

Recommend a reusable **outlined form-picker button** for choosing a value (56dp minimum) and a
**compact filter/dialog variant** (48dp minimum). Form selection should not compete visually with
Confirm booking/Save/Publish. Retain text Clear/Refresh and the existing DOB outlined-input variant.
Dialog/form density can differ deliberately; do not make all pickers look like primary actions.

**Picker-family qualification:** DOB already uses a shared `MaterialDatePicker`
(`app/src/main/java/com/example/medibook/utils/BirthDatePicker.java:24–45`); booking/filter/publication
use platform `DatePickerDialog` (`utils/AppointmentUi.java:30–35`); reminders use platform date/time
dialogs (`activities/AppointmentDetailsActivity.java:164–180`). Schedule editing uses
`MaterialTimePicker` and system 12/24-hour preference
(`activities/admin/AddEditDoctorScheduleActivity.java:275–295`). These are source-confirmed
implementation differences, **not proof of a bad rendered picker**. Standardize entry controls
first. Any later dialog migration is high-risk and separately reviewed: preserve DOB UTC/ISO
conversion, no-future constraint, restored callbacks, Dhaka wall-clock conversion, date eligibility
and existing reminder AM/PM behavior. A single universal picker helper is not justified.
Complete Profile explicitly reconnects a restored DOB picker
(`activities/auth/CompleteProfileActivity.java:29`); Register/Edit Profile call the shared picker
without that reconnect call (`activities/auth/RegisterActivity.java:86–91`,
`activities/patient/EditProfileActivity.java:164–169`). Verify rotation with the picker open in
each flow rather than assuming identical restoration because the picker class is shared. This
is a source-confirmed lifecycle-wiring difference, not a device-confirmed lost-selection finding;
any lifecycle correction requires separate review rather than a covert styling change.

**CSC-03 — Destructive versus affirmative action.** Cancel appointment uses exactly
`Widget.MediBook.PrimaryButton`, the same blue filled hierarchy as Save/Confirm booking
(`app/src/main/res/layout/activity_appointment_details.xml:62–65` versus
`activity_book_appointment.xml:27–28`). Its Material confirmation uses default positive-button
styling (`app/src/main/java/com/example/medibook/activities/AppointmentDetailsActivity.java:66–74`).
The contact Remove icon already uses error tint (`app/src/main/res/layout/item_emergency_contact.xml:40–47`).

Recommend a distinct **destructive outlined/text action** with error foreground/stroke and native
state feedback; use an error-styled positive action only in the destructive confirmation, not in
every Material dialog. Keep Cancel appointment's eligibility, history retention, server checks,
double-action guards and confirmation text/callback exactly intact. The negative “Cancel” button
that dismisses a form/dialog is a neutral action, not destructive. Do not relabel or remove it.
Contact and custom-reminder removal currently happen immediately
(`activities/patient/EmergencyContactActivity.java:136–143`,
`activities/AppointmentDetailsActivity.java:59–64`); no hidden Remove confirmation/Undo exists to
standardize. Adding one would change interaction behavior and needs separate authorization.

**CSC-04 — Appointment status filter.** The native `Spinner` has fixed `layout_height="48dp"`
(`app/src/main/res/layout/activity_appointments.xml:12–13`) and a platform
`simple_spinner_dropdown_item` adapter
(`app/src/main/java/com/example/medibook/activities/AppointmentsActivity.java:60–65`). Other
non-editable choices use outlined autocomplete fields. This establishes different component and
selected-row styling; clipping at large fonts is **not** established. Recommend the tested shared
dropdown/filter variant, `wrap_content` and a minimum rather than a fixed height. Preserve all
four filter choices, Patient/Admin defaults, selected-index meaning, saved state and filtering
behavior (`AppointmentsActivity.java:84–100,184–203,207–212`). Do not convert filtering into new
queries or change what “Upcoming / in progress” means.

**CSC-05 — Programmatic password input.**
`app/src/main/java/com/example/medibook/activities/patient/EditProfileActivity.java:130–151` constructs
`new TextInputLayout(this)` and `new TextInputEditText(layout.getContext())` without the explicit
MediBook outlined style. Its 24dp container padding is dialog content spacing, not the selected
Gender inset. It follows theme defaults instead of the explicit Login/Register form style; the
actual default box/corners must be inspected, not assumed. Recommend a small styled dialog-input
layout or a properly themed construction context, with dialog-owned gutters rather than new
per-child offsets. This is a high-regression-risk location: retain password type/toggle,
non-persistence/autofill policy, clearing on submit/dismiss, owner checks and reauthentication/link
callbacks. Do not change provider-linking logic or add account/password features.

#### CSC-06 to CSC-10 — Cards, information, states and input behavior

**CSC-06 — Density and title roles are implicit.** Shared card styles already define 24dp auth/list
and 16dp feature corners, zero elevation and a pale 1dp border
(`app/src/main/res/values/styles.xml:22–32`). Doctor Admin and Patient cards both use 20dp content
padding, but Admin names use `TitleLarge` and Patient names `TitleMedium` bold
(`app/src/main/res/layout/item_doctor.xml:9–28`, `item_patient_doctor.xml:4–15`). Schedule day titles
also use `TitleLarge` (`item_doctor_schedule.xml:15–29`). Dashboard/feature interiors use 16dp;
paired analytics use 12dp; Login/Doctor Profile use 24dp; wellness independently uses 18dp corners
(`activity_admin_dashboard.xml:92–168,200–244`, `activity_login.xml:26–42`,
`activity_doctor_profile.xml:35–54`, `item_wellness_card.xml:3–8`). Booking/list gutters are 16dp
while Edit Profile/details use 24dp (`activity_book_appointment.xml:9`,
`activity_appointments.xml:8`, `activity_edit_profile.xml:21`, `activity_appointment_details.xml:9`).

These exact differences are confirmed; different card roles are **not automatically errors**.
Specify compact feature/metric, standard list and spacious auth/detail density roles. Adopt one
default doctor-list title role, retaining a larger Admin variant only if explicitly approved.
Standardize sibling section alignment within each screen first. Do not flatten all cards to one
radius, add shadows everywhere, or reduce the liked Login gutters to match a dense list.

**CSC-07 — Appointment hierarchy.** Details places all summary data in one selectable
`TitleMedium` TextView (`app/src/main/res/layout/activity_appointment_details.xml:14–16`), populated
by `AppointmentUi.summary()` (`app/src/main/java/com/example/medibook/utils/AppointmentUi.java:43–47`)
and the newline-delimited `appointment_summary`
(`app/src/main/res/values/strings_appointments.xml:35`). Next Appointment separates doctor,
specialty/date/time/slot/status (`app/src/main/res/layout/item_patient_directory_header.xml:15–36`);
history uses a title/body pair on a teal card (`item_appointment.xml:1–11`,
`app/src/main/java/com/example/medibook/adapters/AppointmentAdapter.java:34–42`). History's background
does not vary by status, but the status is already stated in text. Do not claim missing status or
introduce a booked-slot visualization.

Recommend reusable appointment **text roles / compact and detail layouts** using only existing
values: prominent doctor, body metadata, clear textual status and slot/fee labels. Keep compact
history versus full detail as explicit variants. Preserve selectable/copyable detail content,
legacy placeholders, cancelled/completed history and `SlotSerials`' durable-number/time fallback;
do not calculate new display ranks or alter status logic.

**CSC-08 — Loading/state drift.** The shared compact session include has a 24dp indicator in a
32dp view and start-aligned message (`app/src/main/res/layout/view_patient_session_loading.xml:1–11`).
Four Admin management/editor layouts duplicate a similar row; for example Manage Doctors centers
the message (`activity_manage_doctors.xml:26–47`). Login/Register/Complete Profile specify a 28dp
indicator in a 32dp view (`activity_login.xml:95–105`, `activity_register.xml:184–195`,
`activity_complete_profile.xml:55–58`), while Admin editor/header operation indicators use 32dp
views without equivalent explicit indicator sizing (`activity_add_edit_doctor.xml:83–90`,
`item_doctor_list_header.xml:37–44`). Default indicator geometry inside those views must be checked
on device; XML alone does not establish clipping. Full state indicators use 48dp views; directory
state icons differ between 48dp Admin and 56dp Patient (`item_doctor_list_state.xml:17–29`,
`item_patient_directory_state.xml:7–11`).

Recommend session/action/page progress roles with explicit host/indicator geometry, and reuse the
existing state-card pattern. Different workload roles may legitimately use different sizes or a
linear indicator. Do not put a spinner on every card, remove warm cached content or reinstate
repeated session fetches. Complete Profile's progress has a static “Saving…” content description
but is shown for general `state.loading`, including session work
(`activity_complete_profile.xml:55–58`,
`app/src/main/java/com/example/medibook/activities/auth/CompleteProfileActivity.java:38–47`); match
the description to the actual visible operation or its adjacent status. Verify announcements
without duplicate spinner/live-region speech.

**CSC-09 — Feedback severity.** Login/Register/Complete Profile's general error text is explicitly
red, but Edit Profile save failure, appointment load errors and contact sync/error messages share
neutral Body styling (`app/src/main/res/layout/activity_login.xml:118–127`,
`activity_complete_profile.xml:59–61`, `activity_edit_profile.xml:68–72`,
`activity_appointments.xml:30–31`, `activity_emergency_contact.xml:32–39`). Admin editor Java sets
operation text color by state (`app/src/main/java/com/example/medibook/activities/admin/AddEditDoctorActivity.java:149–150`,
`activities/admin/AddEditDoctorScheduleActivity.java:220–221`). This is different severity treatment,
not proof that text-only messages are inaccessible.

Recommend shared error/warning/information/success **presentation roles**, with readable text and
the existing recovery action; never rely on color alone or derive severity by parsing translated
strings. Preserve field-local Material `setError`, first-invalid focus and persistent operational
messages. Booking failure deliberately stays in a Material dialog after availability refresh
(`activities/patient/BookAppointmentActivity.java:147–153`); do not replace it with a disappearing
Snackbar merely for uniformity. Toast success, Snackbar feedback, inline validation and blocking
confirmation have different purposes; all need coherent typography, not one universal container.

**CSC-10 — IME/focus configuration.** Registration specifies Next/Done on Name/email/password
inputs (`app/src/main/res/layout/activity_register.xml:54–80,147–174`), whereas equivalent
Name/Phone fields in Edit/Complete Profile omit explicit IME actions
(`activity_edit_profile.xml:27–41`, `activity_complete_profile.xml:19–31`). Not every input,
including registration Phone, has an explicit action. Shared input appearance therefore does not
guarantee consistent keyboard traversal. Actual keyboard actions remain D. Specify role-based
Next/Done, keep appropriate person-name/email/phone/number/password keyboards and existing autofill
hints, and verify traversal through non-editable DOB/dropdowns without an unwanted keyboard.
Do not add automatic submission, change validation/normalization or make read-only email editable.

#### CSC-11 to CSC-14 — Accessibility, responsive behavior and maintainability

**CSC-11 — Wellness text equivalent.** `item_wellness_card.xml:10–13` under
`app/src/main/res/layout/` hides the image from accessibility, while
`app/src/main/java/com/example/medibook/adapters/WellnessCarouselAdapter.java:45–49` sets only
“Health and wellness card N of M” on the item.
`app/src/main/res/values/patient_wellness_strings.xml:3–6` explicitly leaves titles/descriptions
inside the supplied images and provides no equivalent content strings. The absence of an
app-supplied title/advice equivalent is source-confirmed; whether the current parent is focused
and exactly what TalkBack speaks must be tested. Recommend reviewed textual equivalents of the
**existing** card content as accessibility metadata, not new advice, assets or a carousel redesign.
Preserve all eleven original images/order, 2:1 fitCenter presentation and non-clickable behavior.
Any focus-aware auto-advance adjustment needs separate device evidence/review: the current timer
already pauses for dragging, activity stop and detachment
(`app/src/main/java/com/example/medibook/wellness/HealthWellnessCarousel.java:33–60,93–126`), but has
no explicit accessibility-focus pause condition. Do not claim duplicate timers or a measured
TalkBack interruption.

**CSC-12 — Responsive/accessibility validation gate.** No large-font overlap, inaccessible touch
target or scroll jank is asserted without observation. Source-supported inspection hotspots are:

- Fixed 48dp appointment Spinner and non-scrolling controls above a weight-based list:
  `app/src/main/res/layout/activity_appointments.xml:7–33`. At large font/IME height, verify that
  filters do not leave results/recovery actions unreachable.
- Equal-width Hospital/Emergency Contacts buttons with two-line caps:
  `app/src/main/res/layout/item_patient_directory_header.xml:95–103`; equal-width reminder
  date/time buttons: `dialog_custom_reminder.xml:15–41`; paired analytics cards:
  `activity_admin_dashboard.xml:86–198`. Test long date/time strings, large counts and font size.
- Doctor name/specialty beside a 64dp image: `item_patient_doctor.xml:6–16`; narrow multiline slot
  labels: `item_available_slot.xml:1–3` plus `values/strings_appointments.xml:48`. Text wrapping,
  selection announcement and action reachability require inspection.
- `WeeklyBarChartView` is fixed to 180dp in Admin Dashboard (`activity_admin_dashboard.xml:220–224`),
  but **already scales text using `scaledDensity` and supplies seven-day count descriptions**
  (`app/src/main/java/com/example/medibook/views/WeeklyBarChartView.java:38–66`). Inspect 10/11sp
  chart labels and narrow/large-font count collisions; do not report absent scaling or replace
  the working chart on speculation.
- Main forms are already scrollable. `AuthUi.applyInsets()` handles system bars/cutout/IME and sets
  root padding (`app/src/main/java/com/example/medibook/utils/AuthUi.java:17–28`). Put future screen
  gutters on the content container; do not overwrite inset padding or add double IME spacing.
- Contact action icons already have **48dp targets and name-specific labels**
  (`app/src/main/res/layout/item_emergency_contact.xml:31–47`,
  `app/src/main/java/com/example/medibook/activities/patient/EmergencyContactActivity.java:125–135`).
  Informational status chips intentionally disable click/focus/minimum touch-target expansion;
  this alone is not a touch-target violation. Confirm their status text is encountered in traversal.

If overflow is demonstrated, prefer wrapping and a vertical/stacked compact-width variant over
smaller text, narrower touch targets or forced one-device offsets. Do not add blanket scrolling
containers around existing scrolling lists.

**CSC-13 — Missing explicit tokens/state variants.** Most dimensions/text roles remain literal,
and only Input/PrimaryButton/TextButton/AuthCard/FeatureCard plus GoogleButton are centralized.
Input and PrimaryButton also carry layout margins in their widget styles
(`app/src/main/res/values/styles.xml:2–19`), coupling component appearance to placement. Introduce
tokens/aliases incrementally; keep existing margins during the first dropdown correction and
separate placement later only after checking every consumer. Static `android:textColor` in
`TextButton` and `GoogleButton` replaces the inherited state-list foreground
(`styles.xml:17–20`, `app/src/main/res/values/google_auth_styles.xml:2–12`). This confirms a
state-list override, **not an observed indistinguishable disabled button**: backgrounds/ripples
can still differ. Inspect and use role-specific ColorStateLists if needed, preserving Google's
untinted logo and branding. Do not globally change the liked Login to fix another component.

**CSC-14 — Performance observations, not measured defects.** Patient/Admin doctor directories
already use one scrolling RecyclerView with header/state rows. Booking slots and managed schedules
use `wrap_content` RecyclerViews with nested scrolling disabled inside an outer scroll
(`app/src/main/res/layout/activity_book_appointment.xml:7–26`,
`activity_manage_doctor_schedule.xml:49–80`), so recycling benefits may decrease for larger lists.
Doctor/Schedule adapters refresh the small structural list with `notifyDataSetChanged()`
(`app/src/main/java/com/example/medibook/adapters/DoctorAdapter.java:51–59`,
`adapters/DoctorScheduleAdapter.java:50–61`); slot selection rebinds its current rows
(`adapters/AvailableSlotAdapter.java:35–43`). None proves jank. Keep these working structures
unless profiling representative data demonstrates a problem; then consider one flat list or
targeted updates as a separate, tested change. Do not add a chart, animation, skeleton-loader or
diffing dependency merely to standardize appearance. Preserve the single lifecycle-controlled
eight-second wellness timer and existing lightweight chart.

### 4. Proposed design tokens — review specification, not added resources

Use semantic aliases to the existing palette where possible. Proposed resource names and component
names below do **not** imply that those resources/classes currently exist. Preserve `sans`/Roboto,
light surfaces and the Login's current visual identity.

#### Color and state roles

| Token / semantic role | Proposed value | Rule |
| --- | --- | --- |
| Primary / on-primary | Existing `medibook_blue` `#1565C0` / white | Main affirmative actions and focus; not cancellation. |
| Primary container / on-container | Existing `#EAF2FC` / `#1565C0` | Quiet selection/information accent, not all card backgrounds. |
| Secondary accent / on-secondary | Existing `medibook_teal` `#00BFA5` / `medibook_text` `#163340` | Retain current theme mapping; do not introduce white-on-bright-teal text. |
| Secondary container / on-container | Existing `#D8F3EF` / `medibook_teal_dark` `#005C59` | Active/informational emphasis; status still appears in text. |
| Surface / background | Existing white `#FFFFFF` | Preserve clean light healthcare identity. |
| On-surface / supporting text | Existing `#163340` / `#526671` | Distinct main/supporting information; do not replace body text with low-opacity teal. |
| Outline | Existing `#73888F` | Required input/control boundary. Pale `#EAF2FC` card outlines remain decorative, not the sole control/selection cue. |
| Success | Alias `#005C59` on `#D8F3EF` | Reuse medical teal; provide explicit success copy. |
| Warning | Proposed `#7A4E00` on `#FFF3D6` | Only actual warnings/cached limitations; validate before adding resources. |
| Error / destructive | Existing `medibook_error` `#BA1A1A`; optional container `#FFDAD6` with dark `#410002` text | Field/action error roles and destructive confirmation, not neutral dismiss actions. |
| Selected / checked | Material checked indicator plus existing label; optional primary-container emphasis | Never color alone; retain native radio/dropdown semantics and model-controlled checked state. |
| Focus / error outline | Material 1dp resting, 2dp focused; primary or error role | Inherit Material state lists and label behavior rather than manually drawing borders. |
| Disabled / pressed / hovered | Material role-specific ColorStateLists and ripple/state layers | Preserve separate enabled/disabled states; no blanket alpha on whole forms or tinting of the Google logo. |

**Contrast calculations (opaque sRGB source colors, rounded):** these are checks of applied source
color pairs, not a rendered accessibility certification. Target at least 4.5:1 for ordinary text,
3:1 for qualifying large text and essential non-text boundaries/indicators; inactive controls and
decorative outlines have different applicability. Check actual state layers and effective colors
on device.

| Applied pair | Ratio | Source example |
| --- | --- | --- |
| White on primary `#1565C0` | 5.75:1 | Theme on-primary/primary mapping and filled PrimaryButton. |
| Main text `#163340` on white | 13.28:1 | Login heading/auth surfaces. |
| Supporting text `#526671` on white | 6.00:1 | `TextAppearance.MediBook.Body` on white cards. |
| `#005C59` on `#D8F3EF` | 6.72:1 | Active doctor/schedule chip foreground/background in adapters. |
| Supporting text `#526671` on `#D8F3EF` | 5.14:1 | Appointment-history Body summary on its teal card. |
| Error `#BA1A1A` on white | 6.46:1 | Login error / custom-reminder error text. |
| Outline `#73888F` against white | 3.72:1 | Theme outline role on white outlined fields; state-dependent rendering still D. |

Sources: `app/src/main/res/values/colors.xml:3–13`, `values/themes.xml:2–18`,
`values/styles.xml:2–40`, `layout/activity_login.xml:26–49,118–127`,
`layout/item_appointment.xml:1–9`, `layout/dialog_custom_reminder.xml:88–95`, and
`app/src/main/java/com/example/medibook/adapters/DoctorAdapter.java:126–131` /
`DoctorScheduleAdapter.java:125–129`. No white-on-teal text failure or untinted icon contrast
failure is asserted from a palette swatch without checking actual applied tint/background.

#### Typography, geometry, spacing and motion

| Role / token | Proposed specification | Compatibility / exception |
| --- | --- | --- |
| Screen/auth heading | 28sp / 36sp line height, bold 700 | Existing MediBook Heading reference; keep Login. |
| Section/expanded title | 22sp / 28sp, medium 500 | Explicit expanded-role option; not the default for every list label. |
| Card/list title | 16sp / 24sp, medium 500 | Consistent default for similar doctor/appointment cards; approved emphasis may be bold. |
| Body | 14sp / 20sp, regular 400 | Preserve existing BodyMedium; use 16sp / 24sp for important values/input text. |
| Label / metadata | 14sp / 20sp medium; 12sp / 16sp supporting metadata | Never shrink essential controls to fit; the existing chart remains a reviewed exception. |
| Primary / compact button text | 16sp / 14sp medium | Native Material casing/metrics; allow height growth/wrapping. |
| Spacing scale | 4, 8, 12, 16, 20, 24, 32dp | 4/8 within content, 12/16 between related groups, 24/32 between major sections; retain auth brand's intentional 28dp spacing. |
| Content gutters | 16dp standard list/dashboard; 24dp auth/spacious form/detail | Choose by screen role, align siblings; consider wider-screen max-width only after device review. |
| Field geometry | 56dp minimum control body, `wrap_content`; 12dp radius; normal 16dp inter-field placement | Supporting/error text expands outside body. Multiline biography keeps its natural four-line minimum. No fixed total TextInputLayout height. |
| Field content insets | Material outlined editable/autocomplete defaults, nominal 16dp start/end for icon-free text | Validate actual baselines; use icon-aware Material offsets. Do not force identical raw padding on icon and icon-free fields. |
| Primary/form-picker button | 56dp minimum view/touch area; 12dp filled/outlined corners | Visible painted height also depends on native insets; do not call `minHeight` the measured background height. |
| Text/secondary/filter/dialog button | 48dp minimum touch area; native text/outlined treatment; 16–24dp horizontal padding by role | Keep wrapping and native insets. Google retains its own 48dp/24dp-radius branded variant. |
| Card shell / content | 24dp radius auth/standard spacious list; 16dp feature/metric; 0dp elevation, pale 1dp border | Content 24dp auth/detail, 20dp standard list, 16dp feature, 12dp metric. Wellness 18dp radius is an intentional existing exception. |
| Dialog | Material alert layout, native button bar/stacking and typography; 24dp custom-content gutters | Use a scoped overlay only where needed. Keep system permissions/contacts/settings native. Do not impose a fixed dialog height. |
| Icons / targets | 24dp standard glyph within at least 48dp action target; 8–12dp label gap | Google icon stays untinted 18dp; decorative brand/avatar sizes 56/64/88dp remain role-specific. |
| Session progress | 24dp indicator / 32dp host, 3dp track plus adjacent message | Reuse compact session include; keep session gating/lease behavior. |
| Action / page progress | 28dp indicator / 32dp host for form action; 40dp indicator / 48dp host for page/state | Explicit sizing prevents accidental host/indicator mismatch. Linear progress is valid for ongoing publication/environment refresh. |
| Interaction motion | Native ripple/checked/focus feedback; roughly 150–200ms small state transitions, 200–300ms only where useful | Respect system animator/reduced-motion settings. No new per-row looping/pulsing animations. |
| Carousel / chart | Preserve existing eight-second lifecycle-controlled advance and lightweight chart | Do not duplicate timers, re-encode/redesign assets, or add a chart/animation dependency. Accessibility timing changes require focused review. |

Typography values describe reusable roles, not a mandate to override every Material picker/input
line-height or all text appearances in one change. Validate large fonts before promoting a role.
Retain natural font padding; do not use `includeFontPadding=false` as a universal alignment fix.

### 5. Reusable component plan and reuse decisions

| Proposed reuse boundary | Implementation recommendation if approved | Consumers / safeguards |
| --- | --- | --- |
| Existing Input | Keep `Widget.MediBook.Input`; alias existing dimensions later | Editable fields, password toggle, numeric/multiline variants; preserve Login and input validation. |
| ExposedDropdown | New style with the actual Material 3 exposed-dropdown parent and shared shape/state tokens | Three Gender fields, weekday, specialization; then appointment status filter with preserved selection contract. |
| PickerButton / CompactPickerButton | Reusable outlined form/compact variants; no picker business logic inside a visual style | Booking/publication/filter dates, schedule times, reminder entry controls with appropriate density. |
| Primary / Secondary / Text / Destructive actions | Keep existing Primary/Text base roles; add an outlined secondary and scoped destructive variant | Affirmative Save/Book/Publish versus selection, neutral dismiss and destructive cancellation; no callback changes. |
| GoogleButton | Reuse existing brand treatment, review state-list foreground only if device evidence warrants it | Continue/Connect Google; logo tint, shape and provider flow remain intact. |
| Card + content-density roles | Reuse AuthCard/FeatureCard; define named padding/title roles or small includes, not a universal nested wrapper | Doctor/schedule/history/feature/analytics cards; retain approved compact/spacious and wellness variants. |
| Appointment summary roles | Small compact/detail layouts or existing-view styling for existing values | No new model, status calculation, slot ranks or backend calls; retain selectable detail text. |
| State / SessionLoading / StatusText | Reuse session include and existing title/body/action state-card structure; scoped severity styles | Existing loading/empty/error/retry states; preserve binding IDs, visibility and recovery semantics. |
| Dialog input / dialog overlay | Styled reusable input content; scoped Material alert/destructive overlays | Password confirmation and reminder custom content only after sensitive-flow tests; no universal picker rewrite. |
| Native controls + accessibility metadata | Keep native radio/switch/menu behavior and contextual labels; add equivalent metadata for existing image content | Slot selection, reminder modes, active switches, contacts, informational chips and original wellness cards. |

**Reuse unchanged rather than redesign:**

- Login's brand/illustration, card/field/button composition, colors and Roboto typography are the
  reference. Regression screenshots must protect it even when a shared resource is touched.
- Existing text/password/phone/email/numeric inputs already share a good Material outlined base.
  Read-only account email is intentionally text in profile flows; do not make it editable.
- Existing `MaterialAlertDialogBuilder` confirmation structure, neutral dismissal and field-local
  error validation should be retained. A Material dialog and a Snackbar are not interchangeable.
- Keep the working `BirthDatePicker`, schedule time conversion/restoration and reminder semantics;
  visual reuse does not authorize rewriting their domain handling.
- Keep available-slot `MaterialRadioButton`s, their checked state/model ownership and busy handling
  (`app/src/main/java/com/example/medibook/adapters/AvailableSlotAdapter.java:25–43`). Only available
  inventory is rendered; do not invent a new available/booked/unavailable grid or selection model.
- Keep active/inactive chip text, existing teal/muted semantic pairs and noninteractive behavior.
  Keep contact icon targets/descriptions and native `ACTION_PICK` phone selection; no manual
  emergency-contact form exists to standardize.
- Keep feature/navigation card roles, compact dashboard actions, textual environmental guidance,
  freshness/source labels, weekly chart/counts and the original wellness assets/order/ratio.
- Keep existing system-bar/cutout/IME inset handling, single-scroll doctor directories and warm
  content retention. No navigation/menu redesign is required by the inspected source.

### 6. Phased implementation recommendation — approval required

| Phase | Narrow scope | Acceptance / risk control |
| --- | --- | --- |
| 0 — Review and visual baseline | Approve this report, density/action/token choices; capture current Login, Gender and representative components in both roles | Record device/API/font/display settings and screen states. Confirm reported Gender screenshot. Use API 24 minimum and representative current/target API 37 coverage (`app/build.gradle.kts:8–15`), including real-device checks. No visual pass is claimed yet. |
| 1 — Isolated dropdown correction | New exposed-dropdown style; Gender first, then weekday/specialization | Before/after empty/selected/error/disabled screenshots; Name/Phone/Gender content alignment, labels, popup selection, all values, focus/RTL/rotation. No global editable-input or Login redesign. |
| 2 — Action/selector/state roles | Picker entry controls, destructive cancellation styling, preserved-contract status filter; explicit progress geometry/state colors | Same IDs/callbacks and selection/restoration behavior; affirmatives remain primary; disabled/busy appearance and touch targets clear. Keep persistent booking failure and session gating. |
| 3 — Information hierarchy and accessibility | Approved card/title/density roles, existing appointment-value presentation, scoped feedback roles, IME consistency and wellness text equivalents | Long-name/large-font layouts, selectable details, status/slot/fee accuracy, TalkBack equivalents/announcements. Do not alter chart data, assets, navigation or state computation. Reflow only demonstrated overflow. |
| 4 — Sensitive dialogs/pickers, only if approved | Styled password-confirmation content; any justified picker/theme harmonization separately | Reauthentication/password clearing/provider linkage intact. DOB ISO/UTC/constraints/restoration, Dhaka dates, AM/PM/time validation and reminder lifecycle all unchanged. Keep platform picker if parity cannot be established. |
| 5 — Measured optimization and sign-off | Profile representative scrolling/layout transitions; optimize only demonstrated bottlenecks | No extra loaders/timers/dependencies. Complete full device/accessibility matrix and existing regression tests; review UI-only diff. Defer optional rewrites without measured value. |

Add tokens when a consumer is migrated, preserving old values initially. Avoid a global theme swap,
mass text-size/padding replacement, blanket component inheritance change or simultaneous adapter
rewrite. Each approved phase should be independently reviewable and revertible **without
discarding any unrelated existing work**. This audit does not authorize a commit/deployment or a
destructive Git command.

### 7. Screenshot/device acceptance matrix

**All rows below are pending, not passed.** Test at normal, approximately 130%, 150% and 200%
Android font scaling where supported, normal/larger display size, approximately 320–360dp compact
width and a typical 411dp phone; include landscape/split-window and a larger display. Use long
names/specialties, long email/date/time values, multiline errors and realistic counts. Do not
capture real credentials, tokens or patient data in review artifacts.

| Area | Required states/checks | Acceptance |
| --- | --- | --- |
| Login baseline | Empty/typed/focused/password toggle/error/loading/disabled, Google and keyboard open | Liked design unchanged; no clipped labels/actions or weak disabled feedback. |
| Patient form comparison | Register, Complete Profile, Edit Profile; Name/Phone/DOB/Gender; every existing Gender value | Matching icon-free field content-start and coherent baseline/height; correct non-editable popup, validation and no unwanted IME. |
| Admin forms | Doctor fields/counters/biography/URL, weekday/time/duration/switch; long validation errors | Natural height/wrapping, visible first error, keyboard/focus/recovery reachability and consistent field states. |
| Selection lists | Status/specialization/weekday dropdowns; available radio slots; reminder modes | Current choice is clear and announced; checked/disabled state not color-only; filters and saved state unchanged. |
| Buttons / dialogs | Save/Book/Publish/Cancel appointment, neutral Cancel, destructive confirm, Google password prompt | Consistent hierarchy, at least 48dp action targets, wrapped labels/native dialog stacking; no new automatic submission or bypassed confirmation. |
| Cards / details | Doctor Admin/Patient/profile, history/next/details, analytics/Air Quality/contact cards | Approved density variants, aligned siblings, readable long values, selectable details and accurate status/slot/fee text. |
| Loading / data states | Cold/warm/offline/error/retry/empty/filter-no-match/writing/cached/stale | One appropriate progress treatment per operation; recovery copy persists; cached/live and status meaning not lost or represented only by color. |
| TalkBack / keyboard | Traverse headings, fields, password toggles, popup rows, checked radio buttons, card actions, contact icons, chart and wellness content | Useful labels/order/values/errors without duplicate speech; existing advice has a text equivalent; chart counts remain available; check auto-advance focus stability. |
| Responsive layout | Paired dashboard actions/metrics, reminder date/time, appointment filters above results, long doctor names, chart | No overlap, truncation of essential information, unreachable actions or list starvation; stack/reflow only where needed. |
| Contrast / states | Focused/selected/pressed/error/disabled field/button/icon/indicator states | Check actual composed colors and required boundary contrast, not only palette constants; no loss of selection or focus cues. |
| Insets / rotation | Gesture/three-button navigation, display cutout, IME open/closed, rotation and restored dialogs | No double padding or obscured action; correct owner/selection/draft restoration within existing behavior. |
| Performance / motion | Representative long directories/slots, repeated selection/filtering, navigation, carousel scroll offscreen and return | Responsive input/scrolling; no added nested scrolling/timers/layout thrashing, loading flashes or unnecessary animation; honor system motion settings. |

**Functional guardrails for any later implementation:** run the existing Android unit, Firestore
and RTDB emulator suites, debug build and lint, then targeted device regressions appropriate to the
changed surface. Inspect UI diffs for unchanged auth/session ownership/claims/provider linking,
registration/profile validation, slot availability/serial allocation, booking idempotency,
cancellation eligibility/history/release, publication retry behavior and environmental/contact
permission handling. Tests must use approved local/emulator data; no live mutation is authorized.

For reminders, explicitly preserve **Dhaka date/time, required AM/PM selection, custom modes and
validation, default 24-hour/6-hour reminders, 30-second alarm/vibration and early Dismiss**.
Verify notification/exact-alarm permission fallbacks, no late feedback after dismissal and existing
account/epoch guards. Cosmetic consistency is not permission to reschedule or redesign reminders.

### 8. Audit delivery boundary

This addendum is the only project-file change for this task. The existing dirty tree is not
treated as this audit's implementation. A SHA-256 comparison against the pre-edit baseline of
377 project inputs found only `docs/ui-professionalization-batch25.md` changed; no source,
application resource, configuration, test or other documentation file was added/removed/changed.
The historical report text is preserved, and the documentation whitespace check passed.
No application build/tests or runtime UI checks were run for this documentation-only addition.
The next decision is **review/approval of the proposed phases**, not automatic code implementation.

---

## UI/UX modernization implementation handoff

The subsequent user instruction authorizes implementation and quality verification of the audited
improvements. The planning-only audit above is preserved as historical evidence, not overwritten
or represented as a completed device acceptance test.

See [UI_UX_MODERNIZATION_HANDOFF.md](UI_UX_MODERNIZATION_HANDOFF.md) for the unified design system,
17-screen/component coverage, Gender exposed-dropdown correction, **mandatory exact Login error
copy**, booking/list/details and Admin improvements, accessibility metadata, source-supported
performance changes, verified backup, task-specific file inventory, actual verification results
and remaining physical-device checks. No commit, push, production mutation or deployment is part
of this implementation.
