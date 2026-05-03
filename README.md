# uHabits — Frequency Window

A fork of [Loop Habit Tracker](https://github.com/iSoron/uhabits) that adds a **frequency window** feature, letting you define habits that must be completed a set number of times within a rolling time window rather than on a fixed daily or weekly schedule.

---

## Features

- **Frequency Window scheduling** — set a habit to repeat *N* times per *X* days, not just "daily" or "weekly"
- **Habit streaks & scores** — long-term score calculated from your completion history
- **History editor** — manually edit past check-ins for a habit
- **Reminders & notifications** — per-habit alarms with snooze support
- **Home-screen widgets** — checkmark, score, history, and frequency chart widgets
- **CSV import / export** — back up or migrate habit data
- **Auto-backup** — automatic database backup via Android's backup framework
- **Tasker / automation integration** — trigger check-ins from external automation apps
- **Dark / light theme** — follows system theme preference
- **RTL support** — full right-to-left layout support

---

## Requirements

| Item | Version |
|---|---|
| Android SDK (min) | API 28 (Android 9 Pie) |
| Android SDK (target / compile) | API 34 (Android 14) |
| JDK | 17 |
| Kotlin | 1.9.22 |
| Android Gradle Plugin | 8.4.0 |
| Gradle | See `gradle/wrapper/gradle-wrapper.properties` |

---

## Project Structure

```
uhabits-frequency-window/
├── uhabits-android/        # Android app module (UI, activities, widgets, receivers)
├── uhabits-core/           # Kotlin Multiplatform business logic (models, DB, scoring)
├── uhabits-core-legacy/    # Legacy core assets used during migration
├── uhabits-server/         # Lightweight local server for data sync (included in build)
├── build.gradle.kts        # Root build script
├── settings.gradle.kts     # Module declarations
├── gradle.properties       # JVM / AndroidX flags
├── translators.gradle.kts  # Translator credit generation task
└── gradle/wrapper/         # Gradle wrapper JAR and properties
```

### Key packages inside `uhabits-android`

| Package | Purpose |
|---|---|
| `activities/habits/list` | Main habit list screen with tabs and views |
| `activities/habits/show` | Per-habit detail screen (score, history, frequency charts) |
| `activities/habits/edit` | Create / edit habit dialog |
| `activities/common/dialogs` | Shared dialogs: frequency picker, weekday picker, history editor, color picker |
| `activities/settings` | App settings screen |
| `widgets/` | Home-screen App Widgets |
| `receivers/` | Broadcast receivers for reminders and boot |
| `notifications/` | Notification tray and snooze activity |
| `automation/` | Tasker / external automation entry points |
| `database/` | Android SQLite wrappers |
| `io/` | CSV import / export tasks |

---

## Building

### 1. Clone the repository

```bash
git clone https://github.com/<your-org>/uhabits-frequency-window.git
cd uhabits-frequency-window
```

### 2. Open in Android Studio

Open the project root in **Android Studio Hedgehog (2023.1.1)** or later. Let Gradle sync complete automatically.

### 3. Build from the command line

```bash
# Debug APK
./gradlew :uhabits-android:assembleDebug

# Release APK (requires signing config — see below)
./gradlew :uhabits-android:assembleRelease

# Run unit tests
./gradlew :uhabits-android:test
./gradlew :uhabits-core:test

# Run instrumented tests (requires connected device or emulator)
./gradlew :uhabits-android:connectedAndroidTest
```

---

## Signing a Release Build

Release signing is driven by environment variables so that no secrets are stored in source control.

```bash
export LOOP_KEY_ALIAS="your-key-alias"
export LOOP_KEY_PASSWORD="your-key-password"
export LOOP_KEY_STORE="/path/to/your.keystore"
export LOOP_STORE_PASSWORD="your-store-password"

./gradlew :uhabits-android:assembleRelease
```

If the environment variables are absent, the release build falls back to the debug signing config (useful for local testing).

---

## Application IDs

| Build type | Application ID |
|---|---|
| Debug | `org.isoron.uhabits.debug` |
| Release | `org.isoron.uhabits` |

---

## Permissions

| Permission | Reason |
|---|---|
| `POST_NOTIFICATIONS` | Show habit reminders |
| `RECEIVE_BOOT_COMPLETED` | Re-schedule alarms after device reboot |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | Deliver reminders at the exact time set by the user |
| `VIBRATE` | Vibrate on reminder notification |

---

## Architecture

The app follows a clean-architecture split between the `:uhabits-core` Kotlin Multiplatform module (pure business logic, no Android dependencies) and the `:uhabits-android` module (Android-specific UI and platform adapters).

Dependency injection is handled by **Dagger 2**. Networking uses **Ktor**. Database access is via a thin hand-rolled SQL abstraction over Android SQLite / JDBC (JVM).

```
┌─────────────────────────────┐
│       uhabits-android       │  Activities · Fragments · Widgets
│  (Android UI / adapters)    │  Dagger components · Platform impls
└──────────────┬──────────────┘
               │ depends on
┌──────────────▼──────────────┐
│        uhabits-core         │  Habit models · Scoring · Frequency logic
│  (Kotlin Multiplatform)     │  Database abstraction · CSV I/O
└─────────────────────────────┘
```

---

## Dependencies (key libraries)

| Library | Version | Purpose |
|---|---|---|
| Dagger 2 | 2.51.1 | Dependency injection |
| Kotlin Coroutines | 1.7.3 | Async / background work |
| Ktor | 1.6.8 | HTTP client |
| Guava | 33.1.0-android | Collections / utilities |
| OpenCSV | 5.9 | CSV import / export |
| AppIntro | 6.3.1 | Onboarding intro screens |
| AndroidX AppCompat | 1.6.1 | Backwards-compatible UI components |
| Material Components | 1.11.0 | Material Design UI |
| Espresso | 3.5.1 | UI instrumented tests |
| Mockito-Kotlin | 5.2.1 | Unit test mocking |

---

## License

This project is licensed under the **GNU General Public License v3.0**. See `LICENSE.txt` for the full text.

Original Loop Habit Tracker © 2016–2021 Álinson Santos Xavier.
