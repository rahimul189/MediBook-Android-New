# Java Security Rules replacement coverage

The baseline contained 37 JavaScript test cases: 15 Doctor/Patient cases and 22 Schedule cases.
The Java classes below preserve those cases and their invalid-value loops, not merely their test
counts. Four additional client/transport canaries distinguish actual Rules checks from fixture
bypass. Step 89 replaces the obsolete 23 Pending/manual-approval appointment cases with 27
confirmed-booking/cancellation cases and adds 8 Admin inventory/lock/date-block cases. Current
suite total after Batch 1: **82 tests** (the Step 89 baseline was 77). Batch 2 adds **2 image URL
cases**, bringing the suite to **84**; the appointment read/query case also checks the Admin-only
`updatedAt`/last-20 bridge query. Image tests cover legacy records, valid/blank/removable URLs,
invalid schemes/types/credentials/ports/fragments/lengths and unchanged ownership/timestamp rules.

## DoctorRulesTest — baseline 15 cases

| Java method | Retained security assertions |
| --- | --- |
| `anonymousDoctorAccessDenied` | Anonymous get, unrestricted/active-filtered query and create denied |
| `patientReadsActiveDoctorsWithFilteredQueriesOnly` | Active get/query allowed; inactive/unfiltered/inactive-filtered reads denied |
| `missingProfilesAndProfileOnlyAdminsDenied` | No-profile and profile-role-only Admin accounts cannot read/write Doctors |
| `malformedAdminClaimsGrantNeitherRole` | String/numeric/false/null claims do not grant Admin or Patient doctor access |
| `booleanClaimAdminReadsBothStatusesAndLists` | Boolean Admin end-user token allows active/inactive gets and unfiltered query |
| `patientDoctorWritesDenied` | Patient create, edit, disable, enable and delete denied |
| `adminCreatesEightFieldSchemaWithServerTimestamps` | Correct fields, integer hundredths and equal resolved create/update timestamps |
| `adminEditsAndChangesStatusWithoutChangingIdentity` | Stable document ID/createdAt, advancing updatedAt, disable/enable, delete denied |
| `requiredAndAllowedDoctorFields` | Every missing field and unknown id/role/ownerUid/schedule rejected |
| `doctorTypesTextControlsLengthsAndFeeBounds` | Wrong types, Unicode blanks, controls, lengths, negative/overflow/fraction/string/NaN/infinite fee rejected |
| `doctorValidBoundaryValues` | Text limits, maximum/zero fee and normal biography line breaks accepted |
| `doctorTimestampAndImmutableFieldValidation` | Forged/missing timestamps, changed createdAt, deleted fields, extra IDs and nonboolean status rejected |
| `patientProfileOwnershipAndRoleProtection` | Valid own creation/get allowed; other-owner access, listing, role changes and deletion denied |
| `patientProfileValidationAndAdminWriteDenial` | Profile role escalation, extra claim fields, mismatched email, phone/name/time errors and Admin profile mutations denied |
| `unrelatedCollectionsAndDoctorSubcollectionsDenied` | Patient/Admin/anonymous cannot read/write top-level schedules, unrelated or private subcollections; appointments now have dedicated coverage below |

## Step 78 Patient live-query addition — 1 case

| Java method | Added assertion |
| --- | --- |
| `patientProfileQueryUsesActiveStatusAndStableDocumentId` | Exact `active=true` + document-ID profile query reads an active doctor; inactive/missing/deactivated paths are denied, not returned empty |

## ScheduleRulesTest — baseline 22 cases

| Java method | Retained security assertions |
| --- | --- |
| `anonymousScheduleAccessDenied` | Anonymous get/list/active-filtered query/create/update/delete denied |
| `booleanClaimAdminReadsAllSchedules` | Boolean Admin reads both statuses and directories under active/inactive parents |
| `validPatientReadsOnlyActiveSchedulesOfActiveDoctor` | Active get and active-filtered query allowed; inactive/unfiltered reads denied |
| `patientCannotReadInactiveOrMissingParents` | Active schedule of inactive/missing parent denied, including filtered query |
| `patientAccessTracksDoctorAndScheduleStatus` | Admin parent/schedule changes immediately affect Patient reads |
| `patientScheduleWritesDenied` | Patient create/edit/disable/enable/delete denied |
| `missingProfilesAndProfileOnlyAdminsDenied` | No-profile and profile-role-only Admin cannot read/query/create schedules |
| `malformedAdminClaimsGrantNeitherRole` | False/string/numeric/null/object claims denied for reads/queries/create/update/delete |
| `patientProfileMustBeCompleteAndValid` | Every missing profile field, extra fields, invalid values/types and role/email mismatches rejected |
| `tokenEmailMustMatchProfile` | Missing/wrong token email cannot use profile for schedule access |
| `adminCreatesSixFieldMinuteSchema` | Exactly six fields, ISO day, integer minutes/duration and resolved server updatedAt |
| `adminEditsStatusesWithoutChangingParentOrIdentity` | Stable schedule ID, retained disabled record, unchanged Doctor document |
| `adminCannotDeleteOrAtomicallyMoveSchedule` | Admin deletion and delete+copy batch denied atomically |
| `requiredAndAllowedScheduleFields` | Every missing field and extra/mutable IDs/timezone/createdAt/role/appointments rejected |
| `weekdayTimeTypeRangeAndOrderingValidation` | Invalid days, fractional/string/minute limits, equal/reversed/overnight windows rejected |
| `durationPolicyAndWindowFit` | Nonpositive/out-of-range/fractional/string/NaN/infinite/non-five-multiple/window-too-short rejected |
| `validMinuteWeekdayDurationBoundariesAndRemainder` | First/last minute, ISO endpoints, minimum/maximum slot, remainder and inactive accepted |
| `wrongTypesAndNullsForEveryScheduleField` | Null/map for every field and string/numeric active rejected |
| `serverTimestampAndVersionValidation` | Forged/missing/nonadvancing timestamps and deletion of every required field rejected |
| `parentExistenceAndSafeIdentifierBounds` | Missing parent and unsafe/overlong IDs rejected; 128-char/underscore/dash IDs and inactive parent Admin create accepted |
| `unrelatedCollectionsAndCollectionGroupDenied` | Patient/Admin/anonymous forbidden paths and schedule collection-group queries denied |
| `overlappingNewIdsRemainAllowedByRules` | Boolean Admin token can commit valid overlapping new IDs; no global overlap exclusion claim |

## AppointmentRulesTest — Step 89 + Batch 1 — 31 cases

| Java method | Added security assertions |
| --- | --- |
| `patientBooksAvailableSlotAsAtomicConfirmedReservation` | Valid three-write batch creates the exact 17-field Confirmed appointment, marks the slot booked at generation 1, and creates the six-field lock; all server timestamps advance |
| `incompleteBookingBatchIsDeniedWithoutPartialWrites` | Slot+lock, slot+appointment and lock+appointment batches are denied atomically; the slot stays available and no lock/appointment is left behind |
| `duplicateAndUnlistedIntervalBookingsAreDenied` | Duplicate slot booking, uncoordinated appointment create, reuse of one appointment ID for another slot, and an interval without Admin inventory are denied; a distinct available slot still books |
| `patientCannotForgeIdentityOwnershipOrRole` | Forged UID/name/phone and wrong types, missing profile, wrong token email, profile-role-only Admin, malformed Admin claim, Admin booking and anonymous booking are denied |
| `incompleteOrInvalidPatientProfileBlocksBooking` | Every missing profile field plus blank/overlong/typed name, wrong email/phone, role escalation, invalid createdAt, extra admin field and missing/wrong token email block booking |
| `inactiveOrStaleDoctorAndScheduleReferencesAreDenied` | Forged doctor snapshot/fee, inactive/missing Doctor, disabled/missing/orphan Schedule, wrong weekday, stale Doctor name and changed Schedule window are denied; re-enabling/restoring restores booking |
| `exactSeventeenFieldSchemaAndConfirmedStatusRequired` | Every missing field, extra identifier field, Pending/Completed/Cancelled/lowercase/blank/unknown/non-string status, invalid types/ranges/timestamps and null/map values are rejected, including integer-valued doubles for fee/weekday/minutes |
| `appointmentIdentityPatternIsBounded` | Dot, space, 129-character and non-ASCII appointment IDs are denied; 128-character and underscore/dash IDs are accepted |
| `dateArithmeticAndDhakaWeekdayAreEnforced` | Non-midnight/UTC/off-by-millisecond dates, forged start/end/create/update timestamps, Sunday/month/year boundaries and a past slot are checked; the future Sunday/month/year bookings succeed |
| `forgedSlotAndLockContentsAreDenied` | Forged slot generation/appointment/status/schedule/createdAt/extra field and numeric retypes, plus forged lock appointment/patient/schedule/startAt/createdAt/extra field are denied with no partial state; coherent companion IDs isolate each mutation and the unchanged batch succeeds |
| `patientCancelsOwnFutureBookingAndReleasesSlot` | A valid two-write cancellation changes the appointment to Cancelled, releases the slot to available at the same generation, nulls appointmentId and retains the immutable lock; a repeat is denied |
| `cancellationRequiresBothWritesExactGenerationAndRelease` | A lone appointment update, lone slot update, wrong generation, release to booked and Patient blocked-release without a date block are denied; only the coordinated exact-generation pair commits |
| `cancellationCannotChangeImmutableAppointmentOrSlotFields` | Extra appointment fields, forged updatedAt, changed slot scheduleId or generation, and integer-to-double retypes in appointment/slot fields are denied; appointment and slot remain confirmed/booked |
| `crossPatientAndPastCancellationsAreDenied` | Another Patient cannot cancel the owner's appointment; a past Confirmed appointment cannot be cancelled by its owner |
| `releaseOfAnotherAppointmentsSlotIsDenied` | A cancellation batch that updates appointment A but releases slot B is denied; both appointments and both slots remain unchanged |
| `adminCancelsFutureAppointmentButNotPastOne` | Admin may cancel a future appointment and choose blocked release; Admin cannot cancel a past appointment |
| `pendingCompletedAndTerminalWritesAreDenied` | Seeded Pending, Completed and Cancelled appointments reject every tested status write for Admin and Patient; Confirmed permits only the exact Cancelled transition with the coordinated slot release |
| `appointmentSlotAndLockDeletionIsDeniedForEveryone` | Owner Patient, other Patient, Admin and anonymous cannot delete the appointment, slot or lock; the appointment remains readable |
| `appointmentReadsAndQueriesRespectOwnershipAndAdminRole` | Owner get and filtered query succeed; cross-owner/anonymous access and unfiltered/cross-owner queries are denied; Admin reads both appointments and unfiltered queries but still cannot read user directories |
| `inventoryLocksAndBlocksHaveRoleScopedReads` | Patients read active-doctor slots but not inactive-doctor slots; Admin reads both; booking locks and date blocks are Admin-only; anonymous reads are denied |
| `dateBlockPreventsBookingAndUsesExplicitReopen` | An Admin block denies booking even while the slot says available; a Patient's combined block-delete/booking batch is denied atomically; reason updates keep the block; Admin deletion allows booking, while an explicit slot reopen is separately covered |
| `blockedDateCancellationMustReleaseToBlocked` | With an active block, Patient and Admin available-release are denied; blocked-release succeeds; rebooking stays denied; deleting the block does not reopen the slot; Admin must flip it to available first |
| `bulkCancellationDoesNotImplicitlyUnblockAnInactiveDoctor` | Two Admin cancellations under a block release both slots to blocked and retain the block; after block deletion and an explicit reopen, an inactive Doctor still blocks booking; re-enabling restores it |
| `rebookingAfterCancellationRequiresNextGenerationLock` | Reusing generation 1 or the old lock is denied; rebooking at generation 2 succeeds and both immutable locks remain |
| `retryAfterCommittedBookingDoesNotDuplicateState` | Replaying the same appointment ID and retrying with a new ID are denied after the first commit; exactly one appointment, one lock and generation 1 remain |
| `concurrentSameSlotBookingsAllowExactlyOneWinner` | Two real REST booking batches start from a latch; exactly one commits, the loser gets 403, and the slot/lock/appointment state contains one winner |
| `concurrentDuplicateCancellationsAreSerialized` | Two real REST cancellation batches start from a latch; exactly one commits, the loser gets 403, the appointment is Cancelled once, the slot is released once and the lock remains |
| `patientCancelsUsingMinimalReleasePatchAfterDoctorDeactivation` | Patient cannot read inactive-doctor inventory/private lock but can read own appointment and atomically cancel using only the three mutable release fields; generation/lock/history remain intact |
| `blockedDateMinimalPatchCanRetryBlockedWithoutReadingPrivateBlock` | Available-release is denied atomically under a private block; the minimal blocked-release retry succeeds without changing generation or removing the block |
| `patientReconcilesByStableAppointmentIdNotPrivateLockOrMissingDocument` | Missing appointment get is denied; the same ID becomes own-readable after booking/cancellation, while another Patient and private lock access remain denied |
| `clientReadWriteTransactionUsesRepositoryReadAndWriteShape` | Actual REST begin/read/commit booking transaction reads profile/Doctor/Schedule/slot and writes the exact three companions; an appointment-only-read cancellation transaction still releases after Doctor deactivation |

## InventoryRulesTest — Step 89 + Batch 1 — 9 cases

| Java method | Added security assertions |
| --- | --- |
| `adminPublishesExactTwelveFieldInventory` | Admin creates exact 12-field available and blocked slots with matching Dhaka date, ISO weekday, minute interval, zero generation, null appointmentId and server timestamps |
| `adminPublicationTransactionCreatesMissingSlotAndSkipsBookedSlot` | Actual Admin REST transaction reads missing inventory/block paths and Doctor/Schedule, publishes exactly twelve fields, then retries with no writes after the slot is booked; an overwrite is still denied |
| `inventorySchemaPathsScheduleAndStatusAreStrict` | Every missing field, extra field, missing/disabled Schedule, mismatched dateKey/slotId path, wrong date/day/minute/start/end, invalid status/generation/appointmentId/createdAt/updatedAt types and inactive Doctor are denied; no partial document remains |
| `inventoryWritesAreAdminOnlyAndReadsAreRoleScoped` | Patient reads active-doctor slots, not inactive ones; Admin reads both; anonymous reads and Patient/anonymous/string-admin writes are denied; Admin delete and stale update are denied; slot queries are role-scoped |
| `adminOnlyFlipsUnbookedSlotsAndCannotTouchBookedOnes` | Admin flips available/blocked; same status, booked, unknown, wrong-typed, generation, appointmentId, scheduleId, interval, createdAt and extra-field updates are denied; a booked slot cannot be blocked/reopened/deleted; blocked slots reject booking until explicitly reopened |
| `bookingLocksAreCreateOnlyImmutableHistory` | Standalone or uncoordinated locks are denied; a valid booking creates the exact six-field lock; update/set/delete are denied for Patient, Admin and anonymous; wrong generation path and stale generation batch are denied; the slot stays booked at generation 1 |
| `dateBlocksAreAdminOnlyFourFieldLifecycleDocuments` | Missing/extra fields, wrong path/date/reason/createdAt/updatedAt and Patient/anonymous/string-admin writes are denied; Admin create/read/query/reason-update/delete succeeds; date/createdAt/extra fields are immutable; re-creation after delete is allowed |
| `inactiveDoctorDateBlockRemainsAllowedForAdmin` | Admin can create, read and delete a date block for an inactive Doctor |
| `overlappingAdminInventoryAndBookingsRemainPossible` | Explicit trust-boundary test: overlapping Admin-published slots are accepted and both bookings commit, documenting that Rules cannot prove sibling non-overlap |

## Assertion integrity and limits

- Every denial must be **HTTP 403 with canonical `PERMISSION_DENIED`**, not a network failure,
  malformed request, missing index, 400, 401, 404 or arbitrary exception.
- Allow assertions use anonymous/no-header or synthetic Firebase ID-token clients only. The token's
  Boolean `admin` custom claim is an application role, **not** an Admin SDK or emulator-owner bypass.
- Emulator-owner requests are restricted to fixture clear/seed helpers, which return no assertion
  response. No real credentials, OAuth service accounts, ADC or Firebase Auth account/claim mutations
  are needed. Mock unsigned ID tokens are usable only against the owned emulator.
- Java uses Firestore's documented REST v1 client API. It does not test Android SDK caching, rendering,
  lifecycle, repository transactions or production IAM/ID-token signature validation. REST field
  representations and server-time transforms are explicit, so invalid data is sent to Rules rather
  than rejected by a Java model.
- The emulator does not enforce every production index/limit/transaction behavior. The two real
  concurrent tests exercise REST commits, but they are not an Android concurrency stress test.
- The reservation guarantee is per Admin-published inventory slot and generation: the exact slot
  path, available generation `G`, new create-only lock `g(G+1)` and Confirmed appointment must all
  appear in one batch. Rules cannot query sibling slots, so pairwise non-overlap of Admin inventory
  is a trusted-Admin invariant, not a Rules proof; the explicit overlap test documents this.
- Cancellation is future-only for both Patient and Admin (`startAt > request.time`). A blocked date
  forces release to `blocked`; without a block a Patient may release only to `available`, while
  Admin may choose `available` or `blocked`. Deleting a block never automatically reopens a slot.
- The immutable booking lock remains after cancellation; rebooking uses the next generation and a
  new lock. Cancelled appointments are retained; appointment, slot and lock deletion are denied.
- Integer-valued doubles compare equal to integers in Rules, and `diff()` may report no numeric
  value change. Explicit integer guards and retype regressions protect appointment fee/weekday/
  minute fields and slot generation/weekday/minute fields across all permitted transitions.
- Access counts are 7 (appointment create), 2 (slot booking update), 2 (lock create), 11 (booking
  batch), 4 Patient / 3 Admin (appointment cancellation update), 4 (slot cancellation update),
  8 Patient / 7 Admin (cancellation batch), 2 (inventory create) and 1 (date-block create). These are
  static successful-path estimates without cache savings, not exact runtime measurements; the
  export records evaluated values, not per-request access counters or cache behavior. Tested valid
  single-reservation batches passed and the emulator log contains no 10/20-document access-limit
  errors. The final rule shape also stayed
  below the 1000-expression evaluation budget after removing redundant re-derivation; the first
  shape exceeded it and was simplified without dropping the projected slot/lock/appointment links.
- Dhaka arithmetic assumes the current fixed UTC+06:00 offset with no DST. The `+21600000`
  millisecond shift is tested for Monday, Sunday and month/year boundaries, but a future DST rule
  change would require updating both Rules and Java.
- The stored appointment window ends at minute 1439; the theoretical next-midnight minute 1440 is
  not accepted, consistent with the reviewed Schedule and Java validation policy.
- Bulk cancellation/unavailable-date UI is excluded from Batch 1. The app uses single-field or
  unfiltered appointment queries and local filters, so no composite index is needed for this batch.
  Current app/repository behavior and manual setup are in `docs/appointment-batch1.md`.
- Step 89 changes only the local `firestore.rules`, the Java test module and documentation; no
  production operation, deployment, commit or push is part of this work.
