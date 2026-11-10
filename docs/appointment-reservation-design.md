# Step 88–89 — Firestore reservation design and rules for confirmed appointments

This is the historical Step 88–89 contract/verification record. The subsequent Batch 1 app
implementation and current manual setup are in [appointment-batch1.md](appointment-batch1.md).
References below to “no repository/UI in this step” describe Step 89, not the current app.

## Status and scope

This document records the Step 88 design and its Step 89 implementation in the local
`firestore.rules` and Java emulator tests. Nothing is deployed. Step 89 changes only the local
rules, the Firestore emulator test module and documentation; it does not add UI, a repository,
Cloud Functions, Node.js or Firebase Console/production data. The reservation contract is verified
against the pinned Firestore emulator; production deployment remains a separate review.

The design targets the Firebase Spark plan. It uses only Firestore documents, atomic batched
writes/transactions and Security Rules; no Cloud Functions or paid backend is assumed.

## Requirements and hard constraints

- A successful booking is created directly as persisted `Confirmed`; there is no Pending step.
- Persisted statuses are only `Confirmed` and `Cancelled`. Completion is derived from `endAt` and
  is never written.
- Duplicate bookings of an Admin-published slot and attempts to book unlisted or partially
  overlapping intervals must be rejected under concurrent clients, including a malicious client
  that bypasses the app UI. Pairwise non-overlap of the Admin-published inventory itself remains a
  trusted-Admin invariant (see the limitation below).
- The appointment write and its reservation write must be atomic, and cancellation must atomically
  release the reservation while retaining cancelled appointment history.
- Admin can block a doctor/date and bulk-cancel upcoming appointments, including partial failure.
- Firestore is the source of truth; Admin is protected by the Auth custom claim `admin == true`.
- Firestore Rules cannot query documents or iterate a collection. They can only read a fully
  specified document path with `get()`, `exists()` or `getAfter()`.
- Rules access limits: 10 document accesses per single-document operation and 20 per transaction or
  batched write. The previous 10-call limit also applies to each operation inside a batch.
- `getAfter()` sees the projected state of the whole transaction/batch before commit. Firestore
  provides atomic, serializable write transactions and retries or rejects conflicting commits.

## Alternatives considered and rejected

### 1. Exact-slot reservation only

A lock document keyed by the exact interval, or by `scheduleId + startMinute + endMinute`, prevents
only an identical lock ID from being created twice. It does not prevent a client from creating a
different lock whose interval overlaps the first. Firestore Rules cannot query sibling locks or
prove that no interval overlaps. A malicious client can always choose a different start time. This
is safe only when a trusted publisher first reduces the bookable intervals to a pairwise
non-overlapping inventory — which is the chosen design, not a standalone exact-slot lock.

### 2. Per-minute or per-5-minute bucket locks

To cover an arbitrary 5–180 minute appointment, the client would have to create/check every
covered bucket. A 180-minute appointment needs up to 36 five-minute locks. Checking 36 projected
documents exceeds the 20-access batch limit and the 10-access per-operation limit. Coarser buckets
create false conflicts for arbitrary start minutes and 5-minute durations, and a malicious client
could omit buckets unless every one is checked. This is not implementable within Rules limits.

### 3. Per-doctor/day transactional ledger

A client transaction can read existing intervals and choose a non-overlapping one, but a malicious
client can ignore the transaction and write directly. Rules cannot iterate the ledger or run an
overlap query, so they cannot prove that a newly written interval does not overlap existing
entries. A single ledger document is also a hot-document contention point and cannot be inspected
element-by-element with a dynamic-length array.

### 4. Client transaction over schedules/appointments without a lock

This works only for honest clients. Rules cannot query the appointment collection, and a malicious
client can bypass the client-side check. It is rejected as a security mechanism.

### 5. Cloud Functions / trusted backend

A trusted backend could validate schedule generation and inventory non-overlap. It is outside the
Spark/no-paid-backend scope. It remains the smallest safe alternative if the Admin trust boundary
below is unacceptable.

### 6. Appointment document ID as the uniqueness lock

A slot-derived appointment ID such as `<slotId>-g<generation>` would collide across doctors for the
same date/time because `appointments` is a top-level collection. Including the doctor/date would
exceed the 128-character appointment-ID schema unless the schema were relaxed. The chosen design
keeps appointment IDs as normal client-generated IDs (UUID or Firestore auto-ID) and uses a
path-scoped, create-only booking lock instead.

## Chosen design

Admin publishes a pairwise non-overlapping **slot inventory** for each doctor and Dhaka date. A
patient booking is one atomic three-write batch:

1. update the inventory slot from `available` to `booked`, incrementing its generation;
2. create an immutable **booking lock** at the deterministic slot/generation path;
3. create the appointment as `Confirmed`.

Rules require all three companion writes and cross-check them with `get()`/`getAfter()`; omitting
one denies the batch. Each cancellation requires two companion writes: appointment
`Confirmed -> Cancelled` plus slot release (`available` or `blocked`). These are per-reservation
write sets, not a Rules check of total batch size: additional independently authorized writes are
not categorically forbidden. Clients should submit one booking or cancellation per batch to stay
within the reviewed budgets. The booking lock remains immutable as history and prevents reuse of
that generation.

### Document paths and fields

#### Existing appointment document

`appointments/{appointmentId}`

The existing 17-field schema is unchanged. `appointmentId` is a normal client-generated UUID or
Firestore auto-ID, matching `[A-Za-z0-9_-]{1,128}`. The appointment stores no new `slotId`,
`generation` or lock field: Rules derive the slot/lock paths from `doctorId`, `appointmentDate`,
`startAt` and `endAt`. Persisted `status` is `Confirmed` or `Cancelled`.

#### Slot inventory document

`doctorSlotInventory/{doctorId}/dates/{dateKey}/slots/{slotId}`

- `dateKey` = decimal epoch milliseconds of Dhaka local midnight for `appointmentDate`.
- `slotId` = `s<startAtMillis>-<endAtMillis>`.
- `appointmentDate`: Dhaka-local-midnight Timestamp.
- `dayOfWeek`: ISO Monday 1 … Sunday 7, matching `appointmentDate`.
- `scheduleId`: the schedule that produced the slot.
- `startMinute`, `endMinute`: Dhaka wall-clock minutes; `0 <= start < end <= 1439`.
- `startAt`, `endAt`: exact UTC Timestamps, zero nanoseconds, matching the date and minutes.
- `status`: `available`, `booked` or `blocked`.
- `generation`: integer starting at 0; increments by exactly one on every successful booking and
  is unchanged by cancellation or blocking.
- `appointmentId`: current booking ID, or null when not booked.
- `createdAt`, `updatedAt`: server Timestamps.

Exact field count: 12. Only Admin creates slot documents and defines their intervals, initially as
`available` or `blocked`. Admin may later flip an unbooked slot only between `available` and
`blocked` and must change the status; Admin cannot set `booked`, change `generation`, change
`appointmentId` or alter the interval. Patients may only perform the booking or cancellation
transition described below. Deletion is denied for every role.

#### Booking lock document

`doctorSlotInventory/{doctorId}/dates/{dateKey}/slots/{slotId}/bookings/{bookingLockId}`

- `bookingLockId` = `g<generation>` for the post-booking generation, for example `g1`.
- `appointmentId`: the created appointment document ID.
- `patientUid`: the booking patient's Auth UID.
- `scheduleId`: same schedule as the slot and appointment.
- `startAt`, `endAt`: same interval as the slot and appointment.
- `createdAt`: server Timestamp equal to `request.time`.

Exact field count: 6. Booking locks are create-only and immutable: update and delete are denied
to everyone. They are the hard uniqueness anchor for each slot generation and the idempotency /
reconciliation record if a booking response is lost.

#### Doctor/date block document

`doctorDateBlocks/{doctorId}/dates/{dateKey}`

- `date`: Dhaka-local-midnight Timestamp.
- `reason`: bounded string (0–200 characters).
- `createdAt`, `updatedAt`: server Timestamps.

Exact field count: 4. Existence of this document means the doctor/date is blocked. Only Admin may
create/update/delete it. Booking checks `!exists(blockPath)`; the block is active immediately on
commit. Bulk cancellation then releases/cancels each appointment, and every slot released while the
block exists must become `blocked`, never `available`. Operationally, Admin must verify zero
upcoming Confirmed appointments remain before reopening the date. Rules cannot query that count;
block deletion itself requires only the Admin claim. Deleting the block is an explicit Admin action:
cancellation never deletes it, and deleting it does not automatically reopen slots that were
released to `blocked`; Admin must explicitly flip those slots back to `available`.

### Read policy and reconciliation

Patients can read only their own appointments and must filter appointment queries by their UID.
Admin can read appointments and their embedded patient snapshots, but cannot list users or read
other users' profiles. Valid Patients can read slots only for active doctors; booking locks and
date blocks are Admin-read-only. A Patient must reconcile a lost response using the appointment ID
generated before submission and its own appointment document, not a forbidden booking-lock read.

### Deterministic identities

Pure-Java formulas, mirrored in Rules with `string(timestamp.toMillis())`:

- `slotId = "s" + startAt.toMillis() + "-" + endAt.toMillis()`
- `dateKey = string(appointmentDate.toMillis())`
- `bookingLockId = "g" + generation`
- logical reservation key = `doctorId + "/" + dateKey + "/" + slotId + "/" + bookingLockId`

The path scopes the same slot ID by doctor and date; the booking lock ID scopes it by generation.
Appointment IDs remain independent and globally unique.

## Atomic write sequences

### Booking (one batch/transaction, 3 writes)

1. Read the slot; require `status == available`, generation `G`, `appointmentId == null`.
2. Set slot: `status = booked`, `generation = G + 1`, `appointmentId = newAppointmentId`,
   `updatedAt = request.time`.
3. Create `bookings/g(G+1)` with the appointment ID, patient UID, schedule and interval.
4. Create `appointments/{newAppointmentId}` with status `Confirmed`, exact 17 fields and
   `createdAt = updatedAt = request.time`.

Rules cross-checks (final Step 89 shape):

- **Appointment create** reads the Patient profile, Doctor, Schedule, current slot, projected slot,
  projected lock and date-block existence (7 accesses). It enforces:
  - the exact 17-field appointment schema, `status == Confirmed` and
    `createdAt == updatedAt == request.time`;
  - explicit integer types for fee, weekday and minute fields; numeric equality alone accepts
    integer-valued doubles and is not sufficient to preserve the persisted schema;
  - `patientUid == request.auth.uid`, a valid Patient profile, and matching name/phone;
  - an active Doctor with matching name/specialization/fee, and an active Schedule whose day,
    window, duration and alignment contain the interval;
  - exact equality between appointment and current slot `scheduleId`, `appointmentDate`,
    `dayOfWeek`, `startMinute`, `endMinute`, `startAt` and `endAt`;
  - current slot `available`, generation `G >= 0`, `appointmentId == null`;
  - projected slot `booked`, generation `G + 1`, this appointment ID,
    `updatedAt == request.time`, with only `status`/`generation`/`appointmentId`/`updatedAt`
    changed;
  - projected lock `g(G+1)` has exactly the six lock fields and matching appointment, patient,
    schedule and interval, with `createdAt == request.time`;
  - `startAt > request.time` and no date block.
- **Slot booking update** reads the projected lock and projected appointment (2 accesses):
  - the current slot is `available` and unbooked; the new generation is exactly
    `resource.data.generation + 1`;
  - path `dateKey`/`slotId` match the slot's date and interval;
  - only `status`, `generation`, `appointmentId` and `updatedAt` may change, so the slot
    definition is immutable and no unrelated slot can be edited;
  - projected lock `g(newGeneration)` references this appointment, patient, schedule and interval
    with `createdAt == request.time`;
  - projected appointment is `Confirmed`, owned by `request.auth.uid`, for this doctor/schedule/
    date/interval, with `createdAt == updatedAt == request.time`.
- **Lock create** reads the projected slot and projected appointment (2 accesses):
  - `bookingLockId` equals `g(slot.generation)` and the lock has exactly six fields;
  - projected slot is `booked` with this lock's appointment ID, patient, schedule and interval,
    and `updatedAt == request.time`;
  - projected appointment is `Confirmed`, owned by the lock's patient, for this doctor/schedule/
    date/interval, with `createdAt == updatedAt == request.time`.
- Admin cannot create a booking: `isBookingActor()` rejects any `admin` claim, and anonymous
  requests are rejected.

All three document writes are in one batch/transaction. `getAfter()` sees the batch's projected
state, and Firestore commits the batch atomically. Omitting any one write makes at least one rule
fail.

### Cancellation (one batch, 2 writes)

1. Read the appointment and slot. Require appointment `Confirmed`, `startAt > request.time`,
   patient owner or Admin, slot `booked`, slot `appointmentId == appointmentId`, and the current
   immutable lock at `g(slot.generation)` referencing the same appointment.
2. Update appointment: only `status` (`Confirmed -> Cancelled`) and `updatedAt` may change.
3. Update slot: `appointmentId` becomes null and `generation` is unchanged. If the date block
   exists, `status` must become `blocked`. Without a block, a Patient may only set `available`;
   Admin may set `available` or `blocked`. `updatedAt = request.time`.

Rules cross-checks:

- **Appointment update** reads the current slot, current lock and projected slot: 3 accesses for
  Admin, plus 1 profile read (4) for a Patient. It requires the projected slot release, lock
  identity, exact path/date/interval match, unchanged generation and definition, and
  `slotAfter.updatedAt == request.time`.
- **Slot cancellation update** reads the current appointment, current lock, projected appointment
  and date-block existence (4 accesses). It requires projected appointment `Cancelled` with
  `updatedAt == request.time`, current appointment `Confirmed`, Patient owner or Admin
  authorization, `startAt > request.time`, and the blocked-date release rule above.
- Both writes must be in the same batch; a lone appointment update or lone slot update is denied.

The appointment document is never deleted. The immutable booking lock remains as history. A new
booking of the released slot uses generation `G + 1`, so it creates a new lock and cannot collide
with the cancelled generation `G`. A slot released to `blocked` stays blocked after the block
document is deleted until Admin explicitly sets it back to `available`.

### Admin date block and bulk cancellation

1. Create the `doctorDateBlocks/{doctorId}/dates/{dateKey}` document first. New bookings for that
   doctor/date are denied immediately by the `exists` check.
2. Query upcoming Confirmed appointments for that doctor/date with a client-supplied `startAt`
   bound. Rules independently recheck `startAt > request.time` on every cancellation; a slot that
   starts between the query and commit is no longer cancellable. Keep the block and reconcile
   such failures instead of bypassing the time restriction. Cancel one appointment per atomic
   two-write batch (appointment + slot), with the block document already committed. Each slot
   release becomes `blocked`.
3. Optionally set remaining `available` slots to `blocked`; a slot-block update never overwrites a
   `booked` slot.
4. After eligible cancellation batches succeed, verify no eligible upcoming Confirmed appointments
   remain before an intentional reopen, then delete the
   block document (or keep it permanently if the date is permanently unavailable). Deleting the
   block does not reopen any slot automatically; only an explicit Admin slot update can set a
   `blocked` slot back to `available`.

Partial failure is safe: the block remains active, already-cancelled appointments stay cancelled,
and retries skip them. A booking that committed before the block is visible to the post-block
query and is cancelled; a booking attempted after the block commits is denied. A patient cancel
racing with Admin cancel is serialized; one transition wins, and the slot is released once.

## Static Rules access-budget review

The following are source-level call counts for the successful single-reservation paths, assuming
no cache benefit. They are conservative budget estimates, not measured runtime counters. The
create path reads the profile once and uses direct Doctor/Schedule `get()` calls instead of
redundant `exists()` + `get()` pairs. Cached accesses may not count toward the runtime limit.

| Operation | Document accesses |
| --- | ---: |
| Appointment create | profile 1 + doctor 1 + schedule 1 + current slot 1 + projected slot 1 + projected lock 1 + block exists 1 = **7** |
| Slot booking update | projected lock 1 + projected appointment 1 = **2** |
| Lock create | projected slot 1 + projected appointment 1 = **2** |
| Booking batch total | **11**, below the 20 batch limit; each operation below 10 |
| Patient appointment cancellation update | profile 1 + current slot 1 + current lock 1 + projected slot 1 = **4** |
| Admin appointment cancellation update | current slot 1 + current lock 1 + projected slot 1 = **3** |
| Slot cancellation update | current appointment 1 + current lock 1 + projected appointment 1 + block exists 1 = **4** |
| Patient cancellation batch total | **8**, below 20 |
| Admin cancellation batch total | **7**, below 20 |
| Inventory slot create | doctor 1 + schedule 1 = **2** |
| Date-block create | doctor 1 = **1** |
| Admin unbooked-slot flip / date-block update or delete | **0** external accesses (`isAdmin()` only) |
| Patient slot read | profile 1 + doctor 1 = **2**; Admin **0** |
| Booking-lock/date-block reads | Admin **0**; Patient denied without an access |

The emulator suite completed with **zero** "maximum of 10 document accesses" and **zero**
"maximum of 20 document accesses" errors. The final rules also avoid the expression-evaluation
limit: the first implementation hit Firestore's 1000-expression ceiling on the three-write booking
batch, so Step 89 removed redundant re-derivation while keeping the security links. Slot definitions
are immutable and validated at Admin publication, so the booking rules compare the projected
slot/lock to the trusted appointment and current slot instead of re-checking the full 12-field
inventory schema; the lock helper enforces the exact six-field set while callers compare every
value; and cheap branch guards keep cancellation/booking functions from evaluating for unrelated
updates. The final emulator log contains **zero** expression-limit errors. Rule coverage confirms
projected documents for concurrent bookings (`race-a`), concurrent cancellation (`cancel-race`),
owner cancellation (`cancel-own`), reopened-slot booking (`opened-slot-booking`) and overlapping
inventory (`overlap-long`); the coverage export records evaluated values, not document-access call
counts. It cannot establish exact runtime counts or cache behavior. Passing the valid operations
shows those tested paths stayed within the emulator's limits; it is not a production-wide limit
proof or a guarantee for larger batches.

Continuation review also reproduced a numeric-type loophole: `50025.0` compared equal to the
integer Doctor fee, and `diff()` did not flag retyping inventory generation `0` to `0.0`.
Explicit integer guards now protect appointment creation/cancellation and slot booking,
cancellation and Admin flips. Regression assertions cover fee/weekday/minute/generation retypes;
they do not relax any rule to obtain a passing result. Forgery tests now use coherent appointment
IDs so each denial isolates its intended mutation, followed by a valid positive control.

`get()` on a missing document returns null and the subsequent field comparison denies. `getAfter()`
on a document not written in the batch returns its current state; the `== request.time` checks and
the required projected status ensure the related writes really are in the same batch.

## Concurrency, cancellation and doctor-absence behavior

### Concurrent bookings of one slot

Two clients read generation `G` and both aim at generation `G + 1`. They derive the same
`bookings/g(G+1)` path. The booking lock is create-only, so only one create can succeed;
Firestore's atomic batch rolls back the loser's slot update and appointment create. The slot
booking rule independently requires current `available` and exactly `G + 1`; a stale or different
generation is rejected. A duplicate appointment ID is also rejected by the appointment
create/update rules. The appointment ID is deliberately not the lock because a top-level
appointment ID must be globally unique across doctors.

### Partial overlap

If the published inventory is pairwise non-overlapping, any subset of live bookings is
non-overlapping: each appointment interval must equal its inventory slot interval, and one slot
can have at most one live generation. Partial overlaps can therefore arise only from an
Admin-published overlapping inventory or from a schedule change that leaves an old slot in place.
The publisher helper filters schedule-derived overlaps and existing reserved intervals; the
trusted-Admin invariant is stated below.

### Cancellation races

- Cancel vs cancel: the first appointment update wins; the second sees `Cancelled` and is denied
  with `PERMISSION_DENIED`. Exactly one of two simultaneous cancellation batches commits; the slot
  is released once. For user experience the client may treat an already-cancelled appointment as
  success after re-reading it, but it must not describe the denied second Rules write as committed.
- Cancel vs booking: a booking requires `available`; a booked slot rejects it. After a successful
  cancellation the next booking uses a new generation/lock. A booking that wins before the
  cancellation remains active until cancelled; a cancellation that wins releases the slot for a
  later booking.
- Cancel vs Admin block: the block prevents new bookings; cancellation is still allowed. If the
  block is active at commit, the slot must be released to `blocked` (Patient and Admin). Without a
  block, a Patient may release only to `available`, while Admin may choose `available` or
  `blocked`. The block document continues to deny booking until removed, and deleting it does not
  reopen slots already released to `blocked`.

### Retry and lost responses

The app generates and retains the appointment ID before the batch. If the response is lost after
commit, the slot is `booked`, the lock `g(G+1)` exists and references the appointment. Replaying the
batch, with either the same ID or a new one, is denied. The Patient should read its own appointment
by the original ID to reconcile the outcome; lock reads are Admin-only and slot reads can become
unavailable after Doctor deactivation. If the batch did not commit, a retry can succeed only while
the slot is still available at the expected generation. A repeated cancellation request is denied
by Rules after the first commit; the client must reconcile by reading the already-`Cancelled`
appointment rather than claiming the denied write succeeded.

### Schedule or doctor changes

Slot definitions are immutable. Booking re-checks that the current schedule is active, contains
the interval, has the same duration and has the correct alignment; booking also checks that the
doctor is active and its snapshot fields match. If a schedule is shortened, disabled or changed so
the slot no longer fits, new bookings are denied. Existing appointments remain valid records and
can still be cancelled. Admin should block affected dates and regenerate inventory after schedule
edits. Doctor deactivation prevents new bookings but not cancellation; Admin can block the date and
bulk-cancel upcoming appointments.

### Partial failure

Booking and cancellation batches are atomic: there is no half-booked or half-released state. Slot
inventory publication is create-only and the helper can filter against all existing intervals for
the date, so a retry publishes only missing slots. Bulk cancellation is one atomic batch per
appointment; the block document is the safety net while retries complete. Do not automatically
delete a block after a bulk operation. Intentional reopening requires checking that no eligible
upcoming Confirmed appointments remain; past appointments still persist as Confirmed and derive
their Completed display state from `endAt`.

## Trusted-Admin limitation

Firestore Rules cannot query or iterate sibling slot documents. Therefore they **cannot prove**
that the Admin-published inventory is pairwise non-overlapping, nor that two concurrent Admin
publishers did not each add an overlapping slot. The non-overlap invariant is a trusted-Admin
operational invariant established by the publisher using `SlotInventory` and by passing every
existing interval for the date as reserved. A malicious Admin or a publisher bug can still create
overlapping slots.

This is an explicit trust boundary, not a Rules guarantee. Admin is already the privileged role
that can block dates, cancel appointments and change schedules; the collision would not let an
ordinary patient escalate privileges. `InventoryRulesTest.overlappingAdminInventoryAndBookingsRemainPossible`
documents that both overlapping Admin-published slots and both bookings are accepted. If a hard
guarantee against a malicious Admin or a buggy publisher is required, the smallest safe alternative
is a trusted backend/Cloud Functions publisher on Blaze, or a separately reviewed server-side
validator. No such service is introduced here.

All other guarantees — duplicate prevention, exact-interval matching, atomic appointment +
reservation creation, atomic cancellation/release, date blocking and retention of cancelled
history — are enforced by Rules against non-Admin clients.

## Step 88 Java helpers and tests

Added:

- `app/src/main/java/com/example/medibook/utils/ReservationIdentity.java`
  Deterministic slot IDs, Dhaka date keys, generation lock IDs, reservation keys, half-open
  overlap and strict-before-start cancellation eligibility.
- `app/src/main/java/com/example/medibook/utils/SlotInventory.java`
  Deterministic, pairwise non-overlapping inventory from `AppointmentSlots` candidates, with
  reserved-interval filtering for already-published/booked/blocked intervals.
- `app/src/test/java/com/example/medibook/utils/ReservationIdentityTest.java` (13 tests)
- `app/src/test/java/com/example/medibook/utils/SlotInventoryTest.java` (11 tests)

Coverage includes duplicate slots, partial overlaps, containment, adjacency, arbitrary minute
alignment, 5-minute durations, different doctors/dates/generations, leap/year boundaries, Dhaka
midnight keys, device-timezone independence, cancellation at and around the start boundary, and
unmodifiable/sorted output.

## Step 89 implementation

Completed in the local rules and emulator tests:

- Rewrote the reservation section of `firestore.rules`: `Confirmed`/`Cancelled` only; atomic
  three-write booking; immutable `g<generation>` locks; coordinated two-write cancellation;
  Admin-only inventory publication and status flips; Admin-only date blocks; role-scoped reads.
- Replaced the obsolete 23-case Pending appointment suite with 27 booking, cancellation, read and
  real-concurrency cases, and added 8 Admin inventory/lock/date-block cases.
- Added REST-null helpers and partial-update batch helpers so Admin-created inventory and
  owner-seeded inventory are both testable without rewriting immutable fields.
- Registered `InventoryRulesTest` in `RulesIntegrationSuite`.
- Updated this design, `tests/firestore/README.md` and `tests/firestore/COVERAGE.md`.
- Continuation review tightened integer-type preservation, isolated forged-field assertions, and
  added blocked-date Admin release and Patient atomic block-delete/booking denial checks.

No `AppointmentRepository`, booking UI, inventory UI, production index or deployment is added in
this step.

### Emulator test matrix

| Class | Cases | Scope |
| --- | ---: | --- |
| `ClientRulesCanaryTest` | 4 | Anonymous, identity, claim and transport canaries |
| `DoctorRulesTest` | 16 | Preserved Doctor/Patient profile and directory policy |
| `ScheduleRulesTest` | 22 | Preserved weekly schedule policy |
| `AppointmentRulesTest` | 27 | Atomic booking, partial batches, duplicates, forged snapshots, cancellation, blocking, reads, retries, real concurrent booking/cancellation |
| `InventoryRulesTest` | 8 | Admin publication/schema/paths/roles, slot flips, lock immutability, date-block lifecycle, inactive doctor, explicit overlap limitation |
| **Total** | **77** | 4 canaries + 73 existing/new policy cases |

### Concurrent tests

Two real REST commits are started from a latch. Same-slot booking asserts exactly one success;
duplicate cancellation also asserts exactly one success. The loser must receive
`403 PERMISSION_DENIED`. The slot ends with exactly one generation increment and one appointment;
cancellation leaves the slot released once and the immutable lock in place.

## Residual risks and verification items

- The Admin inventory non-overlap invariant is not Rules-provable (see above). Concurrent Admin
  publishers require a single-writer procedure or separate coordination; the helper only filters
  the intervals it is given.
- Deterministic identity formatting was verified in the emulator against the
  `s<millis>-<millis>`, numeric date key and `g<generation>` paths.
- The static 7/2/2 booking and 4+4/3+4 cancellation access estimates fit the 10/20 limits, and
  tested operations completed without an access-limit error. The export cannot prove exact
  runtime counts. The initial 1000-expression overrun was fixed without removing the security
  links described above.
- Firestore Rules cannot prove that slots were generated from the current schedule; booking does
  re-check active schedule window/duration/alignment, but Admin must keep inventory current.
- Bulk cancellation needs a composite index; a missing index is a failed-precondition, not a
  permission denial. The UI must surface it and retry after index creation.
- Spark quotas apply to inventory publication (up to hundreds of slot writes per doctor/date) and
  bulk cancellation. Monitor usage; no paid backend is assumed.
- Production deployment remains out of scope. No Cloud Functions, Node.js, Cloudinary, Realtime
  Database or Firebase Console/production data was added or changed.

## Verification status

Verified on 2026-11-07:

- Final continuation verification (all tasks rerun):
  `.\gradlew.bat :firestore-rules-tests:test :app:testDebugUnitTest :app:assembleDebug --rerun-tasks --console=plain`
  — **BUILD SUCCESSFUL**, **77 emulator tests across 5 classes** and **300 Android tests across
  26 classes**, zero failures, errors or skips. Debug APK
  `app/build/outputs/apk/debug/app-debug.apk`, **13,352,982 bytes**. This rebuilt APK supersedes the
  earlier 13,366,800-byte incremental artifact.
- Final Rules SHA-256: `17F59C6D9AE60B4578110F436DAFF38C87F0736AA04BF50D5790DBA27BF3C1EB`.
- Emulator coverage was streamed to disk (217,305,593 bytes); the final log contains no
  document-access-limit or expression-limit diagnostics. Exact runtime access counts are not
  available from that export.
- `git diff --check` exited 0 (only LF/CRLF warnings). All Steps 83–89 work remains uncommitted.

No Rules deployment, production Firebase operation, UI/repository change, commit or push was part
of this verification.
