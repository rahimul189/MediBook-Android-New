# Admin Doctor CRUD — Firestore integration

## Deployment boundary

**No production Firebase deployment/data/claim change is performed by the development agent.**
The user has deployed Batch 1 Rules and verified publishing/booking on device. Batch 2 adds the
optional `imageUrl` field and requires another manual full Rules publication before new Doctor
Add/Edit saves. RTDB setup and current display limitations are in [Batch 2 setup](batch2-live-display.md).
The remaining milestone notes below describe the original CRUD integration, not a new live check.

## Schema: `doctors/{doctorId}`

These eight fields remain required; optional `imageUrl` is also allowed. ID comes from the document path:

| Field | Stored value |
| --- | --- |
| `name` | Nonblank single-line string, at most 100 characters |
| `specialization` | Nonblank single-line string, at most 100 characters |
| `qualifications` | Nonblank single-line string, at most 200 characters |
| `consultationFee` | **Integer hundredths**, 0–99,999,999 inclusive |
| `biography` | String, at most 1000 characters; empty is allowed; ordinary CR/LF paragraphs allowed |
| `active` | Boolean; a new form defaults to false |
| `createdAt` | Firebase server timestamp on create; immutable on update |
| `updatedAt` | Firebase server timestamp on every write; later than the previous version on update |
| `imageUrl` (optional) | Blank or validated public HTTPS image URL, ≤2048 characters; old records may omit it |

Fee decision: Firestore cannot directly store `BigDecimal`. Integer hundredths avoid binary
floating-point rounding and allow strict integer bounds in Rules. UI `500.25` is stored as
`50025`, and `0.10` as `10`; mapping reconstructs a two-decimal `BigDecimal`. The UI still
accepts 0–999999.99. Currency is not inferred or assigned in this milestone. Do not manually
enter major-unit numbers/doubles in this field. There were no persisted Step 4 preview
records to migrate; unexpected existing schemas are rejected, never silently converted.

## Repository and UI behavior

- `DoctorRepository` uses the default Firestore database. No delete API is exposed.
- New document IDs are allocated locally once per editor and retained across rotation/task
  restoration. A transaction reads that ID and creates only if absent. A lost acknowledgement
  cannot cause a retry to overwrite an existing record or silently allocate a duplicate ID.
- Reads use `Source.SERVER` and reject cache-only/pending-write snapshots. Empty is shown only
  after a successful server read of an empty collection. Invalid documents produce a safe
  error instead of being silently skipped or shown as an empty directory.
- The editor receives only an ID and independently loads the record from the server. Edit
  and status transactions compare the original `createdAt` and `updatedAt` with the current
  server version; concurrent changes produce a conflict. Reload is explicit and warns that
  server data replaces unsaved form entries. There is no last-writer-wins retry of stale edits.
- Edits use `update`, not replacement/upsert, and omit `createdAt`/ID. Status writes contain
  only `active` and server `updatedAt`. Missing records are not recreated. Nothing is deleted.
- Writes use online transactions rather than queued offline `set`/`update` operations. A Save
  result or status-success message is emitted only after the Firestore Task succeeds. Pending
  controls are disabled, back/cancel are guarded, and no list row changes optimistically.
  Firestore may internally retry transaction conflicts; the UI never automatically resubmits.
- Lifecycle ViewModels retain in-flight Tasks without retaining Activities. List/form listeners
  attach only while started. Each protected screen retains the existing account reload,
  forced-claim refresh and server owner-profile check. Wrong-role/signed-out sessions use
  the existing clear-task routing. No record or saved UI state grants Admin authorization.
- Repository calls are bound to the current UID, including checks around transaction reads.
  Completion callbacks are ignored after account changes/session termination. Logout stays
  available but **cannot cancel or undo a transaction already submitted to the server**.
- After a process interruption, no write is automatically repeated. The restored editor requires
  a manual server reload before Save. If a new ID is still absent, the same ID is reused; if
  present, the loaded record is reviewed as an edit. Never interpret a transport error as proof
  that a submitted write did not commit. Refresh/reload first rather than adding a duplicate.
- The list refreshes after re-entry and confirmed status changes, with a manual Refresh/Retry
  control. This is a one-shot directory read, not a real-time listener or paginated feature.

## Security Rules decisions

- Existing Patient `users/{uid}` creation/owner-get rules are unchanged. User listing, updates,
  deletion and client Admin-profile creation remain denied, including for Admin users.
- A trusted **Boolean** `admin == true` custom claim grants doctor read/create/update access.
  A role string in a user profile never grants these powers. Android additionally checks its
  existing Admin profile/session policy. The backend does not rely on that UI gate.
- Patient doctor reads require authentication, **no `admin` claim key**, an existing own
  profile with `role == 'Patient'`, and `active == true`. Missing profiles, profile-only Admins,
  malformed/false/null Admin claims and unauthenticated requests are denied. This is the
  safest interpretation of authenticated Patient reads, not unauthenticated public access.
- A Patient list query must include `where('active', '==', true)`. Rules are not filters;
  unrestricted and inactive-doctor queries are denied. Patient browsing UI is not added yet.
- Allowed fields/types, required/nonblank text, control characters, length limits, integer fee
  bounds, Boolean status and server/immutable timestamps are validated by Rules independently
  of Android validation. Document IDs cannot be supplied as extra fields.
- `allow delete: if false` applies even to Admin clients. Schedules, appointments, subcollections
  and unrelated collections remain denied. No broad authenticated or Admin catch-all exists.
- Previously issued Admin tokens may remain valid until refreshed/expired. These changes do
  not add server-side immediate claim revocation or override the trusted Admin SDK's bypass.

## Automated checks

Android (offline tests do not contact Firebase):

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

Security Rules: see [the isolated emulator suite](../tests/firestore/README.md). It uses only
fixed local endpoint `127.0.0.1:8080`, project `demo-medibook`, and synthetic identities/data.
No Firebase login, project selection, private key or production configuration is needed.

Step 77 migrates Rules integration tests to Java/JUnit using the official Firestore client REST API,
not the Admin SDK. Run `.\gradlew.bat :firestore-rules-tests:test --console=plain`; Java 21+ and the
pinned Java emulator JAR are required, not JavaScript/Node. The original 15 cases are retained in
`DoctorRulesTest`, alongside the 22 Schedule cases, four transport/authorization canaries, the
Step 78 exact-ID Patient profile-query regression and 23 Step 86 appointment cases
(**65 total**). Step 86 changes only the local `firestore.rules`; no deployment is implied.
See `tests/firestore/COVERAGE.md` for the one-for-one security coverage map and current limitations.

Historical verification for the Doctor-only implementation: 68 Android unit tests and 15 Firestore emulator
test cases passed. Debug assembly and lint passed (0 lint errors, 13 warnings).
Production deployment and Android CRUD device testing are not implied by these results.

## Device/test-project review after separately approved rule publication

Use an emulator or development Firebase project and synthetic data, never production test
doctor records or real patient information without separate authorization.

1. Verify empty/loaded/loading/error states, manual Refresh/Retry, and denied doctor access
   under the old Patient-only rules. No denial may be represented as an empty successful list.
2. Submit invalid/zero/decimal/maximum fees and required/overlong fields; invalid input must
   not send a transaction. Verify integer hundredths in the test database.
3. Add a doctor and verify exactly eight fields and both server timestamps. Edit and verify
   the same document ID/`createdAt`, a new `updatedAt`, and no duplicate document.
4. Deactivate/reactivate, including Cancel. Verify only `active`/`updatedAt` change and no
   deletion occurs. Success must wait for server acknowledgement and the list must reload.
5. Rapidly tap Save/status and rotate during reads/writes. Verify a single user submission,
   pending UI guards and confirmation handling. Background/foreground must recheck Admin.
6. Simulate loss of connectivity/permission denial and restore it. No offline/pending result
   may claim success. Check the stable ID after an ambiguous outcome before retrying.
7. Edit the same record in two test clients. The stale editor/status action must fail with a
   conflict and require a manual reload rather than overwrite the newer server version.
8. Kill/restore the process during Save. Verify reload-before-retry, stable ID and no automatic
   write. Logout/change account during an operation; late callbacks must not reopen Admin UI.
9. Verify Patient active-only get/filtered-query access and denied inactive/unfiltered/write
   requests using the emulator suite. Do not assume Android navigation tests prove Rules.
10. Review small-screen landscape, keyboard, large fonts, TalkBack, Cancel/Back and Logout.

No schedules, appointments, Patient-screen changes, new authentication roles or Realtime
Database integration are included. Builds/unit tests cannot verify production deployment.
