# Firestore profile rules security audit — 2026-11-08

## Result and scope

The audited **local** `firestore.rules` is ready for **manual rules-only deployment** for the
policy below. It has **not been deployed**. No production data, custom claims, accounts, Android
source, RTDB rules or other development stages were changed during this audit.

Reference: committed rules at `11b1fd854ab7ce01961057158efaa0d941850a41` (also unchanged from
`3e127f9ed84cfeb089af1fceb2578551cea2278d`). The pre-existing, uncommitted Batch 4 profile additions
were preserved and hardened, not reset. All 78 unrelated modified/untracked files were fingerprinted
before the audit and remained byte-for-byte unchanged, including the existing ZIP/assets.

Audited rules SHA-256:
`064F58FD30A12369169ABA6BA8449386B9EBDE6286C682EC5C24AE62A274CC92`.

## Findings and local corrections

| Finding in the partial profile rules | Correction |
|---|---|
| DOB used only a shape regex, accepting impossible, pre-1900 and future dates | Exact ISO shape plus real calendar-component round trip; 1900 through the current **Asia/Dhaka** date using server `request.time` |
| `isAdmin()` unconditionally allowed every `/users/{uid}` get, including other Admin/unknown-role documents | Cross-account Admin gets require the target document's exact `role == 'Patient'`; owner gets remain available for session checks, including missing profiles |
| `!isAdmin()` treated present false/null/malformed Admin claims as Patient profile writers, unlike established Patient authorization | `ownsPatientProfile(uid)` requires the authenticated path owner and **absence** of the `admin` claim |
| Update validation compared metadata equality without fully validating the final metadata/schema types | Explicit `affectedKeys().hasOnly(...)` and complete final profile validation, including timestamp `createdAt` |
| No root `firebase.json` or `.firebaserc` existed | Added a Firestore-only deployment config pointing to `firestore.rules`, with default project `medibook-2c3a4` |

The malformed-claim issue was an inconsistent Patient-write gate, **not** a demonstrated route to
Admin privileges: existing true-claim checks and forbidden profile role/claim fields remain intact.

### Exact profile policy

- Create: authenticated UID must equal the `/users/{uid}` path; no `admin` claim. Required fields:
  `fullName`, `email`, `phone`, `role`, `createdAt`. Only optional additions: `dateOfBirth`, `gender`.
  Role is exactly `Patient`, email matches the Firebase token, and `createdAt == request.time`.
- Update: same Patient owner only; the existing role must be `Patient`. Only **actual changes** to
  `fullName`, `phone`, `dateOfBirth`, `gender` are allowed. Email, role, createdAt, UID/identity fields,
  Admin flags/claims, persisted age, unlisted metadata and other patients' documents cannot change.
  Final schema and field types remain valid; a batch containing a forbidden write is rejected fully.
- Full name and phone retain their established validation/limits. Present DOB must be a real
  `yyyy-MM-dd` date between `1900-01-01` and today in Dhaka. Leap years and impossible dates are
  checked server-side. Present gender must be exactly `Male`, `Female`, `Other`, or
  `Prefer not to say`. Present null/empty/invalid personal details are rejected.
- DOB and gender are **independently optional** for legacy profiles on create/update and existing
  schedule/appointment gates. Owners may remove these optional fields; none are fabricated.
- Read: signed-in users can get their own document. A trusted Boolean `admin=true` token can
  get any **Patient-role** document by a known UID, but not another Admin/unknown-role document.
  This is document-level access, not field redaction or proof of a particular appointment link.
  Appointment Details chooses the patient UID; Rules do not restrict Admin access to one selected
  appointment. This explicit trusted-Admin policy is the intended scope of this audit.
- No client, including Admin, can list/query the users collection (including a Patient-role filter
  or collection-group query), delete profiles, or use profile subcollections to store authorization.
  Admin has **no** profile create/update privilege. Server/Admin SDK access bypasses client Rules
  and must remain restricted to trusted operators; only that trusted environment may assign claims.

`validOptionalPatientDetails()` is shared by registration, final update validation, schedule profile
validation and appointment profile validation. Other authorization/transaction blocks were compared
with both the pre-audit file and committed rules and are unchanged: Doctor CRUD, schedules,
availability, reservation/locks, Patient/Admin cancellation, date blocks, serial counter/mappings,
appointment ownership queries and the deny-all fallback. The new optional fields do not change the
17/12/6-field appointment/inventory/lock contracts or any existing appointment snapshots.

## Emulator evidence

Executed only the Java/JUnit, loopback-only Firestore harness:

```powershell
.\gradlew.bat :firestore-rules-tests:test --console=plain
```

The harness owns a pinned emulator, refuses an occupied/unapproved endpoint, and uses only
**`demo-medibook` at `127.0.0.1:8080`**, never production or real credentials. Allow/deny assertions
use synthetic end-user tokens, not the emulator setup bypass. Denial means exactly HTTP 403 and
`PERMISSION_DENIED`, not an arbitrary exception or transport failure.

1. Original 97 tests against the unmodified partial rules: 96 passed, one failed because the old
   test expected Admin's Patient-profile get to be forbidden. Updated only that expectation for the
   explicitly requested new read permission; user-list and cross-patient denial checks are retained.
2. Added 35 focused profile tests and one deployment-config canary. Against the partial rules,
   **133 tests / 7 failures** reproduced the validation/authorization gaps before correction.
3. After the local corrections: **133 tests / 7 classes, 0 failures, 0 errors, 0 skips**.

| Class | Tests |
|---|---:|
| ClientRulesCanaryTest | 5 |
| DoctorRulesTest | 18 |
| ScheduleRulesTest | 22 |
| AppointmentRulesTest | 31 |
| InventoryRulesTest | 9 |
| SlotSerialRulesTest | 13 |
| PatientProfileRulesTest | 35 |

Coverage includes create/edit/optional legacy data, wrong types/shapes, leap/calendar/future dates,
immutable identities/role/email/metadata, malformed claims, cross-account/anonymous/Admin denial,
direct Admin gets versus listings, forbidden subcollections, atomic hostile batches and an extended
profile's full booking → Patient cancel → rebooking → Admin cancel flow with an unchanged fixed
serial. Profile edits preserve historical appointment snapshots and cannot book with stale name/
phone snapshots. No expression/access-limit diagnostics occurred. `git diff --check` passed.

Results: `tests/firestore/build/test-results/test/TEST-*.xml` and
`tests/firestore/build/emulator/firestore-emulator.log` (ignored build outputs).

## Configuration and manual deployment

- `firebase.json`: Firestore rules only, exact root path `firestore.rules`; no RTDB, indexes,
  Functions or other deploy targets were added.
- `.firebaserc`: default project `medibook-2c3a4`, matching the existing Android Firebase client
  configuration and package `com.example.medibook` (no client key is reproduced here).
- `firebase.emulators.json`: unchanged; the Java harness still reads this isolated emulator
  config and cannot be redirected to production by `.firebaserc`.
- Firebase CLI was **not found on PATH** here. Manual deployment requires the CLI and an
  appropriately authorized Firebase login. No CLI installation/login/deploy was performed.

From `D:\AndroidStudioProjects\MediBook`, **manual command only — NOT executed**:

```powershell
firebase deploy --only firestore:rules --project medibook-2c3a4 --config firebase.json
```

## Remaining limits / sign-off

No unresolved new authorization regression was found for this policy and tested matrix. This is
not a mathematical proof of every Firebase/production behavior, an IAM/credential audit or an
Android end-to-end test. Admin claims must remain trusted. Pre-existing invalid stored details may
need an owner correction; corrupt protected metadata requires a trusted repair, not relaxed Rules.
No production profile inventory was inspected or migrated.

The established **trusted-Admin non-overlapping slot-publication invariant** remains: Rules cannot
query every sibling slot to prove global non-overlap. Its existing limitation test still passes;
this audit neither changes nor claims to solve that invariant. Admin direct Patient-profile gets
are intentionally not appointment-link scoped, as described above.

RTDB rules SHA-256 remained
`5F003F9042E2273FF5CBA51D765698EEC6DD45E1FAB7251116D420D42682A134`.
No deployment, production writes, reset/stash/clean, stage/commit/push or unrelated stage work.
