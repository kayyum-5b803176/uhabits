package org.isoron.uhabits.activities.habits.list.tabs

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.isoron.uhabits.R

/**
 * Orchestrates private-tab authentication.
 *
 * Flow:
 * 1. Attempt biometric (fingerprint / face) if hardware is available.
 * 2. On biometric error or user choosing "Use PIN" → show PIN dialog.
 * 3. On PIN success → [onSuccess] is invoked on the main thread.
 * 4. On PIN failure or cancel → [onFailure] is invoked.
 *
 * Stealth mode (FLAG_SECURE) is applied to the activity window while a
 * private tab is open, preventing screenshots and the recent-apps thumbnail
 * from revealing private habit data.
 */
class PrivateTabAuthManager(
    private val activity: FragmentActivity,
    private val tabManager: TabManager
) {

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Starts the full auth chain (biometric → PIN fallback).
     * Exactly one of [onSuccess] or [onFailure] will be called.
     */
    fun authenticate(onSuccess: () -> Unit, onFailure: () -> Unit) {
        if (!tabManager.hasPin()) {
            // No PIN set — treat as not locked (shouldn't happen in normal use)
            onSuccess()
            return
        }

        if (canUseBiometric()) {
            showBiometricPrompt(
                onSuccess  = onSuccess,
                onFallback = { showPinEntry(onSuccess, onFailure) },
                onCancel   = onFailure
            )
        } else {
            showPinEntry(onSuccess, onFailure)
        }
    }

    /**
     * Shows a 2-pass dialog for setting up or changing the 4-digit PIN.
     * [onPinSet] is called with the raw PIN once both passes match.
     */
    fun showPinSetup(onPinSet: (String) -> Unit, onCancel: () -> Unit = {}) {
        showPinSetupDialog(onPinSet, onCancel)
    }

    // -----------------------------------------------------------------------
    // Stealth mode
    // -----------------------------------------------------------------------

    /** Prevents screenshots and the recent-apps thumbnail for the activity window. */
    fun enableStealthMode() {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Restores normal screenshot / recents behaviour. */
    fun disableStealthMode() {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    // -----------------------------------------------------------------------
    // Biometric
    // -----------------------------------------------------------------------

    private fun canUseBiometric(): Boolean {
        val mgr = BiometricManager.from(activity)
        val result = mgr.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        )
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun showBiometricPrompt(
        onSuccess: () -> Unit,
        onFallback: () -> Unit,
        onCancel: () -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                        // User tapped "Use PIN"
                        onFallback()
                    }
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_CANCELED -> {
                        onCancel()
                    }
                    else -> {
                        // Hardware error or lockout — fall back to PIN
                        onFallback()
                    }
                }
            }

            override fun onAuthenticationFailed() {
                // Wrong biometric — the system dialog stays open, no action needed
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.private_tab_auth_title))
            .setSubtitle(activity.getString(R.string.private_tab_auth_subtitle))
            .setNegativeButtonText(activity.getString(R.string.private_tab_use_pin))
            .build()

        BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
    }

    // -----------------------------------------------------------------------
    // PIN entry
    // -----------------------------------------------------------------------

    private fun showPinEntry(onSuccess: () -> Unit, onFailure: () -> Unit) {
        val input = buildPinField()
        var dialog: AlertDialog? = null
        val errorLabel = buildErrorLabel()

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(24)
            setPadding(p, dp(8), p, 0)
            addView(input)
            addView(errorLabel)
        }

        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.private_tab_enter_pin)
            .setView(layout)
            .setCancelable(true)
            .setPositiveButton(android.R.string.ok, null)   // override below to control dismiss
            .setNegativeButton(android.R.string.cancel) { _, _ -> onFailure() }
            .setOnCancelListener { onFailure() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString()
                when {
                    pin.length != 4 -> {
                        errorLabel.text = activity.getString(R.string.private_tab_pin_too_short)
                        errorLabel.visibility = android.view.View.VISIBLE
                        input.text?.clear()
                    }
                    tabManager.verifyPin(pin) -> {
                        dialog.dismiss()
                        onSuccess()
                    }
                    else -> {
                        errorLabel.text = activity.getString(R.string.private_tab_pin_wrong)
                        errorLabel.visibility = android.view.View.VISIBLE
                        input.text?.clear()
                    }
                }
            }
        }

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    // -----------------------------------------------------------------------
    // PIN setup (2-pass)
    // -----------------------------------------------------------------------

    private fun showPinSetupDialog(onPinSet: (String) -> Unit, onCancel: () -> Unit) {
        val input     = buildPinField()
        val errorLabel = buildErrorLabel()

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(24)
            setPadding(p, dp(8), p, 0)
            addView(input)
            addView(errorLabel)
        }

        var dialog: AlertDialog? = null
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.private_tab_set_pin)
            .setMessage(R.string.private_tab_set_pin_message)
            .setView(layout)
            .setCancelable(true)
            .setPositiveButton(activity.getString(R.string.private_tab_next), null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString()
                if (pin.length != 4) {
                    errorLabel.text = activity.getString(R.string.private_tab_pin_too_short)
                    errorLabel.visibility = android.view.View.VISIBLE
                    input.text?.clear()
                } else {
                    dialog.dismiss()
                    showPinConfirm(firstPin = pin, onPinSet = onPinSet, onCancel = onCancel)
                }
            }
        }

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun showPinConfirm(firstPin: String, onPinSet: (String) -> Unit, onCancel: () -> Unit) {
        val input      = buildPinField()
        val errorLabel = buildErrorLabel()

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(24)
            setPadding(p, dp(8), p, 0)
            addView(input)
            addView(errorLabel)
        }

        var dialog: AlertDialog? = null
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.private_tab_confirm_pin)
            .setView(layout)
            .setCancelable(true)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val confirm = input.text.toString()
                when {
                    confirm.length != 4 -> {
                        errorLabel.text = activity.getString(R.string.private_tab_pin_too_short)
                        errorLabel.visibility = android.view.View.VISIBLE
                        input.text?.clear()
                    }
                    confirm == firstPin -> {
                        dialog.dismiss()
                        onPinSet(confirm)
                    }
                    else -> {
                        errorLabel.text = activity.getString(R.string.private_tab_pin_mismatch)
                        errorLabel.visibility = android.view.View.VISIBLE
                        input.text?.clear()
                    }
                }
            }
        }

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    // -----------------------------------------------------------------------
    // View helpers
    // -----------------------------------------------------------------------

    private fun buildPinField(): EditText = EditText(activity).apply {
        inputType  = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        gravity    = Gravity.CENTER
        textSize   = 24f
        filters    = arrayOf(InputFilter.LengthFilter(4))
        hint       = "• • • •"
        setSingleLine()
    }

    private fun buildErrorLabel(): TextView = TextView(activity).apply {
        setTextColor(
            ContextCompat.getColor(activity, android.R.color.holo_red_light)
        )
        textSize  = 13f
        gravity   = Gravity.CENTER
        setPadding(0, dp(6), 0, 0)
        visibility = android.view.View.GONE
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
