# Batch 2 — live displays, Doctor URLs and light UI

**Update:** Batch 2.5 replaces visible Q tokens with durable Firestore slot numbers while preserving
the RTDB wire format. See [current UI/numbering setup](ui-professionalization-batch25.md).

## What is authoritative

Firestore remains the ONLY booking/cancellation store. Its atomic reservation/release writes,
retained history, Patient self-cancellation, Admin individual cancellation and derived Completed
are unchanged. Tokens still use `AppointmentPolicy.queueToken(startMinute)`: **Q0601 / Q0631**.
No serving progression, renumbering, waits, push notifications, backend or Batch 3 components.

## RTDB demonstration and trust boundary

| Path | Writers | Readers |
| --- | --- | --- |
| `display/status/{patientUid}/{appointmentId}` | That UID (no Admin claim), or Boolean `admin=true` | Same UID or Admin; list query requires `changedAt`, last ≤20 |
| `display/bookings/{appointmentId}` | Boolean `admin=true` only | Admin only; same bounded list query |
| `display/queues/{doctorId}/{Dhaka-midnight-millis}/{Q-token}` | Boolean `admin=true` only | Signed-in accounts without an Admin claim, or Boolean Admin; one doctor/date only |

RTDB cannot read Firestore, prove bookings, or verify Firestore profile roles. A private self hint
is deliberately NOT trusted: a Patient can forge their own hint but cannot write an Admin feed,
another Patient's branch or the shared queue. Before rendering an alert, the app reads the
appointment with Firestore `Source.SERVER` and compares ID, owner, Doctor, date, token, status and
timestamp. Invalid/stale hints are hidden. Queue rows contain only status/time keyed by token,
never patient UIDs, appointment IDs, names or phone numbers; they are explicitly nonauthoritative.

After Firestore reconciliation, an independent best-effort RTDB transaction writes the own status.
An Admin cancellation also writes the affected Patient's status and shared token. No RTDB Task
is chained into booking/cancellation success and no RTDB handler writes Firestore.

While the **Admin dashboard is open**, a server-only Firestore listener mirrors the most recent
20 changed appointments into these displays. Re-entry/reconnection reconciles that bounded window;
it is not a complete/durable outbox, and older unsynced records can be missed. Shared displays may
lag when no Admin is online. Guaranteed automatic cross-user delivery requires a trusted backend
(not implemented/required here). No Patient-written feed is ever used by the Admin bridge.

Listeners detach on stop/account changes. Reconnect retries reads/verification; stable per-ID
fingerprints and timestamp-checked RTDB transactions avoid duplicate writes/alerts and older queue
generations overwriting newer ones. Cards show the latest update rather than accumulating popups.
Transport/setup failures are isolated and visible, with manual retry. No disk persistence enabled.
Spark quotas and unbounded stored history still need manual monitoring; Rules are not rate limits.

## Manual Firebase setup — NOT performed by this session

1. In **medibook-2c3a4**, create Realtime Database on the existing Spark plan in **locked mode**.
   Select an available region. Copy the exact HTTPS root URL; do not guess the region/instance.
2. RTDB → Rules: manually publish the entire local `database.rules.json`. Never use open/test rules.
   The current trusted Boolean `admin=true` claim is reused; no secrets/roles are written by clients.
   Sign out/in after any authorized claim change. RTDB needs no manually seeded data.
3. Paste the URL into `app/src/main/res/values/firebase_display.xml` (`medibook_database_url`) and
   rebuild/install. Blank uses an existing FirebaseOptions database URL, otherwise disables only live
   displays. No credential file needs editing. Missing URL/undeployed Rules must not block bookings.
4. Firestore → `(default)` → Rules: **manually redeploy the entire updated `firestore.rules`** for
   optional `imageUrl`. New Add/Edit writes include it even if blank. Legacy Doctors still load and
   book normally. All other access controls and appointment schemas are retained.

## Doctor photo flow and UI

Manually upload a photo to Cloudinary Free (or another public HTTPS image host), copy its delivery
URL and paste into Admin Add/Edit Doctor → Doctor photo URL. Blank clears the photo. Validation
allows up to 2048 ASCII URL characters, no credentials, port, fragment or whitespace. Glide renders
Directory/Profile images with a local Doctor avatar for missing/invalid/failed URLs. No upload,
API key, signed backend or paid storage. Cleartext traffic is disabled, including HTTP redirects.

Local vectors provide a no-text Calendar + Cross adaptive/monochrome launcher, API 24/25 fallback,
Doctor avatar and Doctor + Calendar login illustration. Dashboards use small feature cards;
blue `#1565C0`, teal `#00BFA5`, white/light theme and Android `sans` (Roboto). Existing forms/navigation
are retained. Stock raster icon files remain preserved but the anydpi resources override them.

## Verification and short device check

Run `./gradlew :app:testDebugUnitTest :firestore-rules-tests:test :rtdb-rules-tests:test :app:assembleDebug :app:lintDebug`.
Both Rules harnesses are Java-only, owned loopback `demo-medibook` emulators; never production.
RTDB tests also use a genuine SSE subscription. See `tests/rtdb/README.md` for the pinned JAR.

Fresh combined `--rerun-tasks` result: **342 Android tests / 31 classes, 84 Firestore tests / 5
classes, 18 RTDB tests / 1 class**, zero failures/errors/skips. Debug assembly succeeded; lint
reported **0 errors, 15 noncritical warnings** (14 prior warnings plus Glide update availability).
No device was connected to ADB. No production Rules/data/claims were changed by this session.

After manual setup/install: Patient books → own Home status; Admin dashboard opens → booking card
and shared doctor/date Q token; Admin cancels individually → Patient Home cancellation card;
Patient also self-cancels another future booking → retained history and eligible slot release.
Check image present/blank/bad URL, another account's isolation, offline/reconnect, screen rotation
and logout. Device verification is pending unless an ADB device is connected.
