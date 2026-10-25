package s.p.y.security

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import s.p.y.service.AdminReceiver
import java.security.MessageDigest

object SecurityManager {

    private const val PREFS_NAME = "spy_security_prefs"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_ENABLED = "pin_enabled"
    private const val KEY_STEALTH_MODE = "stealth_mode"

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isPinEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PIN_ENABLED, false) && getPrefs(context).getString(KEY_PIN_HASH, null) != null
    }

    fun setPin(context: Context, pin: String) {
        val hash = hashPin(pin)
        getPrefs(context).edit()
            .putString(KEY_PIN_HASH, hash)
            .putBoolean(KEY_PIN_ENABLED, true)
            .apply()
    }

    fun disablePin(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_PIN_HASH)
            .putBoolean(KEY_PIN_ENABLED, false)
            .apply()
    }

    fun verifyPin(context: Context, enteredPin: String): Boolean {
        val savedHash = getPrefs(context).getString(KEY_PIN_HASH, null) ?: return true
        return savedHash == hashPin(enteredPin)
    }

    private fun hashPin(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // --- Stealth Mode & Launcher Icon Visibility ---

    fun isLauncherIconVisible(context: Context): Boolean {
        return try {
            val component = ComponentName(context, "${context.packageName}.LauncherAlias")
            val state = context.packageManager.getComponentEnabledSetting(component)
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        } catch (e: Exception) {
            android.util.Log.e("SecurityManager", "Error checking launcher icon state", e)
            true
        }
    }

    fun setLauncherIconVisible(context: Context, visible: Boolean): Boolean {
        return try {
            val component = ComponentName(context, "${context.packageName}.LauncherAlias")
            val newState = if (visible) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            context.packageManager.setComponentEnabledSetting(
                component,
                newState,
                PackageManager.DONT_KILL_APP
            )
            getPrefs(context).edit().putBoolean(KEY_STEALTH_MODE, !visible).apply()
            true
        } catch (e: Exception) {
            android.util.Log.e("SecurityManager", "Failed to update launcher icon visibility", e)
            false
        }
    }

    /**
     * Automatically hides the app icon from launcher on app startup.
     * Uses DONT_KILL_APP so current session is uninterrupted.
     */
    fun autoHideOnStart(context: Context) {
        try {
            if (isLauncherIconVisible(context)) {
                val success = setLauncherIconVisible(context, false)
                if (success) {
                    android.widget.Toast.makeText(
                        context,
                        "👻 App icon hidden! Open anytime by typing 'a.p.y' in your browser.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SecurityManager", "Failed to auto-hide launcher icon on start", e)
        }
    }

    fun isStealthMode(context: Context): Boolean = !isLauncherIconVisible(context)

    fun setStealthMode(context: Context, enabled: Boolean): Boolean =
        setLauncherIconVisible(context, !enabled)

    // --- Device Admin (Uninstall Protection) ---

    fun getAdminComponent(context: Context): ComponentName {
        return ComponentName(context, AdminReceiver::class.java)
    }

    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(getAdminComponent(context))
    }

    fun createDeviceAdminIntent(context: Context): Intent {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, getAdminComponent(context))
        intent.putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Protects the ADB Shell from unauthorized uninstallation by unauthorized users."
        )
        return intent
    }

    fun removeDeviceAdmin(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(getAdminComponent(context))) {
            dpm.removeActiveAdmin(getAdminComponent(context))
        }
    }

    // --- Open by Default Link Settings ---

    fun openLinkHandlingSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val intent = Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) { }
        }
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
