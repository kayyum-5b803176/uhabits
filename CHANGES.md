# Fix: IndexOutOfBoundsException (same crash, deeper cause)
**Date:** 20260503

## Root cause (real one)
The previous fix aligned `getItemCount()` with `isFilterActive`, but the five
cache listener callbacks (`onItemChanged`, `onItemInserted`, `onItemMoved`,
`onItemRemoved`, `onRefreshFinished`) still used `activeTabId != null` as their
guard for calling `rebuildFilteredPositions()`.

Timeline of the crash:
1. `syncPrivateTabIds()` sets `privateTabIds` → `isFilterActive = true`
   → `rebuildFilteredPositions()` runs but cache is empty → `filteredPositions = []`
2. Cache finishes loading → `onRefreshFinished()` fires
   → `activeTabId == null` so **no rebuild** → `filteredPositions` stays `[]`
3. RecyclerView calls `getItemCount()` → `filteredPositions.size = 0`
   — but a pending `notifyItemInserted(N)` from the cache load already told
   RecyclerView there are N items → stale internal count
4. `getItemViewType(0)` → `virtualToReal(0)` → `filteredPositions[0]` → crash

## Fix
Changed all five callbacks to use `isFilterActive` instead of `activeTabId != null`:

| Callback | Before | After |
|---|---|---|
| `onItemChanged` | `activeTabId != null` | `isFilterActive` |
| `onItemInserted` | `activeTabId != null` | `isFilterActive` |
| `onItemMoved` | `activeTabId != null` | `isFilterActive` |
| `onItemRemoved` | `activeTabId != null` | `isFilterActive` |
| `onRefreshFinished` | `activeTabId != null` | `isFilterActive` |

Whenever any filter is active (tab filter OR private-tab hiding), all cache
mutations now go through `rebuildFilteredPositions() + notifyDataSetChanged()`
so `filteredPositions` is always in sync with the count RecyclerView sees.

## Changed file
- `uhabits-android/src/main/java/org/isoron/uhabits/activities/habits/list/views/HabitCardListAdapter.kt`
