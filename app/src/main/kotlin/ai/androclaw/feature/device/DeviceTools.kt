package ai.androclaw.feature.device

import ai.koog.agents.core.tools.Tool
import ai.koog.serialization.typeToken
import ai.androclaw.agent.OpenClawConfig
import ai.androclaw.feature.device.*
import kotlinx.serialization.Serializable

// ── Result types ──────────────────────────────────────────────────────────────

@Serializable data class DeviceActionResult(val success: Boolean, val message: String)
@Serializable data class ScreenContentResult(val text: String, val packageName: String, val activityName: String)
@Serializable data class NotificationListResult(val notifications: List<NotificationSummary>, val count: Int)
@Serializable data class NotificationSummary(
    val key: String, val appName: String, val title: String,
    val text: String, val timestamp: Long, val isOngoing: Boolean)
@Serializable data class UsageResult(val apps: List<AppUsageSummary>, val period: String)
@Serializable data class AppUsageSummary(val appName: String, val packageName: String,
                                          val screenTime: String, val lastUsed: Long)
@Serializable data class VpnStatusResult(val running: Boolean, val state: String,
                                          val blockedDomains: List<String>)
@Serializable data class DeviceAdminStatus(
    val isAdminActive: Boolean,
    val isCameraDisabled: Boolean,
    val isScreenCaptureDisabled: Boolean
)

// ── Outer-level Tool Arguments (avoiding nested classes in inner classes) ──

@Serializable
data class RemoteWipeArgs(
    val confirmPhrase: String,  // must be "CONFIRM WIPE" to proceed
    val wipeExternalStorage: Boolean = false,
)

@Serializable
data class SetPasswordPolicyArgs(
    val minLength: Int = 6,
    val maxFailedAttempts: Int = 0,
    val maxScreenOffTimeoutSeconds: Int = 0,
)

@Serializable
data class SetCameraDisabledArgs(
    val disabled: Boolean,
)

@Serializable
data class SetScreenCaptureDisabledArgs(
    val disabled: Boolean,
)

@Serializable
data class ClickElementArgs(
    val text: String = "",
    val resourceId: String = "",
)

@Serializable
data class TypeTextArgs(val text: String)

@Serializable
data class ScrollScreenArgs(val direction: String = "down")  // "up" | "down"

@Serializable
data class ListNotificationsArgs(val packageFilter: String = "", val limit: Int = 20)

@Serializable
data class DismissNotificationArgs(val key: String = "", val packageName: String = "")

@Serializable
data class WatchPackageArgs(val packageName: String, val watch: Boolean = true)

@Serializable
data class BlockDomainArgs(val domain: String, val startVpnIfNeeded: Boolean = true)

@Serializable
data class UnblockDomainArgs(val domain: String)

@Serializable
data class GetScreenTimeArgs(val periodHours: Long = 24, val limit: Int = 10)

@Serializable
data class GetRecentAppsArgs(val periodMinutes: Long = 60)

/**
 * Device capability tools — registered in SkillRegistry when the respective
 * service is active (checked via isEnabled guards).
 *
 * All tools degrade gracefully when the underlying service is unavailable,
 * returning a clear message explaining how to enable it rather than throwing.
 */
class DeviceTools(
    private val config: OpenClawConfig,
    private val context: android.content.Context,
) {
    private val deviceAdmin   by lazy { DeviceAdminManager(context) }
    private val vpnManager    by lazy { VpnManager(context) }
    private val usageManager  by lazy { AppUsageManager(context) }

    fun allTools(): List<Tool<*, *>> = listOf(
        // Device Admin
        LockScreenTool(),
        RemoteWipeTool(),
        SetPasswordPolicyTool(),
        SetCameraDisabledTool(),
        SetScreenCaptureDisabledTool(),
        GetDeviceAdminStatusTool(),

        // Accessibility
        ReadScreenTool(),
        ClickElementTool(),
        TypeTextTool(),
        ScrollScreenTool(),
        PressBackTool(),
        PressHomeTool(),

        // Notification Listener
        ListNotificationsTool(),
        DismissNotificationTool(),
        WatchPackageTool(),

        // VPN
        VpnStatusTool(),
        BlockDomainTool(),
        UnblockDomainTool(),

        // Usage Stats
        GetScreenTimeTool(),
        GetRecentAppsTool(),
    )

    // ── Device Admin tools ────────────────────────────────────────────────────

    inner class LockScreenTool : Tool<Unit, DeviceActionResult>(
        argsType = typeToken<Unit>(),
        resultType = typeToken<DeviceActionResult>(),
        name = "device_lock_screen",
        description = "Lock the device screen immediately. Requires Device Admin to be active."
    ) {
        override suspend fun execute(args: Unit): DeviceActionResult {
            if (!deviceAdmin.isActive)
                return DeviceActionResult(false,
                    "Device Admin not active. Enable it in Settings → Security → Device Admins → OpenClaw.")
            return deviceAdmin.lockNow().fold(
                onSuccess = { DeviceActionResult(true, "Screen locked.") },
                onFailure = { DeviceActionResult(false, it.message ?: "Failed to lock screen.") },
            )
        }
    }

    inner class RemoteWipeTool : Tool<RemoteWipeArgs, DeviceActionResult>(
        argsType = typeToken<RemoteWipeArgs>(),
        resultType = typeToken<DeviceActionResult>(),
        name = "device_remote_wipe",
        description = "Factory reset the device. DESTRUCTIVE AND IRREVERSIBLE. " +
                      "User must supply confirmPhrase='CONFIRM WIPE' explicitly. " +
                      "ALWAYS ask the user to confirm verbally before calling this tool."
    ) {
        override suspend fun execute(args: RemoteWipeArgs): DeviceActionResult {
            if (args.confirmPhrase != "CONFIRM WIPE")
                return DeviceActionResult(false,
                    "Wipe not confirmed. User must say 'CONFIRM WIPE' explicitly.")
            if (!deviceAdmin.isActive)
                return DeviceActionResult(false, "Device Admin not active.")
            return deviceAdmin.wipeDevice(args.wipeExternalStorage).fold(
                onSuccess = { DeviceActionResult(true, "Device wipe initiated.") },
                onFailure = { DeviceActionResult(false, it.message ?: "Wipe failed.") },
            )
        }
    }

    inner class SetPasswordPolicyTool : Tool<SetPasswordPolicyArgs, DeviceActionResult>(
        argsType = typeToken<SetPasswordPolicyArgs>(),
        resultType = typeToken<DeviceActionResult>(),
        name = "device_set_password_policy",
        description = "Set device password policy: minimum length, max failed attempts before wipe, " +
                      "and max screen timeout. Set 0 to leave a policy unchanged."
    ) {
        override suspend fun execute(args: SetPasswordPolicyArgs): DeviceActionResult {
            if (!deviceAdmin.isActive)
                return DeviceActionResult(false, "Device Admin not active.")
            if (args.minLength > 0) deviceAdmin.setMinPasswordLength(args.minLength)
            if (args.maxFailedAttempts > 0) deviceAdmin.setMaxFailedPasswords(args.maxFailedAttempts)
            if (args.maxScreenOffTimeoutSeconds > 0)
                deviceAdmin.setMaxScreenOffTimeout(args.maxScreenOffTimeoutSeconds * 1000L)
            return DeviceActionResult(true, "Password policy updated.")
        }
    }

    inner class SetCameraDisabledTool : Tool<SetCameraDisabledArgs, DeviceActionResult>(
        argsType = typeToken<SetCameraDisabledArgs>(),
        resultType = typeToken<DeviceActionResult>(),
        name = "device_set_camera_disabled",
        description = "Disable or enable the device camera. Requires Device Admin to be active."
    ) {
        override suspend fun execute(args: SetCameraDisabledArgs): DeviceActionResult {
            if (!deviceAdmin.isActive)
                return DeviceActionResult(false, "Device Admin not active.")
            return deviceAdmin.setCameraDisabled(args.disabled).fold(
                onSuccess = { DeviceActionResult(true, "Camera disabled status set to ${args.disabled}.") },
                onFailure = { DeviceActionResult(false, it.message ?: "Failed to set camera disabled status.") }
            )
        }
    }

    inner class SetScreenCaptureDisabledTool : Tool<SetScreenCaptureDisabledArgs, DeviceActionResult>(
        argsType = typeToken<SetScreenCaptureDisabledArgs>(),
        resultType = typeToken<DeviceActionResult>(),
        name = "device_set_screen_capture_disabled",
        description = "Disable or enable screen capture (screenshots/screen recording). Requires Device Admin to be active."
    ) {
        override suspend fun execute(args: SetScreenCaptureDisabledArgs): DeviceActionResult {
            if (!deviceAdmin.isActive)
                return DeviceActionResult(false, "Device Admin not active.")
            return deviceAdmin.setScreenCaptureDisabled(args.disabled).fold(
                onSuccess = { DeviceActionResult(true, "Screen capture disabled status set to ${args.disabled}.") },
                onFailure = { DeviceActionResult(false, it.message ?: "Failed to set screen capture disabled status.") }
            )
        }
    }

    inner class GetDeviceAdminStatusTool : Tool<Unit, DeviceAdminStatus>(
        argsType = typeToken<Unit>(),
        resultType = typeToken<DeviceAdminStatus>(),
        name = "device_get_admin_status",
        description = "Get the active status of Device Admin and its policies (camera and screen capture restrictions)."
    ) {
        override suspend fun execute(args: Unit): DeviceAdminStatus {
            return DeviceAdminStatus(
                isAdminActive = deviceAdmin.isActive,
                isCameraDisabled = deviceAdmin.isCameraDisabled(),
                isScreenCaptureDisabled = deviceAdmin.isScreenCaptureDisabled()
            )
        }
    }

    // ── Accessibility tools ────────────────────────────────────────────────────

    private fun accessService() = OpenClawAccessibilityService.getInstance()

    inner class ReadScreenTool : Tool<Unit, ScreenContentResult>(
        argsType = typeToken<Unit>(),
        resultType = typeToken<ScreenContentResult>(),
        name = "device_read_screen",
        description = "Read all visible text from the current screen. " +
                      "Useful to understand what app is open and what's displayed."
    ) {
        override suspend fun execute(args: Unit): ScreenContentResult {
            val svc = accessService()
                ?: return ScreenContentResult(
                    "Accessibility Service not enabled. " +
                    "Go to Settings → Accessibility → OpenClaw to enable.",
                    "", ""
                )
            return ScreenContentResult(
                text         = svc.getScreenText(),
                packageName  = svc.foregroundPackage,
                activityName = svc.foregroundActivity,
            )
        }
    }

    inner class ClickElementTool : Tool<ClickElementArgs, DeviceActionResult>(
        argsType = typeToken<ClickElementArgs>(),
        resultType = typeToken<DeviceActionResult>(),
        name = "device_click_element",
        description = "Click a UI element on the screen. Provide text (visible label) or resourceId."
    ) {
        override suspend fun execute(args: ClickElementArgs): DeviceActionResult {
            val svc = accessService()
                ?: return DeviceActionResult(false, "Accessibility Service not enabled.")
            val clicked = when {
                args.text.isNotBlank()       -> svc.clickByText(args.text)
                args.resourceId.isNotBlank() -> svc.clickById(args.resourceId)
                else -> return DeviceActionResult(false, "Provide text or resourceId.")
            }
            return DeviceActionResult(clicked, if (clicked) "Clicked." else "Element not found.")
        }
    }

    inner class TypeTextTool : Tool<TypeTextArgs, DeviceActionResult>(
        argsType = typeToken<TypeTextArgs>(),
        resultType = typeToken<DeviceActionResult>(),
        name = "device_type_text",
        description = "Type text into the currently focused input field on screen."
    ) {
        override suspend fun execute(args: TypeTextArgs): DeviceActionResult {
            val svc = accessService()
                ?: return DeviceActionResult(false, "Accessibility Service not enabled.")
            val ok = svc.typeText(args.text)
            return DeviceActionResult(ok, if (ok) "Text entered." else "No focused input found.")
        }
    }

    inner class ScrollScreenTool : Tool<ScrollScreenArgs, DeviceActionResult>(
        argsType = typeToken<ScrollScreenArgs>(),
        resultType = typeToken<DeviceActionResult>(),
        name = "device_scroll",
        description = "Scroll the screen up or down."
    ) {
        override suspend fun execute(args: ScrollScreenArgs): DeviceActionResult {
            val svc = accessService()
                ?: return DeviceActionResult(false, "Accessibility Service not enabled.")
            val dir = if (args.direction.lowercase() == "up") ScrollDirection.UP else ScrollDirection.DOWN
            val ok  = svc.scroll(dir)
            return DeviceActionResult(ok, if (ok) "Scrolled ${args.direction}." else "No scrollable view.")
        }
    }

    inner class PressBackTool : Tool<Unit, DeviceActionResult>(
        argsType = typeToken<Unit>(),
        resultType = typeToken<DeviceActionResult>(),
        name = "device_press_back",
        description = "Press the Back button."
    ) {
        override suspend fun execute(args: Unit): DeviceActionResult {
            val ok = accessService()?.pressBack() ?: false
            return DeviceActionResult(ok, if (ok) "Back pressed." else "Accessibility Service not enabled.")
        }
    }

    inner class PressHomeTool : Tool<Unit, DeviceActionResult>(
        argsType = typeToken<Unit>(),
        resultType = typeToken<DeviceActionResult>(),
        name = "device_press_home",
        description = "Press the Home button."
    ) {
        override suspend fun execute(args: Unit): DeviceActionResult {
            val ok = accessService()?.pressHome() ?: false
            return DeviceActionResult(ok, if (ok) "Home pressed." else "Accessibility Service not enabled.")
        }
    }

    // ── Notification tools ─────────────────────────────────────────────────────

    inner class ListNotificationsTool : Tool<ListNotificationsArgs, NotificationListResult>(
        argsType = typeToken<ListNotificationsArgs>(),
        resultType = typeToken<NotificationListResult>(),
        name = "device_list_notifications",
        description = "List current notifications. Filter by app package name. Requires Notification Listener permission."
    ) {
        override suspend fun execute(args: ListNotificationsArgs): NotificationListResult {
            val svc = OpenClawNotificationListener.instance
                ?: return NotificationListResult(listOf(
                    NotificationSummary("", "system", "Notification Listener not enabled.",
                        "Go to Settings → Apps → Special app access → Notification access → OpenClaw",
                        System.currentTimeMillis(), false)
                ), 0)
            val notifs = svc.getActiveNotifications(args.packageFilter)
                .take(args.limit)
                .map { n -> NotificationSummary(n.key, n.appName, n.title, n.text, n.timestamp, n.isOngoing) }
            return NotificationListResult(notifs, notifs.size)
        }
    }

    inner class DismissNotificationTool : Tool<DismissNotificationArgs, DeviceActionResult>(
        argsType = typeToken<DismissNotificationArgs>(),
        resultType = typeToken<DeviceActionResult>(),
        name = "device_dismiss_notification",
        description = "Dismiss a notification by key, or dismiss all notifications from an app by packageName."
    ) {
        override suspend fun execute(args: DismissNotificationArgs): DeviceActionResult {
            val svc = OpenClawNotificationListener.instance
                ?: return DeviceActionResult(false, "Notification Listener not enabled.")
            when {
                args.key.isNotBlank()         -> svc.dismissNotification(args.key)
                args.packageName.isNotBlank() -> svc.dismissAllFromPackage(args.packageName)
                else -> return DeviceActionResult(false, "Provide key or packageName.")
            }
            return DeviceActionResult(true, "Notification(s) dismissed.")
        }
    }

    inner class WatchPackageTool : Tool<WatchPackageArgs, DeviceActionResult>(
        argsType = typeToken<WatchPackageArgs>(),
        resultType = typeToken<DeviceActionResult>(),
        name = "device_watch_package_notifications",
        description = "Add or remove an app package from the notification watch list. When the list is non-empty, only watched packages emit notification events."
    ) {
        override suspend fun execute(args: WatchPackageArgs): DeviceActionResult {
            if (args.watch) OpenClawNotificationListener.watchedPackages.add(args.packageName)
            else            OpenClawNotificationListener.watchedPackages.remove(args.packageName)
            return DeviceActionResult(true,
                "${if (args.watch) "Watching" else "Unwatched"} ${args.packageName}.")
        }
    }

    // ── VPN tools ─────────────────────────────────────────────────────────────

    inner class VpnStatusTool : Tool<Unit, VpnStatusResult>(
        argsType = typeToken<Unit>(),
        resultType = typeToken<VpnStatusResult>(),
        name = "device_vpn_status",
        description = "Get VPN status and list of blocked domains."
    ) {
        override suspend fun execute(args: Unit) = VpnStatusResult(
            running        = vpnManager.isRunning,
            state          = OpenClawVpnService.state.value.name,
            blockedDomains = vpnManager.getBlockedDomains().toList(),
        )
    }

    inner class BlockDomainTool : Tool<BlockDomainArgs, DeviceActionResult>(
        argsType = typeToken<BlockDomainArgs>(),
        resultType = typeToken<DeviceActionResult>(),
        name = "device_block_domain",
        description = "Block all network traffic to a domain (e.g. 'instagram.com'). Starts the VPN if not running and startVpnIfNeeded is true."
    ) {
        override suspend fun execute(args: BlockDomainArgs): DeviceActionResult {
            vpnManager.blockDomain(args.domain)
            if (args.startVpnIfNeeded && !vpnManager.isRunning) vpnManager.start()
            return DeviceActionResult(true, "${args.domain} blocked.")
        }
    }

    inner class UnblockDomainTool : Tool<UnblockDomainArgs, DeviceActionResult>(
        argsType = typeToken<UnblockDomainArgs>(),
        resultType = typeToken<DeviceActionResult>(),
        name = "device_unblock_domain",
        description = "Remove a domain from the block list."
    ) {
        override suspend fun execute(args: UnblockDomainArgs): DeviceActionResult {
            vpnManager.unblockDomain(args.domain)
            return DeviceActionResult(true, "${args.domain} unblocked.")
        }
    }

    // ── Usage Stats tools ──────────────────────────────────────────────────────

    inner class GetScreenTimeTool : Tool<GetScreenTimeArgs, UsageResult>(
        argsType = typeToken<GetScreenTimeArgs>(),
        resultType = typeToken<UsageResult>(),
        name = "device_get_screen_time",
        description = "Get screen time by app for the last N hours. Requires Usage Access permission."
    ) {
        override suspend fun execute(args: GetScreenTimeArgs): UsageResult {
            if (!usageManager.hasPermission)
                return UsageResult(listOf(AppUsageSummary(
                    "system", "permission", "0m", 0L).copy(
                        appName = "Usage Access not granted — go to Settings → Apps → Special app access → Usage access")),
                    "N/A")
            val stats = usageManager.getTopApps(args.periodHours, args.limit)
            return UsageResult(
                apps   = stats.map { AppUsageSummary(it.appName, it.packageName, it.formattedTime, it.lastUsed) },
                period = "Last ${args.periodHours}h",
            )
        }
    }

    inner class GetRecentAppsTool : Tool<GetRecentAppsArgs, UsageResult>(
        argsType = typeToken<GetRecentAppsArgs>(),
        resultType = typeToken<UsageResult>(),
        name = "device_get_recent_apps",
        description = "Get apps launched in the last N minutes, in order. Useful for context."
    ) {
        override suspend fun execute(args: GetRecentAppsArgs): UsageResult {
            if (!usageManager.hasPermission)
                return UsageResult(emptyList(), "Usage Access not granted")
            val launches = usageManager.getRecentLaunches(args.periodMinutes)
            return UsageResult(
                apps   = launches.map { AppUsageSummary(it.appName, it.packageName, "", it.timestamp) },
                period = "Last ${args.periodMinutes}min",
            )
        }
    }
}
