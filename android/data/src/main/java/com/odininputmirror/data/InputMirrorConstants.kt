package com.odininputmirror.data

import android.view.KeyEvent
import android.view.MotionEvent

internal val CONTROLLER_BUTTONS = intArrayOf(
    KeyEvent.KEYCODE_BUTTON_A,
    KeyEvent.KEYCODE_BUTTON_B,
    KeyEvent.KEYCODE_BUTTON_X,
    KeyEvent.KEYCODE_BUTTON_Y,
    KeyEvent.KEYCODE_BUTTON_L1,
    KeyEvent.KEYCODE_BUTTON_R1,
    KeyEvent.KEYCODE_BUTTON_L2,
    KeyEvent.KEYCODE_BUTTON_R2,
    KeyEvent.KEYCODE_BUTTON_THUMBL,
    KeyEvent.KEYCODE_BUTTON_THUMBR,
    KeyEvent.KEYCODE_BUTTON_START,
    KeyEvent.KEYCODE_BUTTON_SELECT,
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
)

internal val CONTROLLER_AXES = intArrayOf(
    MotionEvent.AXIS_X,
    MotionEvent.AXIS_Y,
    MotionEvent.AXIS_Z,
    MotionEvent.AXIS_RX,
    MotionEvent.AXIS_RY,
    MotionEvent.AXIS_RZ,
    MotionEvent.AXIS_HAT_X,
    MotionEvent.AXIS_HAT_Y,
    MotionEvent.AXIS_LTRIGGER,
    MotionEvent.AXIS_RTRIGGER,
)

internal const val BUS_USB = 0x0003
internal const val BUS_BLUETOOTH = 0x0005

internal const val PREFS = "input_mirror"
internal const val KEY_SOURCE = "source"
internal const val KEY_TARGET = "target"
internal const val KEY_SOURCE_GUID = "source_guid"
internal const val KEY_TARGET_GUID = "target_guid"
internal const val KEY_HOME_AS_BACK = "home_as_back"
internal const val KEY_COMBO_HOLD_KILL_APP = "combo_hold_kill_app"
internal const val KEY_VIRTUAL_MOUSE = "virtual_mouse"
internal const val KEY_AUTO_MIRROR_ENABLED = "auto_mirror_enabled"
// Generation of the live options (see MirrorSettings.configGeneration docs).
internal const val KEY_CONFIG_GENERATION = "config_generation"
internal const val KEY_EXPECTED_RUNNING = "expected_running"
internal const val KEY_STARTED_AT = "started_at"
internal const val KEY_MANUAL_INTERNAL_GUID = "manual_internal_guid"
internal const val KEY_MANUAL_EXTERNAL_GUID = "manual_external_guid"
internal const val KEY_MANUAL_EXTERNAL_NAME = "manual_external_name"
// Namespace for per-controller mappings, keyed by the REAL device's vendor:product. Namespaced
// because these share a preferences file with the mirror's own settings.
internal const val KEY_MAPPING_PREFIX = "mapping_"

internal const val STARTING_GRACE_MS = 1500L
internal const val HEARTBEAT_STALE_MS = 5000L
// Line prefix the daemon uses to acknowledge the config it is actually running. Line 1 of the
// heartbeat stays the timestamp the liveness check reads; everything after is "key value" pairs.
internal const val GENERATION_PREFIX = "generation "
// Commands the control fifo carries. It is a channel rather than a signal precisely so that the
// mapping wizard's capture steps could join it without any new machinery.
internal const val RELOAD_COMMAND = "reload\n"
// The daemon stops forwarding while a capture step is open — pressing A to map it must not also
// press A in whatever is on screen — and closes the step on its own after 30s, so a wizard that
// crashed cannot leave the controller mute.
internal const val CAPTURE_BUTTON_COMMAND = "capture button\n"
internal const val CAPTURE_STICK_COMMAND = "capture stick\n"
internal const val CAPTURE_OFF_COMMAND = "capture off\n"
// Give the daemon time to run its cleanup (restore hidden nodes, destroy uinput devices) on SIGTERM
// before escalating to SIGKILL. 0.5s comfortably covers the restore (a handful of syscalls) even
// under load, so a stop never orphans a hidden node.
internal const val STOP_MIRROR_COMMAND =
    "pids=\$(pidof input_mirror 2>/dev/null); [ -z \"\$pids\" ] && exit 0; kill -TERM \$pids 2>/dev/null; sleep 0.5; for pid in \$pids; do [ -d /proc/\$pid ] && kill -KILL \$pid 2>/dev/null; done; exit 0"
