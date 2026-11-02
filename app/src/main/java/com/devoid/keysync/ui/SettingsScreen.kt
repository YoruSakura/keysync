package com.devoid.keysync.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import com.devoid.keysync.R
import com.devoid.keysync.data.external.RootInputConnection
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.InputMode
import com.devoid.keysync.model.LanguagePreference
import com.devoid.keysync.model.ThemePreference
import com.devoid.keysync.model.TouchMode
import com.devoid.keysync.util.keyCodeToString
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    appConfig: AppConfig = AppConfig.Default,
    rootConnectionStatus: RootInputConnection.Status = RootInputConnection.Status.DISCONNECTED,
    rootCaptureStatus: RootInputConnection.CaptureStatus = RootInputConnection.CaptureStatus.Idle,
    onRetryRoot: () -> Unit = {},
    onExportBackup: () -> Unit = {},
    onImportBackup: () -> Unit = {},
    onExportTouchDiagnostics: () -> Unit = {},
    onSave: (AppConfig) -> Unit = {},
    onNavigateBack: () -> Unit = {},
    onNavigateAbout: () -> Unit = {}
) {
    var newConfig by remember(appConfig) { mutableStateOf(appConfig) }
    val unknownKey = stringResource(R.string.unknown_key)
    val leftMouseButton = stringResource(R.string.left_mouse_button)
    val rightMouseButton = stringResource(R.string.right_mouse_button)
    val middleMouseButton = stringResource(R.string.middle_mouse_button)
    fun Int.localizedKeyName() = keyCodeToString(
        unknownLabel = unknownKey,
        leftMouseButtonLabel = leftMouseButton,
        rightMouseButtonLabel = rightMouseButton,
        middleMouseButtonLabel = middleMouseButton
    )
    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(text = stringResource(R.string.settings_title))
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }, actions = {
                    Button(modifier = Modifier.padding(end = 8.dp), enabled = newConfig != appConfig, onClick = {
                        onSave(newConfig)
                    }) {
                        Text(stringResource(R.string.save))
                    }
                })
        }
    ) { innerPadding ->
        val scrollState = rememberScrollState()
        Box(
            Modifier
                .padding(innerPadding)
                .padding(8.dp)
                .verticalScroll(state = scrollState)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (com.devoid.keysync.BuildConfig.TOUCH_CORE) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.touch_core_title), style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.mumu_profiles_location), style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = onExportTouchDiagnostics) { Text(stringResource(R.string.export_touch_diagnostics)) }
                        }
                    }
                }
                InputModeSettingsCard(
                    selectedMode = newConfig.inputMode,
                    rootConnectionStatus = rootConnectionStatus,
                    rootCaptureStatus = rootCaptureStatus,
                    onModeSelected = { newConfig = newConfig.copy(inputMode = it) },
                    onRetryRoot = onRetryRoot
                )
                Card {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            stringResource(R.string.key_configuration),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row {
                            KeyConfigTextField(
                                title = stringResource(R.string.toggle_aiming_mode),
                                value = newConfig.shootingModeKeyCode.localizedKeyName(),
                                onMouseKeyEvent = {
                                    newConfig = newConfig.copy(shootingModeKeyCode = it)
                                }
                            ) {
                                newConfig=newConfig.copy(shootingModeKeyCode = it.nativeKeyEvent.keyCode)
                                return@KeyConfigTextField true
                            }
                        }
                        HorizontalDivider()
                        KeyConfigTextField(
                            title = stringResource(R.string.fire_action),
                            value = newConfig.fireKeyCode.localizedKeyName(),
                            onMouseKeyEvent = {
                                newConfig = newConfig.copy(fireKeyCode = it)
                            }
                        ) {
                            newConfig=newConfig.copy(fireKeyCode = it.nativeKeyEvent.keyCode)
                            return@KeyConfigTextField true
                        }
                        HorizontalDivider()
                        KeyConfigTextField(
                            title = stringResource(R.string.scope_action),
                            value = newConfig.scopeKeyCode.localizedKeyName(),
                            onMouseKeyEvent = {
                                newConfig = newConfig.copy(scopeKeyCode = it)
                            }
                        ) {
                            newConfig=newConfig.copy(scopeKeyCode = it.nativeKeyEvent.keyCode)
                            return@KeyConfigTextField true
                        }
                    }
                }
                Card {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.mapped_button_size),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text("${(newConfig.buttonScale * 100).roundToInt()} %")
                        }
                        Slider(value = newConfig.buttonScale,
                            valueRange = 0f..2f, steps = 0, onValueChange = {
                                newConfig = newConfig.copy(buttonScale = it)
                            })
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            Text(
                                stringResource(R.string.delete_mapping_data),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                modifier = Modifier.padding(top = 8.dp),
                                text = stringResource(R.string.delete_mapping_data_description),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = newConfig.deleteDataOnRemove, onCheckedChange = {
                            newConfig = newConfig.copy(deleteDataOnRemove = it)
                        })
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    var cancellableDropDownExpanded by remember { mutableStateOf(false) }

                    ConstraintLayout(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        val (title,
                            cancelTitle,
                            cancelBody,
                            cancelSelector) = createRefs()
                        Text(
                            stringResource(R.string.touch_behavior),
                            modifier = Modifier.constrainAs(title) {
                                top.linkTo(parent.top)
                                start.linkTo(parent.start)
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            stringResource(R.string.cancelable_button),
                            modifier = Modifier
                                .constrainAs(cancelTitle) {
                                    top.linkTo(title.bottom)
                                }
                                .padding(top = 8.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            modifier = Modifier
                                .constrainAs(cancelBody) {
                                    top.linkTo(cancelTitle.bottom)
                                }
                                .padding(top = 8.dp),
                            text = stringResource(R.string.cancelable_button_description),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedCard(modifier = Modifier.constrainAs(cancelSelector) {
                            bottom.linkTo(cancelBody.bottom)
                            top.linkTo(cancelTitle.top)
                            end.linkTo(parent.end)
                        }, onClick = { cancellableDropDownExpanded = true }) {
                            Row(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .widthIn(min = 60.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    newConfig.cancellableTouchMode.localizedName(),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = cancellableDropDownExpanded,
                                onDismissRequest = { cancellableDropDownExpanded = false }) {
                                TouchMode.entries.forEach {
                                    DropdownMenuItem(
                                        text = { Text(it.localizedName()) },
                                        onClick = {
                                            newConfig = newConfig.copy(cancellableTouchMode = it)
                                            cancellableDropDownExpanded = false
                                        })
                                }
                            }
                        }
                    }


                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    var dropDownExpanded by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            Text(
                                stringResource(R.string.theme),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                            Text(
                                modifier = Modifier.padding(top = 8.dp),
                                text = stringResource(R.string.theme_description),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedCard(onClick = { dropDownExpanded = true }) {
                            Row(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .widthIn(min = 60.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    newConfig.themePreference.localizedName(),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = dropDownExpanded,
                                onDismissRequest = { dropDownExpanded = false }) {
                                ThemePreference.entries.forEach {
                                    DropdownMenuItem(
                                        text = { Text(it.localizedName()) },
                                        onClick = {
                                            newConfig = newConfig.copy(themePreference = it)
                                            dropDownExpanded = false
                                        })
                                }
                            }
                        }

                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    var languageDropDownExpanded by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            Text(
                                stringResource(R.string.language),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                            Text(
                                modifier = Modifier.padding(top = 8.dp),
                                text = stringResource(R.string.language_description),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedCard(onClick = { languageDropDownExpanded = true }) {
                            Row(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .widthIn(min = 60.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    newConfig.languagePreference.localizedName(),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = languageDropDownExpanded,
                                onDismissRequest = { languageDropDownExpanded = false }
                            ) {
                                LanguagePreference.entries.forEach {
                                    DropdownMenuItem(
                                        text = { Text(it.localizedName()) },
                                        onClick = {
                                            newConfig = newConfig.copy(languagePreference = it)
                                            languageDropDownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = {
                    newConfig = AppConfig.Default
                }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Text(
                            modifier = Modifier.padding(start = 16.dp),
                            text = stringResource(R.string.reset_to_defaults),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.mapping_backup),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.mapping_backup_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onExportBackup, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.export_backup))
                            }
                            OutlinedButton(onClick = onImportBackup, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.restore_backup))
                            }
                        }
                    }
                }
                FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = onNavigateAbout) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Icon(Icons.Rounded.Info, contentDescription = null)
                        Text(
                            modifier = Modifier.padding(start = 16.dp),
                            text = stringResource(R.string.about),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InputModeSettingsCard(
    selectedMode: InputMode,
    rootConnectionStatus: RootInputConnection.Status,
    rootCaptureStatus: RootInputConnection.CaptureStatus,
    onModeSelected: (InputMode) -> Unit,
    onRetryRoot: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.input_mode),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.selectableGroup()) {
                InputModeOption(
                    selected = selectedMode == InputMode.ROOT,
                    title = stringResource(R.string.input_mode_root),
                    description = stringResource(R.string.input_mode_root_description),
                    onClick = { onModeSelected(InputMode.ROOT) }
                )
                HorizontalDivider()
                InputModeOption(
                    selected = selectedMode == InputMode.SHIZUKU,
                    title = stringResource(R.string.input_mode_shizuku),
                    description = stringResource(R.string.input_mode_shizuku_description),
                    onClick = { onModeSelected(InputMode.SHIZUKU) }
                )
            }
            Text(
                text = stringResource(R.string.root_service_status),
                style = MaterialTheme.typography.titleMedium
            )
            val connectionText = when (rootConnectionStatus) {
                RootInputConnection.Status.READY -> stringResource(R.string.root_status_ready)
                RootInputConnection.Status.CONNECTING -> stringResource(R.string.root_status_connecting)
                RootInputConnection.Status.DISCONNECTED -> {
                    stringResource(R.string.root_status_unavailable)
                }
            }
            val connectionColor = when (rootConnectionStatus) {
                RootInputConnection.Status.READY -> MaterialTheme.colorScheme.primary
                RootInputConnection.Status.CONNECTING -> MaterialTheme.colorScheme.onSurfaceVariant
                RootInputConnection.Status.DISCONNECTED -> MaterialTheme.colorScheme.error
            }
            Text(
                text = connectionText,
                style = MaterialTheme.typography.bodyMedium,
                color = connectionColor
            )
            if (rootConnectionStatus == RootInputConnection.Status.READY) {
                Text(
                    text = rootCaptureStatus.localizedDescription(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (rootCaptureStatus is RootInputConnection.CaptureStatus.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            } else if (rootConnectionStatus != RootInputConnection.Status.CONNECTING) {
                FilledTonalButton(onClick = onRetryRoot) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = stringResource(R.string.retry_root)
                    )
                }
            }
        }
    }
}

@Composable
private fun InputModeOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                modifier = Modifier.padding(top = 4.dp),
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RootInputConnection.CaptureStatus.localizedDescription(): String = when (this) {
    RootInputConnection.CaptureStatus.Idle -> stringResource(R.string.root_capture_idle)
    is RootInputConnection.CaptureStatus.Starting -> {
        pluralStringResource(
            R.plurals.root_capture_starting,
            candidateDeviceCount,
            candidateDeviceCount
        )
    }
    is RootInputConnection.CaptureStatus.Active -> {
        pluralStringResource(R.plurals.root_capture_active, deviceCount, deviceCount)
    }
    is RootInputConnection.CaptureStatus.Failed -> stringResource(
        when (reason) {
            RootInputConnection.CaptureFailure.NO_ANDROID_INPUT_DEVICES -> {
                R.string.root_capture_no_android_devices
            }
            RootInputConnection.CaptureFailure.ROOT_SERVICE_LOST -> R.string.root_capture_root_lost
            RootInputConnection.CaptureFailure.SERVICE_REJECTED -> R.string.root_capture_rejected
            RootInputConnection.CaptureFailure.NO_MATCHED_INPUT_NODES -> {
                R.string.root_capture_no_matched_nodes
            }
            RootInputConnection.CaptureFailure.NO_KEYBOARD_NODE -> {
                R.string.root_capture_no_keyboard_node
            }
            RootInputConnection.CaptureFailure.NO_MOUSE_MOTION_NODE -> {
                R.string.root_capture_no_mouse_motion_node
            }
            RootInputConnection.CaptureFailure.NO_MOUSE_BUTTON_NODE -> {
                R.string.root_capture_no_mouse_button_node
            }
            RootInputConnection.CaptureFailure.NO_COMPLETE_KEYBOARD_MOUSE_PAIR -> {
                R.string.root_capture_no_pair
            }
            RootInputConnection.CaptureFailure.UINPUT_UNAVAILABLE -> {
                R.string.root_capture_uinput_unavailable
            }
            RootInputConnection.CaptureFailure.DEVICE_PERMISSION_DENIED -> {
                R.string.root_capture_permission_denied
            }
            RootInputConnection.CaptureFailure.CAPTURE_BUSY -> R.string.root_capture_busy
            RootInputConnection.CaptureFailure.NATIVE_ERROR -> R.string.root_capture_native_error
        }
    )
}

@Composable
private fun TouchMode.localizedName(): String = stringResource(
    when (this) {
        TouchMode.TAP -> R.string.touch_mode_tap
        TouchMode.HOLD -> R.string.touch_mode_hold
        TouchMode.MIXED -> R.string.touch_mode_mixed
    }
)

@Composable
private fun ThemePreference.localizedName(): String = stringResource(
    when (this) {
        ThemePreference.DARK -> R.string.theme_dark
        ThemePreference.LIGHT -> R.string.theme_light
        ThemePreference.SYSTEM -> R.string.theme_system
        ThemePreference.SYSTEM_DYNAMIC -> R.string.theme_system_dynamic
    }
)

@Composable
private fun LanguagePreference.localizedName(): String = stringResource(
    when (this) {
        LanguagePreference.SYSTEM -> R.string.language_system
        LanguagePreference.SIMPLIFIED_CHINESE -> R.string.language_simplified_chinese
        LanguagePreference.ENGLISH -> R.string.language_english
    }
)

@Composable
fun KeyConfigTextField(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    onMouseKeyEvent: (Int) -> Unit = {},
    onKeyEvent: (KeyEvent) -> Boolean
) {
    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    Row {
        Text(
            modifier = modifier
                .fillMaxWidth()
                .weight(1f),
            text = title,
            style = MaterialTheme.typography.titleMedium
        )
        OutlinedCard(border = BorderStroke(
            1.dp,
            color = if (hasFocus) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            }
        ),
            modifier = Modifier
                .captureMouseButtonBinding(hasFocus, onMouseKeyEvent)
                .clickable { focusRequester.requestFocus() }) {
            Text(
                modifier = Modifier
                    .padding(vertical = 8.dp, horizontal = 16.dp)
                    .focusRequester(focusRequester)
                    .onKeyEvent { onKeyEvent(it) }
                    .onFocusChanged { hasFocus = it.isFocused }
                    .focusable(),
                text = value,
                style = TextStyle(color = LocalContentColor.current),
            )
        }
    }
}
