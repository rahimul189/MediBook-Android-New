# MediBook — Complete Project Technical Overview

**Project:** MediBook — Single-Hospital Appointment Booking Android Application
**Repository path:** `D:\AndroidStudioProjects\MediBook`
**Document type:** Technical reference, implementation audit and submission/viva companion
**Prepared:** 9 November 2026
**Scope of this document:** description of the **implemented** code only, plus an explicit register of
what is *not* implemented, *not verified*, or only inferable from development records.

---

## 0. How to read this document

This report was written by inspecting the actual source tree, Firebase configuration files, resource
files, rules files and test suites. Three evidence levels are used throughout, and they are never
mixed silently:

| Marker | Meaning |
|---|---|
| **Verified in code** | A statement backed by a specific file, class or method in this repository. |
| **Verified by build/test** | A statement backed by a command run on this machine (results in §14). |
| **Development record** | A statement taken from the team's handoff notes in `docs/`, or provided by the project owner. It is *not* independently provable from the code alone. |
| **Not implemented / unverified** | Explicitly flagged. Absence is stated as absence, never as a planned feature. |

Every major feature is described with the same six-part structure:

> **What it does → How users use it → How it works internally → Technologies/APIs used → Why it is useful → Relevant source files.**

Two conventions are used in file references:

* Paths are relative to the repository root, e.g. `app/src/main/java/com/example/medibook/...`.
* Firebase document paths are written as `collection/document` with `{placeholders}` in braces.

### 0.1 Important honesty note about "planned vs implemented"

The repository contains design documents (for example `docs/appointment-reservation-design.md`) that
describe intentions and rationale. Where a design note and the code disagree, **this report follows
the code**. Features that appear only in design documents — push notifications, Firebase Cloud
Functions, Firebase Storage uploads, a Doctor login role, payment processing — are listed in §18 as
*not implemented* rather than being described as working features.

---

## 1. Project introduction, objectives, architecture and single-hospital scope

### 1.1 Introduction

MediBook is a native Android application written in **Java with XML layouts** that lets patients of a
single hospital browse the hospital's doctors, view each doctor's weekly consultation schedule, book a
published appointment slot, manage and cancel that appointment, receive appointment reminders, keep a
private emergency-contact list, call the hospital, and read a city-level air-quality/UV estimate with
health guidance. A second, role-restricted **Admin** experience lets hospital staff maintain the
doctor directory, maintain weekly schedules, publish bookable slot inventories for specific dates, and
monitor a weekly appointment overview.

The application is deliberately **single-hospital** rather than a marketplace: there is no
multi-tenant hospital entity, no doctor self-registration, no hospital search and no cross-hospital
scheduling. The hospital identity is expressed in three ways:

1. A fixed hospital time zone, `Asia/Dhaka`, in `utils/AppConstants.java`
   (`HOSPITAL_TIME_ZONE_ID`), used by all date, weekday and slot arithmetic.
2. A fixed hospital telephone number in `res/values/batch3_strings.xml`
   (`hospital_phone_number`, `+8801883021666`), dialled through `utils/DialIntents.java`.
3. A single Firebase project (`medibook-2c3a4`, configured in `.firebaserc`) holding one hospital's
   doctors, schedules, slot inventories and appointments.

### 1.2 Objectives

The objectives that are actually realised by the code are:

| # | Objective | Where it is realised |
|---|---|---|
| O1 | Authenticate patients securely with email/password **and** Google Sign-In | `repositories/AuthRepository.java`, `auth/GoogleCredentials.java`, `utils/GoogleAuthFlow.java` |
| O2 | Enforce a server-verified role distinction (Patient vs Admin) that cannot be forged by navigation extras | `utils/SessionPolicy.java`, `utils/AuthNavigation.java`, `activities/AppointmentSessionActivity.java` |
| O3 | Let Admin staff maintain the doctor directory and weekly schedules as server data | `activities/admin/*`, `repositories/DoctorRepository.java`, `repositories/DoctorScheduleRepository.java` |
| O4 | Publish immutable, pre-generated bookable slots for a chosen date | `repositories/SlotInventoryPublisher.java`, `utils/AppointmentSlots.java` |
| O5 | Prevent double booking under concurrency using Firestore transactions and an immutable reservation lock | `repositories/AppointmentRepository.java`, `models/BookingLock.java`, `firestore.rules` |
| O6 | Support self-service cancellation that correctly returns the slot to inventory | `AppointmentRepository.cancel`, `AppointmentWrites.release` |
| O7 | Provide local appointment reminders (two automatic + one custom, optional alarm style) | `reminders/*`, `local/LocalPatientServices.java` |
| O8 | Keep an account-private, device-synchronised emergency-contact list with tap-to-dial | `local/LocalPatientServices.java`, `activities/patient/EmergencyContactActivity.java` |
| O9 | Provide a coarse-location, city-level environment/health reading from a public API | `airquality/*`, `activities/patient/AirQualityActivity.java` |
| O10 | Present a wellness carousel and a dashboard-style patient home without sacrificing correctness | `wellness/*`, `activities/patient/PatientHomeActivity.java` |
| O11 | Demonstrate Android laboratory competencies: Activities, lifecycle, permissions, notifications, alarms, background work, RecyclerView, ViewPager2, custom View, ViewModel, View Binding, both Firebase databases | see §13 mapping table |
| O12 | Keep behaviour testable offline: pure policy classes separated from Firebase/Android types | 58 unit-test classes, §14 |

### 1.3 Architectural style

The application uses a **layered, lifecycle-aware, unidirectional** architecture with no dependency
injection framework (no Hilt/Dagger) and no Kotlin coroutines/Flow. Layers:

```
┌────────────────────────────────────────────────────────────────────────────┐
│ Presentation        Activities (17, incl. 1 abstract base)                  │
│                     Adapters (11), custom Views (2), XML layouts (37)       │
│                     ViewModels (8) for operation survival across rotation   │
├────────────────────────────────────────────────────────────────────────────┤
│ Application state   AuthRepository (singleton), cache/* (owner-scoped),      │
│                     LocalPatientServices (singleton coordinator)            │
├────────────────────────────────────────────────────────────────────────────┤
│ Domain / policy     utils/* pure classes: AppointmentPolicy, SessionPolicy,  │
│                     DhakaDateTime, SlotSerials, ReminderSchedule, ...        │
├────────────────────────────────────────────────────────────────────────────┤
│ Data access         repositories/* (24 files): Firestore + RTDB access,      │
│                     transactions, mappers, single-flight read coalescing     │
├────────────────────────────────────────────────────────────────────────────┤
│ Backend             Firebase Authentication, Cloud Firestore,                │
│                     Realtime Database, Open-Meteo HTTP API (read-only)       │
└────────────────────────────────────────────────────────────────────────────┘
```

Five architectural decisions dominate the codebase and are worth stating explicitly, because they
explain most of the unusual-looking code:

**(A) The server is always the authority; the client cache is only a rendering aid.**
Every Firestore read that matters for a decision uses `Source.SERVER` and then checks
`snapshot.getMetadata().isFromCache()` / `hasPendingWrites()` before trusting it. See
`AppointmentRepository.confirmed(...)`, `DoctorRepository.loadDoctors(...)`,
`PatientDoctorRepository.watch(...)`. Caches (`cache/PatientDataCache.java`,
`cache/DashboardCache.java`) are memory-only, account-bound, bounded and explicitly documented as
"never authorization".

**(B) Role is derived from two independent facts, not from one field.**
`SessionPolicy.evaluate(claims, profile, authenticatedEmail)` requires the Firebase ID-token claim
`admin == true` **and** the Firestore profile field `role == "Admin"` for Admin; a Patient must have
*no* `admin` key at all and `role == "Patient"`. A profile field alone grants nothing. This is
mirrored in `firestore.rules` (`isAdmin()`, `isPatient()`, `ownsPatientProfile()`).

**(C) Authentication operations are serialised in one in-process state machine.**
`AuthRepository` permits one credential operation at a time, publishes immutable `State` objects,
uses monotonically increasing `operationId` values to discard late callbacks, and applies a 20-second
verification watchdog so a slow network cannot leave a screen on a permanent spinner
(`VERIFY_TIMEOUT_MS`, `scheduleVerificationWatchdog`).

**(D) Screens re-verify rather than trust their Intent.**
`AuthNavigation.openDestination(...)` only navigates. Each protected screen independently calls
`AuthRepository.checkSession()` in `onStart()` and gates its content on the freshly verified state, e.g.
`activities/AppointmentSessionActivity.java` (`canAccess()`), `PatientHomeActivity.onAuthStateChanged`.
Intents carry *path identities* (doctor ID, appointment ID, owner UID) but never a role or an
authorisation decision.

**(E) Two databases with explicitly different jobs.**
Cloud Firestore is the transactional source of truth. Realtime Database holds only (i) small
display/projection records with a revision counter (`dashboards/*`, `display/*`) and (ii) a
best-effort live queue hint. `repositories/DashboardMirror.java` documents the rule: "No transaction
here ever participates in booking success. RTDB is never a booking authority."

### 1.4 Single-hospital scope, stated precisely

| In scope (implemented) | Out of scope (not implemented) |
|---|---|
| One hospital identity (name/time zone/phone) | Multiple hospitals, branches or tenants |
| Doctors as Firestore records managed by Admin | Doctor accounts, doctor login, doctor self-service |
| Weekly recurring schedules per doctor | One-off/exception sessions beyond date blocks |
| Admin-published per-date slot inventory | Automatic rolling publication by a server job |
| Patient booking, cancellation, reminders | Rescheduling, waiting lists, payments, insurance |
| Private per-patient emergency contacts | Sharing contacts with doctors or hospital staff |
| City-level environment data from a public API | Hospital-specific pollution sensors or medical advice |

### 1.5 Technology stack (verified from Gradle files)

| Component | Version / value | Evidence |
|---|---|---|
| Language | Java (source/target compatibility 11) | `app/build.gradle.kts` |
| Android Gradle Plugin | 9.5.1 | `gradle/libs.versions.toml` (`agp`) |
| Gradle wrapper | 9.6.0 | `gradle/wrapper/gradle-wrapper.properties` |
| Gradle daemon toolchain | 25 | `gradle/gradle-daemon-jvm.properties` (`toolchainVersion=25`) |
| compileSdk / targetSdk | 37 / 37 | `app/build.gradle.kts` |
| minSdk | 24 (Android 7.0) | `app/build.gradle.kts` |
| namespace / applicationId | `com.example.medibook` | `app/build.gradle.kts` |
| versionCode / versionName | 1 / "1.0" | `app/build.gradle.kts` |
| View Binding | enabled | `buildFeatures { viewBinding = true }` |
| Firebase BOM | 35.1.0 | `gradle/libs.versions.toml` |
| Firebase modules | `firebase-auth`, `firebase-firestore`, `firebase-database` | `app/build.gradle.kts` |
| Google Sign-In | `androidx.credentials:credentials:1.6.0`, `credentials-play-services-auth:1.6.0`, `com.google.android.libraries.identity.googleid:1.2.1` | `gradle/libs.versions.toml` |
| Image loading | Glide 4.16.0 | `gradle/libs.versions.toml` |
| Background work | `androidx.work:work-runtime:2.12.0` | `gradle/libs.versions.toml` |
| UI | Material Components 1.10.0, AppCompat 1.6.1, ConstraintLayout 2.1.4, RecyclerView 1.4.0, ViewPager2 1.0.0, Activity 1.13.0, Lifecycle ViewModel 2.11.0 | `gradle/libs.versions.toml` |
| Test | JUnit 4.13.2, `org.json:json`, Espresso 3.5.1, `androidx.test.ext:junit` 1.1.5 | `gradle/libs.versions.toml` |
| Build services | `com.google.gms.google-services` 4.5.0 | `app/build.gradle.kts` |
| Firebase project | `medibook-2c3a4` | `.firebaserc` |
| RTDB instance | `https://medibook-2c3a4-default-rtdb.asia-southeast1.firebasedatabase.app` | `res/values/firebase_display.xml` |

There is no Kotlin source (`compileDebugKotlin NO-SOURCE` in Gradle output), no Hilt, no Room, no
Retrofit/OkHttp, no Coil, no Firebase Cloud Messaging, and no Firebase Storage dependency.

### 1.6 Source-code inventory (verified counts)

| Area | Count |
|---|---|
| Main Java files (`app/src/main/java`) | **156** files, **15,209** lines |
| Activities | **18** classes = 17 concrete (including `MainActivity`) + 1 abstract base (`AppointmentSessionActivity`) |
| Repository classes (`repositories/`) | **23** (+ `package-info`) |
| Utility/policy classes (`utils/`) | **46** |
| ViewModels | **8** |
| Adapters | **10** (+ `package-info`) |
| Domain models | **9** (+ `package-info`) |
| Custom Views | **2** (`WeeklyBarChartView`, `AdaptiveColumnsLayout`) |
| Layouts (`res/layout`) | **37** |
| Drawables (XML vector + `drawable-nodpi` PNG) | **31** (includes 10 wellness photographs and `avatar_doctor`) |
| `res/values` XML files | **16** (strings are split across several feature files) |
| Unit-test classes | **58** (`app/src/test`) — 600 tests executed |
| Instrumentation sources | **9** files (`app/src/androidTest`, 7 classes containing `@Test`) |
| Security-rules test sources | **18** Java files (`tests/firestore` 14, `tests/rtdb` 4) |
| Firestore rules | `firestore.rules`, 773 lines |
| RTDB rules | `database.rules.json`, 100 lines |

---

## 2. Repository layout and build topology

### 2.1 Gradle projects

`settings.gradle.kts` declares **three** Gradle projects:

| Gradle path | Directory | Purpose |
|---|---|---|
| `:app` | `app/` | The Android application |
| `:firestore-rules-tests` | `tests/firestore/` | JVM-only JUnit suite that boots a real Firestore emulator and exercises `firestore.rules` over REST |
| `:rtdb-rules-tests` | `tests/rtdb/` | JVM-only JUnit suite that boots a Realtime Database emulator and exercises `database.rules.json` |

Repository resolution is centralised with `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, and the Android
plugin repositories are content-filtered to `com.android.*`, `com.google.*`, `androidx.*`.

The presence of the two test modules is important for the viva: the security rules are **not** taken
on trust; they have an executable test suite (see §14.3).

### 2.2 Directory map

```
MediBook/
├── app/                                   Android application module
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml        permissions, 16 activities, 3 receivers
│       │   ├── java/com/example/medibook/
│       │   │   ├── MainActivity.java      launcher/router
│       │   │   ├── MediBookApplication.java
│       │   │   ├── activities/            auth/, patient/, admin/, Appointments*,
│       │   │   │                          AppointmentDetails*, AppointmentSession*
│       │   │   ├── adapters/              11 RecyclerView/Pager adapters + diff helpers
│       │   │   ├── airquality/            7 classes: HTTP client, parser, cache, guidance
│       │   │   ├── auth/                  GoogleCredentials (Credential Manager)
│       │   │   ├── cache/                 DashboardCache, PatientDataCache
│       │   │   ├── local/                 emergency contacts, private store, coordinator
│       │   │   ├── models/                10 immutable domain models
│       │   │   ├── reminders/             9 classes: policy, schedule, worker, receivers
│       │   │   ├── repositories/          24 data-access/mapping classes
│       │   │   ├── system/                airplane-mode / battery broadcasts
│       │   │   ├── utils/                 46 pure policy/formatting helpers
│       │   │   ├── viewmodels/            8 ViewModels
│       │   │   ├── views/                 custom Canvas views
│       │   │   └── wellness/              carousel plan, card catalog, carousel controller
│       │   ├── keepRules/rules.keep       R8/optimisation keep rules
│       │   └── res/                       layouts, drawables, strings, themes, xml rules
│       ├── test/                          58 JVM unit-test classes
│       ├── androidTest/                   Espresso/instrumentation suites
│       └── debug/                         debug-only manifest + UiFixtureActivity
├── tests/firestore/                       Firestore rules emulator suite
├── tests/rtdb/                            RTDB rules emulator suite
├── docs/                                  18 pre-existing development/design documents
│                                          + this report
├── firestore.rules                        Firestore security rules (deployed by Firebase CLI)
├── firestore.indexes.json                 one composite index declaration
├── database.rules.json                    RTDB security rules
├── firebase.json / firebase.emulators.json
├── .firebaserc                            project alias
├── gradle/libs.versions.toml              version catalog
├── build.gradle.kts, settings.gradle.kts
├── gradlew, gradlew.bat, gradle/wrapper/
└── .gitignore
```

### 2.3 Machine-local (git-ignored) files that the build requires

| File | Why it is ignored | Consequence |
|---|---|---|
| `app/google-services.json` | Contains the Firebase Android API key/client IDs | A clone without this file **cannot build**; it must be downloaded from the Firebase console |
| `local.properties` | Contains the local Android SDK path | Must be regenerated by Android Studio on each machine |
| `.idea/*` | Per-machine IDE settings | Not portable; harmless |
| `.gradle/`, `**/build/` | Gradle caches and outputs | Regenerated |

`app/google-services.json` is present on this machine but deliberately excluded from version control.
It is **not** reproduced in this document, and no key material from it appears anywhere in this
report.

---

## 3. Firebase topology overview

A detailed treatment is in §7; this section establishes the shape.

### 3.1 Collections and paths actually used by the client

| Path | Written by | Read by | Purpose |
|---|---|---|---|
| `users/{uid}` | `AuthRepository.register`, `updatePatientProfile`, `completeGoogleProfile` | `AuthRepository.verifySession`, `PatientProfileRepository`, rules | Patient/Admin profile: `fullName`, `email`, `phone`, `role`, `createdAt`, optional `dateOfBirth`, `gender` |
| `users/{uid}/emergencyContacts/list` | `LocalPatientServices.mutateContact` | `LocalPatientServices.watchContacts` | One private document holding up to 10 contacts in fixed slots `c0`–`c9` plus a `phoneKeys` bijection |
| `users/{uid}/notifications/{id}` | — | — | Retired; rules deny all access (`allow read, write: if false`) |
| `doctors/{doctorId}` | `DoctorRepository.addDoctor/editDoctor/setActive` | `DoctorRepository`, `PatientDoctorRepository`, booking transaction | Doctor profile: `name`, `specialization`, `qualifications`, `consultationFee` (integer hundredths), `biography`, `active`, `createdAt`, `updatedAt`, optional `imageUrl` |
| `doctors/{doctorId}/schedules/{scheduleId}` | `DoctorScheduleRepository.add/edit/setActive` | same + booking transaction | Weekly window: `dayOfWeek` (ISO 1–7), `startTime`/`endTime` (Dhaka minutes), `slotDurationMinutes`, `active`, `updatedAt` |
| `appointments/{appointmentId}` | booking transaction, cancellation transaction | `AppointmentRepository.list/get`, dashboards | 17-field appointment snapshot plus `status` |
| `doctorSlotInventory/{doctorId}/dates/{dateKey}` | `SlotSerialRepository.assign` | serial allocation | Per doctor/date serial counter: `lastSerial`, `lastSlotId`, `updatedAt` |
| `doctorSlotInventory/{doctorId}/dates/{dateKey}/slots/{slotId}` | `SlotInventoryPublisher.publish` | availability query, booking transaction | Bookable interval: 12 fields incl. `status` (`available`/`booked`/`blocked`), `generation`, `appointmentId` |
| `.../slots/{slotId}/bookings/{gN}` | booking transaction | rules `getAfter`, audit | Immutable reservation lock per generation |
| `.../dates/{dateKey}/serials/{slotId}` | `SlotSerialRepository` | `numbers()`, details screen | Display-only slot number (`number`, `createdAt`) |
| `doctorDateBlocks/{doctorId}/dates/{dateKey}` | Admin only (rules exist; no UI in this app) | `SlotInventoryPublisher`, `AppointmentRepository.cancel` fallback | Marks a date closed so published slots are not created/reopened |

### 3.2 Realtime Database tree actually used by the client

| Path | Writer | Reader | Payload |
|---|---|---|---|
| `dashboards/patients/{uid}` | `DashboardMirror` (patient) | `PatientDashboardRepository` via mirror | `revision` + `summary{revision, checkedAt, appointmentId, startAt, endAt}` |
| `dashboards/admin` | `DashboardMirror` (admin) | `AdminDashboardRepository` | `revision` + `summary{revision, checkedAt, weekStart, todayStart, days/d0..d6, todayTotal, weekTotal, confirmedToday, cancelledToday, completedToday}` |
| `display/status/{uid}/{appointmentId}` | `RealtimeDisplayRepository.publish` | patient feed | `doctorId`, `dateKey`, `queueToken`, `status`, `changedAt` — no names/phones |
| `display/bookings/{appointmentId}` | same (Admin only) | Admin feed | same fields, `status == "Confirmed"` |
| `display/queues/{doctorId}/{dateKey}/{queueToken}` | same (Admin only) | booking screen queue hint | `status`, `changedAt` only |
| `publishedAnnouncements`, `draftAnnouncements` | — | — | Retained but explicitly denied (`read: false`, `write: false`) |

### 3.3 Truth flow (one sentence per hop)

Firestore transaction commits → the client re-reads the appointment with `Source.SERVER` →
`RealtimeDisplayRepository.publishBestEffort(...)` writes an RTDB event with a monotonic `changedAt` →
`DashboardMirror` reserves an RTDB `revision`, then reads Firestore again, then conditionally writes a
compact `summary` → dashboards render RTDB `summary` **only** when it is connected, fresh and
field-for-field equal to a current Firestore result (`DashboardPayload.matches`), otherwise they render
the Firestore value and label the state. Cancellation of a booking never depends on any of this.

---
## 4. Complete Patient feature inventory

### 4.1 Patient navigation graph

```
MainActivity (launcher, router)
   │  verified session? ── no ──► LoginActivity ──► RegisterActivity
   │                                   │                 │
   │                                   └──► CompleteProfileActivity (Google user with no profile)
   ▼ yes
PatientHomeActivity  (verified PATIENT only; a verified Admin is redirected to Admin Dashboard)
   ├── Toolbar ▸ Edit Profile ───────────────► EditProfileActivity (also: connect a Google account)
   ├── Toolbar ▸ Log out ────────────────────► LoginActivity (task cleared)
   ├── Header: search field + specialization filter (filters the list in place)
   ├── Header: Next Appointment card ────────► AppointmentDetailsActivity
   ├── Header: My Appointments ──────────────► AppointmentsActivity
   ├── Header: Call Hospital ────────────────► system dialer (ACTION_DIAL)
   ├── Header: Emergency Contacts ───────────► EmergencyContactActivity
   ├── Header: Health & Environment ─────────► AirQualityActivity
   ├── Header: Health & Wellness carousel (11 cards, 8 s auto-advance)
   └── Doctor row ───────────────────────────► DoctorProfileActivity
                                                  ├── Book Appointment ──► BookAppointmentActivity
                                                  ├── My Appointments ───► AppointmentsActivity
                                                  └── Log out
AppointmentsActivity (own history)
   └── Row ──► AppointmentDetailsActivity
                  ├── Cancel (Confirmed and future only)
                  ├── Set / Edit / Remove custom reminder
                  └── Patient name / phone / age / gender block
```

### 4.2 Patient feature table

| Feature | Entry point | Verified behaviour |
|---|---|---|
| Email/password login | `LoginActivity` | Validates email format and non-empty password, disables the form while busy, blocks Back during the operation, maps Firebase failures to specific messages |
| Patient registration | `RegisterActivity` | Name, email, phone, date of birth (picker), gender (dropdown), password + confirmation; creates the Auth user then the Firestore profile; deletes the new Auth account if the profile write is rejected |
| Google Sign-In | `LoginActivity` "Continue with Google" | Credential Manager chooser → Google ID token → `signInWithCredential`; incomplete Google accounts go to `CompleteProfileActivity` |
| Session verification | every protected screen | Online reload + forced token refresh + server profile read + claim/role policy; 120 s in-memory lease; quiet revalidation on resume |
| Profile editing | `EditProfileActivity` | Updates only `fullName`, `phone`, `dateOfBirth`, `gender`; can link a Google credential after password re-authentication |
| Doctor directory | `PatientHomeActivity` | Live `doctors where active == true`, sorted by name; search plus specialization filter; per-account cache for instant warm render |
| Doctor profile | `DoctorProfileActivity` | Live doctor document and live active schedules; fee, qualifications, biography, weekly windows; Book button requires an active doctor |
| Slot availability | `BookAppointmentActivity` | Date picker limited to today or later (Dhaka); server-only availability read; explicit empty reasons; live queue hint |
| Booking | `BookAppointmentActivity` | Confirmation dialog → Firestore transaction → appointment created; rotation- and duplicate-tap-safe; persistent failure dialog |
| Appointment list | `AppointmentsActivity` | Own appointments for Patients; All/Upcoming/Completed/Cancelled filter; post-booking result Snackbar |
| Appointment details | `AppointmentDetailsActivity` | Server read, slot number resolution, patient demographics, cancel, reminder card |
| Cancellation | `AppointmentDetailsActivity` | Only `Confirmed` with `startAt > now`; dialog-confirmed; slot returned to inventory |
| Reminders | `AppointmentsActivity` (master toggle) + details (per appointment) | Two automatic reminders (24 h, 6 h) via WorkManager; one custom reminder via AlarmManager, notification or alarm style |
| Emergency contacts | `EmergencyContactActivity` | Up to 10 contacts, system contact picker, duplicate prevention, tap-to-dial, Firestore-synchronised |
| Hospital calling | Patient Home header | `ACTION_DIAL` with the fixed hospital number; no `CALL_PHONE` permission |
| Health & Environment | `AirQualityActivity` | Coarse location once (or labelled fallback), Open-Meteo AQI/PM2.5/PM10/UV, 15-minute cache, deterministic guidance |
| Health & Wellness carousel | Patient Home header | 11 cards, 8 s auto-advance with wrap, dots + counter, page preserved across rotation |
| System notices | Patient Home | Foreground airplane-mode banner and low-battery Snackbar (laboratory demonstration) |
| Logout | Toolbars | Clears caches, cancels that account's work/alarms/notifications, signs out of Firebase, clears Credential Manager state |

---

## 5. Complete Admin feature inventory

### 5.1 Admin navigation graph

```
MainActivity ── verified ADMIN (claim + profile) ──► AdminDashboardActivity
   ├── Weekly Appointment Overview (counts + custom bar chart; RTDB projection or Firestore fallback)
   ├── Manage Doctors ──────────────► ManageDoctorsActivity
   │                                     ├── Add Doctor ──────► AddEditDoctorActivity
   │                                     ├── Edit Doctor ─────► AddEditDoctorActivity (path ID only)
   │                                     ├── Activate / Deactivate (confirmed dialog)
   │                                     ├── Manage Schedule ─► ManageDoctorScheduleActivity
   │                                     │                      ├── Add/Edit window ► AddEditDoctorScheduleActivity
   │                                     │                      ├── Enable/Disable window
   │                                     │                      └── Publish slots for a date
   │                                     └── Log out
   ├── Manage Appointments ─────────► AppointmentsActivity (Admin mode: all patients)
   └── Log out
```

### 5.2 Admin feature table

| Feature | Entry point | Verified behaviour |
|---|---|---|
| Admin landing | `AdminDashboardActivity` | Requires a verified Admin; Patients are routed to Patient Home without seeing content |
| Weekly analytics | `AdminDashboardActivity` | Today/week/confirmed/cancelled counts and a Saturday–Friday chart; RTDB used only when it matches Firestore, otherwise labelled Firestore/cached/error states |
| Doctor list | `ManageDoctorsActivity` | Server list with loading/writing/error states; Back blocked while a write is pending |
| Add/edit doctor | `AddEditDoctorActivity` | Name, specialization, qualifications, fee (decimal → integer hundredths), biography, optional image URL; optimistic concurrency check; no delete |
| Doctor activation | `ManageDoctorsActivity` | Explicit target state with confirmation; inactive doctors leave the patient directory (query + rules) |
| Schedule list | `ManageDoctorScheduleActivity` | Weekly windows per doctor with day, times, duration, active state |
| Add/edit schedule | `AddEditDoctorScheduleActivity` | Day selector, Material time pickers, duration in 5-minute steps (5–180), overlap preflight and re-check inside the transaction |
| Slot publishing | `ManageDoctorScheduleActivity` | Choose a bookable date → confirm → generate slots from active windows → create-only transactions → assign serials |
| Admin appointment list | `AppointmentsActivity` (Admin) | All appointments; doctor-name and date filters; status filter; cancellation stays on the details screen |
| Logout | All admin screens | Same sign-out path as Patient |

Admin does **not** get: patient-directory browsing, emergency contacts, or any ability to delete
appointments, doctors or schedules (rules forbid deletion).

---

## 6. Feature implementation details

Each subsection follows the required template.

### 6.1 Email/password authentication and server-verified sessions

**What it does.** Creates and authenticates accounts, then proves against the server — not against local
state — that the account has a valid profile and a role the current screen may display.

**How users use it.** The launcher shows a session card while verification runs; success opens Patient
Home or Admin Dashboard. New patients register from Login; existing users sign in with email/password or
Google. Invalid credentials, invalid profiles and network failures each produce a specific message and
never a half-logged-in screen.

**How it works internally.**

1. `LoginActivity.validateForm()` validates locally, then calls `AuthRepository.signIn(email, password)`.
2. `signIn` runs `operationBlocked()`, `beginOperation()` (increments `operationId`, publishes
   `State(loading = true)`) and `auth.signInWithEmailAndPassword(...)`.
3. On success, `verifySession(user, operation, false)` performs a three-step server proof:
   `user.reload()` → `user.getIdToken(true)` (forced refresh so claims are current) →
   `firestore.collection("users").document(uid).get(Source.SERVER)`.
4. `acceptProfile(...)` evaluates `SessionPolicy.evaluate(claims, profile, authenticatedEmail)`:
   required keys exactly `fullName, email, phone, role, createdAt` (optional `dateOfBirth`, `gender`);
   `email` must equal the token email; `phone` must match `^\+?[0-9]{7,15}$`; `createdAt` must be a
   `Timestamp`; Admin only if `claims.admin == true` **and** `role == "Admin"`; Patient only if the
   `admin` key is absent **and** `role == "Patient"`.
5. Success stores `verifiedUid`, `verifiedAt` (elapsed realtime) and `tokenExpiresAt`, binds caches to the
   UID, calls `LocalPatientServices.verifiedBestEffort(uid, role)` and publishes the verified profile.
6. `VerifiedSessionLease.reusable(...)` permits reuse for 120 s of elapsed realtime while the token has
   more than 30 s left, so normal navigation avoids a network verification. Warm resumes perform a
   *quiet* revalidation that keeps the screen visible; only a genuine account/role problem removes it
   (`AuthRepository.revalidationKeepsSession`).
7. Every failure increments `operationId` (late callbacks are discarded), signs out, clears caches and
   cancels that account's local reminders. A 20-second watchdog (`VERIFY_TIMEOUT_MS`) prevents a
   permanent loader.

**Technologies/APIs used.** `FirebaseAuth`, `FirebaseUser.reload()`, `getIdToken(boolean)`,
`FirebaseFirestore` with `Source.SERVER`, Google Tasks callbacks, `Handler` watchdog,
`SystemClock.elapsedRealtime()`.

**Why it is useful.** It converts "the UI thinks you are an Admin" into "the server just confirmed your
claims and profile", and it removes the rotation bug where an in-flight sign-in is repeated or abandoned.

**Relevant source files.** `repositories/AuthRepository.java`, `utils/SessionPolicy.java`,
`utils/VerifiedSessionLease.java`, `utils/AuthFailurePresentation.java`, `utils/AuthErrorMessages.java`,
`utils/AuthNavigation.java`, `utils/LaunchRouting.java`, `activities/auth/LoginActivity.java`,
`MainActivity.java`, and the unit tests `SessionPolicyTest`, `AuthSessionPolicyTest`, `LaunchRoutingTest`,
`AccountTransitionVisibilityTest`.

---

### 6.2 Registration with atomic account + profile creation and rollback

**What it does.** Creates a Firebase Auth account **and** its Firestore patient profile, guaranteeing that
a rejected profile write does not leave an orphan Auth account.

**How users use it.** Register screen: full name, email, phone (normalised), date of birth via
`DatePickerDialog`, gender from `PatientGender.OPTIONS`, password and confirmation.

**How it works internally.** `AuthRepository.register(...)` validates the gender wire value →
`createUserWithEmailAndPassword` → builds the profile map with `role = "Patient"` and
`createdAt = FieldValue.serverTimestamp()` → `set()` on `users/{uid}` → `verifySession(..., true)` using
`SessionPolicy.evaluatePatientRegistration` (which additionally rejects an Admin claim). If the profile
write fails, `rollbackNewAccount(user, operation)` calls `user.delete()` and reports either
`PROFILE_FAILED_ACCOUNT_REMOVED` or `PROFILE_FAILED_ACCOUNT_REMAINS`, so the user is never told
"registered" when they are not.

**Technologies/APIs used.** Firebase Auth, Firestore `set` with server timestamp,
`FirebaseAuthUserCollisionException`, `FirebaseAuthWeakPasswordException`,
`PatientProfileValidation`, `PatientAge`, `PatientGender`.

**Why it is useful.** Auth and Firestore cannot share a transaction, so a compensating delete is the
correct pattern; it also demonstrates defence in depth because `validPatientProfile()` re-validates the
exact schema server-side.

**Relevant source files.** `activities/auth/RegisterActivity.java`,
`repositories/AuthRepository.java` (`register`, `rollbackNewAccount`),
`utils/PatientProfileValidation.java`, `utils/PatientAge.java`, `utils/PatientGender.java`,
`utils/BirthDatePicker.java`, `firestore.rules`.

---

### 6.3 Google Sign-In (Credential Manager) and profile completion

**What it does.** Adds "Continue with Google" for sign-in and lets an existing password account link a
Google credential from Edit Profile.

**How users use it.** Tap the Google button → system credential chooser → either Patient Home (complete
profile) or `CompleteProfileActivity` (Google identity with no MediBook profile).

**How it works internally.**

* `utils/GoogleAuthFlow.java` is a small state machine:
  `IDLE → SIGN_IN|LINK → (REAUTHENTICATING for LINK) → SELECTING → EXCHANGING → VERIFYING → SUCCEEDED/FAILED`,
  with a monotonically increasing `request` id. `AuthRepository` accepts a credential only while
  `validGoogle(request, stage)` holds, so a late chooser result cannot attach to another operation.
* `auth/GoogleCredentials.launchIfNeeded(state)` reads the generated `default_web_client_id` string,
  rejects anything not ending in `.apps.googleusercontent.com` (`GOOGLE_CONFIGURATION`), builds a
  `GetSignInWithGoogleOption`, and calls `CredentialManager.getCredentialAsync(...)` with a
  `CancellationSignal` and a `MutableContextWrapper` so recreation neither leaks nor crashes.
* The result must be a `CustomCredential` of type
  `GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL`; the ID token goes to
  `AuthRepository.acceptGoogleCredential(...)` → `signInWithCredential` (or `linkWithCredential`).
  Local parsing is never treated as authentication — Firebase verifies signature, audience and expiry.
* `LINK` mode first requires `reauthenticate(EmailAuthProvider.getCredential(...))`, then re-reads the
  server profile and re-checks the role before opening the chooser (`GoogleAccountPolicy.canLink`).
  `EditProfileActivity.confirmPassword()` collects the password in a dialog.
* A Google identity with no profile resolves to `Destination.COMPLETE_PROFILE`; the repository publishes
  a `PendingProfile` (not a logged-in user), so the router opens `CompleteProfileActivity`, where
  `completeGoogleProfile(...)` runs a Firestore **transaction** that creates the profile once or updates
  only missing details (`GoogleProfileCompletion.plan` → `ALREADY_COMPLETE`/`UPDATE_DETAILS`/`CREATE`).
* Sign-out calls `GoogleCredentials.clearState(...)` → `clearCredentialStateAsync`.

**Technologies/APIs used.** `androidx.credentials.CredentialManager`, `GetSignInWithGoogleOption`,
`GoogleIdTokenCredential`, `GoogleAuthProvider`, `signInWithCredential` / `linkWithCredential` /
`reauthenticate`, Firestore transaction.

**Why it is useful.** It demonstrates modern (non-deprecated) Google Sign-In via Credential Manager,
account linking with re-authentication, and idempotent onboarding under retries.

**Relevant source files.** `auth/GoogleCredentials.java`, `utils/GoogleAuthFlow.java`,
`utils/GoogleAccountPolicy.java`, `utils/GoogleProfileCompletion.java`,
`activities/auth/CompleteProfileActivity.java`, `activities/patient/EditProfileActivity.java`,
`repositories/AuthRepository.java`, `res/values/google_auth_strings.xml`,
`res/values/google_auth_styles.xml`, `res/raw/google_auth_keep.xml`.

---

### 6.4 Patient profile editing and Google linking

**What it does.** Lets a patient correct their own display details without being able to change role,
email or account creation time.

**How users use it.** Patient Home ▸ toolbar ▸ Edit Profile; date and gender pickers behave as in
registration; a "Connect Google" section appears when no Google provider is linked.

**How it works internally.** `AuthRepository.updatePatientProfile(...)` refuses unless a verified Patient
exists and no other auth operation is running; it writes **only** `fullName`, `phone`, `dateOfBirth`,
`gender`, then re-verifies the session so every screen shows the new name immediately.
`firestore.rules` independently enforces an affected-keys allowlist
(`hasOnly(['fullName','phone','dateOfBirth','gender'])`) and re-validates the full final schema.

**Technologies/APIs used.** Firestore `update`, `ProfileUpdateListener`, rules functions
`validSchedulePatient` / `validWritablePatientDetails`.

**Why it is useful.** It is the clearest example of defence in depth: the client restricts fields and the
rules restrict the same fields again even if the client is modified.

**Relevant source files.** `activities/patient/EditProfileActivity.java`,
`repositories/AuthRepository.java`, `res/layout/activity_edit_profile.xml`,
`res/layout/dialog_confirm_password.xml`, `utils/PatientProfileValidation.java`, `firestore.rules`.

---

### 6.5 Doctor directory with search and specialization filtering

**What it does.** Shows every active doctor as a live list with instant client-side search and filtering.

**How users use it.** Type in the header search field, open the specialization dropdown, clear filters, tap
a row.

**How it works internally.**

* `PatientHomeActivity` composes a static header (`PatientHomeHeaderAdapter`) and the list
  (`PatientDoctorAdapter`) with `ConcatAdapter`.
* `PatientDoctorRepository.doctors(owner, callback)` attaches a listener with `MetadataChanges.INCLUDE` on
  `doctors where active == true`. It ignores cache-only snapshots until a server snapshot arrives, applies a
  12-second timeout, detects permission/session changes, and skips re-mapping when a snapshot has no
  document changes.
* `PatientDoctorDirectoryViewModel` owns query text and specialization and filters through the pure
  `PatientDoctorDirectory.filter(active, query, specialization)` (case-insensitive name/specialization,
  query capped at 100 characters).
* The last server-confirmed directory is cached per account (`PatientDataCache.putDirectory`), so a warm
  return renders immediately and refreshes quietly.
* Rules require the `active == true` filter for Patients (rules are not filters), and the repository always
  supplies it.

**Technologies/APIs used.** Firestore snapshot listeners, `MetadataChanges`, RecyclerView `ListAdapter`
with diffing, `ConcatAdapter`, `MaterialAutoCompleteTextView`, ViewModel.

**Why it is useful.** It is a low-latency directory that still refuses to treat cached or
permission-denied content as authorised data.

**Relevant source files.** `activities/patient/PatientHomeActivity.java`,
`repositories/PatientDoctorRepository.java`, `viewmodels/PatientDoctorDirectoryViewModel.java`,
`utils/PatientDoctorDirectory.java`, `adapters/PatientDoctorAdapter.java`,
`adapters/PatientDoctorRowDiff.java`, `adapters/PatientHomeHeaderAdapter.java`,
`res/layout/activity_patient_home.xml`, `res/layout/item_patient_doctor.xml`,
`res/menu/menu_patient_home.xml`.

---

### 6.6 Doctor profile and weekly schedule display

**What it does.** Shows one doctor's details and the hospital's weekly consultation windows.

**How users use it.** Tap a doctor; read qualifications, fee, biography and weekly timings; press "Book
Appointment"; retry if a live read fails.

**How it works internally.** `DoctorProfileActivity` validates the Intent context
(`PatientDoctorProfileContract`: owner UID + doctor ID), gates on a verified Patient with the same UID, and
starts two live listeners in parallel when a cached doctor already exists (profile first, then schedules,
when it does not). Images load through `DoctorImages` (Glide, circular crop, `avatar_doctor` placeholder,
URL validated by `DoctorImageUrl`). Schedules are formatted by `PatientScheduleDisplay`/`DoctorScheduleUi`
into Dhaka 12-hour times with a pluralised interval label. If a refresh fails after a good render, the
server data stays visible with a "Saved information" notice rather than a blank screen.

**Technologies/APIs used.** Firestore listeners, Glide 4.16.0, plurals resources,
`ViewCompat.setAccessibilityHeading`.

**Why it is useful.** Patients never see a deactivated doctor, because both the query and the rules require
`active == true`, and stale-but-useful data is labelled rather than silently trusted.

**Relevant source files.** `activities/patient/DoctorProfileActivity.java`,
`repositories/PatientDoctorRepository.java`, `utils/PatientDoctorProfileContract.java`,
`utils/DoctorImages.java`, `utils/DoctorImageUrl.java`, `utils/PatientScheduleDisplay.java`,
`utils/DoctorScheduleUi.java`, `res/layout/activity_doctor_profile.xml`.

---

### 6.7 Slot availability, booking and appointment creation

**What it does.** Shows the slots the Admin published for a doctor on a date, and books exactly one of them
exactly once.

**How users use it.** Doctor profile ▸ Book Appointment → choose a date (today or later) → choose a time
chip showing its number (`#03`) → Confirm → read the dialog (doctor, date, times, fee, slot number) →
Confirm. Success returns to My Appointments with a result Snackbar.

**How it works internally.**

1. `BookAppointmentActivity` extends `AppointmentSessionActivity`, so access requires a verified Patient
   whose UID matches the screen owner.
2. `AppointmentRepository.available(owner, doctorId, midnight)` performs a **server** read chain: doctor
   document → active schedules → `doctorSlotInventory/{doctor}/dates/{midnight}/slots`. Every snapshot must
   be server-confirmed (`confirmed(...)` throws `NETWORK` for cache/pending writes). Classification with
   the pure `availabilityFrom(...)`: inactive doctor → `DOCTOR_INACTIVE`; no window on that weekday →
   `NO_ACTIVE_SCHEDULE`; no published slots → `NO_INVENTORY`; slots but none matching a window →
   `NO_FUTURE_SLOTS`; all matching slots taken/past → `NO_AVAILABLE_SLOTS`. Overlapping published
   intervals are rejected (`SlotInventory.nonOverlapping`).
3. Serial numbers are attached by `SlotSerialRepository.numbers(...)`; a missing sidecar leaves the number
   unresolved (`—`) rather than guessing.
4. `book(owner, appointmentId, selectedSlot, shownDoctor)` first calls `get(owner, id)`: if the appointment
   already exists for the owner it is returned (idempotent retry after an ambiguous response); a
   permission/missing result means "not created yet" and the transaction proceeds.
5. The transaction re-reads slot, doctor, schedule and patient profile, then rejects if the slot is not
   `available`, is in the past, the doctor quote changed (`AppointmentPolicy.sameQuote`) or the slot no
   longer fits (`fits`); otherwise it **updates** the slot to `booked` with `generation + 1` and
   `appointmentId`, **creates** the immutable lock
   `.../slots/{slotId}/bookings/g{generation+1}` (`BookingLock`), and **creates**
   `appointments/{id}` with the 17-field snapshot from `AppointmentWrites.confirmed`
   (`status = "Confirmed"`, fee in integer hundredths, server timestamps).
6. `reconcileWrite(...)` re-reads the appointment from the server and only then reports success, so a
   timeout is resolved by evidence.

**Technologies/APIs used.** Firestore `runTransaction`, `Source.SERVER`,
`FieldValue.serverTimestamp()`, Tasks API (`continueWithTask`), `MaterialAlertDialogBuilder`, slot chips in a
RecyclerView, `AppointmentOperationViewModel` (survives rotation so a tap is not repeated).

**Why it is useful.** This is the correctness core: generation-stamped slots plus append-only locks mean two
devices racing for one slot cannot both succeed, and a retried request cannot double-book.

**Relevant source files.** `activities/patient/BookAppointmentActivity.java`,
`activities/AppointmentSessionActivity.java`, `repositories/AppointmentRepository.java`,
`repositories/AppointmentWrites.java`, `repositories/InventorySlotMapper.java`,
`repositories/BookingLockMapper.java`, `models/InventorySlot.java`, `models/BookingLock.java`,
`utils/AppointmentPolicy.java`, `utils/ReservationIdentity.java`, `utils/SlotInventory.java`,
`utils/SlotSerials.java`, `repositories/SlotSerialRepository.java`,
`adapters/AvailableSlotAdapter.java`, `res/layout/activity_book_appointment.xml`,
`res/layout/item_available_slot.xml`, `viewmodels/AppointmentOperationViewModel.java`.

---

### 6.8 Appointment list, filters and result feedback

**What it does.** Lists the patient's own appointments (or every appointment for Admin) with status
filtering and, for Admin, doctor and date filtering.

**How users use it.** Patient Home ▸ My Appointments. Patients see All/Upcoming/Completed/Cancelled; after
booking, a Snackbar confirms doctor, date and slot number.

**How it works internally.**

* `AppointmentReadRepository` wraps `AppointmentRepository.list` in `SingleFlightReads`, so two screens
  requesting the same list at the same time share one pending server read; completed values are never
  reused as a fresh result.
* `AppointmentRepository.list(owner, admin)` queries `appointments` (filtered by `patientUid` for
  Patients), requires a server snapshot, and sorts by `startAt` then id in memory — deliberately avoiding a
  composite index. Entries are handed to `LocalPatientServices.listBestEffort` for reminder reconciliation.
* Displayed status is derived: `AppointmentDisplayStatus.of(appointment, now)` returns `CANCELLED` for a
  cancelled record, `COMPLETED` once `now >= endAt`, else `CONFIRMED`. This is the "automatic completion"
  mechanism (§8.5).
* `AppointmentsActivity` keeps `snapshotKnown` so a warm refresh does not flash a spinner above existing
  rows, preserves filter state in `onSaveInstanceState`, and re-submits only changed rows through
  `AppointmentAdapter` + `AppointmentRowDiff`.

**Technologies/APIs used.** Firestore queries, `SingleFlightReads`, RecyclerView `ListAdapter` with
`DiffUtil.ItemCallback`, `MaterialAutoCompleteTextView`, `DatePickerDialog`, Snackbar.

**Why it is useful.** It demonstrates list hygiene (diffing, single-flight reads, cached-first render) and a
clear separation between stored status and derived status.

**Relevant source files.** `activities/AppointmentsActivity.java`,
`repositories/AppointmentReadRepository.java`, `repositories/AppointmentRepository.java`,
`repositories/AppointmentMapper.java`, `repositories/SingleFlightReads.java`,
`utils/AppointmentDisplayStatus.java`, `adapters/AppointmentAdapter.java`,
`adapters/AppointmentRowDiff.java`, `res/layout/activity_appointments.xml`,
`res/layout/item_appointment.xml`.

---

### 6.9 Appointment details and cancellation

**What it does.** Shows one appointment in full (schedule, fee, slot number, patient demographics, status)
and allows a patient to cancel it before it starts.

**How users use it.** Tap a row → details. Cancel appears only for a future confirmed appointment; a
destructive-styled dialog confirms; success updates the status and returns the slot to inventory.

**How it works internally.**

* `AppointmentDetailsActivity.load()` reads through `AppointmentReadRepository.get(owner, id, role)`. A
  patient-owned cached snapshot may render immediately but is labelled as saved information and is **never**
  used by cancel/reminder actions (`cachedDisplay` and `current` are separate fields).
* Demographics: the patient's own verified profile is used for their own appointment; for an Admin viewing
  another patient's appointment, `PatientProfileRepository.get(uid, ...)` performs a server read that rules
  permit only for an Admin and only when the target profile is a Patient.
* `AppointmentRepository.cancel(owner, id)` runs a transaction that returns immediately if already
  cancelled (idempotent), refuses unless `AppointmentPolicy.canCancel` (`Confirmed` and `startAt > now`),
  sets the appointment to `Cancelled`, and releases the slot to `available` while keeping `generation`
  unchanged (so the slot's identity and serial are stable for re-booking).
* Patients cannot read `doctorDateBlocks`; if the release is denied, the repository retries the same
  cancellation with `blocked = true`, which rules accept only when a block exists.

**Technologies/APIs used.** Firestore `runTransaction`, `AppointmentPolicy`, `AppointmentPresentation`,
`SlotSerialRepository`, `MaterialAlertDialogBuilder` with
`ThemeOverlay_MediBook_DestructiveDialog`.

**Why it is useful.** It shows a complete write workflow with idempotency, authorisation-aware retry, and a
strict separation between display cache and action data.

**Relevant source files.** `activities/AppointmentDetailsActivity.java`,
`repositories/AppointmentRepository.java` (`cancel`, `cancelAttempt`, `reconcileWrite`),
`repositories/AppointmentWrites.java`, `repositories/PatientProfileRepository.java`,
`utils/AppointmentPolicy.java`, `utils/AppointmentPresentation.java`,
`res/layout/activity_appointment_details.xml`.

---

### 6.10 Appointment reminders (automatic, custom, alarm style)

**What it does.** Reminds a patient before a confirmed appointment: automatically 24 h and 6 h before, or at
one custom instant, delivered as a notification or an alarm-style notification with up to 30 seconds of
sound/vibration.

**How users use it.** Master switch: My Appointments ▸ Reminders → explanation → permission → on/off.
Per appointment: details ▸ Set reminder → pick date/time, choose Notification or Alarm → Save; the card
then shows the exact trigger with Edit/Remove.

**How it works internally.**

* `LocalPatientServices` is an application-scoped coordinator initialised by `MediBookApplication.onCreate()`
  and bound to Firebase Auth state. It keeps a per-account **epoch** in `PrivatePatientStore`
  (SharedPreferences) so records, work names, alarms and notifications from a previous account can never
  apply to the new one.
* Defaults: `ReminderSchedule.defaults(start, now)` returns the 24 h and 6 h triggers that are still more
  than a minute in the future. Each becomes a `OneTimeWorkRequest` for `AppointmentReminderWorker` with a
  network constraint, exponential backoff, a unique work name (epoch + appointment + kind) and tags for bulk
  cancellation.
* Custom: `saveCustomReminder` validates the trigger (`ReminderSchedule.customTriggerValid`), cancels the
  appointment's defaults, stores `CustomRecord(start, trigger, state, mode)`, and schedules an
  `AlarmManager` `PendingIntent` (`setExactAndAllowWhileIdle`, downgraded to `setAndAllowWhileIdle` when
  exact-alarm access is absent).
* Delivery is verified, never assumed: the worker requires a fresh server read (`Source.SERVER`, 25 s
  timeout), re-checks epoch/eligibility/permission (`local.workerAllowed(...)`), and marks the record `SENT`
  **before** `ReminderNotifications.show(...)` for at-most-once delivery.
* Cancel/complete/past → `cancelAppointment(...)` cancels work, alarm and notifications and writes a
  tombstone so a stale `Confirmed` read cannot revive the reminder.
* `ReminderSystemReceiver` re-registers custom alarms after boot and after an exact-alarm permission change
  (WorkManager persists default work itself). `ReminderDismissReceiver` stops alarm feedback and cancels the
  notification without touching appointment data.

**Technologies/APIs used.** `androidx.work` (`Worker`, `OneTimeWorkRequest`, `ExistingWorkPolicy`,
`Constraints`), `AlarmManager`, `PendingIntent` with `FLAG_IMMUTABLE`, `BroadcastReceiver`,
`NotificationCompat`/`NotificationManagerCompat`, notification channels `appointment_reminders` and
`appointment_alarms_v2`, `SharedPreferences`, `Tasks.await`, and `ReminderAlarmFeedback` for audio and
vibration.

**Why it is useful.** It is the most complete subsystem in the project: durable scheduling, permission
degradation, reboot recovery, cross-account isolation and a server-verified delivery gate — without a
backend server.

**Relevant source files.** `reminders/ReminderPolicy.java`, `reminders/ReminderSchedule.java`,
`reminders/AppointmentReminderWorker.java`, `reminders/ReminderAlarmReceiver.java`,
`reminders/ReminderSystemReceiver.java`, `reminders/ReminderDismissReceiver.java`,
`reminders/ReminderNotifications.java`, `reminders/ReminderMode.java`,
`reminders/ReminderAlarmFeedback.java`, `reminders/AlarmFeedbackWindow.java`,
`local/LocalPatientServices.java`, `local/PrivatePatientStore.java`,
`activities/AppointmentsActivity.java`, `activities/AppointmentDetailsActivity.java`,
`res/layout/dialog_custom_reminder.xml`.

---
### 6.11 Emergency contacts

**What it does.** Keeps up to ten private emergency contacts per patient, synchronised to Firestore, with
tap-to-dial and duplicate/limit protection.

**How users use it.** Patient Home ▸ Emergency Contacts ▸ Add → system contact picker → the contact appears
with Call and Remove. A counter shows `n / 10`.

**How it works internally.**

* `EmergencyContactActivity` extends `AppointmentSessionActivity` (verified Patient only) and launches
  `Intent.ACTION_PICK` on `ContactsContract.CommonDataKinds.Phone.CONTENT_URI`. The system picker returns a
  content URI, so **no `READ_CONTACTS` permission** is needed — the picker grants access to the chosen row.
* `ContactPickerReader`/`ContactSelection` resolve name and number defensively (no number → `NO_NUMBER`).
* `LocalPatientServices.addContact(uid, epoch, contact)` checks the epoch, requires a completed migration,
  and applies `EmergencyContacts.canAdd(...)`: maximum 10 and canonical-digit duplicate detection
  (`EmergencyContacts.canonical` strips formatting and `+`).
* Persistence is one Firestore document `users/{uid}/emergencyContacts/list` with fixed slots `c0..c9`
  plus a `phoneKeys` map. The rules require a **bijection** (`phoneKeys.size() == contacts.size()` and
  `phoneKeys[slot.phoneKey] == slot`), which makes duplicates impossible without pairwise comparisons, and
  cap the document at 10 entries.
* First use performs a server read before uploading local contacts, then a transaction that re-checks the
  document so a remote deletion is never resurrected (`watchContacts`). Afterwards a metadata-aware listener
  keeps devices in sync; while a write is in flight the local mirror is not overwritten by its echo.

**Technologies/APIs used.** `ActivityResultContracts.StartActivityForResult`, `ContactsContract`, Firestore
transaction + snapshot listener, `SharedPreferences` mirror, `DialIntents` (`ACTION_DIAL`),
`MaterialAlertDialogBuilder`, per-row View Binding.

**Why it is useful.** It demonstrates permission minimisation, a schema-level uniqueness guarantee expressed
in security rules, and offline-tolerant local mirroring.

**Relevant source files.** `activities/patient/EmergencyContactActivity.java`,
`local/EmergencyContacts.java`, `local/EmergencyContact.java`, `local/EmergencyContactDocument.java`,
`local/ContactPickerReader.java`, `local/ContactSelection.java`, `local/LocalPatientServices.java`,
`viewmodels/EmergencyContactViewModel.java`, `res/layout/activity_emergency_contact.xml`,
`res/layout/item_emergency_contact.xml`, `firestore.rules`, `tests/firestore/.../EmergencyContactRulesTest.java`.

---

### 6.12 Hospital calling

**What it does.** Dials the hospital's fixed number from the patient home header.

**How users use it.** Tap "Call Hospital"; the system dialer opens prefilled; the user presses call.

**How it works internally.** `DialIntents.hospital(activity)` reads `hospital_phone_number`
(`+8801883021666`) from resources, validates it with `EmergencyContact.dialable(...)` (digits/`+` only,
7–15 digits), and fires `Intent.ACTION_DIAL` with a `tel:` URI. It catches
`ActivityNotFoundException`/`SecurityException` and shows a Snackbar. It deliberately does not call
`resolveActivity` (package visibility can produce false negatives) and does not request `CALL_PHONE`.

**Technologies/APIs used.** `Intent.ACTION_DIAL`, `Uri.fromParts("tel", ...)`, Snackbar.

**Why it is useful.** Immediate clinical value with zero dangerous permissions — a strong least-privilege
talking point.

**Relevant source files.** `utils/DialIntents.java`, `local/EmergencyContact.java` (`dialable`),
`res/values/batch3_strings.xml`, `adapters/PatientHomeHeaderAdapter.java`.

---

### 6.13 Health & Environment (AQI / PM / UV) with guidance

**What it does.** Shows a city-level current air-quality index, PM2.5, PM10 and UV index for the patient's
approximate area, with conservative deterministic guidance and an explicit freshness/fallback label.

**How users use it.** Patient Home ▸ "Health & Environment". First entry asks for coarse location; if
denied, disabled or slow, a clearly labelled fallback city (Khulna) appears. Retry forces a refresh.

**How it works internally.**

* `AirQualityActivity` (verified Patient) requests `ACCESS_COARSE_LOCATION` once through
  `ActivityResultContracts.RequestPermission`; a denial calls `AirQualityViewModel.onPermissionDenied()` so
  the app does not re-prompt.
* `ApproximateLocation.request(...)` accepts a cached fix up to 10 minutes old, otherwise requests one fix
  with an 8-second timeout and `CancellationSignal`, always calling back exactly once on the main thread.
  Reverse geocoding is bounded to 3 seconds on a daemon thread; failure yields "Current area".
* `AirQualityRepository` caches raw JSON per rounded coordinate (`%.2f,%.2f` ≈ 1 km) in
  `SharedPreferences("air_quality_cache")` for 15 minutes; a failure never overwrites the last good response.
* `AirQualityHttpClient` performs a real HTTPS GET on a repository executor with 10 s connect / 12 s read
  timeouts, redirects disabled, a 256 KiB body cap and a strict coordinate check
  (`|lat| ≤ 90`, `|lon| ≤ 180`).
* `AirQualityParser` validates `current_units.time == "unixtime"`, a plausible UNIX timestamp, and each
  measurement range (AQI ≤ 10000, PM ≤ 100000, UV ≤ 30); null means *unavailable*, never zero.
* `EnvironmentGuidance` maps US EPA AQI bands (0–50, 51–100, 101–150, 151–200, 201–300, 301+), UV bands
  (0–2, 3–5, 6–7, 8–10, 11+) and PM2.5/PM10 bands, and decides whether respirator wording is warranted
  (AQI > 150 or PM2.5 > 55.4 or PM10 > 254).
* `AirQualityAdapter` renders a header (location, timestamp, fallback/cached badges) plus metric rows; a
  60-second ticker refreshes relative freshness labels.

**Technologies/APIs used.** `HttpsURLConnection`, `org.json.JSONObject`, `Geocoder`,
`LocationManagerCompat.getCurrentLocation`, `CancellationSignal`, `SharedPreferences`, `LiveData` +
`AndroidViewModel`, RecyclerView.

**Why it is useful.** It is the only genuine third-party network integration, and it is engineered
defensively: bounded input, bounded output, bounded wait, explicit staleness, labelled fallback, and no
coordinates stored in any database.

**Relevant source files.** `airquality/AirQualityHttpClient.java`, `airquality/AirQualityParser.java`,
`airquality/AirQualityReport.java`, `airquality/AirQualityRepository.java`,
`airquality/AirQualityException.java`, `airquality/ApproximateLocation.java`,
`airquality/EnvironmentGuidance.java`, `viewmodels/AirQualityViewModel.java`,
`activities/patient/AirQualityActivity.java`, `adapters/AirQualityAdapter.java`,
`res/layout/activity_air_quality.xml`, `res/layout/item_air_quality_header.xml`,
`res/layout/item_air_quality_metric.xml`, `AndroidManifest.xml`.

---

### 6.14 Health & Wellness carousel

**What it does.** Displays eleven wellness/guide cards above the doctor directory, advancing every eight
seconds and wrapping at the end.

**How users use it.** Swipe horizontally; dots and an `n / 11` counter follow; the carousel pauses while
dragging and resumes afterwards. The visible page survives rotation.

**How it works internally.** `HealthWellnessCarousel` owns a `ViewPager2`, a dot `LinearLayout` and a counter.
A single `Runnable` is re-posted with `WellnessCarouselPlan.INTERVAL_MS` (8 000 ms), and
`WellnessCarouselPlan.nextPage(current, count)` wraps, so duplicate timers cannot exist. The timer stops when
the Activity stops (`setCarouselActive(false)`), while the pager is dragged, and when the header detaches from
the window; it restarts on attach and after a manual swipe. `WellnessCards` holds the ordered drawable and
description pairs (ten photographic PNGs plus a vector accent); the class comment records that card 06
(Hospital Services) and the Blood Pressure/Heart Health cards are intentionally excluded. Accessibility: each
page exposes "Health and wellness card n of 11" and reads `title. description.`

**Technologies/APIs used.** `ViewPager2`, RecyclerView adapter (`WellnessCarouselAdapter`),
`Handler`/`Looper`, `View.OnAttachStateChangeListener`, `drawable-nodpi` assets, string resources.

**Why it is useful.** It is a self-contained UI feature that also demonstrates correct timer lifecycle
management — a common source of leaks and double-advance bugs.

**Relevant source files.** `wellness/HealthWellnessCarousel.java`, `wellness/WellnessCards.java`,
`wellness/WellnessCarouselPlan.java`, `adapters/WellnessCarouselAdapter.java`,
`res/layout/item_wellness_card.xml`, `res/values/patient_wellness_strings.xml`,
`res/drawable-nodpi/wellness_*.png`, `app/src/test/.../wellness/WellnessCarouselTest.java`.

---

### 6.15 Foreground system notices (airplane mode and battery)

**What it does.** Demonstrates dynamic broadcast reception: an airplane-mode banner and a low-battery
Snackbar on Patient Home.

**How users use it.** Toggle airplane mode or drop the battery while Patient Home is open.

**How it works internally.** `PatientSystemNotices` registers a runtime receiver for
`ACTION_AIRPLANE_MODE_CHANGED`, `ACTION_BATTERY_LOW` and `ACTION_BATTERY_OKAY` with
`ContextCompat.registerReceiver(..., RECEIVER_EXPORTED)` (these are protected system broadcasts and cannot be
forged by other apps), reads the initial battery state through a null receiver for
`ACTION_BATTERY_CHANGED`, and unregisters in `onStop()`. `SystemNoticeState` lives in a ViewModel, so the
banner survives rotation, and the low-battery Snackbar is emitted only on the transition into the low state
(`updateBattery` reports whether it changed).

**Technologies/APIs used.** `BroadcastReceiver`, `BatteryManager`, `Settings.Global.AIRPLANE_MODE_ON`,
`ContextCompat.registerReceiver`, ViewModel.

**Why it is useful.** A textbook lab requirement (dynamic receiver plus lifecycle) implemented without
background services or manifest receivers.

**Relevant source files.** `system/PatientSystemNotices.java`, `system/SystemNoticeState.java`,
`system/SystemNoticesViewModel.java`, `activities/patient/PatientHomeActivity.java`.

---

### 6.16 Admin doctor management

**What it does.** Creates, edits and activates/deactivates doctor records. Deletion is intentionally
impossible.

**How users use it.** Admin Dashboard ▸ Manage Doctors ▸ Add / Edit / Activate-Deactivate. The editor blocks
Back while a write is pending and reports the server's error text.

**How it works internally.**

* `DoctorListViewModel` loads via `DoctorRepository.loadDoctors(owner)`, which requires a server snapshot,
  verifies the owner UID, sorts case-insensitively by name and maps with `DoctorMapper`.
* `DoctorRepository.addDoctor` uses a **transaction**: it fails with `ALREADY_EXISTS` if the allocated
  document ID appeared meanwhile, then writes `DoctorMapper.createMap(...)`.
* `editDoctor`/`setActive` call `updateDoctor`, which compares the caller's `createdAt`/`updatedAt` with the
  server document inside the transaction (`CONFLICT` on mismatch) — optimistic concurrency control. Activation
  is an explicit target state, never a toggle inferred from stale UI.
* The fee is converted to integer hundredths (`BigDecimal.movePointRight(2).longValueExact()`), matching the
  rules' `consultationFee is int`.
* Successful writes call `PatientDataCache.invalidateDoctors()`.

**Technologies/APIs used.** Firestore transaction, ViewModel, RecyclerView + `DoctorAdapter`, TextInputLayout
validation (`DoctorFormValidation`, `DoctorErrorMessages`), Activity Result API for editor results.

**Why it is useful.** It shows concurrency-safe editing and a deliberate no-delete policy that protects
historical appointments from dangling references.

**Relevant source files.** `activities/admin/ManageDoctorsActivity.java`,
`activities/admin/AddEditDoctorActivity.java`, `viewmodels/DoctorListViewModel.java`,
`viewmodels/DoctorEditorViewModel.java`, `repositories/DoctorRepository.java`,
`repositories/DoctorMapper.java`, `utils/DoctorFormValidation.java`, `adapters/DoctorAdapter.java`,
`res/layout/activity_manage_doctors.xml`, `res/layout/activity_add_edit_doctor.xml`.

---

### 6.17 Admin weekly schedule management

**What it does.** Maintains each doctor's recurring weekly consultation windows and their slot duration.

**How users use it.** Manage Doctors ▸ (row) Manage Schedule ▸ Add/Edit window: day of week (ISO 1–7), start
time, end time, slot duration (5–180 minutes in 5-minute steps), active flag.

**How it works internally.** `DoctorScheduleRepository.write(...)` applies three stages:

1. **Validation** — `DoctorScheduleValidation.isValid(draft)`: day 1–7, `0 ≤ start < end ≤ 1439`,
   duration 5–180 and a multiple of 5, duration ≤ window length.
2. **Server preflight** — `load(owner, doctorId)` reads the doctor and all schedules from the server, then
   checks `ALREADY_EXISTS`/`NOT_FOUND`/`CONFLICT` (version mismatch) and
   `DoctorScheduleConflicts.overlaps(draft, latest.schedules)`.
3. **Transaction** — re-reads the parent doctor and every sibling window, re-checks version and overlaps,
   then `set` (new) or `update` (edit/status) with `updatedAt = serverTimestamp`.

A code comment states the limit honestly: *"a new ID inserted after the outside query is NOT in this read
set. Neither this transaction nor the Rules guarantee global overlap exclusion."* This is recorded in §18.

Schedules store Dhaka **wall-clock minutes** (`startTime`/`endTime`), not instants, so they stay valid
indefinitely in a DST-free time zone.

**Technologies/APIs used.** Firestore transaction, `MaterialTimePicker`, Material dropdown, ViewModel,
`DoctorScheduleConflicts`, rules `validSchedule()`.

**Why it is useful.** It is the input to slot generation; a wrong window creates wrong bookable times, so the
triple check is justified.

**Relevant source files.** `activities/admin/ManageDoctorScheduleActivity.java`,
`activities/admin/AddEditDoctorScheduleActivity.java`, `viewmodels/DoctorScheduleListViewModel.java`,
`viewmodels/DoctorScheduleEditorViewModel.java`, `repositories/DoctorScheduleRepository.java`,
`repositories/DoctorScheduleMapper.java`, `repositories/DoctorScheduleStore.java`,
`utils/DoctorScheduleValidation.java`, `utils/DoctorScheduleConflicts.java`,
`utils/DoctorScheduleContract.java`, `adapters/DoctorScheduleAdapter.java`,
`res/layout/activity_manage_doctor_schedule.xml`, `res/layout/activity_add_edit_doctor_schedule.xml`.

---

### 6.18 Admin slot publishing (inventory generation)

**What it does.** Converts a doctor's weekly windows into immutable bookable slot documents for one date and
assigns each a permanent display number.

**How users use it.** Manage Schedule ▸ choose publication date (defaults to the doctor's next matching
weekday) ▸ "Publish slots" ▸ confirm. The screen reports "n slots published", "0 new slots" (already
published) or a specific failure.

**How it works internally** (`SlotInventoryPublisher.publish`):

1. Rejects invalid IDs and past dates (`DhakaDateTime.isDateBookable`).
2. A process-wide `BUSY` set keyed by doctor ID prevents concurrent publication of the same doctor from one
   device.
3. Refuses if the date is blocked (`doctorDateBlocks/{doctor}/dates/{dateKey}` exists).
4. Loads doctor and schedules from the server; refuses inactive doctors.
5. `candidates(...)` requires an active window on that weekday (`NO_ACTIVE_SCHEDULE`) and generates intervals
   with `AppointmentSlots.generate(...)` (only slots starting strictly in the future; `NO_FUTURE_SLOTS` if
   none).
6. Reads existing inventory from the server, verifies existing intervals do not overlap, and computes the
   difference with `SlotInventory.nonOverlapping(candidates, reserved)` — so publishing twice only adds what
   is missing.
7. Creates **one document per transaction** (deliberately small, below the rules' access/expression budgets).
   Inside each: skip if the deterministically named slot exists (never overwrite), re-check the date block,
   re-check doctor active, re-check the candidate fits the schedule and is still in the future, then `set`
   with `InventorySlotMapper.publishMap(...)` (`status = "available"`, `generation = 0`,
   `appointmentId = null`).
8. Finally `SlotSerialRepository.assignDate(...)` allocates display serials chronologically for slots without
   one, in a transaction that enforces `lastSerial + 1` and an immutable mapping.

Slot document IDs are deterministic: `ReservationIdentity.slotId(startAtMillis, endAtMillis)` →
`s<start>-<end>`.

**Technologies/APIs used.** Firestore `runTransaction` chain, Tasks continuation chain,
`FieldValue.serverTimestamp()`, `DhakaDateTime`, `AppointmentSlots`, `SlotInventory`, `SlotSerials`, serial
sidecar transaction.

**Why it is useful.** Idempotent publication is what makes the Admin workflow safe: pressing Publish twice
must not duplicate slots or renumber existing ones, and existing bookings must never be invalidated.

**Relevant source files.** `repositories/SlotInventoryPublisher.java`,
`repositories/SlotSerialRepository.java`, `utils/AppointmentSlots.java`, `utils/SlotInventory.java`,
`utils/SlotSerials.java`, `utils/ReservationIdentity.java`, `models/AppointmentSlot.java`,
`repositories/InventorySlotMapper.java`, `activities/admin/ManageDoctorScheduleActivity.java`.

---

### 6.19 Admin weekly appointment overview

**What it does.** Shows today's and this week's appointment counts plus a Saturday–Friday bar chart, with a
live projection path and an honest labelled fallback.

**How users use it.** Admin Dashboard; cards and chart update while open; Retry appears when the state is
`CACHED` or `ERROR`.

**How it works internally.**

* `WeeklyAnalyticsRepository.query(now)` issues **one bounded range query** on `appointments` where
  `appointmentDate ∈ [weekStart, weekStart + 7 days)`, so no historical collection is scanned.
  `WeeklyAppointmentStats.weekStart/dayStart` define a Dhaka Saturday-start week.
* Counting runs on a single daemon executor and results are reused for unchanged document content until the
  next change, day boundary or week rollover. Stale results are rejected by generation/data-version/account
  checks.
* `AdminDashboardRepository` prefers the RTDB projection only when `DashboardMirror.usable(expected)` holds —
  RTDB connected, summary fresh, and field-for-field equal to the expected payload. Then the state is `LIVE`;
  otherwise Firestore counts are shown as `FIRESTORE`/`CACHED`.
* `WeeklyBarChartView` is a custom `View` drawing bars and values on a `Canvas`, including a baseline bar for
  zero counts.

**Technologies/APIs used.** Firestore range query + snapshot listener, `ExecutorService`,
`WeeklyAppointmentStats`, RTDB mirror with revision CAS, custom `View`/`Canvas`/`Paint`.

**Why it is useful.** It is the project's data-visualisation feature and its clearest example that
optimisation must not weaken correctness: a faster RTDB number is shown only after it agrees with the
authoritative store.

**Relevant source files.** `activities/admin/AdminDashboardActivity.java`,
`repositories/AdminDashboardRepository.java`, `repositories/WeeklyAnalyticsRepository.java`,
`repositories/DashboardMirror.java`, `utils/WeeklyAppointmentStats.java`, `utils/DashboardPayload.java`,
`views/WeeklyBarChartView.java`, `res/layout/activity_admin_dashboard.xml`.

---

### 6.20 Launch routing and session recovery

**What it does.** Guarantees that tapping the launcher always lands on a real screen — either the task the
patient left or a freshly built destination — never an empty task.

**How users use it.** Press the launcher icon from any state: logged out, logged in on a deep screen, or after
the process was killed.

**How it works internally.** `MainActivity` asks `AuthRepository.verifiedUser()` (in-memory only). If a user is
verified, `AuthNavigation.findDestinationTask` looks for another app task whose root Activity is the correct
destination for that role (`LaunchRouting.taskRootsAt`) and `resumeTask` calls `moveToFront()`. Otherwise
`LaunchRouting.actionFor(...)` chooses `RESUME_EXISTING_TASK`, `NAVIGATE_NOW` or `VERIFY_SESSION`; in the last
case the session card shows and `onAuthStateChanged` routes. `AuthNavigation.openDestination(...)` uses
`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` when a task must be rebuilt, and omits the flags when the
destination replaces the router inside the launcher task.

**Technologies/APIs used.** `ActivityManager.getAppTasks()`, `AppTask.moveToFront()`, Intent flags,
`LaunchRouting` pure policy (unit tested).

**Why it is useful.** It fixes a real Android task-affinity bug that appears when authenticated screens live in
their own task — exactly the kind of detail a viva examiner may probe.

**Relevant source files.** `MainActivity.java`, `utils/LaunchRouting.java`, `utils/AuthNavigation.java`,
`utils/PatientScreenAccess.java`, `res/layout/activity_main.xml`.

---
## 7. Firebase Authentication, Cloud Firestore and Realtime Database

This chapter answers three questions per service: **what it does in this app**, **where the data
lives**, and **how security is enforced**.

### 7.1 Firebase Authentication

**What it does.** Provides identity (UID), credential verification and the trusted custom claim
`admin` that distinguishes hospital staff from patients. It is the *only* source of the UID used in
every Firestore path and RTDB rule.

**What is stored where.** Firebase Authentication stores the account (email, provider, password hash
managed by Firebase). The app stores **no** password anywhere: `AuthRepository` publishes immutable
state without passwords, and `LoginActivity`/`RegisterActivity` clear password fields after a
successful transition. The trusted `admin` custom claim lives in the ID token and is set outside this
repository (see §11.4).

**Implemented flows.**

| Flow | API | Notes |
|---|---|---|
| Email/password sign-in | `signInWithEmailAndPassword` | Error mapping: network, too-many-requests, invalid credentials, disabled account, configuration, rejected |
| Patient registration | `createUserWithEmailAndPassword` + Firestore `set` | Compensating delete on profile failure |
| Google sign-in | Credential Manager → `GoogleAuthProvider.getCredential` → `signInWithCredential` | ID token validated by Firebase, not by local parsing |
| Google link | `reauthenticate(EmailAuthProvider...)` then `linkWithCredential` | UID must be preserved; email must match |
| Token refresh | `getIdToken(true)` | Forced refresh before role decisions |
| Sign-out | `signOut()` + `clearCredentialStateAsync` | Also clears caches, work, alarms, notifications |
| Session lease | `VerifiedSessionLease` (120 s) | Avoids redundant network verification during navigation |

**How security is enforced.** Two layers:

1. **Client policy** (`SessionPolicy`): Admin requires `claims.admin == true` *and* profile
   `role == "Admin"`; Patient requires the absence of the `admin` key *and* profile `role == "Patient"`.
   A profile field alone never grants Admin, and a malformed/false claim never makes a Patient.
2. **Server rules**: `isAdmin()` reads `request.auth.token.get('admin', false) == true`;
   `isPatient()` requires the token to have no `admin` key and the profile document to say
   `role == 'Patient'`. Rules cannot be bypassed by editing the APK.

**Why this design.** Custom claims are the documented Firebase way to grant privileged access; coupling
them to a profile field prevents a "role = Admin" typo in Firestore from granting anything, and prevents
a leaked/forged claim from working without a matching profile.

**Relevant source files.** `repositories/AuthRepository.java`, `utils/SessionPolicy.java`,
`utils/VerifiedSessionLease.java`, `auth/GoogleCredentials.java`, `utils/GoogleAuthFlow.java`,
`utils/GoogleAccountPolicy.java`, `utils/LaunchRouting.java`, `firestore.rules`.

### 7.2 Cloud Firestore

**What it does.** Stores every authoritative record: profiles, doctors, weekly schedules, appointment
snapshots, published slot inventories, reservation locks, serial sidecars and date blocks. All writes
that change business state are **transactions** with server-confirmed readback.

**Where the data lives (exact paths).** See §3.1. Key schema notes:

* `appointments/{id}` stores **17 fields** (`patientUid`, `patientName`, `patientPhone`, `doctorId`,
  `doctorName`, `doctorSpecialization`, `consultationFee`, `scheduleId`, `dayOfWeek`,
  `appointmentDate`, `startMinute`, `endMinute`, `startAt`, `endAt`, `status`, `createdAt`,
  `updatedAt`). The document ID is path identity and is never duplicated as a field.
* `consultationFee` is an **integer in hundredths** (500.00 → `50000`), avoiding floating-point money.
* `appointmentDate` is the Dhaka **local midnight** `Timestamp`; `startAt`/`endAt` are exact UTC
  instants. `dayOfWeek` is ISO 1–7 (Monday = 1), explicitly *not* `Calendar` numbering.
* `schedules` store Dhaka wall-clock **minutes after midnight** (`startTime`, `endTime`) and
  `slotDurationMinutes`.
* Inventory slot IDs are `s<startAtMillis>-<endAtMillis>`, so a slot's identity is derivable from the
  appointment's stored instants without extra fields.

**Composite index.** `firestore.indexes.json` declares exactly one:
`appointments` (`patientUid` ASC, `status` ASC, `startAt` ASC), used by the patient "next appointment"
query. The appointment list deliberately avoids a second index by sorting in memory (documented in
`AppointmentRepository.list`).

**How security is enforced** (`firestore.rules`, 773 lines). Summary by collection:

| Path | Read | Create | Update | Delete |
|---|---|---|---|---|
| `users/{uid}` | own profile, or Admin reading a **Patient** profile | own UID, no admin claim, exact schema (`validPatientProfile`) | own patient profile, **only** `fullName/phone/dateOfBirth/gender`, full-schema revalidation | never |
| `users/{uid}/notifications/*` | denied | denied | denied | denied |
| `doctors/{id}` | Admin; Patient only if `active == true` | Admin, exact schema, `createdAt == request.time` | Admin, `createdAt` immutable, `updatedAt` strictly increasing, affected keys allowlist | never |
| `doctors/{id}/schedules/{sid}` | Admin; Patient if full valid patient profile + doctor active + schedule active | Admin, exact 6-field schema | Admin, same schema, `updatedAt` increasing | never |
| `appointments/{id}` | Admin; owning Patient only | only as part of the validated 3-write booking batch (`validAppointmentCreate` + `getAfter`) | only `Confirmed → Cancelled` with coordinated slot release | never |
| `doctorSlotInventory/{d}/dates/{k}` | Admin | Admin, `validSerialCounter(first = true)` | Admin, `validSerialCounter(false)` | never |
| `.../slots/{slotId}` | Admin; Patient if doctor active | Admin only, `validInventorySlotCreate` | Admin; **or** booking actor `available → booked`; **or** cancellation `booked → not booked` | never |
| `.../slots/{slotId}/bookings/{gN}` | Admin | any booking actor, validated against the projected slot/appointment | never (immutable history) | never |
| `.../serials/{slotId}` | Admin; patient with an appointment | Admin, must equal counter `lastSerial` and be a sidecar of an existing slot | never | never |
| `doctorDateBlocks/{d}/dates/{k}` | Admin | Admin | Admin (reason only) | Admin |
| `users/{uid}/emergencyContacts/list` | own Patient | own Patient, `validContactList()` | same | never |
| `/{document=**}` | denied | denied | denied | denied |

Cross-document integrity inside a single atomic batch is enforced with `getAfter(...)`: the booking
batch must contain the slot transition, the lock and the appointment, and rules verify the projected
values agree (`appointmentId`, `generation`, instants, fee, schedule, patient phone/name). A forged or
partial batch is denied as a whole.

**Why this design.** Firestore cannot run server code in this project (no Cloud Functions), so the
rules must be strong enough to be the final authority. `getAfter` is what makes a three-document
invariant enforceable without a server.

**Relevant source files.** `firestore.rules`, `firestore.indexes.json`, `firebase.json`,
`repositories/*`, `tests/firestore/src/test/java/com/example/medibook/rules/*`.

### 7.3 Realtime Database

**What it does.** Three narrowly scoped jobs:

1. **Dashboard projection** (`dashboards/*`) — a small, revision-stamped summary so dashboards can show
   verified-looking numbers instantly and cheaply, with a compare-and-set protocol.
2. **Display feed** (`display/status/{uid}`, `display/bookings/{id}`) — a notification-style hint that
   one of *your* appointments changed, carrying no name/phone/UID.
3. **Queue hint** (`display/queues/{doctor}/{date}/{token}`) — coarse slot-status hints for the booking
   screen, keyed by an ordinal token rather than a patient identity.

**Where the data lives.** See §3.2. Crucially, **no appointment document is mirrored wholesale** into
RTDB; only derived, non-identifying fields (`doctorId`, `dateKey`, `queueToken`, `status`,
`changedAt`) are written, and RTDB never decides whether a booking succeeded.

**How it works internally.**

* `RealtimeDisplayRepository.root()` resolves the database URL from
  `R.string.medibook_database_url` (falling back to `FirebaseApp` options), validates it against
  `https://<instance>.(firebaseio.com|<region>.firebasedatabase.app)` — no guessed region — and caches
  a **single** `DatabaseReference` because `useEmulator` mutates the SDK's RepoInfo key.
* After a Firestore operation is reconciled, `publishBestEffort(owner, appointment)` checks the owner,
  reads the token **without** forcing a refresh, and writes with `runTransaction` only if
  `RealtimeAppointmentEvent.shouldReplace(current, values)` says the new event is newer. Nothing here
  can fail the booking.
* `DashboardMirror.reconcile(truth)` reserves a revision first (transaction incrementing `revision`),
  then reads Firestore (`Source.SERVER`), then writes `summary` **only if** the reservation revision is
  still current. A late read therefore cannot overwrite a newer publication. A 20-second timeout
  releases the busy flag.
* Readers require `connected == true`, `DashboardPayload.fresh(summary, now)` and
  `DashboardPayload.matches(expected, summary)` before treating the projection as `LIVE`.

**How security is enforced** (`database.rules.json`, 100 lines).

| Path | Read | Write |
|---|---|---|
| root | `false` | `false` |
| `publishedAnnouncements`, `draftAnnouncements` | `false` | `false` |
| `dashboards/patients/{uid}` | signed in, `auth.uid == $uid`, **no** `admin` key | same, `newData.exists()`, validation |
| `dashboards/admin` | `auth.token.admin === true` | same, validation |
| `display/status/{uid}` | Admin, or `auth.uid == $uid` with no admin key; list query must order by `changedAt` and limit ≤ 20 | same |
| `display/bookings/{id}` | Admin only, list query bounded | Admin only |
| `display/queues/{doctor}/{date}/{token}` | signed in (Admin, or no admin key) | Admin only |

Validation is unusually strict for a course project: revision is monotonic (equal or +1),
`checkedAt` may not exceed server `now`, queue tokens must match
`^Q(000[1-9]|00[1-9][0-9]|0[1-9][0-9]{2}|1[0-3][0-9]{2}|14[0-3][0-9]|1440)$` (minutes 0–1439),
`weekTotal` must equal the sum of the seven day counters, and `todayStart`/`weekStart` are recomputed
from `now` in the rule (Dhaka = UTC+06:00, no DST). Unknown children are rejected.

**Why RTDB at all.** The course requires demonstration of Realtime Database; using it as a *verified
projection* rather than a second source of truth demonstrates the more interesting lesson: two
databases can coexist if one is explicitly subordinate and reconciled.

**Relevant source files.** `database.rules.json`, `repositories/RealtimeDisplayRepository.java`,
`repositories/DashboardMirror.java`, `repositories/PatientDashboardRepository.java`,
`repositories/AdminDashboardRepository.java`, `models/RealtimeAppointmentEvent.java`,
`utils/DashboardPayload.java`, `res/values/firebase_display.xml`,
`tests/rtdb/src/test/java/com/example/medibook/rtdb/*`.

### 7.4 What is *not* used in Firebase

| Service | Status |
|---|---|
| Cloud Functions | Not used; no `functions/` directory, no callable functions |
| Firebase Cloud Messaging (push) | Not used; all notifications are device-local |
| Firebase Storage | Not used; doctor images are external HTTPS URLs loaded by Glide |
| Firebase Analytics / Crashlytics / Performance | Not used; no dependencies or initialisation |
| Firestore offline persistence configuration | Not explicitly enabled/disabled in this repository |
| Firebase App Check | Not used |

---

## 8. Appointment lifecycle in depth

This chapter is the end-to-end narrative requested for booking, publishing, serial allocation,
cancellation, automatic completion, caching, synchronisation and fallback.

### 8.1 Stage 0 — configuration (Admin, per doctor, once)

1. Admin creates a doctor (`doctors/{id}`) with an integer-hundredths fee and `active = true`.
2. Admin creates one or more weekly windows (`doctors/{id}/schedules/{sid}`) such as Monday 09:00–12:00
   with 20-minute slots.

Nothing is bookable yet: a window describes *potential*, not *inventory*.

### 8.2 Stage 1 — publish a date (Admin)

`SlotInventoryPublisher.publish(owner, doctorId, year, month, day)`:

```
date blocked? ──yes──► DATE_BLOCKED
      │no
doctor active? ──no──► DOCTOR_INACTIVE
      │yes
window on that ISO weekday? ──no──► NO_ACTIVE_SCHEDULE
      │yes
generate future intervals ──empty──► NO_FUTURE_SLOTS
      │non-empty
read existing slots (server) ──overlap in existing──► IllegalArgumentException (publication stopped)
      │
difference = candidates − existing
      │
for each difference (one transaction per slot):
      slot exists? ──yes──► skip (never overwrite)
      date became blocked? ──yes──► DATE_BLOCKED
      doctor inactive? ──yes──► DOCTOR_INACTIVE
      candidate still fits schedule and future? ──no──► NOT_ELIGIBLE
      write slot {status: available, generation: 0, appointmentId: null}
      │
assign display serials for slots that have none (chronological, counter transaction)
```

Result: N immutable interval documents plus N serial sidecars.

### 8.3 Stage 2 — availability read (Patient)

`AppointmentRepository.available(...)` reads doctor → active schedules → slots, all from the server, then
classifies:

| Condition | Reason shown |
|---|---|
| Doctor inactive | "Doctor is not available" (`DOCTOR_INACTIVE`) |
| No active window on that weekday | `NO_ACTIVE_SCHEDULE` |
| No slots published for that date | `NO_INVENTORY` |
| Slots exist but none match a window | `NO_FUTURE_SLOTS` |
| Matching slots are all booked/past | `NO_AVAILABLE_SLOTS` |
| Available slots exist | list rendered, sorted by start instant |

Serial numbers are joined from sidecars; an unallocated sidecar renders `—` rather than a guess.

### 8.4 Stage 3 — booking (atomic, idempotent)

```
patient taps Confirm
  → BookingOperationViewModel holds the Task (rotation-safe)
  → get(owner, newAppointmentId)
        exists? → return it (retry after timeout)
        permission/not-found? → continue
  → runTransaction:
        slot.status == "available"            (else SLOT_TAKEN)
        slot.startAt > now                    (else NOT_ELIGIBLE)
        doctor quote unchanged                (else NOT_ELIGIBLE)
        slot still fits the active schedule   (else NOT_ELIGIBLE)
        update slot: booked, generation+1, appointmentId
        create lock: .../bookings/g{generation+1}
        create appointment: 17 fields, status Confirmed
  → reconcileWrite: server read the appointment
        appointment.status == Confirmed → success
        otherwise → surface the real error
```

Two invariants make this safe:

* **Generation stamping.** Every successful booking increments the slot's `generation` and appends a
  *new* lock document named after that generation. Locks are immutable, so the history of a slot is an
  append-only log; a cancelled-then-rebooked slot has `g1`, `g2`, … and rules validate that each lock's
  generation matches the projected slot and appointment.
* **Deterministic slot identity.** `s<start>-<end>` means the appointment's stored instants locate the
  exact inventory document without an extra mapping field — which is also how `cancel` finds the slot.

### 8.5 Stage 4 — automatic completion (derived, never persisted)

There is **no** scheduled job and **no** `status = "Completed"` write. Instead:

* `AppointmentDisplayStatus.of(persistedStatus, endAtMillis, nowMillis)` returns `COMPLETED` when
  `now >= endAt` for a `Confirmed` appointment, and always `CANCELLED` for a cancelled one.
* Screens re-evaluate it on a 30-second ticker (`AppointmentsActivity.clock`,
  `AppointmentDetailsActivity.clock`) and on every render, so a finished appointment moves from
  "Upcoming" to "Completed" without a write.
* Admin's weekly counters also derive "completed today" from `endAt` versus `now`
  (`WeeklyAppointmentStats`).
* Because `endAt` is an absolute UTC instant, the derivation is timezone-free.

**Why derived rather than stored:** a stored completion would need a trusted server job; deriving it is
correct, costs no writes, and cannot drift. The trade-off (documented in §18) is that "Completed" is a
presentation state, not a queryable database state.

### 8.6 Stage 5 — cancellation

```
patient taps Cancel (only Confirmed && startAt > now)
  → runTransaction:
        already Cancelled? → no-op success
        canCancel?         → Confirmed and start instant in the future
        appointment → Cancelled, updatedAt
        slot → available, appointmentId = null, generation unchanged
   (patients cannot read date blocks; if release is denied,
    retry once with status = "blocked", which rules allow only if a block exists)
  → reconcileWrite: Cancelled confirmed by server read
```

Consequences: the slot keeps its number and identity, so it can be booked again (generation increments
again); the patient's list immediately shows `Cancelled`; reminders are cancelled by
`LocalPatientServices` on the next reconciliation; RTDB display and dashboard projections are refreshed
best-effort.

### 8.7 Caching layers (what is cached, where, for how long, and what it may never do)

| Cache | Storage | Lifetime | Scope | May never |
|---|---|---|---|---|
| `PatientDataCache` | Process memory | Until invalidated/logout/account switch; bounded (500 directory, 24 profiles, 24 schedule lists, 24 details, 128 serials) | One UID | Authorise access, or supply data to cancel/booking actions |
| `DashboardCache` | Process memory | 120 s (`MAX_AGE_MS`) and same Dhaka day for analytics | One UID | Replace a live verification for actions |
| `PrivatePatientStore` | `SharedPreferences` | Durable per account epoch | One UID + epoch | Store names/phones of appointments (only IDs, times, states, modes, contacts) |
| AQI cache | `SharedPreferences("air_quality_cache")` | 15 minutes, per 1 km grid | Device | Overwrite a good response with a failure |
| Firestore SDK cache | SDK-managed | SDK policy | SDK | Be treated as confirmed (`isFromCache()` is always checked) |

Invalidation points: successful doctor add/edit/activate → `invalidateDoctors()`;
successful booking/cancel → `invalidateAppointments(uid)`; sign-out/account switch → `clear()`
on both caches plus `LocalPatientServices.signOutBestEffort()`; account UID change → caches cleared
before the new account binds.

### 8.8 Synchronisation mechanisms

| Mechanism | Where | Purpose |
|---|---|---|
| Firestore transactions | booking, cancel, doctor write, schedule write, serial allocation, contact write, Google profile completion | Atomic invariants under concurrency |
| Server-confirmed readback | `reconcileWrite`, `DashboardMirror`, `DoctorRepository` | Distinguish "committed" from "unknown" |
| Optimistic concurrency | `createdAt`/`updatedAt` comparison in doctor and schedule updates | Detect lost updates |
| `SingleFlightReads` | appointment list/details | Share a pending read between screens; never reuse a completed one |
| Snapshot listeners | directory, profile, schedules, appointments dashboard, contacts | Foreground live updates |
| `MetadataChanges.INCLUDE` | most listeners | Detect cache-only/pending emissions |
| Generation/version counters | listeners, analytics, mirrors | Discard stale callbacks after account/role/date changes |
| Monotonic `changedAt` | RTDB display writes | Last-writer-wins without resurrecting older state |
| RTDB revision CAS | `DashboardMirror` | Prevent a late Firestore read from overwriting a newer summary |
| Unique work names + epoch tags | reminders | No duplicate reminders; no cross-account leakage |
| 30-second UI clock | list, details, dashboard | Keep time-derived status/labels correct |

### 8.9 Fallback mechanisms

| Situation | Fallback | Explicit to the user? |
|---|---|---|
| RTDB unavailable, rules-denied or stale | Render Firestore result; dashboard state `FIRESTORE`/`CACHED` | Yes (state label + retry) |
| Firestore directory refresh fails after a good render | Keep server-confirmed content, show "Saved information" | Yes |
| Appointment detail refresh fails | Show previously loaded server snapshot, no actions from cache | Yes (message) |
| Location denied/disabled/timeout | Khulna coordinates, label "Approximate location (fallback)" | Yes (badge) |
| AQI network/formula/HTTP failure | Show last valid 15-minute cache or an explicit error | Yes |
| Exact-alarm permission missing | `setAndAllowWhileIdle` (inexact) + Snackbar offering Settings | Yes |
| Notification permission off | Reminders stay inactive; UI offers Settings | Yes |
| Custom "alarm" style unavailable | Falls back to notification-only delivery for that reminder | Yes (dialog help text) |
| Patient cannot read date blocks during cancel | Retry cancellation as "blocked" release | No (transparent, rules-authorised) |
| Verified-session revalidation fails transiently | Keep the already-verified screen (server still authorises every operation) | No (silent, bounded) |

---

## 9. Emergency contacts, reminders, notifications, calling, environment and wellness — consolidated view

The individual mechanics are in §6.11–§6.14. This chapter states how the group behaves as a whole,
because examiners usually ask "where does this live and what happens when the user is offline?".

### 9.1 Data ownership summary

| Feature | Authoritative store | Local store | Leaves the device? |
|---|---|---|---|
| Emergency contacts | Firestore `users/{uid}/emergencyContacts/list` | `SharedPreferences` mirror per account epoch | Yes, only that patient's own document |
| Default reminders | Local `SharedPreferences` ledger + WorkManager | same | No names/phones; worker sends only IDs/times |
| Custom reminders | Local ledger + `AlarmManager` | same | No |
| Notification content | Built locally from a server-verified appointment read | — | No |
| Hospital call | Resource string | — | Number opens the dialer; the user places the call |
| Environment data | None (stateless) | Last JSON per coordinate, 15 min | Coordinates go to Open-Meteo only; never to Firebase |
| Wellness cards | Static resources | — | No |

### 9.2 Offline behaviour

* Contacts: readable from the local mirror; edits are refused while the list is not server-synchronised
  (`SYNC_PENDING`), so the UI never pretends an unsynced write succeeded.
* Reminders: scheduling is local, but **delivery** requires connectivity because the worker insists on a
  fresh server status (`NetworkType.CONNECTED`, `Source.SERVER`). If the network is unavailable the worker
  retries with backoff up to 8 attempts and stops before the appointment start. This is deliberate:
  reminding a patient about a cancelled appointment is worse than not reminding.
* Environment: cache-first; a cached reading is shown with a freshness label.
* Booking/cancel: never queued offline — the app requires a server transaction.

### 9.3 Privacy properties worth stating in the viva

* No `READ_CONTACTS`, no `CALL_PHONE`, no fine or background location, no `READ_PHONE_STATE`.
* Notification public version shows only "You have an appointment reminder" (`VISIBILITY_PRIVATE`), so
  the doctor/date body is hidden on a locked screen.
* Reminder work input contains an appointment **ID**, not a doctor or patient name.
* RTDB display records contain no patient name, phone or UID in the payload (UID only in the path of the
  owner's own branch).
* Coordinates are never written to Firestore/RTDB or logged.

---
## 10. External APIs, SDKs and libraries

**Rule applied:** only integrations with evidence in the build files, manifest or code are listed. No
API is claimed because it "would make sense" for such an app.

### 10.1 Third-party network API

| API | Purpose | Integration point | Data source | Permission | Failure handling | Affected features |
|---|---|---|---|---|---|---|
| **Open-Meteo Air Quality API** (`https://air-quality-api.open-meteo.com/v1/air-quality`) | Current US AQI, PM2.5, PM10, UV index for a coordinate | `airquality/AirQualityHttpClient.java` via `HttpsURLConnection`; parameters `current=us_aqi,pm2_5,pm10,uv_index&timezone=auto&timeformat=unixtime` | Open-Meteo's CAMS-based city-level model data (public, no API key) | `INTERNET` only | HTTP ≠ 200 → `HTTP` error; IO/timeout → `NETWORK`; malformed/excessive body → `FORMAT`; last good 15-min cache shown instead | Health & Environment screen |

Evidence: `AirQualityHttpClient.BASE_ENDPOINT`, `endpoint(...)`, `readResponse(...)`; `AndroidManifest.xml`
declares `INTERNET`. No API key, token or account exists for this API, so there is no secret to leak.

**Not present:** no weather API, no maps API, no geocoding web API (reverse geocoding uses the device
`Geocoder`), no SMS/email API, no payment API, no AI/LLM API.

### 10.2 Firebase SDKs

| SDK | Version | Purpose in this app | Failure handling |
|---|---|---|---|
| `firebase-auth` | BOM 35.1.0 | Identity, email/password, Google credential exchange, custom claims | Error-code mapping in `AuthRepository.mapError/mapGoogleError`; UI messages from `AuthErrorMessages`/`AuthFailurePresentation` |
| `firebase-firestore` | BOM 35.1.0 | Authoritative data, transactions, listeners | `Failure`/`OperationFailure` with real SDK code; cache/pending snapshots rejected; user-facing text via `AppointmentUi.errorText`, `DoctorErrorMessages`, `DoctorScheduleErrorMessages` |
| `firebase-database` | BOM 35.1.0 | Verified projections and display hints | All optional paths swallow errors (`Log.w`) after Firestore success; UI state labels (`live_*` strings) communicate unavailable/offline/unverified |
| `com.google.gms.google-services` | 4.5.0 (plugin) | Generates Firebase config resources from `google-services.json` | Build-time only |

### 10.3 Google Sign-In stack

| Library | Purpose | Notes |
|---|---|---|
| `androidx.credentials:credentials` 1.6.0 | Modern credential flow | `CredentialManager.getCredentialAsync` |
| `androidx.credentials:credentials-play-services-auth` 1.6.0 | Play-services backed provider | Required for the Google option |
| `com.google.android.libraries.identity.googleid:googleid` 1.2.1 | `GetSignInWithGoogleOption`, `GoogleIdTokenCredential` | ID token is handed to Firebase for verification |

### 10.4 AndroidX / UI / utility libraries

| Library | Purpose | Used by |
|---|---|---|
| `appcompat` 1.6.1 | `AppCompatActivity`, Material theme base | every Activity |
| `material` 1.10.0 | Material 3 components: `MaterialToolbar`, `TextInputLayout`, `MaterialAutoCompleteTextView`, `MaterialTimePicker`, `MaterialAlertDialogBuilder`, `Snackbar`, `ShapeableImageView` | all screens |
| `constraintlayout` 2.1.4 | Screen layouts | most layouts |
| `recyclerview` 1.4.0 | Doctor list, appointments, slots, schedules, AQI metrics | `adapters/*` |
| `viewpager2` 1.0.0 | Wellness carousel | `WellnessCarouselAdapter`, `HealthWellnessCarousel` |
| `activity` 1.13.0 | `OnBackPressedCallback`, `ActivityResultContracts`, `registerForActivityResult` | auth, contacts, AQI, admin editors |
| `lifecycle-viewmodel` 2.11.0 | Rotation-surviving state and in-flight operations | 8 ViewModels |
| `work-runtime` 2.12.0 | Default appointment reminders | `AppointmentReminderWorker`, `LocalPatientServices` |
| `glide` 4.16.0 | Doctor image loading with placeholder/error/fallback and circular crop | `utils/DoctorImages.java` |
| Core/AppCompat platform APIs | `NotificationCompat`, `ContextCompat`, `ViewCompat`, `WindowCompat`, `LocationManagerCompat`, `NotificationManagerCompat` | notifications, accessibility headings, edge-to-edge insets, location |

### 10.5 Platform capabilities used (not "APIs" but integration points)

`AlarmManager`, `PendingIntent`, `BroadcastReceiver`, `NotificationManager`/channels, `Vibrator`,
`MediaPlayer`/`Ringtone` (alarm feedback), `ContactsContract` picker, `Geocoder`,
`LocationManager`, `BatteryManager`, `Settings.Global`, `ActivityManager` task APIs, `SharedPreferences`,
`Handler`/`Looper`, `ExecutorService`, `Canvas`/`Paint` (custom chart).

### 10.6 Manifest permissions and justification (least privilege)

| Permission | Why | Where used |
|---|---|---|
| `INTERNET` | Firebase + Open-Meteo | all network |
| `POST_NOTIFICATIONS` | Android 13+ notification consent | `ReminderNotifications.allowed`, runtime request in `AppointmentsActivity` |
| `ACCESS_COARSE_LOCATION` | One approximate fix for city-level environment data | `ApproximateLocation`; fine/background location deliberately absent |
| `SCHEDULE_EXACT_ALARM` | Precise custom reminder alarms; optional, inexact fallback exists | `LocalPatientServices.scheduleCustomAlarm` |
| `RECEIVE_BOOT_COMPLETED` | Restore custom alarms after reboot | `ReminderSystemReceiver` |
| `VIBRATE` | Alarm-style reminder feedback | `ReminderAlarmFeedback` |
| `WAKE_LOCK` | Let alarm feedback complete while the device is dozing | alarm path |

Deliberately **removed/absent**: `FOREGROUND_SERVICE` is stripped with `tools:node="remove"` and the
WorkManager `SystemForegroundService` is removed from the merged manifest — the project documents that
reminders use ordinary short workers, never a foreground service. No `CALL_PHONE`, `READ_CONTACTS`,
`ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `READ_SMS`, `CAMERA`, external storage.

`android:usesCleartextTraffic="false"` is set globally; a debug-only network security config exists for
instrumentation (§14.2) and does not ship in release.

---

## 11. CLI and development tools

Two categories are distinguished: **code-verifiable** (the repository proves the tool is part of the
workflow) and **development-history** (reported/derived, not provable from code).

### 11.1 Code-verifiable

| Tool | Evidence | Role |
|---|---|---|
| **Android Studio** | `.idea/AndroidProjectSystem.xml`, `.idea/gradle.xml`, `.idea/deploymentTargetSelector.xml`, `.idea/appInsightsSettings.xml`, project layout conventions | Primary IDE: editing, Gradle sync, emulator/device deployment, Layout Inspector/Logcat |
| **Gradle 9.6.0 (wrapper)** | `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties` (`distributionUrl=...gradle-9.6.0-bin.zip`, SHA-256 pinned) | Build system: `assembleDebug`, `testDebugUnitTest`, `lintDebug`, rules-test modules |
| **Android Gradle Plugin 9.5.1 + Google Services 4.5.0** | `gradle/libs.versions.toml`, `build.gradle.kts` | Compilation, resource processing, View Binding, R8/optimisation, Firebase config generation |
| **JDK 25 toolchain** | `gradle/gradle-daemon-jvm.properties` (`toolchainVersion=25`) | Gradle daemon JVM |
| **JUnit 4.13.2** | `gradle/libs.versions.toml`, 58 test classes | 600 JVM tests |
| **Espresso 3.5.1 / AndroidX Test** | `androidTestImplementation` entries, `app/src/androidTest` | Instrumentation/UI tests |
| **Git** | `.git`, `.gitignore`, clean working tree, commit history | Version control; the cleanup in this task was performed with Git status as the safety net |
| **GitHub** | Repository history is present locally; remote configuration is *not* in the working tree, so GitHub hosting cannot be verified from code. Treat as development-history. | Collaboration/backup |
| **Firebase Console** | Cannot be proven from code; implied by `.firebaserc` project alias, `google-services.json` presence and custom-claim requirement | Project creation, Auth providers, rules deployment, claim assignment |
| **Firebase CLI** | `firebase.json`, `.firebaserc`, `firestore.indexes.json`, `firebase.emulators.json` are exactly the CLI's configuration files; `firestore.rules`/`database.rules.json` are deployment sources | Rules/index deployment and emulators — **configuration is verifiable; no CLI invocation is scripted in the repository** |
| **ADB** | Documented handoff commands (install APK, `adb reverse` for emulator ports); `.idea/deploymentTargetSelector.xml` records emulator targets | Installing the APK, Logcat, port reversing for local Firebase emulators |
| **Firebase Emulator Suite (Java harness)** | `tests/firestore/src/.../LocalFirestoreEmulator.java`, `tests/rtdb/.../LocalDatabaseEmulator.java`, `firebase.emulators.json` | Boots Firestore 1.22.0 / RTDB emulators from Gradle for rules testing without Node |
| **Python (reporting only)** | Not part of the project; used in this session to sum JUnit XML results | One-off measurement, not a project dependency |

### 11.2 Development-history / not verifiable in code

| Item | Status |
|---|---|
| Physical device used for review | Reported in `docs/*.md` (for example a OnePlus Nord CE5 in `docs/TASK_1_PERFORMANCE_HANDOFF.md`); not verifiable from code |
| Firebase CLI deployment events | Rules files exist and are written for deployment; deployment timestamps are not recorded in the repo |
| **Java Admin SDK utility for the `admin` custom claim** | **Not present in this repository.** `docs/admin-doctor-firestore.md` and `firestore.rules` state that the claim must be assigned by a trusted Admin SDK environment. No script, service-account path or utility is committed (and committing service-account JSON is explicitly forbidden by `.gitignore`). This is a **missing artefact**, tracked in §18. |
| GitHub push/PR history | Not available in this working copy |
| Android Studio version number | Not recorded in the repository |

### 11.3 Reproducible commands

From the repository root (Windows, Git Bash / PowerShell compatible):

```bash
# build the debug APK
./gradlew.bat :app:assembleDebug --console=plain

# run all 600 JVM unit tests
./gradlew.bat :app:testDebugUnitTest --console=plain

# build the instrumentation APK (run on device/emulator separately)
./gradlew.bat :app:assembleDebugAndroidTest --console=plain

# Firestore security-rules suite (needs the local emulator JAR cache)
./gradlew.bat :firestore-rules-tests:test --console=plain

# Realtime Database security-rules suite
./gradlew.bat :rtdb-rules-tests:test --console=plain

# static analysis
./gradlew.bat :app:lintDebug --console=plain

# whitespace/conflict-marker check used after edits
git diff --check
```

The rules-test modules are documented in `tests/firestore/README.md` and `tests/rtdb/README.md`; they
deliberately avoid Node and the Firebase CLI by downloading the emulator JAR once into
`$HOME/.cache/firebase/emulators/`, booting it from Java, and speaking the emulator REST API with
synthetic unsigned tokens against the `demo-medibook` project.

---

## 12. Android laboratory requirements — mapping with code references

The table below maps commonly required lab competencies to this project. "Verified" means the code
demonstrates it unambiguously; "partial" means a real but narrower implementation; "unverified" means the
requirement (as usually worded) is not demonstrated here.

| # | Requirement | Status | Feature that fulfils it | Code reference |
|---|---|---|---|---|
| R1 | Firebase Authentication (email/password) | Verified | Login + Registration | `repositories/AuthRepository.java` (`signIn`, `register`), `activities/auth/LoginActivity.java`, `RegisterActivity.java` |
| R2 | Authentication with a second provider | Verified | Google Sign-In + account linking | `auth/GoogleCredentials.java`, `utils/GoogleAuthFlow.java`, `AuthRepository.beginGoogleLink` |
| R3 | Role-based access (privileged user) | Verified | Admin via custom claim + profile | `utils/SessionPolicy.java`, `firestore.rules` (`isAdmin`), `AdminDashboardActivity` |
| R4 | Cloud Firestore create | Verified | Doctor add, schedule add, booking, contact list, profile | `DoctorRepository.addDoctor`, `DoctorScheduleRepository.add`, `AppointmentRepository.book`, `LocalPatientServices.mutateContact` |
| R5 | Cloud Firestore read (single + query) | Verified | Directory, profile, lists, availability | `PatientDoctorRepository`, `AppointmentRepository.list/get`, `WeeklyAnalyticsRepository.query` |
| R6 | Cloud Firestore update | Verified | Doctor edit/activate, schedule edit, profile edit, cancellation | `DoctorRepository.updateDoctor`, `AuthRepository.updatePatientProfile`, `AppointmentRepository.cancel` |
| R7 | Cloud Firestore delete | **Partial / intentionally absent** | Only date blocks (Admin) are deletable; appointments/doctors/schedules are never deleted | `firestore.rules` (`allow delete: if false`), `doctorDateBlocks` delete rule |
| R8 | Firestore transactions / atomicity | Verified | Booking, cancellation, optimistic-concurrency writes, serial allocation | `AppointmentRepository.book/cancel`, `DoctorRepository.updateDoctor`, `SlotSerialRepository.assign` |
| R9 | Realtime Database write | Verified | Display feed + dashboard projection | `RealtimeDisplayRepository.publish`, `DashboardMirror.reconcile` |
| R10 | Realtime Database read | Verified | Live queue hint + dashboards | `RealtimeDisplayRepository.queue/dashboard`, `PatientDashboardRepository`, `AdminDashboardRepository` |
| R11 | Security rules for both databases | Verified (tested by separate suites) | Whole data model | `firestore.rules`, `database.rules.json`, `tests/firestore`, `tests/rtdb` |
| R12 | Local notifications | Verified | Appointment reminders (channels, actions, privacy) | `reminders/ReminderNotifications.java`, `ReminderDismissReceiver.java` |
| R13 | Runtime permission handling | Verified | Notifications (API 33+), coarse location, exact alarm | `AppointmentsActivity.manageReminders`, `AirQualityActivity`, `LocalPatientServices.exactAlarmsAllowed` |
| R14 | Java + XML UI | Verified | Entire app (no Compose, no Kotlin) | `app/src/main/java/**`, `app/src/main/res/layout/**` |
| R15 | Multiple Activities + explicit Intents | Verified | 16 Activities, contract classes for extras | `AndroidManifest.xml`, `utils/PatientDoctorProfileContract`, `DoctorScheduleContract` |
| R16 | Activity lifecycle correctness | Verified | Listener add/remove in `onStart`/`onStop`, state in `onSaveInstanceState`, operations in ViewModels, timers stopped on stop | `activities/AppointmentSessionActivity.java`, `PatientHomeActivity`, `AppointmentsActivity`, `HealthWellnessCarousel` |
| R17 | RecyclerView + adapter | Verified | Directory, appointments, slots, schedules, AQI, contacts | `adapters/*` |
| R18 | ViewPager2 / carousel | Verified | Wellness carousel | `wellness/HealthWellnessCarousel.java` |
| R19 | ViewModel + LiveData | Verified | 8 ViewModels, `MutableLiveData` in AQI | `viewmodels/*` |
| R20 | View Binding | Verified | All screens | `app/build.gradle.kts` (`viewBinding = true`), `*Binding` usage |
| R21 | Background work (WorkManager) | Verified | Default reminders | `reminders/AppointmentReminderWorker.java`, `LocalPatientServices.enqueueDefault` |
| R22 | AlarmManager + BroadcastReceiver | Verified | Custom reminder alarm, dismiss, boot/exact-alarm restoration | `reminders/ReminderAlarmReceiver.java`, `ReminderSystemReceiver.java`, `ReminderDismissReceiver.java` |
| R23 | Dynamic broadcast receiver | Verified (foreground demo) | Airplane mode / battery notices | `system/PatientSystemNotices.java` |
| R24 | Custom View (Canvas drawing) | Verified | Weekly bar chart | `views/WeeklyBarChartView.java` |
| R25 | Third-party REST API integration | Verified | Open-Meteo air quality | `airquality/AirQualityHttpClient.java`, `AirQualityParser.java` |
| R26 | Image loading library | Verified | Doctor avatars | `utils/DoctorImages.java` (Glide) |
| R27 | Input validation and error UX | Verified | Auth, profile, doctor, schedule forms | `PatientProfileValidation`, `DoctorFormValidation`, `DoctorScheduleValidation`, `AuthErrorMessages` |
| R28 | Date/time correctness (time zone) | Verified | Dhaka calendar, slots, reminders | `utils/DhakaDateTime.java`, `AppConstants.HOSPITAL_TIME_ZONE_ID` |
| R29 | Local persistence | Verified | `SharedPreferences` stores (private patient data, AQI cache) | `local/PrivatePatientStore.java`, `airquality/AirQualityRepository.java` |
| R30 | Unit testing | Verified | 58 classes / 600 tests | `app/src/test/**` |
| R31 | Instrumentation/UI testing | Verified (code present; not executed in this session) | Espresso suites incl. offline UI runner | `app/src/androidTest/**`, `UiOnlyTestRunner`, `UiFixtureActivity` |
| R32 | Push notifications (FCM) | **Not implemented** | — | no FCM dependency/service |
| R33 | Cloud Functions / serverless backend | **Not implemented** | — | no `functions/` |
| R34 | Firebase Storage upload | **Not implemented** | — | doctor `imageUrl` is an external link only |
| R35 | Google Maps / location mapping | **Not implemented** | Only a coarse fix for AQI; no map UI | `airquality/ApproximateLocation.java` |
| R36 | Room / SQLite | **Not implemented** | — | no Room dependency |
| R37 | Camera / media | **Not implemented** | — | no camera permission or usage |
| R38 | Payments | **Not implemented** | Fee is displayed, never charged | `Appointment.consultationFee` is informational |

---

## 13. UI/UX design, navigation, accessibility and role restrictions

### 13.1 Design system

| Element | Implementation |
|---|---|
| Theme | Material 3, `Theme.Material3.Light.NoActionBar` (`Base.Theme.MediBook`), light-only with a `values-night` variant present |
| Brand colours | `medibook_blue #1565C0` (primary), `medibook_teal #00BFA5` (secondary), teal-dark success `#005C59`, error `#BA1A1A`, warning `#7A4E00`, warm/neutral surfaces (`res/values/colors.xml`) |
| Typography/spacing | Material 3 type scale via theme; `res/values/dimens.xml` for spacing/component sizes |
| Shape/elevation | Material cards, layered surfaces, `slot_background.xml` for slot chips |
| Imagery | Vector icons (`ic_*`), `illustration_login.xml`, `avatar_doctor` fallback, ten wellness photographs in `drawable-nodpi` |
| Edge-to-edge | `AuthUi.applyInsets(activity, root)` applies window insets on every screen; `windowSoftInputMode="adjustResize"` on form screens |
| Feedback | Inline validation errors, persistent dialogs for write failures, Snackbars for transient results, explicit loading/empty/error state rows |

### 13.2 Navigation model

The app uses a **shallow, explicit stack** rather than a single-Activity/Navigation-Component graph:

* Launcher router (`MainActivity`) → role destination.
* Toolbars provide Up (`finish()`), plus role-appropriate menu actions.
* Cross-feature jumps use `FLAG_ACTIVITY_CLEAR_TOP` where returning to a hub makes sense
  (`AppointmentsActivity` empty state → Patient Home).
* Unauthorised or wrong-role access is resolved by wiping the task and rebuilding the correct
  destination (`AuthNavigation.openDestination(..., clearTask = true)`), which prevents "back into an
  Admin screen as a Patient".
* Back is intentionally blocked during authentication, profile creation, and pending doctor/schedule
  writes (`OnBackPressedCallback`), because abandoning a half-finished server write produces confusing
  state.

### 13.3 Role-based restrictions (enforced twice)

| Capability | Patient | Admin | Enforcement |
|---|---|---|---|
| Patient Home / directory / booking / reminders / contacts / AQI | Yes (own account) | No (redirected) | `PatientScreenAccess`, `AppointmentSessionActivity.requiredRole()`, `firestore.rules` |
| Admin dashboard / doctor + schedule management / publishing | No | Yes | `SessionPolicy` (claim + profile), each Admin screen re-checks in `onAuthStateChanged`, rules `isAdmin()` |
| Read own appointments | Yes | Yes (all) | Query filter + rules `resource.data.patientUid == request.auth.uid` |
| Read another patient's profile | No | Yes, only if that profile is a Patient | `firestore.rules` `users` get rule |
| Write doctor/schedule/inventory | No | Yes | rules create/update predicates |
| Set a reminder | Own future confirmed appointment only | No | `AppointmentPolicy.canCustomRemind` + `LocalPatientServices` epoch |
| Emergency contacts | Own only | No | rules (`isPatient() && auth.uid == uid`) |

### 13.4 Accessibility measures found in code

* `ViewCompat.setAccessibilityHeading(...)` on primary headings in Patient Home, Doctor Profile, Book
  Appointment, Appointment Details and Admin Dashboard.
* Content descriptions for icon-only actions, including parameterised ones
  (`contact_call_description`, `contact_remove_description`).
* Wellness carousel exposes "Health and wellness card n of 11" and reads the card text; the counter is
  textual, not colour-only.
* Status is never conveyed by colour alone: text labels accompany state colours (e.g. state messages,
  `AppointmentPresentation.bindStatus`).
* `android:supportsRtl="true"` in the manifest.
* Large touch targets via Material components; `dialog_custom_reminder.xml` uses radio buttons with a
  helper text explaining the alarm fallback.
* Notification privacy: `VISIBILITY_PRIVATE` with a neutral public version.

**Limitation (honest):** there is no formal accessibility audit (TalkBack walkthrough, contrast
measurement, font-scale sweep) recorded for this project, and some decorative vector icons may not carry
descriptions. This is listed in §18.

### 13.5 Empty, loading and error states

Every data screen has three explicit states rather than a blank view: a loading row/card, a specific
empty message (e.g. "No doctors available", "No appointments match this filter"), and an error message
with a Retry action where retry is safe. Cached content is always labelled ("Saved information") so the
user can distinguish verified from remembered data.

---

## 14. Testing, verification and build results

### 14.1 This session's executed checks (verified by build/test)

| Check | Command | Result |
|---|---|---|
| JVM unit tests | `./gradlew.bat :app:testDebugUnitTest --rerun` | **BUILD SUCCESSFUL** — **600 tests, 58 suites, 0 failures, 0 errors, 0 skipped** |
| Debug APK assembly | `./gradlew.bat :app:assembleDebug` | **BUILD SUCCESSFUL** — `app/build/outputs/apk/debug/app-debug.apk` (23,281,510 bytes) |
| Whitespace/conflict check | `git diff --check` | exit 0, no output |
| Working tree after cleanup + documentation | `git status --porcelain` | clean before documentation was added (new doc file is the only addition) |

Only one build warning appeared (pre-existing): the debug manifest's
`tools:replace="android:networkSecurityConfig"` warns that no other declaration exists. It is
debug-only and does not affect release.

**Not executed in this session, and why:**

* `:firestore-rules-tests:test` / `:rtdb-rules-tests:test` — require the emulator JAR cache
  (`$HOME/.cache/firebase/emulators/`) and a local loopback emulator; they are separate suites and were
  deliberately not run because running them would regenerate the 269 MB coverage dump removed during
  cleanup, and because the task scope was cleanup plus documentation.
* `:app:lintDebug` — not run here; it was run in previous recorded builds.
* Instrumentation tests — require a booted emulator/device, Firebase emulators on ports 9099/8080/9000
  and `adb reverse`, plus approved test accounts; none of that was set up for this documentation task.

### 14.2 Test inventory (code-verified)

**JVM unit tests — 58 classes.** Coverage by area:

| Area | Example classes |
|---|---|
| Models | `AppointmentTest`, `AppointmentSlotTest`, `AppointmentStatusTest`, `DoctorTest`, `DoctorScheduleTest`, `RealtimeAppointmentEventTest` |
| Policy/validation | `AppointmentPolicyTest`, `AppointmentValidationTest`, `AppointmentSerialsTest`, `AppointmentSlotsTest`, `SlotInventoryTest`, `SlotSerialsTest`, `ReservationIdentityTest`, `DoctorFormValidationTest`, `DoctorScheduleValidationTest`, `DoctorScheduleConflictsTest`, `PatientProfileValidationTest`, `PatientAgeTest`, `PatientScheduleDisplayTest` |
| Auth/session | `SessionPolicyTest`, `AuthSessionPolicyTest`, `VerifiedSessionLeaseTest`, `GoogleAccountPolicyTest`, `GoogleAuthFlowTest`, `GoogleProfileCompletionTest`, `LaunchRoutingTest`, `AccountTransitionVisibilityTest`, `PatientScreenAccessTest` |
| Booking workflow | `AppointmentWorkflowTest`, `ReservationMappingTest`, `AppointmentMapperTest` |
| Reminders | `ReminderPolicyTest`, `ReminderScheduleTest`, `AlarmFeedbackWindowTest`, `ReminderVisibilityPolicyTest` |
| Local data | `PrivatePatientStoreTest`, `EmergencyContactDocumentTest`, `ContactSelectionTest` |
| Environment | `AirQualityTest`, `EnvironmentGuidanceTest` |
| Caching/rendering | `DashboardCacheTest`, `PatientDataCacheTest`, `DashboardProjectionTest`, `WeeklyAppointmentStatsTest`, `UiPresentationTest`, `DhakaDateTimeTest`, `AppointmentDisplayStatusTest` |
| Adapters/ViewModels | `AppointmentRowDiffTest`, `PatientDoctorRowDiffTest`, `PatientDoctorDirectoryViewModelTest`, `DoctorScheduleViewModelTest`, `WellnessCarouselTest`, `SystemNoticeStateTest` |

**Instrumentation suites (`app/src/androidTest`, 7 test classes + harness/runner).**
`AppointmentListStructureTest`, `AppointmentSlotDisplayTest`, `AuthenticatedAppointmentsTest`,
`ReadPerformanceTest`, `ReloginLocalSessionTest`, `UiResourceTest`, `ExampleInstrumentedTest`, supported by
`repositories/LocalDatabaseHarness.java`, `ui/UiOnlyTestRunner.java` and the debug-only
`UiFixtureActivity`. `UiOnlyTestRunner` lets resource/structure tests run offline without account
initialisation (`-Pmedibook.uiTestRunner=com.example.medibook.ui.UiOnlyTestRunner`), which is why
`testInstrumentationRunner` is a Gradle property in `app/build.gradle.kts`.

**Security-rules suites (`tests/`).** Firestore: `AppointmentRulesTest`, `ClientRulesCanaryTest`,
`DashboardQueryRulesTest`, `DoctorRulesTest`, `EmergencyContactRulesTest`, `GoogleIdentityRulesTest`,
`InventoryRulesTest`, `PatientProfileRulesTest`, `ScheduleRulesTest`, `SlotSerialRulesTest`, driven by
`RulesIntegrationSuite`, with `LocalFirestoreEmulator`, `RulesClient`, `RulesFixtures`. RTDB:
`DashboardRulesTest`, `RealtimeRulesTest` with `LocalDatabaseEmulator`, `RtdbClient`. Coverage notes live
in `tests/firestore/COVERAGE.md`.

### 14.3 Historically recorded results (development record, not re-run here)

The team's handoff notes record:

| Record | Value | Source |
|---|---|---|
| Combined batch validation | 82 emulator rules tests / 5 classes, 317 Android tests / 28 classes, APK 13,415,123 bytes | `docs/...` batch notes quoted in `tests/firestore/README.md` |
| Performance task verification | 594 Android unit tests passed; 12 offline + 3 authenticated instrumentation tests passed; 3 cold verifications, 0 quiet revalidations in final Logcat | `docs/TASK_1_PERFORMANCE_HANDOFF.md` |
| Current unit-test count measured in this session | **600 tests / 58 suites** | this report, §14.1 |

The discrepancy between 594 and 600 is explained by tests added after that handoff was written; the
authoritative current number is the one measured here.

### 14.4 Emulator / physical-device verification status

| Aspect | Status |
|---|---|
| Emulator used for development and instrumentation | Yes (recorded; `.idea/deploymentTargetSelector.xml` lists emulator targets) |
| Physical-device functional review | Recorded for earlier steps (doctor management reviewed on a phone per `docs/admin-doctor-management-ui.md`) |
| Physical-device performance/battery/FPS measurement | **Not done** (explicitly listed as remaining work in `docs/TASK_1_PERFORMANCE_HANDOFF.md`) |
| Production Firebase data/rules changes during this task | None — no deploy, no writes |

---

## 15. AI-assisted development tools

This section separates what the repository proves from what the project owner reports. This distinction
matters for academic integrity statements.

### 15.1 Confirmed by project evidence

| Tool | Evidence | What it contributed |
|---|---|---|
| **OpenCode** | Two handoff documents reference an OpenCode temporary workspace, e.g. `docs/BATCH_3_GOOGLE_AUTH_HANDOFF.md` cites `C:\Users\user\AppData\Local\Temp\opencode\MediBook-before-batch3-20261109-112447-758e43c0.zip`, and `docs/UI_UX_MODERNIZATION_HANDOFF.md` cites `...\Temp\opencode\MediBook-ui-modernization-...\project-inputs.zip` | Batch implementation work with pre-batch snapshot archives and written handoffs |

That is the **only** AI-tool fingerprint in the tracked files. It proves an OpenCode workspace was used
around the Google-auth and UI-modernisation batches; it does not prove which files each prompt produced.

### 15.2 User-provided development context (not verifiable from code)

| Tool | Reported role | Verification status |
|---|---|---|
| **Antigravity** | Reported as part of the development toolchain | No artefact, config, log or commit trailer in the repository; **unverified** |
| Other assistants (ChatGPT/Claude/Copilot/Gemini) | Occasionally mentioned in general discussion | No evidence; **not claimed** |

### 15.3 How AI assistance is bounded in this project

Regardless of which assistant contributed, the repository shows a pattern that is defensible in a viva:

* Business rules live in **security rules** and **transactions**, not in generated UI code.
* Pure policy classes are **unit tested** (600 tests), so generated logic can be checked.
* Design intent is recorded in `docs/` handoffs with explicit "limits/remaining checks" sections.
* The documentation distinguishes design documents from implemented behaviour (§0.1).

**Recommended disclosure for submission:** state that OpenCode was used for batch implementation with
documented handoffs, that Antigravity was
part of the toolchain per the owner, and that all architectural decisions, rules and tests are
reviewable in the repository. Do **not** claim tools for which no evidence exists.

---
## 16. Matrices

### 16.1 Feature-to-technology matrix

| Feature | Firebase Auth | Firestore | RTDB | WorkManager | AlarmManager | Notifications | HTTP API | Glide | ViewModel | RecyclerView | Custom View |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Login / Register / Google | ● | ● | | | | | | | | | |
| Session verification | ● | ● | | | | | | | | | |
| Profile edit | ● | ● | | | | | | | | | |
| Doctor directory | ● | ● | | | | | | ● | ● | ● | |
| Doctor profile | ● | ● | | | | | ● | ● | | ● | |
| Availability + booking | ● | ● | ○ | | | | | | ● | ● | |
| Slot publishing | ● | ● | ○ | | | | | | ● | ● | |
| Serial allocation | | ● | | | | | | | | | |
| Appointment list | ● | ● | ○ | | | | | | ● | ● | |
| Details / cancellation | ● | ● | ○ | | | | | | ● | | |
| Default reminders | ● | ● | | ● | | ● | | | | | |
| Custom reminder (alarm) | ● | ● | | ● | ● | ● | | | | | |
| Emergency contacts | ● | ● | | | | | | | ● | ● | |
| Hospital call | | | | | | | | | | | |
| Health & Environment | ● | | | | | | ● | | ● | ● | |
| Wellness carousel | | | | | | | | | | ● | |
| Admin dashboard | ● | ● | ● | | | | | | ● | | ● |
| Airplane/battery notices | | | | | | | | | ● | | |

● = primary use, ○ = optional best-effort projection.

### 16.2 Requirement-to-implementation matrix (course/lab view)

| Requirement wording (typical) | Implemented? | Demonstration artefact | Where to point in code |
|---|---|---|---|
| "Integrate Firebase Authentication" | Yes, two providers | Login as patient; sign in with Google | `AuthRepository`, `LoginActivity` |
| "Implement role-based login" | Yes, claim + profile | Admin account lands on dashboard, patient cannot | `SessionPolicy`, `AdminDashboardActivity` |
| "Perform Firestore CRUD" | Create/read/update yes; delete deliberately restricted | Admin adds doctor, edits fee, deactivates | `DoctorRepository`, rules |
| "Use Firestore queries" | Yes (equality + range + index) | Directory filter, next appointment, weekly range | `PatientDoctorRepository`, `PatientDashboardRepository`, `WeeklyAnalyticsRepository` |
| "Use Realtime Database" | Yes (write + read + rules) | Dashboard counts; live queue hint | `RealtimeDisplayRepository`, `DashboardMirror` |
| "Write security rules" | Yes, for both databases, plus test suites | Show `firestore.rules` `validAppointmentCreate` and `getAfter` | `firestore.rules`, `database.rules.json` |
| "Show notifications" | Yes, with channels/actions/privacy | Set a custom reminder 1 minute ahead | `ReminderNotifications` |
| "Use a background task" | Yes, WorkManager + AlarmManager | Default 24 h reminder entry; custom alarm | `AppointmentReminderWorker`, `LocalPatientServices` |
| "Use RecyclerView" | Yes, six screens | Patient directory, appointments | `adapters/*` |
| "Use a third-party API" | Yes, Open-Meteo | Open Health & Environment | `AirQualityHttpClient` |
| "Persist data locally" | Yes, SharedPreferences stores | Reminder ledger, contacts mirror, AQI cache | `PrivatePatientStore` |
| "Handle Activity lifecycle" | Yes | Rotate during booking/date picker; listener logs | `AppointmentSessionActivity`, ViewModels |
| "Runtime permissions" | Yes | Deny notifications, deny location, deny exact alarms | `AppointmentsActivity`, `AirQualityActivity` |
| "Custom View" | Yes | Admin chart | `WeeklyBarChartView` |
| "Unit + instrumentation tests" | Yes (600 unit; instrumentation present) | Run `testDebugUnitTest` live | `app/src/test`, `app/src/androidTest` |

---

## 17. Step-by-step demonstration plan for the course teacher

**Preparation (before the session)**

1. Emulator or phone signed into a **Patient** test account; a second device/emulator or a signed-out
   state for the **Admin** account. Two accounts are needed because roles are mutually exclusive.
2. Ensure the Admin test account has the `admin` custom claim (set outside the app; see §18 risk R-3).
3. Ensure `app/google-services.json` is present and the build is installed:
   `./gradlew.bat :app:assembleDebug` then install `app-debug.apk`.
4. Have a doctor with at least one weekly window, and one **future date with published slots**. If the
   date is not published, publish it live in step 7.
5. Keep Logcat filtered to `MediBookAppointments`, `MediBookAuth`, `MediBookLiveDisplay`,
   `MediBookSlotSerial` to show evidence during the demo.

**Demonstration sequence (about 15 minutes)**

| Step | Action | What to say / what it proves |
|---|---|---|
| 1 | Launch the app while signed out | `MainActivity` is a router; unauthenticated state goes to Login. Point at the session card and explain server verification. |
| 2 | Register a new patient (or sign in) | Show validation errors first (bad email, short password, mismatched confirmation), then success. Mention atomic account+profile creation and rollback. |
| 3 | Sign out, sign in with Google on the login screen | Credential Manager chooser → Patient Home. Mention account linking from Edit Profile and re-authentication. |
| 4 | Patient Home tour | Search a doctor by name, filter by specialization, open the Next Appointment card, note the wellness carousel auto-advancing every 8 s, the hospital call button, Emergency Contacts and Health & Environment entries. |
| 5 | Open a doctor profile | Live profile + weekly schedule; images via Glide; explain that only `active == true` doctors are readable. |
| 6 | Open Health & Environment | Grant coarse location (or deny to show the labelled fallback), show AQI/PM/UV and guidance, then rotate the device to show state survival. |
| 7 | Admin: publish slots | Sign in as Admin → Manage Doctors → Manage Schedule → pick tomorrow's date → Publish → report N slots. Publish again to show "0" (idempotent, never duplicates). |
| 8 | Patient: book a slot | Choose the date, pick a time chip (point out `#03` serial), confirm, read the confirmation dialog (fee, slot number), then land on My Appointments with the result Snackbar. |
| 9 | Race demonstration (optional, strong) | On two devices, both open the same slot; book on device A, attempt on device B → B receives "slot already booked", not a duplicate appointment. Explain generation + lock + transaction. |
| 10 | Appointment details | Show slot number, fee, patient demographics, derived status. Point out that "Completed" is derived from `endAt`, not stored. |
| 11 | Custom reminder | Set a reminder 1 minute ahead in **Alarm** mode; background the app; let the notification appear with sound/vibration; open it (feedback stops) and press Dismiss on another appointment to show the receiver. |
| 12 | Cancel | Cancel the appointment; show it becomes Cancelled and the slot is available again on the patient booking screen. Mention the blocked-date retry path. |
| 13 | Emergency contacts | Add a contact through the system picker, try adding the same number again (duplicate rejected), call via the dialer (no `CALL_PHONE` permission), remove it. |
| 14 | Admin dashboard | Show today/week counts and the Saturday–Friday chart. Disconnect the network briefly to show the state label/fallback, then reconnect. |
| 15 | Security walkthrough | Open `firestore.rules` and show: Patient list filter, booking batch with `getAfter`, immutable locks, `allow delete: if false`, contact bijection, and `isAdmin()` requiring the claim. Then show the rules test suites in `tests/`. |
| 16 | Build/test evidence | Run `./gradlew.bat :app:testDebugUnitTest` and show 600 passing tests; show `git diff --check` clean and `git status` clean. |

**Likely viva questions and short answers**

| Question | Answer |
|---|---|
| Why Firestore *and* RTDB? | Firestore is the transactional source of truth; RTDB is a verified, best-effort projection for fast dashboards and a queue hint. RTDB never decides a booking. |
| How do you prevent double booking? | A single Firestore transaction updates the slot from `available` to `booked`, increments `generation`, appends an immutable `bookings/gN` lock and creates the appointment; rules validate all three with `getAfter`. |
| What stops a patient from becoming Admin? | Admin needs the signed `admin` custom claim **and** profile `role == "Admin"`; the claim cannot be set from the client, and rules use the claim, not the profile field. |
| Where is the "Completed" status written? | Nowhere; it is derived from `endAt` compared with the current time, so no server job is needed. |
| Why does a reminder need the network? | The worker re-reads the appointment from the server before notifying, so a cancelled booking is never reminded. Offline → retry with backoff, then skip. |
| What happens if the same booking is retried? | The deterministic appointment ID makes the retry find the already-created document and return it instead of creating a second one. |
| Why no `READ_CONTACTS`? | The contact is chosen through the system picker, which returns a URI for that one row; the app never needs to read the address book. |
| What is cached, and can it authorise anything? | Only display data in memory/SharedPreferences, account-scoped and bounded; every action still goes through a server transaction and rules. |

---

## 18. Known issues, incomplete features, technical risks and future improvements

### 18.1 Known limitations (verified in code or documented by the team)

| ID | Limitation | Impact | Evidence / note |
|---|---|---|---|
| L-1 | Release build has **no signing configuration** | `assembleRelease` yields an unsigned APK; only debug is deliverable | `app/build.gradle.kts` has no `signingConfigs` |
| L-2 | `app/google-services.json` and `local.properties` are git-ignored | A fresh clone cannot build until both are supplied | `.gitignore` |
| L-3 | Global schedule-overlap exclusion is **not** guaranteed across concurrent new IDs | Two Admins creating overlapping windows simultaneously could both succeed | `DoctorScheduleRepository.write` comment; `firestore.rules` cannot query siblings |
| L-4 | Appointment list queries are unpaginated | Large histories load fully; performance risk at scale | `AppointmentRepository.list` |
| L-5 | Admin analytics re-reads up to 7 days of appointment documents | Cost/latency grows with volume; no server-side aggregation | `WeeklyAnalyticsRepository.query` |
| L-6 | "Completed" is presentation-only; it is not queryable as a stored status | Reports filtering by "Completed" must derive it | `AppointmentDisplayStatus` |
| L-7 | Slot serials are display-only sidecars | If a sidecar is missing/undeployed, the label shows `—` | `SlotSerialRepository.numbers`, `SlotSerials.UNAVAILABLE` |
| L-8 | RTDB projections are foreground-only and best-effort | Dashboards fall back to Firestore when RTDB is unavailable | `DashboardMirror`, `RealtimeDisplayRepository` |
| L-9 | No password reset / email verification flow | Users must re-register or use Google if they forget a password | not present in code |
| L-10 | No account deletion UI | A user cannot delete their account in-app | `user.delete()` only used in registration rollback |
| L-11 | Doctors are records, not users | No doctor login, availability self-service or consultation notes | by design (single-hospital scope) |
| L-12 | No push notifications (FCM) | Reminders only exist on the device that scheduled them | no FCM dependency |
| L-13 | No Firebase Storage / avatar upload | Admin can only paste an external image URL | `DoctorImages`, `imageUrl` rules |
| L-14 | Environment data is city-level model data, not a hospital sensor | Cannot be used for clinical decisions; the UI says "estimate" | `AirQualityReport` comment |
| L-15 | Exact alarms may be downgraded to inexact | A custom reminder can arrive late on devices without exact-alarm access | `LocalPatientServices.scheduleCustomAlarm` |
| L-16 | Reminder delivery requires connectivity | Offline at reminder time → retries, then skips | `AppointmentReminderWorker` |
| L-17 | Announcements are retired | Rules deny the collections; no UI | `database.rules.json`, `docs/hotfix-announcements.md` |
| L-18 | No formal accessibility audit | Unverified TalkBack/contrast/font-scale behaviour | not recorded |
| L-19 | Instrumentation and rules suites were **not run** in this session | Their current pass status is unverified here | §14.1 |
| L-20 | Physical-device performance not measured | Emulator/count-based evidence only | `docs/TASK_1_PERFORMANCE_HANDOFF.md` |

### 18.2 Technical risks

| Risk | Likelihood | Consequence | Mitigation present |
|---|---|---|---|
| R-1 Rules deployed to production differ from the repository copy | Medium | Client assumptions mismatch enforcement | Repository holds rules sources + test suites; deployment discipline required |
| R-2 Custom `admin` claim granted to the wrong account | Low/Medium | Unauthorised Admin access | Requires both claim and profile role; rules use claim; audit the claim list in Firebase console |
| R-3 **No committed Admin-claim utility** | Certain (artefact missing) | A new environment cannot create an Admin without manual work | Documented manual step; recommend committing a *script* (never a service-account key) |
| R-4 Firestore billing/quota exhaustion from listeners | Low | Reads stop, UI shows error | Listeners are attached only in `onStart`, removed in `onStop`; bounded queries |
| R-5 Time-zone assumption (Dhaka is UTC+06:00, no DST) | Very low | Rules' date arithmetic would break if DST were introduced | Documented constant; `AppConstants` |
| R-6 Device clock manipulation | Low | Reminder could fire early/late | Worker checks `now < due → retry` and `now >= expires → skip` |
| R-7 `google-services.json` leakage | Low | Firebase API keys are not secrets, but project identifiers are exposed | File ignored by Git; do not paste it into reports |
| R-8 Concurrent Admin edits to the same doctor/schedule | Low | One edit reports `CONFLICT` | Optimistic concurrency on `createdAt`/`updatedAt` |

### 18.3 Explicitly incomplete or out of scope (do not present as features)

* Rescheduling / editing a booked appointment (only cancel + rebook).
* Waiting lists, overbooking, walk-in queue management.
* Payments, invoices, insurance, refunds (fee is informational only).
* Prescriptions, medical records, lab reports, chat/telemedicine.
* Doctor-side application, availability exceptions beyond whole-date blocks, leave management.
* Multi-branch/multi-hospital support, localization beyond English resources.
* Analytics dashboards beyond the weekly counts/chart; export/reporting.
* Offline booking queue (intentionally rejected).
* Any AI/LLM feature inside the app.

### 18.4 Future improvements (ordered by value/effort)

1. **Pagination + server aggregates** for appointment lists and Admin analytics (largest scalability win).
2. **FCM push reminders** so reminders survive device change, with Firestore-triggered fan-out via a
   trusted backend.
3. **Signed release build** + CI (build, unit tests, lint, rules tests) so the deliverable is verifiable.
4. **Password reset / email verification** flows and an account-deletion path.
5. **Commit a small, secret-free Admin-claim script** (reads a service-account path from an environment
   variable) to close risk R-3.
6. **Reschedule support** (cancel + rebook in one guided flow) with the same transaction discipline.
7. **Accessibility pass**: TalkBack sweep, contrast check, font scale 200 %, content descriptions for all
   icons.
8. **Instrumentation CI on an emulator matrix** (API 24, 33, 37) including the rules suites.
9. **Optional doctor role** with scoped rules, if the course scope expands.

### 18.5 Final project status

| Dimension | Status |
|---|---|
| Build | **Passing** (`assembleDebug`, 23.3 MB APK) |
| Unit tests | **Passing** (600 / 58 suites, 0 failures) |
| Rules tests | Present and previously documented as passing; **not executed in this session** |
| Instrumentation tests | Present; **not executed in this session** |
| Patient features | Complete for the documented scope (auth, directory, booking, cancellation, reminders, contacts, calling, environment, wellness) |
| Admin features | Complete for the documented scope (doctors, schedules, publishing, analytics) |
| Security model | Two-layer (client policy + rules) with cross-document `getAfter` invariants; strict RTDB validation |
| Documentation | This report plus 18 development documents in `docs/` |
| Cleanup | Generated test/build artefacts removed (287 MB); sources, resources, rules, tests and docs preserved |
| Version control | Working tree clean before documentation; **no commits, pushes, resets, stashes or deploys performed** |
| Overall | **Submission-ready as a debug-build academic project**, with the limitations in §18.1 stated openly |

---

## Appendix A — Key source-file index

### A.1 Activities (17)

`MainActivity` · `activities/AppointmentSessionActivity` (abstract base) ·
`activities/AppointmentsActivity` · `activities/AppointmentDetailsActivity` ·
`activities/auth/LoginActivity` · `RegisterActivity` · `CompleteProfileActivity` ·
`activities/patient/PatientHomeActivity` · `DoctorProfileActivity` · `BookAppointmentActivity` ·
`EmergencyContactActivity` · `AirQualityActivity` ·
`EditProfileActivity` · `activities/admin/AdminDashboardActivity` · `ManageDoctorsActivity` ·
`AddEditDoctorActivity` · `ManageDoctorScheduleActivity` · `AddEditDoctorScheduleActivity`.

### A.2 Repositories (24)

`AuthRepository` · `DoctorRepository` · `DoctorScheduleRepository` · `DoctorScheduleStore` (interface) ·
`PatientDoctorRepository` · `PatientProfileRepository` · `AppointmentRepository` ·
`AppointmentReadRepository` · `AppointmentWrites` · `AppointmentMapper` · `InventorySlotMapper` ·
`BookingLockMapper` · `DoctorMapper` · `DoctorScheduleMapper` · `SlotInventoryPublisher` ·
`SlotSerialRepository` · `RealtimeDisplayRepository` · `DashboardMirror` · `DashboardState` ·
`PatientDashboardRepository` · `AdminDashboardRepository` · `WeeklyAnalyticsRepository` ·
`SingleFlightReads` · `package-info`.

### A.3 Domain models (10)

`Appointment` · `AppointmentSlot` · `AppointmentStatus` · `BookingLock` · `Doctor` · `DoctorSchedule` ·
`InventorySlot` · `RealtimeAppointmentEvent` · `UserProfile` · `package-info`.

### A.4 Reminder subsystem

`ReminderPolicy` · `ReminderSchedule` (with `Kind`, `DefaultRecord`, `CustomRecord`) ·
`AppointmentReminderWorker` · `ReminderAlarmReceiver` · `ReminderSystemReceiver` ·
`ReminderDismissReceiver` · `ReminderNotifications` · `ReminderMode` · `ReminderAlarmFeedback` ·
`AlarmFeedbackWindow`.

### A.5 Environment subsystem

`AirQualityHttpClient` · `AirQualityParser` · `AirQualityReport` · `AirQualityRepository` ·
`AirQualityException` · `ApproximateLocation` · `EnvironmentGuidance`.

### A.6 Local/private data subsystem

`LocalPatientServices` (690 lines; coordinator) · `PrivatePatientStore` · `EmergencyContact` ·
`EmergencyContacts` · `EmergencyContactDocument` · `ContactPickerReader` · `ContactSelection`.

### A.7 Selected XML

`activity_main.xml`, `activity_login.xml`, `activity_register.xml`, `activity_complete_profile.xml`,
`activity_patient_home.xml`, `activity_doctor_profile.xml`, `activity_book_appointment.xml`,
`activity_appointments.xml`, `activity_appointment_details.xml`, `activity_emergency_contact.xml`,
`activity_air_quality.xml`, `activity_edit_profile.xml`, `activity_admin_dashboard.xml`,
`activity_manage_doctors.xml`, `activity_add_edit_doctor.xml`,
`activity_manage_doctor_schedule.xml`, `activity_add_edit_doctor_schedule.xml`,
`dialog_custom_reminder.xml`, `dialog_confirm_password.xml`, `item_*.xml`, `view_auth_brand.xml`,
`view_patient_session_loading.xml`.

### A.8 Configuration

`app/build.gradle.kts` · `build.gradle.kts` · `settings.gradle.kts` · `gradle/libs.versions.toml` ·
`gradle.properties` · `gradle/wrapper/gradle-wrapper.properties` · `AndroidManifest.xml` ·
`firestore.rules` · `database.rules.json` · `firestore.indexes.json` · `firebase.json` ·
`firebase.emulators.json` · `.firebaserc` · `.gitignore`.

---

## Appendix B — Data dictionary (as implemented)

### B.1 `users/{uid}`

| Field | Type | Notes |
|---|---|---|
| `fullName` | string | 1–100 chars, must contain non-whitespace |
| `email` | string | must equal the token email; ≤ 254 chars |
| `phone` | string | `^\+?[0-9]{7,15}$` |
| `role` | string | `"Patient"` or `"Admin"` (Admin only alongside the claim) |
| `createdAt` | timestamp | server timestamp at creation |
| `dateOfBirth` | string (optional) | `YYYY-MM-DD`, real calendar date, not future |
| `gender` | string (optional) | new writes `Male`/`Female`; legacy `Other`/`Prefer not to say` readable |

### B.2 `doctors/{doctorId}`

`name`, `specialization`, `qualifications`, `biography` (strings, trimmed/limited), `consultationFee`
(int hundredths, 0–99,999,999), `active` (bool), `createdAt`/`updatedAt` (timestamps), optional
`imageUrl` (https URL ≤ 2048 chars or empty string). Deletion forbidden.

### B.3 `doctors/{doctorId}/schedules/{scheduleId}`

`dayOfWeek` int 1–7 (Mon–Sun), `startTime`/`endTime` int minutes 0–1439 (Dhaka wall clock),
`slotDurationMinutes` int 5–180 and multiple of 5, `active` bool, `updatedAt` timestamp. Exactly six
fields; IDs only in the path.

### B.4 `appointments/{appointmentId}`

| Field | Type | Meaning |
|---|---|---|
| `patientUid`, `patientName`, `patientPhone` | string | snapshot at booking |
| `doctorId`, `doctorName`, `doctorSpecialization` | string | snapshot at booking |
| `consultationFee` | int | hundredths |
| `scheduleId`, `dayOfWeek` | string / int | window identity |
| `appointmentDate` | timestamp | Dhaka midnight |
| `startMinute`, `endMinute` | int | Dhaka minutes |
| `startAt`, `endAt` | timestamp | exact UTC instants |
| `status` | string | `"Confirmed"` or `"Cancelled"` |
| `createdAt`, `updatedAt` | timestamp | server timestamps |

### B.5 Inventory slot

`scheduleId`, `appointmentDate` (midnight), `dayOfWeek`, `startMinute`, `endMinute`, `startAt`,
`endAt`, `status` (`available`/`booked`/`blocked`), `generation` (≥ 0), `appointmentId` (string or
null), `createdAt`, `updatedAt`. Document ID `s<startAtMillis>-<endAtMillis>`.

### B.6 Booking lock `.../bookings/g{generation}`

`appointmentId`, `patientUid`, `scheduleId`, `startAt`, `endAt`, `createdAt`. Immutable.

### B.7 Emergency contact document `users/{uid}/emergencyContacts/list`

`contacts` map with keys `c0`–`c9`, each `{name, phone, phoneKey}`; `phoneKeys` map
`phoneKey → slotKey` (bijection); `updatedAt` timestamp. Maximum 10 entries.

---

## Appendix C — Glossary for the viva

| Term | Meaning in this project |
|---|---|
| **Slot / inventory slot** | An immutable published, bookable interval document under `doctorSlotInventory/.../slots/{s<start>-<end>}` |
| **Window / schedule** | A recurring weekly availability rule (day + start/end minutes + duration) |
| **Generation** | Counter incremented on each successful booking of a slot; each generation has its own immutable lock |
| **Reservation lock** | Append-only document proving a specific generation was booked by a specific appointment |
| **Serial** | Human-friendly slot number (`#01`) allocated chronologically per doctor/date; display-only |
| **Queue token** | `Q%04d` ordinal derived from the start minute; used only in RTDB display hints |
| **Verified session** | A UID/claims/profile triple freshly confirmed against Firebase, reusable for 120 s |
| **Quiet revalidation** | A re-check that keeps the current screen visible instead of showing a loader |
| **Projection / mirror** | The RTDB summary copy that is displayed only when it matches Firestore |
| **Epoch (local)** | Random per-account token that scopes local reminders/contacts so accounts cannot leak into each other |
| **Tombstone** | A local `cancelled` reminder record preventing a stale `Confirmed` read from rescheduling |
| **Dhaka midnight** | The `Timestamp` for 00:00 Asia/Dhaka of a date, used as a Firestore path key and date field |

---

## Appendix D — Cleanup record for this task

**Deleted (proven generated, git-ignored, reproducible):**

| Path | Size | Why safe |
|---|---|---|
| `tests/firestore/build/` | 286,948,839 bytes total, incl. `emulator/rule-coverage.json` (269 MB) and `emulator/firestore-emulator.log` (4.6 MB) | Gradle/module output of the separate rules-test project; its own `.gitignore` ignores `/build/`; regenerated by `:firestore-rules-tests:test`; `tests/firestore/README.md` itself documents `rule-coverage.json` as raw, varying, ignored diagnostic output |
| `tests/rtdb/build/` | 156,258 bytes | Compiled test classes, emulator log, JUnit XML, `tmp/jar_extract_*` scratch dirs; regenerated by `:rtdb-rules-tests:test` |

Total removed: **~287 MB**, 80 files. No tracked file was touched; `git status` remained clean, so no
source, resource, rule, test or documentation file was lost.

**Preserved but noted as cleanup candidates (not deleted because they affect build time, are
device-specific, or could not be proven unnecessary):**

| Path | Size | Reason preserved |
|---|---|---|
| `app/build/` | 142 MB (now ~120 MB after rebuild) | Contains the verified debug APK and incremental intermediates; deleting only adds rebuild time and risks losing an artefact needed for the demo |
| `build/` (root) | 12 MB | Gradle configuration-cache/problem reports; harmless, regenerated |
| `.gradle/` | 33 MB | Project-local Gradle cache; deleting would slow the required `assembleDebug` verification and must not be done mid-build |
| `.idea/` | 159 KB | IDE/device configuration; git-ignored, but preserving it keeps the local project usable |
| `local.properties` | 448 bytes | Machine-specific SDK path; required to build |
| `app/google-services.json` | 1,415 bytes | Local Firebase configuration; required to build; must never be committed |
| `docs/admin-doctor-management-ui.md` | 12 lines | Very short and largely superseded, but it is a tracked historical record and contains a pointer to the authoritative document; deleting documentation was out of scope for "proven unnecessary" |

No duplicate source files, abandoned experiments, `*.bak`/`*.orig`/`*.tmp`/`*.log` files outside
`build/`, stray APKs outside `build/`, `.iml` files, or `.DS_Store` files were found in the working tree.

---

*End of report.*
