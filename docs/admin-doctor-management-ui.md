# Admin Doctor Management UI

Phase 2 Step 4 introduced the Java/XML Doctor Management screens, RecyclerView adapter,
Material styling, View Binding, validation and Admin session gates. It originally used
explicitly labelled local previews and was reviewed on a physical Android phone.

**Step 5 supersedes the preview-only behavior.** The same screens now use Cloud Firestore;
no local preview list is restored, seeded or treated as stored doctor data. See
[Doctor Firestore integration](admin-doctor-firestore.md) for the schema, rule decisions,
pending/error/retry behavior, test commands and updated device-review checklist.

Schedules, appointments and a Doctor authentication role remain outside this milestone.
