package org.openbridge.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenBridgeApp(
    allPermissionsGranted: Boolean,
    missingPermissionsList: List<String>,
    onRequestPermissions: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onStartBridge: () -> Unit,
    onStopBridge: () -> Unit,
    onSendTestSms: () -> Unit,
    onSendTestCall: () -> Unit,
    onUnpair: () -> Unit,
    onLanguageChange: (String) -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val connectionState by BridgeStore.connectionState.collectAsState()
    val pairedPeer by BridgeStore.pairedPeerName.collectAsState()
    val qrPayload by BridgeStore.qrPayload.collectAsState()
    val events by BridgeStore.events.collectAsState()
    val logs by BridgeStore.logs.collectAsState()
    val currentLanguage by BridgeStore.language.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        val badgeColor = when (connectionState) {
                            BleConnectionState.CONNECTED -> Color(0xFF2E7D32)
                            BleConnectionState.ADVERTISING -> Color(0xFFEF6C00)
                            BleConnectionState.STOPPED -> Color(0xFF757575)
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(badgeColor)
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        Icon(
                            if (connectionState == BleConnectionState.CONNECTED) {
                                Icons.Default.BluetoothConnected
                            } else {
                                Icons.Default.BluetoothSearching
                            },
                            contentDescription = null
                        )
                    },
                    label = { Text(stringResource(R.string.tab_bridge)) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_activity)) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
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
                qrPayload = qrPayload,
                allPermissionsGranted = allPermissionsGranted,
                missingPermissionsList = missingPermissionsList,
                onRequestPermissions = onRequestPermissions,
                onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
                onStartBridge = onStartBridge,
                onStopBridge = onStopBridge,
                onSendTestSms = onSendTestSms,
                onSendTestCall = onSendTestCall
            )
            1 -> ActivityTab(
                modifier = Modifier.padding(padding),
                events = events
            )
            else -> SettingsAndLogsTab(
                modifier = Modifier.padding(padding),
                logs = logs,
                currentLanguage = currentLanguage,
                onLanguageChange = onLanguageChange,
                onUnpair = onUnpair
            )
        }
    }
}

@Composable
private fun BridgeHomeTab(
    modifier: Modifier,
    connectionState: BleConnectionState,
    pairedPeer: String?,
    qrPayload: String,
    allPermissionsGranted: Boolean,
    missingPermissionsList: List<String>,
    onRequestPermissions: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onStartBridge: () -> Unit,
    onStopBridge: () -> Unit,
    onSendTestSms: () -> Unit,
    onSendTestCall: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val qrBitmap: Bitmap? = remember(qrPayload) { QrGenerator.generate(qrPayload, 520) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Status Card
        val statusColor = when (connectionState) {
            BleConnectionState.CONNECTED -> Color(0xFFE8F5E9)
            BleConnectionState.ADVERTISING -> Color(0xFFFFF3E0)
            BleConnectionState.STOPPED -> MaterialTheme.colorScheme.surfaceVariant
        }
        val statusText = when (connectionState) {
            BleConnectionState.CONNECTED -> stringResource(R.string.status_connected)
            BleConnectionState.ADVERTISING -> stringResource(R.string.status_advertising)
            BleConnectionState.STOPPED -> stringResource(R.string.status_stopped)
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = statusColor),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(statusText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = if (pairedPeer != null) {
                        stringResource(R.string.paired_badge, pairedPeer)
                    } else {
                        stringResource(R.string.not_paired_badge)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (connectionState == BleConnectionState.STOPPED) {
                        Button(onClick = onStartBridge, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.btn_start_bridge))
                        }
                    } else {
                        OutlinedButton(onClick = onStopBridge, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.btn_stop_bridge))
                        }
                    }
                }
            }
        }

        // Permissions Card
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.permissions_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (allPermissionsGranted) {
                    Text(
                        stringResource(R.string.permissions_all_ok),
                        color = Color(0xFF2E7D32),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        "${stringResource(R.string.permissions_missing)}: ${missingPermissionsList.joinToString(", ")}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.btn_grant_permissions))
                    }
                }
                OutlinedButton(onClick = onRequestBatteryUnrestricted, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.btn_battery_optimization))
                }
            }
        }

        // QR Code Pairing Card
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.qr_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { clipboard.setText(AnnotatedString(qrPayload)) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy QR")
                    }
                }
                Text(stringResource(R.string.qr_subtitle), style = MaterialTheme.typography.bodySmall)
                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(10.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Pairing QR Code",
                            modifier = Modifier.size(210.dp)
                        )
                    }
                }
            }
        }

        // Self-Test Controls
        if (connectionState == BleConnectionState.CONNECTED) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onSendTestSms, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.btn_test_sms), fontSize = 12.sp)
                }
                OutlinedButton(onClick = onSendTestCall, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.btn_test_call), fontSize = 12.sp)
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
            Text(stringResource(R.string.activity_empty))
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(events, key = { it.id }) { event ->
            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = if (event.kind == "SMS") Icons.Default.Sms else Icons.Default.Call,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(event.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                timeFmt.format(Date(event.timestamp)),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Text(event.subtitle, style = MaterialTheme.typography.bodySmall)
                        if (!event.otp.isNullOrBlank()) {
                            Text(
                                text = stringResource(R.string.otp_label, event.otp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsAndLogsTab(
    modifier: Modifier,
    logs: List<String>,
    currentLanguage: String,
    onLanguageChange: (String) -> Unit,
    onUnpair: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.language_title), fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = currentLanguage == "system",
                        onClick = { onLanguageChange("system") },
                        label = { Text(stringResource(R.string.lang_system), fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = currentLanguage == "en",
                        onClick = { onLanguageChange("en") },
                        label = { Text(stringResource(R.string.lang_en), fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = currentLanguage == "fa",
                        onClick = { onLanguageChange("fa") },
                        label = { Text(stringResource(R.string.lang_fa), fontSize = 12.sp) }
                    )
                }
            }
        }

        Button(
            onClick = onUnpair,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.btn_unpair))
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Debug Logs", fontWeight = FontWeight.SemiBold)
            Row {
                TextButton(onClick = { clipboard.setText(AnnotatedString(logs.joinToString("\n"))) }) {
                    Text(stringResource(R.string.btn_copy_logs))
                }
                TextButton(onClick = { BridgeStore.clearLogs() }) {
                    Text(stringResource(R.string.btn_clear_logs))
                }
            }
        }

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121212)),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logs) { line ->
                    Text(
                        text = line,
                        color = Color(0xFFB9F6CA),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
