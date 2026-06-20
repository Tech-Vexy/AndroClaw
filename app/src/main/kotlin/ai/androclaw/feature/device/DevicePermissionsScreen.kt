package ai.androclaw.feature.device

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.androclaw.feature.device.*

private val BgColor       = Color(0xFF0D1117)
private val SurfaceColor  = Color(0xFF1A1F2E)
private val ClawBlue      = Color(0xFF6C9EFF)
private val TextPrimary   = Color(0xFFE6EDF3)
private val TextSecondary = Color(0xFF8B949E)
private val SuccessGreen  = Color(0xFF3FB950)
private val WarnOrange    = Color(0xFFD29922)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePermissionsScreen(onBack: () -> Unit) {
    val context       = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-check status every time the screen resumes (user may return from Settings)
    var refreshTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Status checks (recomputed on every resume)
    val adminActive    by remember(refreshTick) {
        derivedStateOf {
            val dpm = context.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE)
                    as DevicePolicyManager
            dpm.isAdminActive(ComponentName(context, OpenClawDeviceAdmin::class.java))
        }
    }
    val accessEnabled  by remember(refreshTick) { derivedStateOf { OpenClawAccessibilityService.isEnabled } }
    val notifEnabled   by remember(refreshTick) { derivedStateOf { OpenClawNotificationListener.isEnabled } }
    val vpnRunning     by remember(refreshTick) { derivedStateOf { OpenClawVpnService.isRunning } }
    val usageEnabled   by remember(refreshTick) { derivedStateOf {
        val usm = context.getSystemService(android.content.Context.USAGE_STATS_SERVICE)
                as android.app.usage.UsageStatsManager
        val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY,
            System.currentTimeMillis() - 3600_000L, System.currentTimeMillis())
        stats != null && stats.isNotEmpty()
    }}

    // Device Admin activation launcher
    val adminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshTick++ }

    // VPN permission launcher
    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            context.startService(
                Intent(context, OpenClawVpnService::class.java)
                    .setAction(OpenClawVpnService.ACTION_START)
            )
        }
        refreshTick++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device Capabilities", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null, tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceColor),
            )
        },
        containerColor = BgColor,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "These are optional system-level permissions that give OpenClaw deeper " +
                "device integration. Each is individually controlled and can be revoked at any time.",
                color    = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )

            Spacer(Modifier.height(4.dp))

            // 1 — Device Admin
            DeviceCapabilityCard(
                icon        = Icons.Default.AdminPanelSettings,
                title       = "Device Admin",
                description = "Lock screen on command, enforce password policy, and protect OpenClaw " +
                              "from being uninstalled without first deactivating admin.",
                isEnabled   = adminActive,
                enableLabel = "Activate",
                disableLabel = "Deactivate",
                onEnable = {
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                            ComponentName(context, OpenClawDeviceAdmin::class.java))
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Allows OpenClaw to lock your screen and enforce security policies.")
                    }
                    adminLauncher.launch(intent)
                },
                onDisable = {
                    val dpm = context.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE)
                            as DevicePolicyManager
                    dpm.removeActiveAdmin(ComponentName(context, OpenClawDeviceAdmin::class.java))
                    refreshTick++
                },
                warningText = "Remote wipe requires Device Admin. Only activate if you understand the implications.",
            )

            // 2 — Accessibility Service
            DeviceCapabilityCard(
                icon        = Icons.Default.Accessibility,
                title       = "Accessibility Service",
                description = "Read on-screen text, click UI elements, type text, and scroll — " +
                              "lets the agent interact with any app on your behalf.",
                isEnabled   = accessEnabled,
                enableLabel = "Open Settings",
                onEnable = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
            )

            // 3 — Notification Listener
            DeviceCapabilityCard(
                icon        = Icons.Default.Notifications,
                title       = "Notification Access",
                description = "See all notifications from every app. " +
                              "The agent can summarise, filter, or act on notifications you ask it to watch.",
                isEnabled   = notifEnabled,
                enableLabel = "Open Settings",
                onEnable = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
            )

            // 4 — VPN Service
            DeviceCapabilityCard(
                icon        = Icons.Default.VpnKey,
                title       = "VPN (Domain Blocking)",
                description = "Block specific websites or domains on command. " +
                              "Useful for focus mode: \"block social media until 6pm\".",
                isEnabled   = vpnRunning,
                enableLabel = "Enable VPN",
                disableLabel = "Stop VPN",
                onEnable = {
                    val prepare = VpnService.prepare(context)
                    if (prepare != null) {
                        vpnLauncher.launch(prepare)
                    } else {
                        context.startService(
                            Intent(context, OpenClawVpnService::class.java)
                                .setAction(OpenClawVpnService.ACTION_START)
                        )
                        refreshTick++
                    }
                },
                onDisable = {
                    context.startService(
                        Intent(context, OpenClawVpnService::class.java)
                            .setAction(OpenClawVpnService.ACTION_STOP)
                    )
                    refreshTick++
                },
                warningText = "All device traffic routes through the VPN while active. " +
                              "OpenClaw only uses it for domain blocking.",
            )

            // 5 — Usage Stats
            DeviceCapabilityCard(
                icon        = Icons.Default.BarChart,
                title       = "Usage Access",
                description = "See which apps you've used and for how long. " +
                              "Gives the agent context about your day and enables proactive suggestions.",
                isEnabled   = usageEnabled,
                enableLabel = "Open Settings",
                onEnable = {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                },
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun DeviceCapabilityCard(
    icon: ImageVector,
    title: String,
    description: String,
    isEnabled: Boolean,
    enableLabel: String = "Enable",
    disableLabel: String? = null,
    onEnable: () -> Unit,
    onDisable: (() -> Unit)? = null,
    warningText: String? = null,
) {
    Surface(
        color    = SurfaceColor,
        shape    = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(icon, null,
                    tint     = if (isEnabled) SuccessGreen else TextSecondary,
                    modifier = Modifier.size(22.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        StatusBadge(isEnabled)
                    }
                    Text(description, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }

            warningText?.let {
                Surface(
                    color = WarnOrange.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(Icons.Default.Warning, null,
                            tint     = WarnOrange,
                            modifier = Modifier.size(14.dp).padding(top = 1.dp))
                        Text(it, color = WarnOrange, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                if (isEnabled && onDisable != null && disableLabel != null) {
                    OutlinedButton(
                        onClick = onDisable,
                        border  = androidx.compose.foundation.BorderStroke(
                            1.dp, Color(0xFFFF5555).copy(0.4f)),
                    ) {
                        Text(disableLabel, color = Color(0xFFFF5555), fontSize = 13.sp)
                    }
                }
                if (!isEnabled) {
                    Button(
                        onClick = onEnable,
                        colors  = ButtonDefaults.buttonColors(containerColor = ClawBlue),
                        shape   = RoundedCornerShape(10.dp),
                    ) {
                        Text(enableLabel, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(enabled: Boolean) {
    val (bg, fg, label) = if (enabled)
        Triple(SuccessGreen.copy(0.15f), SuccessGreen, "Active")
    else
        Triple(TextSecondary.copy(0.12f), TextSecondary, "Inactive")

    Surface(color = bg, shape = RoundedCornerShape(4.dp)) {
        Text(label, color = fg, fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

