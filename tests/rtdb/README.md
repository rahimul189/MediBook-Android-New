# RTDB security/display tests (Java only)

Run `./gradlew :rtdb-rules-tests:test` (Windows: `.\gradlew.bat :rtdb-rules-tests:test`).
Requires a free loopback port **9000** and the official pinned Java emulator:

- Download: https://storage.googleapis.com/firebase-preview-drop/emulator/firebase-database-emulator-v4.11.2.jar
- Save: `%USERPROFILE%\.cache\firebase\emulators\firebase-database-emulator-v4.11.2.jar`
- SHA-256: `b70d99344caf17c98b6f910fa8f6edf32a7c016cb1035e8915f70d38901eb97f`
  (verified against firebase-tools' official `downloadableEmulatorInfo.json`).

The harness starts/stops only its own process and refuses an occupied port, different configured
host, bad checksum or non-loopback/non-demo request. It never reads Firebase credentials or touches
production. Setup alone uses the emulator owner control lane to install local `database.rules.json`
and clear fixtures. Allow/deny assertions and SSE subscriptions use synthetic end-user ID tokens,
including real Boolean Admin claims—not the owner bypass. No Node/JavaScript harness or CLI needed.

Coverage: private self hints, cross-patient denial, shared/Admin write denial, Boolean/malformed
claims, bounded/indexed queries, no global listing, strict schemas/keys/timestamps, immutable
identities, monotonic updates, deletion denial, sanitized queues, atomic hostile multipath updates,
and actual initial/change SSE events. A canary explicitly permits a forged PRIVATE hint: this is the
cross-database limitation, not authorization for shared feeds. Android model tests reject it unless
it exactly matches a server-read Firestore appointment and viewer ownership.

XML results: `tests/rtdb/build/test-results/test/`; process log: `tests/rtdb/build/emulator/`.

`DashboardRulesTest` covers owner-only private next-appointment pointers, Admin-only aggregate
read/write, exact schemas, counts/period consistency, monotonic revision reservation/publication,
stale-writer denials, conditional REST compare-and-set, real summary SSE updates, and denial of all
retired announcement access (without deleting retained data). The original booking/token/display
suite remains unchanged. RTDB cannot prove Firestore records: app model tests check authoritative
fallback and refuse a forged/mismatched private pointer.
