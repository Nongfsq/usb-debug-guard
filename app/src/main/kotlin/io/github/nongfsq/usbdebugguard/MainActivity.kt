package io.github.nongfsq.usbdebugguard

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PhoneForwarded
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    internal val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        GuardPrefs.setLastAction(this, if (granted) "notification-permission:granted" else "notification-permission:denied")
        if (!granted) {
            Toast.makeText(this, R.string.toast_notification_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UsbDebugGuardTheme {
                GuardScreen()
            }
        }
    }
}

data class UiState(
    val serviceEnabled: Boolean,
    val guarded: Boolean,
    val mode: GuardMode,
    val requireAdb: Boolean,
    val lockOnDisconnect: Boolean,
    val dismissKeyguard: Boolean,
    val localeMode: LocaleMode,
    val report: GuardReport,
    val lastAction: String,
    val notificationsAvailable: Boolean,
)

@Composable
fun UsbDebugGuardTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme(
            primary = Color(0xFF8BB8FF),
            secondary = Color(0xFFB8C7DB),
            tertiary = Color(0xFFD9C4A2),
        )
        else -> lightColorScheme(
            primary = Color(0xFF005DBA),
            secondary = Color(0xFF526070),
            tertiary = Color(0xFF6F5B2C),
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf(readUiState(context, refreshProbe = false)) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    val updateChecker = remember {
        GitHubReleaseUpdateChecker(
            feedClient = GitHubReleaseFeedClient("USB-Debug-Guard/${BuildConfig.VERSION_NAME}"),
            currentVersion = BuildConfig.VERSION_NAME,
        )
    }

    DisposableEffect(context, lifecycleOwner) {
        val preferences = GuardPrefs.prefs(context)
        val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            scope.launch {
                uiState = withContext(Dispatchers.IO) {
                    readUiState(context, refreshProbe = true)
                }
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    uiState = withContext(Dispatchers.IO) {
                        readUiState(context, refreshProbe = true)
                    }
                }
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.screen_main_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                StatusCard(uiState.report.status)
            }
            item {
                SectionTitle(R.string.section_protection)
                Card(shape = RoundedCornerShape(24.dp)) {
                    SwitchItem(
                        icon = Icons.Filled.PowerSettingsNew,
                        title = stringResource(R.string.switch_guard_enabled),
                        summary = stringResource(R.string.switch_guard_summary),
                        checked = uiState.serviceEnabled,
                        onCheckedChange = { enabled ->
                            GuardPrefs.setServiceEnabled(context, enabled)
                            if (enabled) {
                                requestNotificationPermissionIfNeeded(context)
                                startGuardService(context, GuardService.ACTION_START)
                            } else {
                                startGuardService(context, GuardService.ACTION_STOP)
                            }
                            uiState = readUiState(context, refreshProbe = false)
                        },
                    )
                }
            }
            item {
                SectionTitle(R.string.section_behavior)
                Card(shape = RoundedCornerShape(24.dp)) {
                    RadioItem(
                        icon = Icons.Filled.Security,
                        title = stringResource(R.string.mode_screen_off),
                        summary = stringResource(R.string.mode_screen_off_summary),
                        selected = uiState.mode == GuardMode.ScreenOff,
                        onClick = {
                            GuardPrefs.setGuardMode(context, GuardMode.ScreenOff)
                            startGuardService(context, GuardService.ACTION_REFRESH)
                            uiState = readUiState(context, refreshProbe = false)
                        },
                    )
                    RadioItem(
                        icon = Icons.Filled.Smartphone,
                        title = stringResource(R.string.mode_dim_awake),
                        summary = stringResource(R.string.mode_dim_awake_summary),
                        selected = uiState.mode == GuardMode.DimAwake,
                        onClick = {
                            GuardPrefs.setGuardMode(context, GuardMode.DimAwake)
                            startGuardService(context, GuardService.ACTION_REFRESH)
                            uiState = readUiState(context, refreshProbe = false)
                        },
                    )
                    SwitchItem(
                        icon = Icons.Filled.Code,
                        title = stringResource(R.string.option_require_adb),
                        summary = stringResource(R.string.option_require_adb_summary),
                        checked = uiState.requireAdb,
                        onCheckedChange = {
                            GuardPrefs.setRequireAdb(context, it)
                            startGuardService(context, GuardService.ACTION_REFRESH)
                            uiState = readUiState(context, refreshProbe = false)
                        },
                    )
                    SwitchItem(
                        icon = Icons.Filled.Lock,
                        title = stringResource(R.string.option_lock_on_disconnect),
                        summary = stringResource(R.string.option_lock_on_disconnect_summary),
                        checked = uiState.lockOnDisconnect,
                        onCheckedChange = {
                            GuardPrefs.setLockOnDisconnect(context, it)
                            uiState = readUiState(context, refreshProbe = false)
                        },
                    )
                    SwitchItem(
                        icon = Icons.AutoMirrored.Filled.PhoneForwarded,
                        title = stringResource(R.string.option_dismiss_keyguard),
                        summary = stringResource(R.string.option_dismiss_keyguard_summary),
                        checked = uiState.dismissKeyguard,
                        onCheckedChange = {
                            GuardPrefs.setDismissKeyguard(context, it)
                            startGuardService(context, GuardService.ACTION_REFRESH)
                            uiState = readUiState(context, refreshProbe = false)
                        },
                    )
                }
            }
            item {
                SectionTitle(R.string.section_language)
                Card(shape = RoundedCornerShape(24.dp)) {
                    RadioItem(
                        icon = Icons.Filled.Translate,
                        title = stringResource(R.string.language_system),
                        summary = stringResource(R.string.language_system_summary),
                        selected = uiState.localeMode == LocaleMode.System,
                        onClick = {
                            LocaleHelper.apply(context, LocaleMode.System)
                            context.findMainActivity()?.recreate()
                        },
                    )
                    RadioItem(
                        icon = Icons.Filled.Translate,
                        title = stringResource(R.string.language_english),
                        summary = stringResource(R.string.language_english_summary),
                        selected = uiState.localeMode == LocaleMode.English,
                        onClick = {
                            LocaleHelper.apply(context, LocaleMode.English)
                            context.findMainActivity()?.recreate()
                        },
                    )
                    RadioItem(
                        icon = Icons.Filled.Translate,
                        title = stringResource(R.string.language_simplified_chinese),
                        summary = stringResource(R.string.language_simplified_chinese_summary),
                        selected = uiState.localeMode == LocaleMode.SimplifiedChinese,
                        onClick = {
                            LocaleHelper.apply(context, LocaleMode.SimplifiedChinese)
                            context.findMainActivity()?.recreate()
                        },
                    )
                }
            }
            item {
                SectionTitle(R.string.section_updates)
                Card(shape = RoundedCornerShape(24.dp)) {
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
                        headlineContent = {
                            Text(
                                stringResource(R.string.update_current_version, BuildConfig.VERSION_NAME),
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        supportingContent = {
                            Text(
                                when {
                                    updateChecking -> stringResource(R.string.update_checking)
                                    updateResult is UpdateCheckResult.Available -> stringResource(
                                        R.string.update_available,
                                        (updateResult as UpdateCheckResult.Available).version,
                                    )
                                    updateResult is UpdateCheckResult.UpToDate -> stringResource(
                                        R.string.update_up_to_date,
                                        (updateResult as UpdateCheckResult.UpToDate).latestVersion,
                                    )
                                    updateResult is UpdateCheckResult.Failed -> stringResource(R.string.update_failed)
                                    else -> stringResource(R.string.update_manual_summary)
                                }
                            )
                        },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FilledTonalButton(
                            modifier = Modifier.weight(1f),
                            enabled = !updateChecking,
                            onClick = {
                                updateChecking = true
                                scope.launch {
                                    updateResult = withContext(Dispatchers.IO) { updateChecker.check() }
                                    updateChecking = false
                                }
                            },
                        ) {
                            Text(stringResource(R.string.button_check_updates))
                        }
                        val available = updateResult as? UpdateCheckResult.Available
                        if (available != null) {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    openReleasePage(context, available.releaseUrl)
                                },
                            ) {
                                Text(stringResource(R.string.button_open_release))
                            }
                        }
                    }
                }
            }
            item {
                SectionTitle(R.string.section_diagnostics)
                Card(shape = RoundedCornerShape(24.dp)) {
                    DiagnosticItem(
                        icon = Icons.Filled.Bolt,
                        label = stringResource(R.string.diag_root),
                        value = stringResource(
                            when {
                                uiState.report.circuit.open -> R.string.diag_root_circuit_open
                                uiState.report.circuit.lastOutcome == RootOutcome.Success -> R.string.diag_root_ready
                                uiState.report.circuit.lastOutcome == RootOutcome.Denied -> R.string.diag_root_missing
                                uiState.report.circuit.lastOutcome == RootOutcome.Exception -> R.string.diag_root_unavailable
                                else -> R.string.diag_root_not_tested
                            }
                        ),
                    )
                    DiagnosticItem(
                        icon = Icons.Filled.Smartphone,
                        label = stringResource(R.string.diag_usb),
                        value = stringResource(if (uiState.report.probe.usbConnected) R.string.diag_usb_connected else R.string.diag_usb_disconnected),
                    )
                    DiagnosticItem(
                        icon = Icons.Filled.Code,
                        label = stringResource(R.string.diag_adb),
                        value = stringResource(if (uiState.report.probe.adbEnabled) R.string.diag_adb_enabled else R.string.diag_adb_disabled),
                    )
                    DiagnosticItem(
                        icon = Icons.Filled.Settings,
                        label = stringResource(R.string.diag_mode),
                        value = stringResource(if (uiState.mode == GuardMode.ScreenOff) R.string.mode_screen_off else R.string.mode_dim_awake),
                    )
                    DiagnosticItem(
                        icon = Icons.Filled.Security,
                        label = stringResource(R.string.diag_service),
                        value = stringResource(if (uiState.serviceEnabled) R.string.diag_service_on else R.string.diag_service_off),
                    )
                    DiagnosticItem(
                        icon = Icons.Filled.Warning,
                        label = stringResource(R.string.diag_notifications),
                        value = stringResource(
                            if (uiState.notificationsAvailable) {
                                R.string.diag_notifications_available
                            } else {
                                R.string.diag_notifications_blocked
                            }
                        ),
                    )
                    DiagnosticItem(
                        icon = Icons.Filled.Refresh,
                        label = stringResource(R.string.diag_last_action),
                        value = uiState.lastAction.ifBlank { stringResource(R.string.diag_none) },
                    )
                }
            }
            item {
                SectionTitle(R.string.section_actions)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        enabled = uiState.report.circuit.open && !uiState.report.circuit.manualRetryConsumed,
                        onClick = {
                            startGuardService(context, GuardService.ACTION_RETRY_ROOT)
                            Toast.makeText(context, R.string.toast_root_retry_requested, Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text(stringResource(R.string.button_retry_root))
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            startGuardService(context, GuardService.ACTION_LOCK_NOW)
                            Toast.makeText(context, R.string.toast_lock_requested, Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text(stringResource(R.string.button_lock_now))
                    }
                }
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        startGuardService(context, GuardService.ACTION_REFRESH)
                        uiState = readUiState(context, refreshProbe = false)
                    },
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.button_refresh))
                }
            }
        }
    }
}

private fun requestNotificationPermissionIfNeeded(context: Context) {
    val activity = context.findMainActivity() ?: return
    if (Build.VERSION.SDK_INT >= 33 &&
        activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        activity.notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun StatusCard(status: GuardStatus) {
    val statusColor = when (status) {
        GuardStatus.Protected -> MaterialTheme.colorScheme.primary
        GuardStatus.RootRequired,
        GuardStatus.RootUnavailable,
        GuardStatus.CircuitOpen,
        GuardStatus.RestoreFailed -> MaterialTheme.colorScheme.error
        GuardStatus.WaitingUsb, GuardStatus.WaitingAdb -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (status == GuardStatus.Protected) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = statusColor,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(statusTitle(status)),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(statusSummary(status)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.screen_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun SwitchItem(
    icon: ImageVector,
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(summary) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}

@Composable
private fun RadioItem(
    icon: ImageVector,
    title: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(summary) },
        trailingContent = {
            RadioButton(selected = selected, onClick = onClick)
        },
    )
}

@Composable
private fun DiagnosticItem(icon: ImageVector, label: String, value: String) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(label) },
        trailingContent = {
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
    )
}

private fun statusTitle(status: GuardStatus): Int = when (status) {
    GuardStatus.Protected -> R.string.status_protected
    GuardStatus.Idle -> R.string.status_idle
    GuardStatus.WaitingUsb -> R.string.status_waiting_usb
    GuardStatus.WaitingAdb -> R.string.status_waiting_adb
    GuardStatus.RootRequired -> R.string.status_root_required
    GuardStatus.RootUnavailable -> R.string.status_root_unavailable
    GuardStatus.CircuitOpen -> R.string.status_circuit_open
    GuardStatus.RestoreFailed -> R.string.status_restore_failed
    GuardStatus.Unknown -> R.string.status_unknown
}

private fun statusSummary(status: GuardStatus): Int = when (status) {
    GuardStatus.Protected -> R.string.status_protected_summary
    GuardStatus.Idle -> R.string.status_idle_summary
    GuardStatus.WaitingUsb -> R.string.status_waiting_usb_summary
    GuardStatus.WaitingAdb -> R.string.status_waiting_adb_summary
    GuardStatus.RootRequired -> R.string.status_root_required_summary
    GuardStatus.RootUnavailable -> R.string.status_root_unavailable_summary
    GuardStatus.CircuitOpen -> R.string.status_circuit_open_summary
    GuardStatus.RestoreFailed -> R.string.status_restore_failed_summary
    GuardStatus.Unknown -> R.string.status_unknown_summary
}

private fun readUiState(context: Context, refreshProbe: Boolean): UiState {
    val report = if (refreshProbe) {
        GuardStateMachine(context).refresh()
    } else {
        GuardReport(
            status = GuardPrefs.lastStatus(context),
            probe = UsbAdbState(usbConnected = false, adbEnabled = false),
            circuit = AndroidRootSafetyStore(context).snapshot(),
        )
    }
    return UiState(
        serviceEnabled = GuardPrefs.serviceEnabled(context),
        guarded = GuardPrefs.isGuarded(context),
        mode = GuardPrefs.guardMode(context),
        requireAdb = GuardPrefs.requireAdb(context),
        lockOnDisconnect = GuardPrefs.lockOnDisconnect(context),
        dismissKeyguard = GuardPrefs.dismissKeyguard(context),
        localeMode = GuardPrefs.localeMode(context),
        report = report,
        lastAction = GuardPrefs.lastAction(context),
        notificationsAvailable = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
    )
}

private fun startGuardService(context: Context, action: String) {
    val intent = Intent(context, GuardService::class.java).setAction(action)
    context.startForegroundService(intent)
}

private fun openReleasePage(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.toast_no_browser, Toast.LENGTH_LONG).show()
    }
}

private tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}
