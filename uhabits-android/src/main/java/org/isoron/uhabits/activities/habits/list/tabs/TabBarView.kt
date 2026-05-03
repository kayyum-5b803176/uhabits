/*
 * Copyright (C) 2016-2021 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 * (modified to add private-tab support)
 */

package org.isoron.uhabits.activities.habits.list.tabs

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.isoron.uhabits.R

/**
 * A horizontally-scrollable row of tab chips shown below the main toolbar.
 *
 * Chips:
 *  - **"All"** — always first, shows every habit.
 *  - **Custom tabs** — one chip per [HabitTab]. Private tabs show a lock prefix.
 *  - **"+"** — opens the create-tab dialog.
 *
 * Interactions:
 *  - **Tap** a non-private chip → [Listener.onTabSelected]
 *  - **Tap** a private chip     → [Listener.onPrivateTabRequested] (caller handles auth)
 *  - **Long-press** any custom chip → options dialog (rename / privacy / change PIN / delete)
 */
class TabBarView(context: Context) : HorizontalScrollView(context) {

    // -----------------------------------------------------------------------
    // Listener
    // -----------------------------------------------------------------------

    interface Listener {
        /** Fired when a non-private tab chip is tapped (null = "All"). */
        fun onTabSelected(tabId: String?)

        /**
         * Fired when a *private* tab chip is tapped.
         * The caller is responsible for authenticating and then calling
         * [setSelectedTab] on success. The chip is NOT selected automatically.
         */
        fun onPrivateTabRequested(tabId: String)

        /** Fired when the user confirms a new tab name via the "+" chip. */
        fun onTabCreated(name: String, isPrivate: Boolean)

        /** Fired when the user renames a tab via long-press. */
        fun onTabRenamed(tabId: String, newName: String)

        /** Fired when the user deletes a tab via long-press. */
        fun onTabDeleted(tabId: String)

        /** Fired when the user toggles the private flag on an existing tab. */
        fun onPrivacyToggled(tabId: String, makePrivate: Boolean)

        /** Fired when the user requests a PIN change for an existing private tab. */
        fun onChangePinRequested(tabId: String)
    }

    var listener: Listener? = null

    // -----------------------------------------------------------------------
    // Internal state
    // -----------------------------------------------------------------------

    private var selectedTabId: String? = null
    private var tabs: List<HabitTab>   = emptyList()

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity     = Gravity.CENTER_VERTICAL
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

    fun setTabs(tabs: List<HabitTab>) {
        this.tabs = tabs
        if (selectedTabId != null && tabs.none { it.id == selectedTabId }) {
            selectedTabId = null
            listener?.onTabSelected(null)
        }
        rebuildChips()
    }

    /** Programmatically selects a tab without firing [Listener.onTabSelected]. */
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
            label      = context.getString(R.string.tab_all),
            tabId      = null,
            isSelected = selectedTabId == null,
            isPrivate  = false
        ))

        // One chip per custom tab
        tabs.forEach { tab ->
            container.addView(buildChip(
                label      = tab.name,
                tabId      = tab.id,
                isSelected = tab.id == selectedTabId,
                isPrivate  = tab.isPrivate
            ))
        }

        // "+" chip
        container.addView(buildAddChip())
    }

    private fun buildChip(
        label: String,
        tabId: String?,
        isSelected: Boolean,
        isPrivate: Boolean
    ): TextView {
        return TextView(context).apply {
            text     = label
            typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity  = Gravity.CENTER

            val hPad = dp(14)
            val vPad = dp(6)
            setPadding(hPad, vPad, hPad, vPad)

            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginEnd = dp(6)
            }

            background = buildChipBackground(isSelected, isPrivate)
            setTextColor(if (isSelected) Color.WHITE else getThemeColor(R.attr.contrast80))

            setOnClickListener {
                if (isPrivate && tabId != null) {
                    // Private tab — delegate to caller for auth; do NOT switch yet
                    if (tabId != selectedTabId) {
                        listener?.onPrivateTabRequested(tabId)
                    }
                } else {
                    // Normal tab
                    if (selectedTabId != tabId) {
                        selectedTabId = tabId
                        rebuildChips()
                        listener?.onTabSelected(tabId)
                    }
                }
            }

            if (tabId != null) {
                setOnLongClickListener {
                    showTabOptionsDialog(tabId, label, isPrivate)
                    true
                }
            }
        }
    }

    private fun buildAddChip(): TextView {
        return TextView(context).apply {
            text     = "+"
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity  = Gravity.CENTER

            val hPad = dp(14)
            val vPad = dp(5)
            setPadding(hPad, vPad, hPad, vPad)

            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            background   = null
            setTextColor(getThemeColor(R.attr.contrast60))

            setOnClickListener { showCreateTabDialog() }
        }
    }

    // -----------------------------------------------------------------------
    // Background helper
    // -----------------------------------------------------------------------

    private fun buildChipBackground(selected: Boolean, private: Boolean): android.graphics.drawable.Drawable {
        val drawable = android.graphics.drawable.GradientDrawable()
        drawable.shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
        drawable.cornerRadius = dp(4).toFloat()
        when {
            selected && private -> {
                // Purple fill when private tab is active
                drawable.setColor(0xFF7B1FA2.toInt())
            }
            selected -> {
                drawable.setColor(getThemeColor(android.R.attr.colorPrimary))
            }
            else -> {
                // Unselected — private tabs look identical to normal tabs
                drawable.setStroke(dp(1), getThemeColor(R.attr.contrast40))
                drawable.setColor(Color.TRANSPARENT)
            }
        }
        return drawable
    }

    // -----------------------------------------------------------------------
    // Dialogs
    // -----------------------------------------------------------------------

    private fun showCreateTabDialog() {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint      = context.getString(R.string.tab_name_hint)
            val p = dp(16)
            setPadding(p, p / 2, p, p / 2)
        }

        val privateToggle = CheckBox(context).apply {
            text    = context.getString(R.string.tab_private_label)
            val p = dp(16)
            setPadding(p, p / 2, p, p / 2)
        }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(input)
            addView(privateToggle)
        }

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.tab_create_title))
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    listener?.onTabCreated(name, privateToggle.isChecked)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTabOptionsDialog(tabId: String, currentName: String, isPrivate: Boolean) {
        val options = buildList {
            add(context.getString(R.string.tab_rename))
            if (isPrivate) {
                add(context.getString(R.string.tab_change_pin))
                add(context.getString(R.string.tab_make_public))
            } else {
                add(context.getString(R.string.tab_make_private))
            }
            add(context.getString(R.string.delete))
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(currentName)
            .setItems(options) { _, which ->
                if (isPrivate) {
                    when (which) {
                        0 -> showRenameDialog(tabId, currentName)
                        1 -> listener?.onChangePinRequested(tabId)
                        2 -> listener?.onPrivacyToggled(tabId, false)
                        3 -> showDeleteConfirmDialog(tabId, currentName)
                    }
                } else {
                    when (which) {
                        0 -> showRenameDialog(tabId, currentName)
                        1 -> listener?.onPrivacyToggled(tabId, true)
                        2 -> showDeleteConfirmDialog(tabId, currentName)
                    }
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
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

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
