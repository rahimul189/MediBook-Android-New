# Patient registration and shared account authentication

This step uses Firebase Authentication and the **default Cloud Firestore database**.
It does not use Realtime Database. No Console changes or rules deployments are performed by the app.

**Doctor CRUD extension:** `firestore.rules` now also contains the locally reviewed doctor
schema/access rules described in [admin-doctor-firestore.md](admin-doctor-firestore.md).
The existing Patient profile rules and authentication implementation are preserved. Rule
publication is a separate owner action, not part of the coding agent's implementation.

## Manual Firebase Console prerequisites

1. Open https://console.firebase.google.com/ and select the Firebase project associated
   with this app's local `app/google-services.json`. Do not upload that file or service-account keys.
2. Open **Build > Authentication > Sign-in method** (first-time projects may show
   **Get started**). Enable **Email/Password**, then **Save**. Email-link login is not needed.
3. Open **Build > Firestore Database**. If the default database has not been created,
   create it in **production mode**, reviewing the location before confirming.
4. The reviewed Patient creation/owner-read rules must already be published before registration.
   Routing alone requires no new permissions. Doctor CRUD separately requires review/publication
   of the full new ruleset; the local file changing does not update deployed rules.
   The manual publication steps below are retained for setup reference only.

## Exactly how to publish the local rules manually

1. Open the project's **Build > Firestore Database** page and select the **(default)** database.
2. Open the **Rules** tab.
3. Save a copy of the currently deployed rules outside the repository if they need to be retained.
   Review any existing applications using this database: these rules intentionally deny every
   collection except a user's own profile and the specifically allowed doctor operations, and
   will replace the entire current ruleset. Do not publish during an implementation-only task.
4. Open the repository-root `firestore.rules` locally. Review its complete contents.
5. Replace the Console Rules editor contents with the **entire** local file. Do not append it
   to existing permissive rules: overlapping allow rules are combined with OR, not denied by precedence.
6. Review the changes and use the Console's rules testing tools where available.
7. Click **Publish**, and confirm the Console shows the new version as published.
8. Allow time for rules propagation before exercising the device test checklist.

These are **Firestore** rules only. Do not paste them into Realtime Database Rules.
No Firebase CLI deployment or service-account credential is required for this manual process.

## Security boundaries

- Signup creates only `users/{auth.uid}`, with exactly `fullName`, `email`, `phone`,
  `role: "Patient"`, and a server-generated `createdAt` timestamp.
- Names are nonblank and bounded to 100 characters. Email must match the authenticated token
  and be at most 254 characters. Phones are normalized to 7–15 digits with an optional leading `+`.
- Reads are owner-only direct document reads. Collection listing, profile updates, profile
  deletion, extra fields, forged timestamps, and role changes are denied.
- No Admin-wide profile permissions are enabled. The `admin == true` **custom claim** must
  only ever be assigned through a trusted administrative process outside Android. A profile role
  is never an Admin privilege. The rules let either account read only its own user profile;
  the separately reviewed doctor rules grant only the doctor operations described above.
- Shared login reloads the Auth account, force-refreshes the ID token (`getIdToken(true)`), and
  reads the owner's profile with `Source.SERVER`. The app applies this strict routing policy:

  | Signed Admin claim | Server profile role | Route |
  | --- | --- | --- |
  | Boolean `true` | `Admin` | Admin Dashboard |
  | `admin` key absent | `Patient` | Patient Home |
  | Any mismatch, including explicit `false`, null, string, or numeric Admin claim | Any | Sign out |
  | Unavailable claims or missing/invalid profile | Any | Sign out |

  A valid profile has exactly the five expected fields, valid name/phone, an email matching the
  authenticated identity, and a Firestore Timestamp. Public registration remains Patient-only,
  even if a valid Admin claim/profile pair is encountered during its final verification.
- The backend rules, not UI navigation, enforce database access. Doctor CRUD now has explicit
  claim-based rules. Further privileged data features require their own reviewed rules.
- Passwords are passed only to Firebase Authentication; they are not stored in Firestore,
  model objects, preferences, instance-state bundles, application logs, or repository state.
- Firebase manages persistent auth credentials. Each launch and protected-screen re-entry reloads
  the account, refreshes claims, and fetches the profile from the **server**, never from cached
  profile data for authorization. Offline/unverifiable sessions fail closed and are signed out.
- Logout clears Firebase Auth and the navigation task. Late session-check callbacks cannot
  reopen either protected screen. Each screen is non-exported and reveals its welcome content
  only after verification of its required role. A verified user arriving at the wrong screen is
  redirected to their own home; a claim/profile mismatch is signed out by the repository.
  No role is accepted from Intent extras, local preferences or saved UI state.
  Existing theme and fixed `Asia/Dhaka` configuration are unchanged.
- Authentication account enabling, claim/profile changes and the standalone utility are outside
  Android development. The owner has reported successful enabled Admin login on a phone.
  Disabled accounts must still fail authentication. The current doctor implementation updates
  the local Firestore rules only, never deploys them, and does not change Realtime Database rules.

## Partial registration and interruption handling

Authentication account creation and a Firestore write cannot be one atomic transaction:

- A server-accepted profile write is required before registration can enter Patient Home.
- If the profile write fails, the repository tries to delete **only the newly created Auth
  account**. After deletion succeeds, the UI explains the failure and allows a fresh registration.
- If deletion fails, the user is signed out and explicitly told that the account may remain.
  Do not repeatedly register with the same email. A trusted administrator must review it.
- If the profile was saved but the subsequent verification fails, the account/profile are
  retained and the user is signed out. Retry ordinary Login after resolving the problem.
- A process kill between the two services can also leave a missing profile. Login never
  silently creates one or grants access; it signs out and explains the recovery requirement.
- For an incomplete account, the administrator should inspect both **Authentication > Users**
  and the matching **Firestore > Data > users/{uid}**. If the profile is genuinely absent,
  remove the incomplete Auth account manually (or repair it through a separately reviewed,
  trusted administrative process), then let the patient register again. Do not delete a valid
  existing patient account as a workaround for a network or permissions error.
- A Firestore write interrupted by loss of connectivity can remain pending until the server
  responds. Loading/back-navigation guards stay active while the outcome is unknown; this
  intentionally avoids deleting an account while a write may still succeed. Restore connectivity.
  If Android terminates the process, use the incomplete-account recovery above if necessary.

Requests survive Activity rotation in an in-process repository, with listeners attached only
while Activities are started. Process death does not automatically resubmit registration.

## Device / test-project checks (not executed automatically)

Use test accounts in a development Firebase project; never real patient information.

1. Submit empty/invalid forms; confirm inline errors and no network submission.
2. Register a new patient. Verify the Auth user and exact five profile fields, including a
   server timestamp. Verify no password is in Firestore. Patient Home should show the name.
3. Rapidly tap submit and rotate during the request. Confirm no duplicate submission.
4. Logout, press Back, and reopen the app. It must remain on Login.
5. Login, close/reopen the app, and background/foreground Patient Home. Confirm server-backed
   session restoration. Wrong passwords and unavailable networks must not open Patient Home.
6. In a development project, deny profile writes temporarily to test partial-registration
   cleanup; restore the reviewed rules afterwards. Test failed cleanup/network interruption
   separately. No failure may be displayed as successful registration.
7. Test missing profiles, malformed profiles, owner-read permission denial, disabled/deleted
   Auth accounts, and Admin profiles without a Boolean true claim. All must fail closed.
8. With synthetic local unit-test inputs, verify both valid routes and every mismatch, including
   string/numeric/null/false claims. Permission/token/network failures must never publish a verified
   user. Existing failure handlers clear Firebase Auth and the repository session.
9. When separately authorized to test an enabled Admin account in a development environment:
   verify shared Login -> Admin Dashboard, session restore, rotation, foreground rechecks,
   Logout -> Login, and Back not reopening the dashboard. Do not enable the actual Admin as
   part of this implementation or use real account details in logs/screenshots.
10. With an ordinary test Patient, verify Admin Dashboard cannot reveal content even if reached
    through an internal navigation test. It must return the verified Patient to Patient Home.
    With a signed-out user it must return to Login. No Activity accepts a role extra.

Role-policy tests in `SessionPolicyTest` are deterministic and do not contact Firebase. They do
not replace device/emulator integration tests of SDK failures and Android navigation. The app
has no automatic account enabling, custom-claim assignment, or metadata repair path.

Changing/removing custom claims does not instantly invalidate all previously issued tokens.
Foreground/session gates force a refresh, but future backend access must still be secured by
reviewed rules. Immediate revocation would require additional server-enforced checks.

### Rules checks before production use

Use the Firestore rules simulator or a separately configured Firebase Emulator Suite harness
(see `tests/firestore` for the Java/JUnit local demo-project suite). End-user client tests cover:

| Request | Expected |
| --- | --- |
| Unauthenticated profile read/create | Denied |
| Authenticated own exact Patient profile create, matching email, server timestamp | Allowed |
| Create another UID's profile | Denied |
| Create role Admin, add extra field, forge timestamp, use wrong type/email | Denied |
| Blank/oversized name or invalid phone | Denied |
| Read own profile / read another profile | Allowed / Denied |
| Query/list users, update any profile field, delete profile | Denied |
| Read/write unrelated collection, including with Admin claim | Denied; only the explicit doctor, schedule and appointment extensions are added |

Rules have not been deployed by the coding agent. The local Java emulator suite passed its
doctor-access, Patient-profile, schedule and Step 86 appointment checks; see
`docs/admin-doctor-firestore.md` and `tests/firestore/README.md`. The Node-free suite runs with
`.\gradlew.bat :firestore-rules-tests:test --console=plain`.
It uses authenticated/anonymous REST clients that evaluate Rules, not Admin SDK permission assertions;
no real Auth accounts or claims are changed. See `tests/firestore/README.md` for Java emulator setup.
A successful Android build or emulator test does not verify production Firebase configuration.
