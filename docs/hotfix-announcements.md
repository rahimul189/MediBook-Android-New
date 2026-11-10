# Archived hotfix notes — announcement feature retired

**Do not follow the announcement setup instructions below.** Announcements and the in-app
Notification Center have been removed and denied in local Rules. Data is retained, not deleted.
Current database architecture and manual setup are in [BATCH_2_HANDOFF.md](BATCH_2_HANDOFF.md).
The reminder and emergency-contact implementation remains preserved.

## Historical Batch 1 hotfix and hospital announcements

## Rules to publish manually

No rules have been deployed. Publish `firestore.rules` to Cloud Firestore and
`database.rules.json` to the configured Realtime Database after reviewing them. `firebase.json`
references both files. Preserve the existing booking/display rules when publishing the whole files.
Do not grant reads at the RTDB root: such a grant would override all child draft restrictions.

- Emergency contacts: `users/{authenticated UID}/emergencyContacts/list`, Patient-owner get and
  transactional writes only. Ten fixed slots plus a bijective `phoneKeys` index enforce the limit
  and canonical-number uniqueness below Firestore's expression budget. Numbers are normalized to
  ASCII digits with an optional leading `+`; the Rules verify that each key matches its number.
- Migration: an existing server document (including an empty list) always wins. A transaction
  imports local contacts only when the document is still absent. The document is never deleted;
  an empty list retains the migration boundary. Adds/removes change the latest server list, not
  a stale full-list cache, so concurrent devices cannot restore another device's deletions.
- Announcements: `publishedAnnouncements/{id}` and `draftAnnouncements/{id}`. Only the Boolean
  `admin=true` custom claim permits writes; a profile field or Intent never grants privileges.
  Patients cannot read draft records, draft queries, or ancestor locations. Both lists use indexed,
  bounded queries (20 records per page; 3 on the dashboard). Publishing/unpublishing moves a notice
  atomically, retaining its ID and creation time. Timestamps use RTDB server values.
- Text is plain text (120-character title, 5,000-character body), never HTML. The RTDB Rules regex
  grammar does not interpret `\\xNN`/`\\uNNNN` as character escapes: the JSON-level Unicode escapes
  inside the regex classes are intentional. Emulator tests cover controls and Unicode whitespace.

## Runtime checks still requiring a connected device

- Alarm + notification: system alarm tone with notification/ringtone and generated-tone fallbacks,
  vibration, automatic 30-second cutoff, Dismiss/swipe/open, overlapping reminders and sign-out.
  Audio respects device alarm volume/DND; no storage, contacts-book or direct-call permission is
  requested. A bounded partial wake lock keeps the short cutoff running. Notification-only remains
  the default. Android/OEM background restrictions and network revalidation can delay delivery.
- Custom date and 12-hour time pickers: Dhaka midnight/noon, device timezone changes, editing/removal,
  reboot, exact-alarm access changes, and legacy saved reminders. Without exact-alarm access Android
  receives an inexact fallback; the save confirmation offers the relevant settings screen.
- Native contact picker and cross-device live add/remove, airplane/reconnect, 10-contact boundary,
  logout/account switch, and legacy local migration.
- Admin CRUD and patient dashboard/View All/detail live changes, unpublication/removal while detail
  is open, pagination, rotation and background/foreground transitions.

All Firebase test traffic is restricted to owned loopback emulators and the synthetic
`demo-medibook` project. No production records or credentials are used by the tests.
