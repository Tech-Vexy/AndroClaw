package ai.androclaw.feature.device

import android.Manifest
import ai.androclaw.ui.permissions.PermissionGroups
import org.junit.Assert.*
import org.junit.Test

class PermissionTest {

    @Test fun `CORE group contains notifications and microphone`() {
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in PermissionGroups.CORE)
        assertTrue(Manifest.permission.RECORD_AUDIO in PermissionGroups.CORE)
    }
    @Test fun `TELEPHONY group contains all SMS and call permissions`() {
        assertTrue(Manifest.permission.SEND_SMS      in PermissionGroups.TELEPHONY)
        assertTrue(Manifest.permission.READ_SMS       in PermissionGroups.TELEPHONY)
        assertTrue(Manifest.permission.CALL_PHONE     in PermissionGroups.TELEPHONY)
        assertTrue(Manifest.permission.READ_CALL_LOG  in PermissionGroups.TELEPHONY)
    }
    @Test fun `ALL_CORE is superset of CORE`() {
        assertTrue(PermissionGroups.ALL_CORE.containsAll(PermissionGroups.CORE))
    }
    @Test fun `no duplicate permissions in any group`() {
        assertEquals(PermissionGroups.TELEPHONY.size, PermissionGroups.TELEPHONY.toSet().size)
        assertEquals(PermissionGroups.ALL_CORE.size,  PermissionGroups.ALL_CORE.toSet().size)
    }
}

