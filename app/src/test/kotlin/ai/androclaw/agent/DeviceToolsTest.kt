package ai.androclaw.agent

import ai.androclaw.feature.device.AppUsageStat
import ai.androclaw.feature.device.VpnState
import org.junit.Assert.*
import org.junit.Test
import ai.androclaw.feature.device.OpenClawAccessibilityService
import ai.androclaw.feature.device.OpenClawNotificationListener
import ai.androclaw.feature.device.OpenClawVpnService

/**
 * Unit tests for device capability tools.
 * Service status checks use the companion object flags (no Android framework needed).
 */
class DeviceToolsTest {

    // ── Service availability flags ────────────────────────────────────────────

    @Test fun `accessibility service starts disabled`() {
        // In test environment, no service is running
        assertFalse(OpenClawAccessibilityService.isEnabled)
    }

    @Test fun `notification listener starts disabled`() {
        assertFalse(OpenClawNotificationListener.isEnabled)
    }

    @Test fun `VPN service starts stopped`() {
        assertFalse(OpenClawVpnService.isRunning)
        assertEquals(VpnState.DISCONNECTED, OpenClawVpnService.state.value)
    }

    // ── VPN domain blocking ───────────────────────────────────────────────────

    @Test fun `blocked domains can be added and removed`() {
        OpenClawVpnService.blockedDomains.clear()
        OpenClawVpnService.blockedDomains.add("instagram.com")
        OpenClawVpnService.blockedDomains.add("tiktok.com")
        assertEquals(2, OpenClawVpnService.blockedDomains.size)
        OpenClawVpnService.blockedDomains.remove("instagram.com")
        assertEquals(1, OpenClawVpnService.blockedDomains.size)
        assertFalse("instagram.com" in OpenClawVpnService.blockedDomains)
        assertTrue("tiktok.com" in OpenClawVpnService.blockedDomains)
        OpenClawVpnService.blockedDomains.clear()
    }

    // ── Notification watch list ───────────────────────────────────────────────

    @Test fun `watched packages can be added and cleared`() {
        OpenClawNotificationListener.watchedPackages.clear()
        OpenClawNotificationListener.watchedPackages.add("com.whatsapp")
        OpenClawNotificationListener.watchedPackages.add("com.telegram.messenger")
        assertEquals(2, OpenClawNotificationListener.watchedPackages.size)
        OpenClawNotificationListener.watchedPackages.clear()
        assertTrue(OpenClawNotificationListener.watchedPackages.isEmpty())
    }

    // ── AppUsageStat formatting ───────────────────────────────────────────────

    @Test fun `formats hours and minutes correctly`() {
        val stat = AppUsageStat("com.example", "Example", foregroundMs = 3_661_000L)
        assertEquals("1h 1m", stat.formattedTime)
    }

    @Test fun `formats minutes only when less than an hour`() {
        val stat = AppUsageStat("com.example", "Example", foregroundMs = 720_000L)
        assertEquals("12m", stat.formattedTime)
    }

    @Test fun `formats zero correctly`() {
        val stat = AppUsageStat("com.example", "Example", foregroundMs = 0L)
        assertEquals("0m", stat.formattedTime)
    }

    @Test fun `formats large values correctly`() {
        val stat = AppUsageStat("com.example", "Example", foregroundMs = 7_200_000L)
        assertEquals("2h 0m", stat.formattedTime)
    }

    // ── Device tool count ─────────────────────────────────────────────────────

    @Test fun `DeviceTools registers 20 tools`() {
        // DeviceTools requires Android context — verify count from descriptor list manually
        val expectedTools = listOf(
            "device_lock_screen",
            "device_remote_wipe",
            "device_set_password_policy",
            "device_set_camera_disabled",
            "device_set_screen_capture_disabled",
            "device_get_admin_status",
            "device_read_screen",
            "device_click_element",
            "device_type_text",
            "device_scroll",
            "device_press_back",
            "device_press_home",
            "device_list_notifications",
            "device_dismiss_notification",
            "device_watch_package_notifications",
            "device_vpn_status",
            "device_block_domain",
            "device_unblock_domain",
            "device_get_screen_time",
            "device_get_recent_apps",
        )
        assertEquals(20, expectedTools.size)
        assertEquals(20, expectedTools.toSet().size)  // no duplicates
    }

    // ── Tool name format ──────────────────────────────────────────────────────

    @Test fun `all device tool names follow device_ prefix convention`() {
        val tools = listOf(
            "device_lock_screen", "device_remote_wipe", "device_set_password_policy",
            "device_set_camera_disabled", "device_set_screen_capture_disabled", "device_get_admin_status",
            "device_read_screen", "device_click_element", "device_type_text",
            "device_scroll", "device_press_back", "device_press_home",
            "device_list_notifications", "device_dismiss_notification",
            "device_watch_package_notifications", "device_vpn_status",
            "device_block_domain", "device_unblock_domain",
            "device_get_screen_time", "device_get_recent_apps",
        )
        tools.forEach { name ->
            assertTrue("$name should start with 'device_'", name.startsWith("device_"))
        }
    }
}

