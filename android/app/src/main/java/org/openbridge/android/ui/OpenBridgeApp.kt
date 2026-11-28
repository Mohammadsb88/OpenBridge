package org.openbridge.android.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhoneForwarded
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openbridge.android.R
import org.openbridge.android.data.BleConnectionState
import org.openbridge.android.data.BridgeStore
import org.openbridge.android.data.RelayedEvent
import org.openbridge.android.data.VoiceCapabilityState
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.CircularProgressIndicator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val NavyBg = Color(0xFF0B1020)
private val SlateCard = Color(0xFF141C33)
private val SlateCardBorder = Color(0xFF233056)
private val CyanAccent = Color(0xFF22D3EE)
private val IndigoAccent = Color(0xFF6366F1)
private val EmeraldOk = Color(0xFF10B981)
private val AmberWarn = Color(0xFFF59E0B)

private val OpenBridgeDarkScheme = darkColorScheme(
    primary = CyanAccent,
    onPrimary = Color(0xFF06131F),
    secondary = IndigoAccent,
    onSecondary = Color.White,
    background = NavyBg,
    onBackground = Color(0xFFF1F5F9),
    surface = SlateCard,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1C2645),
    onSurfaceVariant = Color(0xFF94A3B8),
    error = Color(0xFFFB7185)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenBridgeApp(
    allPermissionsGranted: Boolean,
    missingPermissionsList: List<String>,
    shizukuReady: Boolean,
    shizukuAlive: Boolean,
    rootAvailable: Boolean,
    compatibilityChecking: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onOpenOemAutostart: () -> Unit,
    onRequestShizuku: () -> Unit,
    onRunCompatibilityCheck: () -> Unit,
    onToggleForwarding: (Boolean, String) -> Unit,
    onStartBridge: () -> Unit,
    onStopBridge: () -> Unit,
    onSendTestSms: () -> Unit,
    onSendTestCall: () -> Unit,
    onUnpair: () -> Unit,
    onLanguageChange: (String) -> Unit
) {
    MaterialTheme(colorScheme = OpenBridgeDarkScheme) {
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        var showQrDialog by remember { mutableStateOf(false) }
        var showLogsDialog by remember { mutableStateOf(false) }

        val connectionState by BridgeStore.connectionState.collectAsState()
        val pairedPeer by BridgeStore.pairedPeerName.collectAsState()
        val qrPayload by BridgeStore.qrPayload.collectAsState()
        val events by BridgeStore.events.collectAsState()
        val logs by BridgeStore.logs.collectAsState()
        val currentLanguage by BridgeStore.language.collectAsState()
        val voiceMode by BridgeStore.voiceMode.collectAsState()
        val forwardSip by BridgeStore.forwardSipNumber.collectAsState()
        val wifiIp by BridgeStore.wifiIp.collectAsState()
        val autoSpeaker by BridgeStore.autoSpeaker.collectAsState()
        val voiceCapability by BridgeStore.voiceCapability.collectAsState()
        val activeTransport by BridgeStore.activeAudioTransport.collectAsState()

        Scaffold(
            containerColor = NavyBg,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = NavyBg),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Image(
                                painter = painterResource(R.drawable.brand_logo),
                                contentDescription = "OpenBridge Logo",
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(9.dp))
                            )
                            Column {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                                Text(
                                    text = if (pairedPeer != null) {
                                        stringResource(R.string.paired_badge, pairedPeer!!)
                                    } else {
                                        stringResource(R.string.not_paired_badge)
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showQrDialog = true }) {
                            Icon(Icons.Default.QrCode2, contentDescription = "QR", tint = CyanAccent)
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar(containerColor = SlateCard) {
                    val itemColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyanAccent,
                        selectedTextColor = CyanAccent,
                        indicatorColor = CyanAccent.copy(alpha = 0.14f)
                    )
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        colors = itemColors,
                        icon = {
                            Icon(
                                if (connectionState == BleConnectionState.CONNECTED) {
                                    Icons.Default.BluetoothConnected
                                } else {
                                    Icons.AutoMirrored.Filled.BluetoothSearching
                                },
                                contentDescription = null
                            )
                        },
                        label = { Text(stringResource(R.string.tab_bridge)) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        colors = itemColors,
                        icon = { Icon(Icons.Default.History, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_activity)) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        colors = itemColors,
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_settings)) }
                    )
                }
            }
        ) { padding ->
            when (selectedTab) {
                0 -> BridgeHomeTab(
                    modifier = Modifier.padding(padding),
                    connectionState = connectionState,
                    pairedPeer = pairedPeer,
                    wifiIp = wifiIp,
                    activeTransport = activeTransport,
                    shizukuReady = shizukuReady,
                    shizukuAlive = shizukuAlive,
                    voiceCapability = voiceCapability,
                    compatibilityChecking = compatibilityChecking,
                    allPermissionsGranted = allPermissionsGranted,
                    missingPermissionsList = missingPermissionsList,
                    onRequestPermissions = onRequestPermissions,
                    onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
                    onOpenOemAutostart = onOpenOemAutostart,
                    onRequestShizuku = onRequestShizuku,
                    onRunCompatibilityCheck = onRunCompatibilityCheck,
                    onShowQr = { showQrDialog = true },
                    onStartBridge = onStartBridge,
                    onStopBridge = onStopBridge,
                    onSendTestSms = onSendTestSms,
                    onSendTestCall = onSendTestCall
                )
                1 -> ActivityTab(
                    modifier = Modifier.padding(padding),
                    events = events
                )
                else -> SettingsTab(
                    modifier = Modifier.padding(padding),
                    currentLanguage = currentLanguage,
                    voiceMode = voiceMode,
                    forwardSip = forwardSip,
                    autoSpeaker = autoSpeaker,
                    shizukuReady = shizukuReady,
                    rootAvailable = rootAvailable,
                    onLanguageChange = onLanguageChange,
                    onVoiceModeChange = { BridgeStore.setVoiceMode(it) },
                    onForwardSipChange = { BridgeStore.setForwardSipNumber(it) },
                    onAutoSpeakerChange = { BridgeStore.setAutoSpeaker(it) },
                    onRequestShizuku = onRequestShizuku,
                    onToggleForwarding = onToggleForwarding,
                    onOpenDiagnostics = { showLogsDialog = true },
                    onUnpair = onUnpair
                )
            }
        }

        if (showQrDialog) {
            QrPairingDialog(
                qrPayload = qrPayload,
                onDismiss = { showQrDialog = false }
            )
        }

        if (showLogsDialog) {
            DiagnosticsDialog(
                logs = logs,
                onDismiss = { showLogsDialog = false }
            )
        }
    }
}

@Composable
private fun BridgeHomeTab(
    modifier: Modifier,
    connectionState: BleConnectionState,
    pairedPeer: String?,
    wifiIp: String,
    activeTransport: String,
    shizukuReady: Boolean,
    shizukuAlive: Boolean,
    voiceCapability: VoiceCapabilityState?,
    compatibilityChecking: Boolean,
    allPermissionsGranted: Boolean,
    missingPermissionsList: List<String>,
    onRequestPermissions: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onOpenOemAutostart: () -> Unit,
    onRequestShizuku: () -> Unit,
    onRunCompatibilityCheck: () -> Unit,
    onShowQr: () -> Unit,
    onStartBridge: () -> Unit,
    onStopBridge: () -> Unit,
    onSendTestSms: () -> Unit,
    onSendTestCall: () -> Unit
) {
    val accentColor by animateColorAsState(
        targetValue = when (connectionState) {
            BleConnectionState.CONNECTED -> EmeraldOk
            BleConnectionState.ADVERTISING -> AmberWarn
            BleConnectionState.STOPPED -> Color(0xFF64748B)
        },
        label = "status_color"
    )
    val statusText = when (connectionState) {
        BleConnectionState.CONNECTED -> stringResource(R.string.status_connected)
        BleConnectionState.ADVERTISING -> stringResource(R.string.status_advertising)
        BleConnectionState.STOPPED -> stringResource(R.string.status_stopped)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero Gradient Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF172554), Color(0xFF1E1B4B), SlateCard)
                    )
                )
                .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (wifiIp.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Wifi, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(16.dp))
                        Text(
                            text = "Hotspot Audio IP: $wifiIp",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyanAccent
                        )
                    }
                }
                if (activeTransport.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = EmeraldOk, modifier = Modifier.size(16.dp))
                        Text(
                            text = if (activeTransport == "ble_adpcm") {
                                stringResource(R.string.audio_transport_ble)
                            } else {
                                stringResource(R.string.audio_transport_wifi)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = EmeraldOk
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    if (connectionState == BleConnectionState.STOPPED) {
                        Button(
                            onClick = onStartBridge,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(stringResource(R.string.btn_start_bridge), fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onStopBridge,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(stringResource(R.string.btn_stop_bridge))
                        }
                    }
                    OutlinedButton(
                        onClick = onShowQr,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_show_qr), fontSize = 12.sp)
                    }
                }
            }
        }

        // Permissions / Health Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SlateCardBorder, RoundedCornerShape(18.dp))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (allPermissionsGranted) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = if (allPermissionsGranted) EmeraldOk else AmberWarn
                    )
                    Text(
                        text = stringResource(R.string.permissions_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = if (allPermissionsGranted) {
                        stringResource(R.string.permissions_all_ok)
                    } else {
                        "${stringResource(R.string.permissions_missing)} (${missingPermissionsList.take(4).joinToString(", ")})"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (!allPermissionsGranted) {
                        Button(
                            onClick = onRequestPermissions,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.btn_grant_permissions), fontSize = 12.sp)
                        }
                    }
                    OutlinedButton(
                        onClick = onRequestBatteryUnrestricted,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.btn_battery_optimization), fontSize = 12.sp)
                    }
                }
                OutlinedButton(
                    onClick = onOpenOemAutostart,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.btn_oem_autostart), fontSize = 12.sp)
                }
            }
        }

        // Hardware Call Audio Compatibility Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SlateCardBorder, RoundedCornerShape(18.dp))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = CyanAccent)
                    Text(
                        text = stringResource(R.string.compat_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = when {
                        shizukuReady -> stringResource(R.string.shizuku_status_ready)
                        shizukuAlive -> stringResource(R.string.shizuku_status_perm)
                        else -> stringResource(R.string.shizuku_status_off)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (shizukuReady) EmeraldOk else AmberWarn
                )

                val cap = voiceCapability
                if (cap == null) {
                    Text(
                        text = stringResource(R.string.compat_untested),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val (badgeColor, badgeText) = when (cap.verdict) {
                        "two_way_direct" -> EmeraldOk to stringResource(R.string.compat_verdict_two_way)
                        "downlink_only" -> CyanAccent to stringResource(R.string.compat_verdict_downlink)
                        else -> AmberWarn to stringResource(R.string.compat_verdict_fallback)
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = badgeColor.copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(badgeText, color = badgeColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                text = cap.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onRunCompatibilityCheck,
                        enabled = !compatibilityChecking,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (compatibilityChecking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.compat_checking), fontSize = 12.sp)
                        } else {
                            Text(stringResource(R.string.compat_btn_probe), fontSize = 12.sp)
                        }
                    }
                    if (shizukuAlive && !shizukuReady) {
                        OutlinedButton(
                            onClick = onRequestShizuku,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.btn_grant_shizuku), fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Quick Test Card when connected
        if (connectionState == BleConnectionState.CONNECTED) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateCardBorder, RoundedCornerShape(18.dp))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Quick Test", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onSendTestSms, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                            Text(stringResource(R.string.btn_test_sms), fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = onSendTestCall, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                            Text(stringResource(R.string.btn_test_call), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityTab(modifier: Modifier, events: List<RelayedEvent>) {
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    if (events.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.activity_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(events, key = { it.id }) { event ->
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateCardBorder, RoundedCornerShape(16.dp))
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(CyanAccent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (event.kind) {
                                "SMS" -> Icons.Default.Sms
                                "SMS_OUT" -> Icons.AutoMirrored.Filled.Send
                                else -> Icons.Default.Call
                            },
                            contentDescription = null,
                            tint = if (event.kind == "SMS_OUT") EmeraldOk else CyanAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(event.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                timeFmt.format(Date(event.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(event.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!event.otp.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = EmeraldOk.copy(alpha = 0.16f)
                            ) {
                                Text(
                                    text = stringResource(R.string.otp_label, event.otp),
                                    fontWeight = FontWeight.Bold,
                                    color = EmeraldOk,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(
    modifier: Modifier,
    currentLanguage: String,
    voiceMode: String,
    forwardSip: String,
    autoSpeaker: Boolean,
    shizukuReady: Boolean,
    rootAvailable: Boolean,
    onLanguageChange: (String) -> Unit,
    onVoiceModeChange: (String) -> Unit,
    onForwardSipChange: (String) -> Unit,
    onAutoSpeakerChange: (Boolean) -> Unit,
    onRequestShizuku: () -> Unit,
    onToggleForwarding: (Boolean, String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onUnpair: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Voice Mode Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SlateCardBorder, RoundedCornerShape(18.dp))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.PhoneForwarded, contentDescription = null, tint = CyanAccent)
                    Text(stringResource(R.string.voice_title), fontWeight = FontWeight.SemiBold)
                }

                listOf(
                    "standard" to stringResource(R.string.voice_mode_standard),
                    "sip" to stringResource(R.string.voice_mode_sip),
                    "shizuku" to "${stringResource(R.string.voice_mode_shizuku)} (${if (shizukuReady) "Ready" else "Off"})",
                    "root" to "${stringResource(R.string.voice_mode_root)} (${if (rootAvailable) "su" else "No"})"
                ).forEach { (id, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onVoiceModeChange(id) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = voiceMode == id, onClick = { onVoiceModeChange(id) })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (voiceMode == "shizuku" && !shizukuReady) {
                    OutlinedButton(onClick = onRequestShizuku, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_grant_shizuku))
                    }
                }

                HorizontalDivider(color = SlateCardBorder, modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.auto_speaker_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.auto_speaker_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoSpeaker, onCheckedChange = onAutoSpeakerChange)
                }

                HorizontalDivider(color = SlateCardBorder, modifier = Modifier.padding(vertical = 4.dp))
                OutlinedTextField(
                    value = forwardSip,
                    onValueChange = onForwardSipChange,
                    label = { Text(stringResource(R.string.forward_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onToggleForwarding(true, forwardSip) },
                        enabled = forwardSip.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.btn_enable_forward), fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { onToggleForwarding(false, "") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.btn_disable_forward), fontSize = 11.sp)
                    }
                }
            }
        }

        // Language Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SlateCardBorder, RoundedCornerShape(18.dp))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.language_title), fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "system" to stringResource(R.string.lang_system),
                        "en" to stringResource(R.string.lang_en),
                        "fa" to stringResource(R.string.lang_fa)
                    ).forEach { (code, label) ->
                        FilterChip(
                            selected = currentLanguage == code,
                            onClick = { onLanguageChange(code) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanAccent.copy(alpha = 0.2f),
                                selectedLabelColor = CyanAccent
                            ),
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
            }
        }

        // Diagnostics & Unpair
        OutlinedButton(
            onClick = onOpenDiagnostics,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.diagnostics_title))
        }

        Button(
            onClick = onUnpair,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(stringResource(R.string.btn_unpair), color = Color.White)
        }
    }
}

@Composable
private fun QrPairingDialog(qrPayload: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val qrBitmap: Bitmap? = remember(qrPayload) { QrGenerator.generate(qrPayload, 520) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateCard,
        title = { Text(stringResource(R.string.qr_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.qr_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(12.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Pairing QR",
                            modifier = Modifier.size(210.dp)
                        )
                    }
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(qrPayload)) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy QR JSON")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_close))
            }
        }
    )
}

@Composable
private fun DiagnosticsDialog(logs: List<String>, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateCard,
        title = { Text(stringResource(R.string.diagnostics_title), fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(logs.joinToString("\n"))) }) {
                        Text(stringResource(R.string.btn_copy_logs))
                    }
                    TextButton(onClick = { BridgeStore.clearLogs() }) {
                        Text(stringResource(R.string.btn_clear_logs))
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF070A14))
                        .padding(10.dp)
                ) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(logs) { line ->
                            Text(
                                text = line,
                                color = Color(0xFF6EE7B7),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_close))
            }
        }
    )
}
