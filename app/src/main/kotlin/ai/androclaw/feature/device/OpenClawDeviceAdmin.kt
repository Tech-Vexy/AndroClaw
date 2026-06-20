package ai.androclaw.feature.device

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import timber.log.Timber

/**
 * Device Admin Receiver.
 *
 * Declared in AndroidManifest with BIND_DEVICE_ADMIN permission.
 * User must explicitly activate this in Settings → Security → Device Admins,
 * OR via the system dialog launched by DeviceAdminManager.requestActivation().
 *
 * Capabilities once active:
 *   - Lock screen immediately
 *   - Remote wipe (factory reset)
 *   - Password minimum requirements
 *   - Block uninstall (user must deactivate admin first)
 */
class OpenClawDeviceAdmin : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Timber.d("Device Admin: enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Timber.d("Device Admin: disabled")
    }

    override fun onPasswordChanged(context: Context, intent: Intent, user: UserHandle) {
        Timber.d("Device Admin: password changed")
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        Timber.w("Device Admin: password failed attempt")
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, user: UserHandle) {
        Timber.d("Device Admin: password succeeded")
    }

    companion object {
        fun componentName(context: Context) =
            ComponentName(context, OpenClawDeviceAdmin::class.java)
    }
}

/**
 * High-level API for device admin operations.
 * Used by DeviceTools Koog tool implementations.
 */
class DeviceAdminManager(private val context: Context) {

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = OpenClawDeviceAdmin.componentName(context)

    val isActive: Boolean get() = dpm.isAdminActive(admin)

    /**
     * Launch the system dialog asking the user to activate Device Admin.
     * Must be called from an Activity context.
     */
    fun requestActivation(activity: android.app.Activity, requestCode: Int = 9001) {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "OpenClaw needs Device Admin to lock your screen, enforce security policies, " +
                "and protect itself from uninstallation without your consent.")
        }
        activity.startActivityForResult(intent, requestCode)
    }

    /** Lock the screen immediately. Requires admin active. */
    fun lockNow(): Result<Unit> = runCatching {
        check(isActive) { "Device Admin not active" }
        dpm.lockNow()
        Timber.d("DeviceAdmin: screen locked")
    }

    /**
     * Wipe the device (factory reset).
     * DESTRUCTIVE — only call after triple confirmation from user.
     * @param wipeExternalStorage also wipe SD card if true
     */
    fun wipeDevice(wipeExternalStorage: Boolean = false): Result<Unit> = runCatching {
        check(isActive) { "Device Admin not active" }
        val flags = if (wipeExternalStorage) DevicePolicyManager.WIPE_EXTERNAL_STORAGE else 0
        dpm.wipeData(flags)
        Timber.w("DeviceAdmin: device wipe initiated!")
    }

    /**
     * Set minimum password length policy.
     * Only enforced on managed devices; shows a system prompt on personal devices.
     */
    fun setMinPasswordLength(length: Int): Result<Unit> = runCatching {
        check(isActive) { "Device Admin not active" }
        dpm.setPasswordMinimumLength(admin, length)
        Timber.d("DeviceAdmin: minimum password length set to $length")
    }

    /**
     * Set maximum failed password attempts before wipe.
     * 0 = disabled.
     */
    fun setMaxFailedPasswords(max: Int): Result<Unit> = runCatching {
        check(isActive) { "Device Admin not active" }
        dpm.setMaximumFailedPasswordsForWipe(admin, max)
        Timber.d("DeviceAdmin: max failed passwords set to $max")
    }

    /**
     * Set screen lock timeout (ms). User cannot set a longer timeout.
     */
    fun setMaxScreenOffTimeout(timeoutMs: Long): Result<Unit> = runCatching {
        check(isActive) { "Device Admin not active" }
        dpm.setMaximumTimeToLock(admin, timeoutMs)
        Timber.d("DeviceAdmin: max screen off timeout set to ${timeoutMs}ms")
    }

    /** Disable/enable the camera. Requires admin active. */
    fun setCameraDisabled(disabled: Boolean): Result<Unit> = runCatching {
        check(isActive) { "Device Admin not active" }
        dpm.setCameraDisabled(admin, disabled)
        Timber.d("DeviceAdmin: camera disabled = $disabled")
    }

    /** Check if camera is disabled. */
    fun isCameraDisabled(): Boolean {
        if (!isActive) return false
        return dpm.getCameraDisabled(admin)
    }

    /** Disable/enable screen capture. Requires admin active. */
    fun setScreenCaptureDisabled(disabled: Boolean): Result<Unit> = runCatching {
        check(isActive) { "Device Admin not active" }
        dpm.setScreenCaptureDisabled(admin, disabled)
        Timber.d("DeviceAdmin: screen capture disabled = $disabled")
    }

    /** Check if screen capture is disabled. */
    fun isScreenCaptureDisabled(): Boolean {
        if (!isActive) return false
        return dpm.getScreenCaptureDisabled(admin)
    }

    /** Remove device admin — called from Settings "Disable" button. */
    fun deactivate() {
        dpm.removeActiveAdmin(admin)
        Timber.d("DeviceAdmin: deactivated")
    }
}

