# Fix: Private tab chip visual tweaks
**Date:** 20260503

## Changes

| Type | File |
|------|------|
| ✏️ Modified | `uhabits-android/src/main/java/org/isoron/uhabits/activities/habits/list/tabs/TabBarView.kt` |

## What was fixed

1. **No emoji** — removed the 🔒 prefix from private tab chip labels. The tab name is now shown as-is, identical to normal tabs.

2. **No purple outline** — unselected private tab chips now use the same border colour (`contrast40`) as every other unselected chip, so they blend in completely.

3. **Purple background only when selected** — the only visual difference retained is the purple (`#7B1FA2`) filled background when a private tab is the *active* selection (vs the theme primary colour for normal selected tabs).
