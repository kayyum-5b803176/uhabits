# Fix: Session timeout + PIN change verification + Forgot PIN reset
**Date:** 20260503

## Changed files
| Type | File |
|------|------|
| ➕ | `uhabits-android/src/main/java/org/isoron/uhabits/activities/habits/list/tabs/PrivateTabAuthManager.kt` |
| ➕ | `uhabits-core/src/jvmMain/resources/migrations/31.sql` |
| ✏️ | `README.md` |
| ✏️ | `uhabits-android/build.gradle.kts` |
| ✏️ | `uhabits-android/src/main/java/org/isoron/uhabits/activities/habits/list/ListHabitsActivity.kt` |
| ✏️ | `uhabits-android/src/main/java/org/isoron/uhabits/activities/habits/list/tabs/TabBarView.kt` |
| ✏️ | `uhabits-android/src/main/java/org/isoron/uhabits/activities/habits/list/tabs/TabData.kt` |
| ✏️ | `uhabits-android/src/main/java/org/isoron/uhabits/activities/habits/list/tabs/TabManager.kt` |
| ✏️ | `uhabits-android/src/main/java/org/isoron/uhabits/activities/habits/list/views/HabitCardListAdapter.kt` |
| ✏️ | `uhabits-android/src/main/java/org/isoron/uhabits/activities/habits/list/views/HabitCardView.kt` |
| ✏️ | `uhabits-android/src/main/java/org/isoron/uhabits/activities/habits/list/views/HabitGroupCardView.kt` |
| ✏️ | `uhabits-android/src/main/res/values/strings.xml` |
| ✏️ | `uhabits-core/src/jvmMain/java/org/isoron/uhabits/core/Constants.kt` |

---

## Fix 1 — Auth required only after leave/recents/15 min timeout

### What changed
- Added `isPrivateSessionUnlocked: Boolean` (in-memory) and `PREF_LAST_AUTH_MS` (persisted).
- `stampAuthTime()` records `System.currentTimeMillis()` every time auth succeeds (All-tab,
  private-tab direct entry, background re-auth, and PIN change).
- `handleAllTabPrivateAuth()` now skips the biometric/PIN prompt and immediately unlocks if
  `isPrivateSessionUnlocked == true` (set in current foreground session).
- `onStop()` clears `isPrivateSessionUnlocked` and `adapter.unlockedPrivateTabIds`.
- `onStart()` checks elapsed time since `PREF_LAST_AUTH_MS`. If ≥ 15 min it leaves
  `isPrivateSessionUnlocked = false`; otherwise it remains false (cleared in `onStop`).
  The next `handleAllTabPrivateAuth()` call decides whether to skip or show the prompt.

### Result
- Within a session: switch All ↔ custom tabs freely — no repeated prompts.
- Leave app (home/recents) → return within 15 min → no prompt.
- Leave app → return after 15 min → prompt shown once.
- Kill from recents or background > 15 min → prompt shown once on return.

---

## Fix 2 — PIN change requires current credential

`onChangePinRequested` now calls `authManager.authenticate()` first. Only on success
does it open the new-PIN setup dialog. Biometric and PIN-fallback both work as the
verification step. The session is also re-stamped so the fresh auth resets the 15-min clock.

---

## Fix 3 — Forgot PIN: reset with full private-data deletion

### `PrivateTabAuthManager`
- PIN entry dialog now has a **"Forgot PIN?"** neutral button.
- Tapping it shows a confirmation dialog with a clear destruction warning.
- On confirm, `onForgotPin` callback (set by the activity) is invoked.

### `ListHabitsActivity.deleteAllPrivateData()`
- Iterates all private tabs.
- For each: deletes every linked `Habit` via `habitList.remove()`, then every linked
  `HabitGroup` (and its children) via `habitGroupList.remove()`, then the tab itself.
- Calls `tabManager.clearPin()`, disables stealth, switches to "All" tab, resyncs the
  tab bar. No private data survives the reset.
