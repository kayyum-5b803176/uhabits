/*
 * Copyright (C) 2016-2021 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.activities.habits.list.tabs

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.isoron.uhabits.R

/**
 * A horizontally-scrollable row of tab chips that appears below the main
 * toolbar.  It always starts with an **"All"** chip, followed by one chip per
 * [HabitTab], and ends with a **"+"** chip that creates a new tab.
 *
 * Long-pressing any custom tab chip opens a dialog to **Rename** or **Delete**
 * that tab.
 *
 * Connect a [Listener] to react to tab changes and creation/rename/delete.
 */
class TabBarView(context: Context) : HorizontalScrollView(context) {

    // -----------------------------------------------------------------------
    // Public interface
    // -----------------------------------------------------------------------

    interface Listener {
        /** Called when the user selects a tab. [tabId] is null for the "All" tab. */
        fun onTabSelected(tabId: String?)

        /** Called when the user confirms a new tab name via the "+" chip. */
        fun onTabCreated(name: String)

        /** Called when the user renames a tab via long-press. */
        fun onTabRenamed(tabId: String, newName: String)

        /** Called when the user deletes a tab via long-press. */
        fun onTabDeleted(tabId: String)
    }

    var listener: Listener? = null

    // -----------------------------------------------------------------------
    // Internal state
    // -----------------------------------------------------------------------

    /** Tab id of the currently selected tab, or null for "All". */
    private var selectedTabId: String? = null
    private var tabs: List<HabitTab> = emptyList()

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = dp(4)
        setPadding(dp(4), dp(6), dp(16), dp(6))
    }

    init {
        isHorizontalScrollBarEnabled = false
        addView(container, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        rebuildChips()
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Replace the displayed tabs and re-render the chip row. */
    fun setTabs(tabs: List<HabitTab>) {
        this.tabs = tabs
        // If the selected tab was deleted, fall back to "All"
        if (selectedTabId != null && tabs.none { it.id == selectedTabId }) {
            selectedTabId = null
            listener?.onTabSelected(null)
        }
        rebuildChips()
    }

    /** Programmatically select a tab by id (null = "All"). Does NOT fire [Listener.onTabSelected]. */
    fun setSelectedTab(tabId: String?) {
        selectedTabId = tabId
        rebuildChips()
    }

    // -----------------------------------------------------------------------
    // Chip construction
    // -----------------------------------------------------------------------

    private fun rebuildChips() {
        container.removeAllViews()

        // "All" chip
        container.addView(buildChip(
            label = context.getString(R.string.tab_all),
            tabId = null,
            isSelected = selectedTabId == null
        ))

        // One chip per custom tab
        tabs.forEach { tab ->
            container.addView(buildChip(
                label = tab.name,
                tabId = tab.id,
                isSelected = tab.id == selectedTabId
            ))
        }

        // "+" chip
        container.addView(buildAddChip())
    }

    private fun buildChip(label: String, tabId: String?, isSelected: Boolean): TextView {
        return TextView(context).apply {
            text = label
            typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER

            val hPad = dp(14)
            val vPad = dp(6)
            setPadding(hPad, vPad, hPad, vPad)

            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginEnd = dp(6)
            }

            background = buildChipBackground(isSelected)
            setTextColor(if (isSelected) Color.WHITE else getThemeColor(R.attr.contrast80))

            setOnClickListener {
                if (selectedTabId != tabId) {
                    selectedTabId = tabId
                    rebuildChips()
                    listener?.onTabSelected(tabId)
                }
            }

            if (tabId != null) {
                setOnLongClickListener {
                    showTabOptionsDialog(tabId, label)
                    true
                }
            }
        }
    }

    private fun buildAddChip(): TextView {
        return TextView(context).apply {
            text = "+"
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER

            val hPad = dp(14)
            val vPad = dp(5)
            setPadding(hPad, vPad, hPad, vPad)

            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            background = null
            setTextColor(getThemeColor(R.attr.contrast60))

            setOnClickListener { showCreateTabDialog() }
        }
    }

    // -----------------------------------------------------------------------
    // Background helper
    // -----------------------------------------------------------------------

    private fun buildChipBackground(selected: Boolean): android.graphics.drawable.Drawable {
        val drawable = android.graphics.drawable.GradientDrawable()
        drawable.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        drawable.cornerRadius = dp(4).toFloat()
        if (selected) {
            drawable.setColor(getThemeColor(android.R.attr.colorPrimary))
        } else {
            drawable.setStroke(dp(1), getThemeColor(R.attr.contrast40))
            drawable.setColor(Color.TRANSPARENT)
        }
        return drawable
    }

    // -----------------------------------------------------------------------
    // Dialogs
    // -----------------------------------------------------------------------

    private fun showCreateTabDialog() {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = context.getString(R.string.tab_name_hint)
            val p = dp(16)
            setPadding(p, p / 2, p, p / 2)
        }
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.tab_create_title))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) listener?.onTabCreated(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTabOptionsDialog(tabId: String, currentName: String) {
        val options = arrayOf(
            context.getString(R.string.tab_rename),
            context.getString(R.string.delete)
        )
        AlertDialog.Builder(context)
            .setTitle(currentName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(tabId, currentName)
                    1 -> showDeleteConfirmDialog(tabId, currentName)
                }
            }
            .show()
    }

    private fun showRenameDialog(tabId: String, currentName: String) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(currentName)
            selectAll()
            val p = dp(16)
            setPadding(p, p / 2, p, p / 2)
        }
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.tab_rename))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) listener?.onTabRenamed(tabId, newName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteConfirmDialog(tabId: String, name: String) {
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.tab_delete_confirm_title))
            .setMessage(context.getString(R.string.tab_delete_confirm_message, name))
            .setPositiveButton(context.getString(R.string.delete)) { _, _ ->
                listener?.onTabDeleted(tabId)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun getThemeColor(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            tv.data
        } else {
            context.getColor(tv.resourceId)
        }
    }
}
