# Step 67 — Admin Doctor Schedule Management UI

> Historical UI-only milestone. Step 69 replaces the temporary behavior described below with
> [server-backed schedules](admin-doctor-schedule-firestore.md). Use that document for current behavior.

## Scope and navigation

- Manage Doctors now has **Manage Schedule** on each doctor row, including inactive doctors.
  It uses the existing verified Admin/navigation guards and is disabled during Doctor CRUD
  loading/writes, just like the existing row actions.
- The selected doctor's ID/name are a display-context snapshot from the existing server-backed
  list. They never grant authorization. Schedule navigation does not use the Doctor editor's
  Firestore-success result launcher, change a doctor's status, or add a Doctor repository call.
- Both new Activities are non-exported. Each repeats the existing `checkSession()` gate on
  every `onStart`: account reload, forced claim refresh and server owner-profile verification.
  Content stays hidden while checking and on stop. Wrong-role/signed-out sessions use the
  existing clear-task routing. Previews/results are also bound to the selected doctor and
  initiating UID; no role or authorization is accepted from extras or saved state.
- Toolbar Back/Cancel and overflow Logout remain available. Material dialogs, RecyclerView,
  View Binding, XML forms and existing teal/blue/white styles are reused.

## These are local previews, not saved schedules

- The list starts empty. Only entries the user explicitly enters are shown. No schedules are
  fetched, seeded, fabricated, written to Firestore, or used for patient booking.
- Every row, both forms, confirmations and result messages identify **temporary local previews**.
  Save preview validates and returns UI state only. Edit preserves preview identity; disable/
  enable changes only its local Boolean status. Entries are not deleted by status actions.
- Previews are owned by one schedule-screen instance. They survive rotation and task-state
  restoration via primitive Activity bundles and remain while opening its form. Returning to
  Doctor Management and reopening the schedule screen starts empty again. There is no singleton,
  database, preferences or file store. Up to 100 entries bound saved-state size; this is a UI
  safety limit, not a proposed server limit or database schema.
- The loading indicator is the real existing Admin session check, **not a simulated schedule
  request**. Invalid context/results/restored entries have explicit local error states. Retry
  returns to the valid local previews without claiming to fetch saved schedules.
- Form results wait until the returning screen verifies its Admin session again. Malformed,
  duplicate, cross-doctor/account or missing-edit results are rejected, never silently upserted.
- Doctor CRUD code, its repositories/models/ViewModels, Patient/authentication code, Firestore
  rules and the user's unrelated IDE settings are preserved. No rule publication, production
  test writes, schedule repository, appointments or authentication role is introduced.

## Validation and time display

- ISO weekday numbering: Monday = 1 through Sunday = 7. The UI displays localized string
  resources, not raw numbers or `Calendar`'s different numbering.
- Time pickers honor the device's 12/24-hour preference. Weekly values are stored locally as
  minutes after midnight and displayed in the existing hospital timezone (`Asia/Dhaka`), not
  shifted to the handset timezone. No date, UTC scheduling, DST recurrence or booking logic
  is inferred in this UI-only step.
- Day, start, end and duration are required. Start/end range from 00:00 to 23:59. End must be
  later than start on the **same day**; equal, reversed and overnight windows are rejected.
- Duration: whole numbers from **5 to 180 minutes**, in **5-minute increments**, no longer
  than the selected window. These explicit local defaults can be reviewed before backend
  integration. A window need not divide exactly into slots; no partial/real slots are generated.
- Multiple windows per day can be previewed. Overlap, availability/capacity, appointments and
  future server validation are deliberately outside this UI-only milestone.
- New forms have no preselected weekday/times/duration and default inactive. Time-picker
  initial positions are just controls, not application records. Cancel never creates an entry.
- Confirmed selections, typed duration and status survive rotation. A time picker that has
  **not** been confirmed is closed on stop/rotation and can be reopened; restored picker
  dialogs are dismissed before the new screen's session check, preventing an auth-gate bypass.

## Review checklist (physical Android phone/emulator)

1. Admin → Manage Doctors → Manage Schedule. Confirm the selected name, hospital timezone,
   empty preview state and temporary-local notice. Returning/reopening must start empty.
2. Save an empty form; check day/start/end/duration errors and first-error focus.
3. Try equal/reversed/overnight times, 0/negative/fractional/too-large/invalid-step durations,
   and a slot longer than the window. No invalid preview may be added.
4. Enter your own non-sensitive preview and save. Verify weekday/start/end/duration/status,
   chronological weekday ordering and explicitly local-only feedback. No Firebase success text.
5. Edit and save; confirm one existing preview changes, not an appended duplicate. Disable/
   enable with Cancel once and Confirm once; no doctor status or stored availability changes.
6. Cancel/Back from a changed form. Keep editing, then discard. Existing previews stay unchanged.
7. Rotate the list and form, including while a picker is open and after Save. Confirm retained
   inputs/list, closed unconfirmed picker, a fresh session check, and correct deferred result.
8. Change handset timezone and 12/24-hour preference. Verify wall-clock values stay hospital
   local, and formatting remains understandable. Try landscape, large fonts, keyboard, TalkBack
   and keyboard navigation; headers, notices, entries and forms must remain scrollable.
9. Logout/background/re-enter. Back must not reveal Admin content. Patient, expired/disabled,
   signed-out or changed-account sessions must not view/edit prior local previews.
10. Regression-check Doctor Add/Edit/Deactivate/Reactivate separately; schedule actions must
    never invoke Doctor writes. No Firestore rule change/deployment is required for previews.

Offline unit tests cover validation boundaries, immutable status copies, ordered/local ownership,
identity-preserving edits, rejected malformed/duplicate results and bounded preview state.
Build/unit/lint checks do not replace device UI/authentication review.
