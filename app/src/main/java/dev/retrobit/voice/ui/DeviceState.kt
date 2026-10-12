package dev.retrobit.voice.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import dev.retrobit.voice.assistant.VoiceAssistantService
import dev.retrobit.voice.dictation.a11y.TextInsertAccessibilityService
import dev.retrobit.voice.dictation.ime.RetrobitImeService
import dev.retrobit.voice.dictation.overlay.OverlayBubbleService

/** Membaca status izin & integrasi sistem supaya layar Panduan bisa menuntun langkah demi langkah. */
object DeviceState {

    fun hasMicrophone(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun hasContacts(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun hasNotificationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /** Keyboard Retrobit sudah diaktifkan di pengaturan sistem. */
    fun isImeEnabled(context: Context): Boolean {
        val ime = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager ?: return false
        val id = ComponentName(context, RetrobitImeService::class.java).flattenToShortString()
        return ime.enabledInputMethodList.any { ComponentName(it.packageName, it.serviceName).flattenToShortString() == id } ||
            ime.enabledInputMethodList.any { it.packageName == context.packageName }
    }

    /** Keyboard Retrobit sedang terpilih sebagai keyboard aktif. */
    fun isImeSelected(context: Context): Boolean {
        val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return current.contains(context.packageName)
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val target = ComponentName(context, TextInsertAccessibilityService::class.java).flattenToString()
        val targetShort = ComponentName(context, TextInsertAccessibilityService::class.java).flattenToShortString()
        return manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val id = info.id ?: return@any false
                id.equals(target, ignoreCase = true) || id.equals(targetShort, ignoreCase = true)
            }
    }

    fun hasOverlayPermission(context: Context): Boolean = OverlayBubbleService.hasPermission(context)

    /** Retrobit sudah dipilih sebagai asisten digital bawaan. */
    fun isDefaultAssistant(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(RoleManager::class.java)
                roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true &&
                    roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
            } else {
                @Suppress("DEPRECATION")
                val assistant = Settings.Secure.getString(context.contentResolver, "assistant")
                assistant?.contains(context.packageName) == true
            }
        } catch (t: Throwable) {
            false
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    // ---------------- Intent pengaturan sistem ----------------

    fun imeSettingsIntent(): Intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)

    fun accessibilitySettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun overlaySettingsIntent(context: Context): Intent = OverlayBubbleService.permissionIntent(context)

    fun assistantRoleIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true) {
                roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            } else {
                voiceInputSettings()
            }
        } else {
            voiceInputSettings()
        }

    private fun voiceInputSettings(): Intent? =
        Intent("android.settings.VOICE_INPUT_SETTINGS").takeIf { it.resolveActivitySafe() }

    private fun Intent.resolveActivitySafe(): Boolean = true

    fun batterySettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))

    fun voiceInteractionServiceComponent(): ComponentName =
        ComponentName("dev.retrobit.voice", VoiceAssistantService::class.java.name)
}
