package com.odininputmirror.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.odininputmirror.MainActivity
import com.odininputmirror.ui.theme.Palette
import kotlinx.coroutines.delay

private fun Context.findMainActivity(): MainActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is MainActivity) return current
        current = current.baseContext
    }
    return null
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MirrorScreen(viewModel: MirrorViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editorState by viewModel.mappingEditor.state.collectAsStateWithLifecycle()

    // A whole screen rather than an overlay: mapping is the only thing happening while it is open,
    // and the controller is answering it instead of driving whatever would show through behind.
    if (editorState.open) {
        MappingEditorScreen(
            state = editorState,
            onBind = { viewModel.mappingEditor.bind(it) },
            onClear = { viewModel.mappingEditor.clear(it) },
            onCancelBind = { viewModel.mappingEditor.cancelBind() },
            onClearAll = { viewModel.mappingEditor.clearAll() },
            onClose = { viewModel.mappingEditor.close() },
        )
        return
    }
    var pickerOpen by remember { mutableStateOf(false) }
    var externalPickerOpen by remember { mutableStateOf(false) }
    var screenHasFocus by remember { mutableStateOf(false) }
    // Initial gamepad focus prefers the first control (the Local Controller card) and falls back to
    // the always-present primary button for when the card isn't focusable (mirror on / busy).
    val topFocus = remember { FocusRequester() }
    val primaryFocus = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    // Bridge the Activity's analog-stick handler to this screen's scroll state so the right stick
    // free-scrolls the whole page (header included), independent of which control has focus.
    val context = LocalContext.current
    DisposableEffect(context, scrollState) {
        val activity = context.findMainActivity()
        activity?.scrollConsumer = { delta -> scrollState.dispatchRawDelta(delta) }
        onDispose { activity?.scrollConsumer = null }
    }

    // Anchor gamepad focus on the first control, leaving touch mode first. On a touchscreen handheld
    // the window starts in Touch input mode, in which requestFocus() is a silent no-op and the D-pad
    // and stick have no anchor to navigate from until a face button hands focus out.
    val inputModeManager = LocalInputModeManager.current
    val focusManager = LocalFocusManager.current
    // Reports whether a target actually took the focus. The caller needs to know: a request made
    // before the node is placed fails silently, and there is no other way to tell.
    val anchorFocus: () -> Boolean = remember(inputModeManager, topFocus, primaryFocus) {
        {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            runCatching { topFocus.requestFocus() }.isSuccess ||
                runCatching { primaryFocus.requestFocus() }.isSuccess
        }
    }

    // Re-anchor the instant the window gains focus. The launch-time effect below can lose the race
    // when the window isn't focused yet (e.g. while controllers are scanned), so without this the
    // D-pad/stick have no focus to move from until a button press hands it out.
    DisposableEffect(context, anchorFocus) {
        val activity = context.findMainActivity()
        // The Activity hook wants a plain callback; whether the anchor took is only the retry
        // loop's business.
        activity?.onWindowFocused = { anchorFocus() }
        onDispose { activity?.onWindowFocused = null }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Give the gamepad a focus anchor so the D-pad works immediately, retrying across a few frames
    // because the focus node is not necessarily placed on the first pass.
    //
    // Keyed on the editor: coming back from it this whole screen is composed again, and it must take
    // the focus back — the editor's rows are gone and nothing else holds it.
    //
    // The loop stops on OBSERVED focus, not on what requestFocus() reported. That distinction is the
    // whole bug: on the return trip the anchor node exists immediately, so the request "succeeds" on
    // the first frame while the focus goes nowhere, the loop congratulates itself and exits, and the
    // pad is left with nothing to navigate from.
    LaunchedEffect(editorState.open) {
        if (editorState.open) return@LaunchedEffect
        // Let go of the focus before asking for it. Leaving the editor destroys the node that held it,
        // and Compose is then left pointing at something that no longer exists — in that state a fresh
        // requestFocus() is refused outright, which is why the retry loop alone was not enough.
        focusManager.clearFocus(force = true)
        repeat(24) {
            if (screenHasFocus) return@LaunchedEffect
            anchorFocus()
            // Belt and braces: if neither named anchor will take it, ask the focus system for the
            // first thing it can find. Landing somewhere unexpected still beats landing nowhere, which
            // costs the user the whole app until they restart it.
            if (!screenHasFocus) focusManager.moveFocus(FocusDirection.Down)
            delay(50)
        }
    }

    // When a state change disables whatever had focus — toggling the mirror briefly makes the
    // button busy, and starting/stopping it locks the cards and rows — Compose clears focus and
    // the D-pad has nothing to navigate from. Re-anchor to the first available control so gamepad
    // navigation keeps working after every transition.
    LaunchedEffect(state.enabled, state.autoMirrorEnabled, state.busy) {
        anchorFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.screen)
            // Whether anything at all on this screen holds focus. `hasFocus` covers the whole subtree,
            // so one modifier on the root answers a question no FocusRequester can: requestFocus()
            // reports success as soon as its node exists, which is not the same as the node having
            // taken focus.
            .onFocusChanged { screenHasFocus = it.hasFocus },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            Header(enabled = state.enabled, docked = state.docked, loading = state.loading)

            Spacer(Modifier.height(24.dp))
            Text("Automatic Dock Mirror", color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Controllers are selected automatically while dock mode is active.",
                color = Palette.textSecondary,
                fontSize = 14.sp,
            )

            Spacer(Modifier.height(16.dp))
            // Equal heights: the row takes the tallest card's intrinsic height and both fill it. The
            // external card carries a mapping line and a button the local one does not, and without
            // this it simply grows past its neighbour.
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            ) {
                DeviceCard(
                    modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(topFocus),
                    title = "Local Controller",
                    device = state.localDevice,
                    hint = when {
                        state.autoMirrorEnabled -> "Locked while mirror is on"
                        state.hasKnownInternalProfile -> "Auto-detected · tap to change"
                        else -> "Tap to select internal"
                    },
                    enabled = !state.autoMirrorEnabled,
                    onClick = if (state.busy || state.autoMirrorEnabled) null else ({ pickerOpen = true }),
                )
                DeviceCard(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    title = "External Controller",
                    device = state.externalDevice,
                    hint = if (state.manualExternalGuid == null) {
                        "First connected · tap to change"
                    } else {
                        "Selected manually · tap to change"
                    },
                    enabled = true,
                    onClick = if (state.busy) null else ({ externalPickerOpen = true }),
                    hasCustomMapping = (state.mappedControlCount ?: 0) > 0,
                    hasSeededMapping = state.hasSeededMapping,
                    // Only offered once the controller has an identity to save a mapping against.
                    onEditMapping = if (state.externalDevice?.mappingKey != null) {
                        { viewModel.openMappingEditor() }
                    } else {
                        null
                    },
                )
            }

            Spacer(Modifier.height(20.dp))
            SettingRow(
                title = "Home as Back",
                description = "External controller Home button acts as Back",
                checked = state.homeAsBack,
                enabled = !state.busy,
                onCheckedChange = { viewModel.toggleHomeAsBack(it) },
            )
            Spacer(Modifier.height(12.dp))
            SettingRow(
                title = "Select + Start closes app",
                description = "Hold Select and Start for 3 seconds to close the current app",
                checked = state.comboHoldKillApp,
                enabled = !state.busy,
                onCheckedChange = { viewModel.toggleComboHoldKillApp(it) },
            )

            Spacer(Modifier.height(12.dp))
            SettingRow(
                title = "Virtual mouse",
                description = "Turn the external controller into a mouse. Hold Select + right-stick (R3) to switch in or out. " +
                    "Left stick moves the pointer, right stick scrolls, A left-clicks, B right-clicks, " +
                    "R1 opens/closes the notification shade.",
                checked = state.virtualMouse,
                enabled = !state.busy,
                onCheckedChange = { viewModel.toggleVirtualMouse(it) },
            )

            Spacer(Modifier.height(16.dp))
            Text(
                if (state.loading) "Scanning controllers..."
                else if (state.restartWaiting) "Waiting for automatic mirror start..."
                else state.message,
                color = Palette.textSecondary,
                fontSize = 14.sp,
            )
            if (!state.loading && !state.enabled && state.autoMirrorEnabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "The mirror starts automatically when the internal controller and an external controller are available.",
                    color = Palette.textMuted,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = when {
                    state.busy -> "Processing..."
                    state.autoMirrorEnabled -> "Turn Off Automatic Mirror"
                    else -> "Turn On Automatic Mirror"
                },
                stop = state.autoMirrorEnabled,
                focusRequester = primaryFocus,
                // The ViewModel ignores presses while busy, so the button can stay focusable
                // (a disabled control would drop D-pad focus mid-toggle).
                onClick = { viewModel.toggleAutoMirrorEnabled(!state.autoMirrorEnabled) },
            )
        }
    }

    if (pickerOpen) {
        ControllerPicker(
            title = "Select internal controller",
            emptyMessage = "No controllers detected. Connect the built-in controller and try again.",
            // Drop whatever is currently acting as the external pad: this list answers "which one is
            // built into the handheld?", and the mirror source is never that. Matching on path keeps
            // a placeholder external (resolved from saved identity, absent from the list) from
            // filtering anything out. "Automatic (detect)" stays available to undo a wrong pick.
            devices = state.devices.filter { it.path != state.externalDevice?.path },
            selectedGuid = state.manualInternalGuid,
            onSelect = { device ->
                pickerOpen = false
                viewModel.selectInternalController(device?.guid)
            },
            onClose = { pickerOpen = false },
        )
    }

    if (externalPickerOpen) {
        ControllerPicker(
            title = "Select external controller",
            emptyMessage = "No other controllers detected. Connect your controller and try again.",
            // Everything but the built-in pad. A pad that is hidden while mirrored still appears: the
            // device list re-materialises it, so the current source stays pickable.
            devices = state.devices.filter { !it.isInternal && it.path != state.localDevice?.path },
            selectedGuid = state.manualExternalGuid,
            selectedName = state.manualExternalName,
            onSelect = { device ->
                externalPickerOpen = false
                viewModel.selectExternalController(device)
            },
            onClose = { externalPickerOpen = false },
        )
    }

    if (state.unsupported) {
        UnsupportedDeviceDialog(serviceUnresponsive = state.serviceUnresponsive)
    }
}
