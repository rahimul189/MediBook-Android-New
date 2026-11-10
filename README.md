\# 🏥 MediBook — Doctor Appointment Booking System



\*\*MediBook\*\* is an Android-based doctor appointment booking application designed for a single hospital. It provides patients with a convenient way to discover doctors, explore available appointment slots, book appointments, and manage their bookings.



The application also includes a dedicated administration module for managing doctors, schedules, appointments, and hospital announcements.



Developed as an academic Android Development project using \*\*Java, XML, and Firebase\*\*.



\## ✨ Key Features



\### 👤 Patient Module



\- \*\*Authentication:\*\* Patient registration, email/password login, and Google Sign-In.

\- \*\*Doctor Discovery:\*\* Browse and search doctors, filter by specialization, and view doctor profiles.

\- \*\*Doctor Profiles:\*\* View qualifications, consultation fees, and weekly schedules.

\- \*\*Appointment Booking:\*\* Select an available date and time slot to book an appointment.

\- \*\*Appointment Management:\*\* View upcoming and previous appointments, including appointment details and status.

\- \*\*Appointment Cancellation:\*\* Cancel eligible future appointments.

\- \*\*Appointment Serial Numbers:\*\* View the assigned appointment slot number.

\- \*\*Appointment Reminders:\*\* Receive local notifications before scheduled appointments.

\- \*\*Environment \& Health:\*\* View air-quality and environmental health information.

\- \*\*Health \& Wellness:\*\* Explore educational wellness cards and health tips.

\- \*\*Emergency Contacts:\*\* Access saved emergency contacts and the hospital dialer.

\- \*\*Hospital Announcements:\*\* Receive hospital updates through Firebase Realtime Database.



\### 🛡️ Admin Module



\- \*\*Doctor Management:\*\* Add, update, and remove doctor profiles.

\- \*\*Doctor Availability:\*\* Manage weekly schedules and doctor active/inactive status.

\- \*\*Appointment Management:\*\* Review patient appointments and cancel appointments when necessary.

\- \*\*Patient Information:\*\* View relevant patient details associated with appointments.

\- \*\*Hospital Announcements:\*\* Publish and manage announcements.

\- \*\*Appointment Analytics:\*\* Review appointment activity and summary information.



\## 🛠️ Technology Stack



| Category | Technologies |

|---|---|

| Platform | Android |

| Programming Language | Java |

| UI Development | XML, Material Components |

| IDE | Android Studio |

| Build System | Gradle Kotlin DSL |

| Authentication | Firebase Authentication |

| Primary Database | Cloud Firestore |

| Realtime Database | Firebase Realtime Database |

| UI Architecture | Activity-based Android UI, View Binding |

| Image Loading | Glide |

| Doctor Image Storage | Cloudinary |

| Environmental Data | Open-Meteo APIs |

| Version Control | Git \& GitHub |



\## 🏗️ System Architecture



MediBook uses Firebase services to manage authentication, appointment information, and realtime hospital updates.



```text

&#x20;                    MediBook Android App

&#x20;                             |

&#x20;               +-------------+-------------+

&#x20;               |                           |

&#x20;         Patient Module               Admin Module

&#x20;               |                           |

&#x20;               +-------------+-------------+

&#x20;                             |

&#x20;                      Firebase Services

&#x20;                             |

&#x20;          +------------------+------------------+

&#x20;          |                  |                  |

&#x20;    Authentication       Firestore             RTDB

&#x20;          |                  |                  |

&#x20;     User Accounts     Doctors, Slots      Announcements

&#x20;                       Appointments

```



\### Database Responsibilities



\*\*Cloud Firestore\*\*

\- Doctor profiles and weekly schedules

\- Published appointment slots

\- Appointment reservations and cancellation records

\- Patient-related booking information



\*\*Firebase Realtime Database\*\*

\- Hospital announcements and realtime updates

\- Supporting realtime data used by the application



\*\*Firebase Authentication\*\*

\- User registration and sign-in

\- Authenticated access to patient and administrative features



\## 📅 Appointment Booking Workflow



```text

Patient Login

&#x20;    |

Browse Doctors

&#x20;    |

Select Doctor

&#x20;    |

View Available Slots

&#x20;    |

Choose Date \& Time

&#x20;    |

Confirm Appointment

&#x20;    |

Appointment Created

&#x20;    |

View Appointment Details

&#x20;    |

Receive Reminder

```



Appointment reservations are designed to prevent multiple patients from booking the same slot. Each published slot has a stable serial number, which remains associated with that slot even after cancellation and rebooking.



\## 📱 Application Screenshots



Screenshots of the Android application will be added here.



| Patient Interface | Admin Interface |

|---|---|

| Login \& Registration | Admin Dashboard |

| Patient Dashboard | Doctor Management |

| Doctor List \& Profile | Weekly Schedule |

| Appointment Booking | Appointment Management |

| My Appointments | Hospital Announcements |

| Environment \& Health | Appointment Analytics |



\## ⚙️ Installation \& Setup



\### Prerequisites



\- Android Studio

\- Android SDK

\- Java-compatible Android development environment

\- Firebase project configured for Android

\- Internet connection for Firebase services



\### Clone the Repository



```bash

git clone https://github.com/rahimul189/MediBook-Android-New.git

```



\### Open in Android Studio



1\. Open Android Studio.

2\. Select \*\*Open\*\*.

3\. Navigate to the cloned MediBook project.

4\. Allow Gradle synchronization to complete.



\### Firebase Configuration



1\. Create or configure a Firebase project.

2\. Register the Android application using the package name:



&#x20;  ```text

&#x20;  com.example.medibook

&#x20;  ```



3\. Download the Android Firebase configuration file.

4\. Place it at:



&#x20;  ```text

&#x20;  app/google-services.json

&#x20;  ```



5\. Configure Firebase Authentication, Cloud Firestore, and Realtime Database.

6\. Deploy the required Firebase security rules and indexes for your environment.

7\. Configure any additional external services required by the application.



\*\*Security Note:\*\* Firebase configuration files, privileged service-account credentials, and other sensitive local configuration must not be committed to the public repository.



\### Run the Application



1\. Connect an Android device with USB Debugging enabled, or start a compatible emulator.

2\. Select the `app` run configuration.

3\. Build and run the project using Android Studio.



\## 🔐 Security \& Data Management



MediBook is designed around authenticated access and role-based separation between patients and administrators.



\- Firebase Authentication handles user authentication.

\- Firebase Security Rules govern database access.

\- Appointment booking uses controlled reservation operations.

\- Patient information is restricted according to the application's access rules.

\- Sensitive configuration and administrative credentials are excluded from version control.



\## 🧪 Testing



The project includes testing resources for application logic, Android UI behavior, and Firebase security rules.



Testing areas include:



\- Authentication and user-session behavior

\- Appointment booking and cancellation

\- Doctor schedule handling

\- Firestore security rules

\- Patient-related local data handling



\## 🎓 Academic Project



\*\*Project Name:\*\* MediBook — Doctor Appointment Booking System



\*\*Project Type:\*\* University Academic Project



\*\*Course:\*\* Android Development



\*\*Department:\*\* Computer Science and Engineering



\*\*Institution:\*\* Khulna University of Engineering \& Technology (KUET)



\## 👨‍💻 Developer



\*\*Md. Rahimul Haque Rinad\*\*



Department of Computer Science and Engineering  

Khulna University of Engineering \& Technology (KUET)



\*\*GitHub:\*\* \[rahimul189](https://github.com/rahimul189)



\## 📄 Project Purpose



MediBook demonstrates practical Android application development through Java-based application logic, XML user interfaces, Firebase integration, appointment scheduling, database security, and realtime data management.



The project is intended for academic learning, demonstration, and further development.



\---



\*\*MediBook — Making Doctor Appointments Simpler.\*\*

