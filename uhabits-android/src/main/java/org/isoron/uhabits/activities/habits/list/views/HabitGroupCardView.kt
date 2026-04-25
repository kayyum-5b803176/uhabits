package org.isoron.uhabits.activities.habits.list.views

import android.content.Context
import android.graphics.Typeface
import android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.views.RingView
import org.isoron.uhabits.core.models.HabitGroup
import org.isoron.uhabits.core.models.ModelObservable
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior
import org.isoron.uhabits.inject.ActivityContext
import org.isoron.uhabits.utils.currentTheme
import org.isoron.uhabits.utils.dp
import org.isoron.uhabits.utils.sres

class HabitGroupCardView(
    @ActivityContext context: Context,
    private val behavior: ListHabitsBehavior
) : FrameLayout(context),
    ModelObservable.Listener {

    var dataOffset = 0

    var habitGroup: HabitGroup? = null
        set(newHabitGroup) {
            if (isAttachedToWindow) {
                field?.observable?.removeListener(this)
                newHabitGroup?.observable?.addListener(this)
            }
            field = newHabitGroup
            if (newHabitGroup != null) copyAttributesFrom(newHabitGroup)
            addButtonView.habitGroup = newHabitGroup
            collapseButtonView.habitGroup = newHabitGroup
        }

    var score
        get() = scoreRing.getPercentage().toDouble()
        set(value) {
            scoreRing.setPercentage(value.toFloat())
            scoreRing.setPrecision(1.0f / 16)
        }

    var addButtonView: AddButtonView
    var collapseButtonView: CollapseButtonView
    private var innerFrame: LinearLayout
    private var label: TextView
    private var scoreRing: RingView
    private var tabDot: android.view.View

    var showTabDot: Boolean = false
        set(value) {
            field = value
            habitGroup?.let { refreshTabDot(it.tabId) }
        }

    private fun refreshTabDot(tabId: String?) {
        tabDot.visibility = if (showTabDot && tabId != null) android.view.View.VISIBLE else android.view.View.GONE
    }

    private var currentToggleTaskId = 0

    init {
        val dotDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xFF26C6DA.toInt())
        }
        val dotSize = dp(4f).toInt()
        val ringSize = dp(15f).toInt()
        val ringMargin = dp(8f).toInt()

        tabDot = android.view.View(context).apply {
            background = dotDrawable
            layoutParams = FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER)
            visibility = android.view.View.GONE
        }

        scoreRing = RingView(context).apply {
            val thickness = dp(3f)
            layoutParams = FrameLayout.LayoutParams(ringSize, ringSize)
            setThickness(thickness)
        }

        val ringContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(ringSize, ringSize).apply {
                setMargins(ringMargin, 0, ringMargin, 0)
                gravity = Gravity.CENTER
            }
            addView(scoreRing)
            addView(tabDot)
        }

        label = TextView(context).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            if (SDK_INT >= Build.VERSION_CODES.Q) {
                breakStrategy = BREAK_STRATEGY_BALANCED
            }
            setTypeface(typeface, Typeface.BOLD)
        }

        addButtonView = AddButtonView(context, habitGroup)
        collapseButtonView = CollapseButtonView(context, habitGroup)

        innerFrame = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            elevation = dp(1f)

            addView(ringContainer)
            addView(label)
            addView(addButtonView)
            addView(collapseButtonView)

            setOnTouchListener { v, event ->
                v.background.setHotspot(event.x, event.y)
                false
            }
        }

        clipToPadding = false
        layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        val margin = dp(3f).toInt()
        setPadding(margin, 0, margin, margin)
        addView(innerFrame)
    }

    override fun onModelChange() {
        Handler(Looper.getMainLooper()).post {
            habitGroup?.let { copyAttributesFrom(it) }
        }
    }

    override fun setSelected(isSelected: Boolean) {
        super.setSelected(isSelected)
        updateBackground(isSelected)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        habitGroup?.observable?.addListener(this)
    }

    override fun onDetachedFromWindow() {
        habitGroup?.observable?.removeListener(this)
        super.onDetachedFromWindow()
    }

    private fun copyAttributesFrom(hgr: HabitGroup) {
        fun getActiveColor(hgr: HabitGroup): Int {
            return when (hgr.isArchived) {
                true -> sres.getColor(R.attr.contrast60)
                false -> currentTheme().color(hgr.color).toInt()
            }
        }

        val c = getActiveColor(hgr)
        label.apply {
            text = hgr.name
            setTextColor(c)
        }
        scoreRing.apply {
            setColor(c)
        }
        refreshTabDot(hgr.tabId)

        if (collapseButtonView.collapsed) {
            addButtonView.visibility = GONE
        } else {
            addButtonView.visibility = VISIBLE
        }
    }

    private fun updateBackground(isSelected: Boolean) {
        val background = when (isSelected) {
            true -> R.drawable.selected_box
            false -> R.drawable.ripple
        }
        innerFrame.setBackgroundResource(background)
    }

    companion object {
        fun (() -> Unit).delay(delayInMillis: Long) {
            Handler(Looper.getMainLooper()).postDelayed(this, delayInMillis)
        }
    }
}
