#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/poll.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "capture.h"
#include "config.h"
#include "evdev.h"
#include "hide_nodes.h"
#include "mapping.h"
#include "mouse.h"

static volatile sig_atomic_t keep_running = 1;
static const long long COMBO_HOLD_KILL_APP_MS = 3000;
static const long long HEARTBEAT_INTERVAL_MS = 1000;
// Consecutive beats the heartbeat path may stay missing before we conclude this daemon has no owner
// left and shut down. The app deletes that path exactly when it wants us gone (it was uninstalled,
// its data was cleared, or it is abandoning a duplicate launch), so five seconds of absence is
// decisive without being twitchy.
static const int HEARTBEAT_MISSING_LIMIT = 5;

// Events pulled from the source in a single read(). evdev returns whole events only, so one read
// coalesces a frame's worth of axis+button+SYN events instead of one syscall each. 32 is ample —
// a single frame rarely exceeds ~10 events; any overflow is drained by the next loop.
#define EVENT_BATCH_SIZE 32

// Virtual mouse mode (opt-in via --virtual-mouse). Select+R3 held this long toggles the mode; while
// on, the grabbed controller drives a self-created uinput pointer instead of the target node. The
// pointer itself lives in mouse.c; what stays here is the combo that summons it and the shell
// command it occasionally asks for.
static const long long MOUSE_TOGGLE_HOLD_MS = 500;

/*
 * In Odin mode we remap the four face buttons of a mirrored external controller to the
 * Nintendo layout the console uses by default: A and B (south/east) are swapped, and X and Y
 * (north/west) are swapped, so the physically-labelled button acts as its label rather than
 * its Xbox-position equivalent. This is keyed off the target device's USB id, so Xbox mode
 * (product 0x0112) passes through untouched.
 */
#ifndef BTN_SOUTH
#define BTN_SOUTH 0x130
#endif
#ifndef BTN_EAST
#define BTN_EAST 0x131
#endif
#ifndef BTN_NORTH
#define BTN_NORTH 0x133
#endif
#ifndef BTN_WEST
#define BTN_WEST 0x134
#endif
#ifndef BTN_THUMBR
#define BTN_THUMBR 0x13e
#endif
#ifndef BTN_TR
#define BTN_TR 0x137
#endif
#ifndef BTN_SELECT
#define BTN_SELECT 0x13a
#endif
#ifndef BTN_START
#define BTN_START 0x13b
#endif
#define ODIN_VENDOR_ID 0x2020
#define ODIN_NINTENDO_PRODUCT_ID 0x0111
#define RETROID_VENDOR_ID 0x2022

// The vendor a node must carry before hide_node will unlink it. Each supported handheld republishes
// external pads under its OWN vendor — the same one its built-in pad (the mirror target) carries —
// so the target says which vendor marks a firmware twin on this device. Only vendors known to behave
// that way are trusted; anything else keeps the original Odin-only rule, so an unrecognised target
// can never widen what the daemon is willing to delete.
static unsigned short hide_vendor_for_target(int have_target_id, unsigned short target_vendor) {
    if (have_target_id && (target_vendor == ODIN_VENDOR_ID || target_vendor == RETROID_VENDOR_ID)) {
        return target_vendor;
    }
    return ODIN_VENDOR_ID;
}

static unsigned short swap_nintendo_face_button(unsigned short code) {
    switch (code) {
        case BTN_SOUTH:
            return BTN_EAST;
        case BTN_EAST:
            return BTN_SOUTH;
        case BTN_NORTH:
            return BTN_WEST;
        case BTN_WEST:
            return BTN_NORTH;
        default:
            return code;
    }
}

static void handle_signal(int signal_number) {
    (void)signal_number;
    keep_running = 0;
}

/*
 * Run a shell command WITHOUT blocking the mirror loop. A plain system() here would stall input
 * forwarding for the whole duration of the command — `input keyevent` spins up an app_process (JVM)
 * and `dumpsys`/`am` can take hundreds of ms — dropping controller events and, if long enough,
 * starving the heartbeat into a false "dead" state. Forking lets the parent keep polling the source.
 */
static void run_detached(const char *command) {
    pid_t pid = fork();
    if (pid < 0) {
        return;
    }
    if (pid == 0) {
        // Restore default SIGCHLD so system()'s internal waitpid works in the child.
        signal(SIGCHLD, SIG_DFL);
        _exit(system(command) == -1 ? EXIT_FAILURE : EXIT_SUCCESS);
    }
    // Parent: with SIGCHLD ignored (set in main) the child is auto-reaped; never wait on it.
}

static int is_home_button(unsigned short code) {
#ifdef KEY_HOMEPAGE
    if (code == KEY_HOMEPAGE) {
        return 1;
    }
#endif
#ifdef KEY_HOME
    if (code == KEY_HOME) {
        return 1;
    }
#endif
#ifdef BTN_MODE
    if (code == BTN_MODE) {
        return 1;
    }
#endif
    return code == 172;
}

static int is_select_button(unsigned short code) {
#ifdef BTN_SELECT
    if (code == BTN_SELECT) {
        return 1;
    }
#endif
#ifdef KEY_SELECT
    if (code == KEY_SELECT) {
        return 1;
    }
#endif
    return code == 314;
}

static int is_start_button(unsigned short code) {
#ifdef BTN_START
    if (code == BTN_START) {
        return 1;
    }
#endif
#ifdef KEY_START
    if (code == KEY_START) {
        return 1;
    }
#endif
    return code == 315;
}

static void force_stop_foreground_app(void) {
    run_detached(
        "pkg=$(dumpsys activity activities 2>/dev/null | sed -n 's/.*ResumedActivity: ActivityRecord{[^ ]* [^ ]* \\([^/ ]*\\)\\/.*/\\1/p' | head -n 1); "
        "task=$(dumpsys activity activities 2>/dev/null | sed -n 's/.*ResumedActivity: ActivityRecord{.* t\\([0-9][0-9]*\\)}.*/\\1/p' | head -n 1); "
        "[ -z \"$pkg\" ] && pkg=$(dumpsys window 2>/dev/null | sed -n 's/.*mFocusedApp=ActivityRecord{[^ ]* [^ ]* \\([^/ ]*\\)\\/.*/\\1/p' | head -n 1); "
        "[ -z \"$task\" ] && task=$(dumpsys window 2>/dev/null | sed -n 's/.*mFocusedApp=ActivityRecord{.* t\\([0-9][0-9]*\\)}.*/\\1/p' | head -n 1); "
        "case \"$pkg\" in ''|com.odininputmirror|com.android.systemui|com.android.launcher*|*launcher*) ;; *) [ -n \"$task\" ] && cmd activity stack remove \"$task\" 2>/dev/null; am force-stop \"$pkg\" ;; esac"
    );
}

static int write_pid_file(const char *pid_file_path) {
    if (pid_file_path == NULL) {
        return 0;
    }

    FILE *pid_file = fopen(pid_file_path, "w");
    if (pid_file == NULL) {
        fprintf(stderr, "Failed to open pid file %s: %s\n", pid_file_path, strerror(errno));
        return -1;
    }

    fprintf(pid_file, "%ld\n", (long)getpid());
    fclose(pid_file);
    // 644: the app only ever READS these root-owned files back (delete goes through the
    // app-owned parent directory, which needs no write bit on the file itself).
    chmod(pid_file_path, 0644);
    return 0;
}

// Open the heartbeat file once, to be rewritten in place for the life of the run. Keeping the fd
// avoids an open/close pair every second in the mirror loop.
//
// `create` is false for every reopen after the first: the file's absence is how the app orders this
// daemon to stop, so only the opening beat may bring it into existence.
static int open_heartbeat_file(const char *heartbeat_file_path, int create) {
    if (heartbeat_file_path == NULL) {
        return -1;
    }

    int flags = O_WRONLY | O_CLOEXEC | (create ? O_CREAT : 0);
    int fd = open(heartbeat_file_path, flags, 0644);
    if (fd < 0) {
        fprintf(stderr, "Failed to open heartbeat file %s: %s\n", heartbeat_file_path, strerror(errno));
        return -1;
    }
    // Explicit, since umask can strip bits off the O_CREAT mode: the app reads this root-owned file.
    chmod(heartbeat_file_path, 0644);
    return fd;
}

// Record every absolute axis a device declares, with its travel, so a remapped axis can be rescaled
// between the two controllers. Asked once per device at startup: the source is grabbed straight
// afterwards, and neither device's ranges change while it is connected.
static void query_axis_ranges(int fd, struct axis_range *out) {
    unsigned long bits[(ABS_CNT + 8 * sizeof(unsigned long) - 1) / (8 * sizeof(unsigned long))];
    memset(bits, 0, sizeof(bits));
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(bits)), bits) < 0) {
        return;
    }

    for (int code = 0; code < ABS_CNT; code++) {
        size_t word = (size_t)code / (8 * sizeof(unsigned long));
        unsigned long bit = 1UL << ((size_t)code % (8 * sizeof(unsigned long)));
        if (!(bits[word] & bit)) {
            continue;
        }
        struct input_absinfo info;
        if (ioctl(fd, EVIOCGABS(code), &info) == 0 && info.maximum > info.minimum) {
            out[code].present = 1;
            out[code].minimum = info.minimum;
            out[code].maximum = info.maximum;
            // Where the axis sits untouched, asked for rather than assumed: a stick rests mid-travel
            // and a trigger at its minimum, and a binding that pushes an axis one way has to know
            // which end it is pushing from. Clamped because a stick held at this instant would
            // otherwise be recorded as its own resting place.
            int resting = info.value;
            if (resting < info.minimum) resting = info.minimum;
            if (resting > info.maximum) resting = info.maximum;
            out[code].rest = resting;
        }
    }
}

// All state carried across mirror-loop iterations: the fds, the option flags, and the runtime state
// for the kill-app combo, the heartbeat, and virtual-mouse mode. Grouping it keeps the loop body a
// readable dispatcher and lets each feature live in its own handler.
struct mirror_state {
    int source_fd;
    int target_fd;
    int control_fd; // command fifo the app pokes to ask for a config reload

    // Generation of the config currently in force, echoed in the heartbeat so the app can tell an
    // applied change from one still in flight.
    long long config_generation;

    const char *heartbeat_file_path;
    int heartbeat_fd;  // held open for the whole run; rewritten in place each beat
    int heartbeat_created;  // the opening beat made the file; later reopens must never re-create it
    int heartbeat_missing_beats;  // consecutive beats the heartbeat PATH has been gone
    int home_as_back;
    int combo_hold_kill_app;
    int virtual_mouse;
    int swap_nintendo_layout;
    // A Home press we swallowed and still owe a release for. Tracked so turning Home-as-Back off
    // mid-hold keeps swallowing that press to completion instead of handing the target a release
    // for a key it never saw go down.
    int home_swallowed;

    // Select+Start "close app" combo.
    int select_pressed;
    int start_pressed;
    int thumbr_pressed;
    int combo_triggered;
    long long combo_pressed_at_ms;

    long long last_heartbeat_ms;

    // The pointer, when the user has summoned it. Owned by mouse.c: what is left here is only the
    // combo that toggles it and the one thing it cannot do for itself.
    struct mouse_state mouse;
    int mouse_combo_triggered;
    long long mouse_combo_pressed_at_ms;
    int shade_open;  // our view of whether R1 last opened the notification shade

    // The mapping wizard's capture step, when one is open. While it is, nothing is forwarded.
    struct capture capture;

    // The mapping in force, copied out of the config so the hot path never walks the document.
    struct mapping_table mapping;

    // Both controllers' absolute travel, read once at startup: a remapped axis is rescaled between
    // them, and the pad is grabbed afterwards so this is the only chance to ask.
    struct axis_range source_axes[ABS_CNT];
    struct axis_range target_axes[ABS_CNT];

    // Everything the TARGET currently believes held/deflected, tracked as events are forwarded, so
    // entering mouse mode can release all of it (see enter_mouse_mode).
    unsigned char key_down[KEY_CNT];
    int abs_present[ABS_CNT];
    int abs_neutral[ABS_CNT];
    int abs_last[ABS_CNT];
};

// Stamp the heartbeat by rewriting the held-open file in place. The watchdog on the app side only
// checks that the file is non-empty and its mtime is recent, and a write updates mtime — so this is
// equivalent to the old rewrite-from-scratch, minus an open/close pair per second. A write failure
// drops the fd so the next beat reopens, keeping the old code's self-healing behaviour.
static void write_heartbeat(struct mirror_state *s) {
    if (s->heartbeat_file_path == NULL) {
        return;
    }
    // Only the FIRST beat is allowed to create the file. After that its absence is the app's way of
    // saying this daemon should be gone, and re-creating it would be us answering our own summons:
    // a write error drops the fd below, and a reopen with O_CREAT on the next beat would put back the
    // very file the app had just deleted, reset the counter, and leave a root daemon holding the
    // user's controller with nothing left to stop it.
    if (s->heartbeat_fd < 0 && !s->heartbeat_created) {
        s->heartbeat_fd = open_heartbeat_file(s->heartbeat_file_path, 1);
        if (s->heartbeat_fd >= 0) {
            s->heartbeat_created = 1;
        }
    }

    // Owner watchdog. Deliberately tests the PATH, never the fd: unlinking a file does not disturb an
    // open description, so a daemon whose app was uninstalled goes on writing happily to an inode
    // nobody can reach — grabbing the user's controller and keeping its node hidden with no app left
    // to stop or heal it. The app deletes this path only when it wants this daemon gone, so treat a
    // sustained absence as our cue to leave through the normal shutdown, which restores what we hid.
    // Placed after the create above so the first beat makes the file instead of tripping on it.
    if (access(s->heartbeat_file_path, F_OK) != 0) {
        if (++s->heartbeat_missing_beats >= HEARTBEAT_MISSING_LIMIT) {
            fprintf(stderr, "heartbeat file %s missing for %d beats; shutting down\n",
                    s->heartbeat_file_path, s->heartbeat_missing_beats);
            keep_running = 0;
        }
        return;
    }
    s->heartbeat_missing_beats = 0;

    // A previous write failed and dropped the fd. The file is still there — the access() above just
    // proved it — so take it back WITHOUT O_CREAT, which keeps the recovery from doubling as a way to
    // resurrect a heartbeat the app deleted between the two checks.
    if (s->heartbeat_fd < 0) {
        s->heartbeat_fd = open_heartbeat_file(s->heartbeat_file_path, 0);
        if (s->heartbeat_fd < 0) {
            return;
        }
    }

    // Line 1 stays exactly the timestamp: the app judges liveness by this file's mtime and
    // non-emptiness, and nothing appended below may disturb that. Line 2 carries the generation of
    // the config actually in force, which is how a reload is acknowledged.
    char stamp[64];
    int length = snprintf(stamp, sizeof(stamp), "%lld\ngeneration %lld\n", now_ms(), s->config_generation);
    if (length <= 0 || (size_t)length >= sizeof(stamp)) {
        return;
    }

    if (pwrite(s->heartbeat_fd, stamp, (size_t)length, 0) != (ssize_t)length) {
        fprintf(stderr, "Failed to write heartbeat file %s: %s\n", s->heartbeat_file_path, strerror(errno));
        close(s->heartbeat_fd);
        s->heartbeat_fd = -1;
        return;
    }

    // Trim any tail left by a longer previous stamp. now_ms() is monotonic so this never actually
    // shortens in practice; keeping it makes the file exact regardless.
    if (ftruncate(s->heartbeat_fd, (off_t)length) != 0) {
        // Harmless: a stale tail byte doesn't affect the reader, which only checks mtime and size.
    }
}

/*
 * Release everything the TARGET currently believes held, before the pointer takes over.
 *
 * Mirror-side, not mouse-side: these shadows are what the forwarding path recorded on its way past,
 * and only it knows what it has told the target. Once mouse mode starts nothing is forwarded, so a
 * key still down here — or a stick still deflected — would stay that way for as long as the pointer
 * is up, and the game underneath would sit there holding a direction nobody is pressing.
 */
static void release_target_holds(struct mirror_state *s) {
    for (int code = 0; code < KEY_CNT; code++) {
        if (s->key_down[code]) {
            emit_event(s->target_fd, EV_KEY, (unsigned short)code, 0);
            s->key_down[code] = 0;
        }
    }
    for (int code = 0; code < ABS_CNT; code++) {
        if (s->abs_present[code] && s->abs_last[code] != s->abs_neutral[code]) {
            emit_event(s->target_fd, EV_ABS, (unsigned short)code, s->abs_neutral[code]);
            s->abs_last[code] = s->abs_neutral[code];
        }
    }
    emit_event(s->target_fd, EV_SYN, SYN_REPORT, 0);
}

// Hand control to the pointer, then quiesce the target — in that order, because a pointer that fails
// to come up leaves us in gamepad mode with everything still forwarding, and releasing the user's
// held buttons on the way to not changing anything would be a visible glitch for no reason.
static void enter_mouse_mode(struct mirror_state *s) {
    if (mouse_enter(&s->mouse) != 0) {
        return;
    }
    release_target_holds(s);
    s->shade_open = 0;
}

static void leave_mouse_mode(struct mirror_state *s) {
    mouse_leave(&s->mouse);
}

// Poll timeout for the next iteration: fast while a hold combo is pending (to catch the threshold
// without new events) or in mouse mode (frame cadence), otherwise idle at 1s (heartbeat rate).
static int compute_loop_timeout(const struct mirror_state *s) {
    int timeout_ms = 1000;
    if (s->mouse.mode) {
        timeout_ms = (int)MOUSE_FRAME_INTERVAL_MS;
    } else if (s->combo_hold_kill_app && s->select_pressed && s->start_pressed && !s->combo_triggered) {
        timeout_ms = 50;
    }
    if (s->virtual_mouse && s->select_pressed && s->thumbr_pressed && !s->mouse_combo_triggered && timeout_ms > 50) {
        timeout_ms = 50;
    }
    return timeout_ms;
}

/*
 * Adopt a freshly parsed config.
 *
 * The reason this is not `*current = *next` is that a flag turning OFF may own live state only the
 * old value knows how to unwind. Mouse mode is the clear case: dropping --virtual-mouse while the
 * pointer exists would strand a uinput device and a visible cursor with nothing left that would ever
 * destroy them. So unwind first against the OLD values, then adopt.
 *
 * Turning a flag ON needs no such care — every feature here starts from an idle state.
 */
static void apply_config(struct mirror_state *s, const struct config *next) {
    if (s->virtual_mouse && !next->virtual_mouse) {
        if (s->mouse.mode) {
            leave_mouse_mode(s);
        }
        s->mouse_combo_triggered = 0;
        s->mouse_combo_pressed_at_ms = 0;
    }
    if (s->combo_hold_kill_app && !next->combo_hold_kill_app) {
        // Drop a hold in progress so re-enabling later doesn't inherit a stale, already-expired
        // timer and fire the moment both buttons are next seen down.
        s->combo_triggered = 0;
        s->combo_pressed_at_ms = 0;
    }

    s->home_as_back = next->home_as_back;
    s->combo_hold_kill_app = next->combo_hold_kill_app;
    s->virtual_mouse = next->virtual_mouse;
    s->config_generation = next->generation;

    // Adopting a mapping needs no unwinding: it only decides what the NEXT event is forwarded as.
    // A key already down was forwarded under the old table and will be released under the new one,
    // which is the one case worth knowing about — and it costs a stuck key only if the user remaps
    // mid-press, which the wizard makes impossible because it stops forwarding while it captures.
    mapping_load(&s->mapping, next->bindings, next->binding_count);
}

// Read whatever the app queued on the control fifo and report whether a reload was asked for.
// Commands are one per line and unknown ones are ignored, so a newer app talking to an older daemon
// degrades to "no-op" rather than to a parse error.
enum control_command {
    CONTROL_NONE = 0,
    CONTROL_RELOAD,
    CONTROL_CAPTURE_BUTTON,
    CONTROL_CAPTURE_STICK,
    CONTROL_CAPTURE_OFF,
};

static enum control_command drain_control(int fd) {
    char buffer[256];
    enum control_command command = CONTROL_NONE;

    for (;;) {
        ssize_t got = read(fd, buffer, sizeof(buffer) - 1);
        if (got <= 0) {
            break;
        }
        buffer[got] = '\0';
        // A command is a short word written in one call, and a write below PIPE_BUF is atomic, so a
        // command never arrives split across two reads. Where several arrived between polls the last
        // one read wins, which is the state the app most recently asked for.
        if (strstr(buffer, "capture button") != NULL) {
            command = CONTROL_CAPTURE_BUTTON;
        } else if (strstr(buffer, "capture stick") != NULL) {
            command = CONTROL_CAPTURE_STICK;
        } else if (strstr(buffer, "capture off") != NULL) {
            command = CONTROL_CAPTURE_OFF;
        } else if (strstr(buffer, "reload") != NULL) {
            command = CONTROL_RELOAD;
        }
    }
    return command;
}

// Flip mouse mode if Select+R3 has been held past the threshold. Checked every loop because the hold
// may complete on a poll timeout with no new event.
static void maybe_toggle_mouse_mode(struct mirror_state *s) {
    if (!s->virtual_mouse || !s->select_pressed || !s->thumbr_pressed || s->mouse_combo_triggered ||
        now_ms() - s->mouse_combo_pressed_at_ms < MOUSE_TOGGLE_HOLD_MS) {
        return;
    }
    s->mouse_combo_triggered = 1;
    if (!s->mouse.mode) {
        enter_mouse_mode(s);
    } else {
        leave_mouse_mode(s);
    }
}

// Fire the kill-app combo when it completes on a poll timeout (no new event to carry it over).
static void maybe_kill_combo_on_timeout(struct mirror_state *s) {
    if (s->combo_hold_kill_app && s->select_pressed && s->start_pressed && !s->combo_triggered &&
        now_ms() - s->combo_pressed_at_ms >= COMBO_HOLD_KILL_APP_MS) {
        force_stop_foreground_app();
        s->combo_triggered = 1;
    }
}

// Track Select/Start/R3 press state (read by both combos) and (re)arm the mouse-toggle hold. Only a
// combo key's own press may (re)arm the timer, so another button pressed while both are held can't
// reset an in-progress hold. Called for every EV_KEY event.
static void update_combo_tracking(struct mirror_state *s, const struct input_event *ev) {
    if (is_select_button(ev->code)) {
        if (ev->value == 1) s->select_pressed = 1;
        else if (ev->value == 0) s->select_pressed = 0;
    } else if (is_start_button(ev->code)) {
        if (ev->value == 1) s->start_pressed = 1;
        else if (ev->value == 0) s->start_pressed = 0;
    } else if (ev->code == BTN_THUMBR) {
        if (ev->value == 1) s->thumbr_pressed = 1;
        else if (ev->value == 0) s->thumbr_pressed = 0;
    }

    if (s->virtual_mouse) {
        int is_mouse_combo_key = is_select_button(ev->code) || ev->code == BTN_THUMBR;
        if (is_mouse_combo_key && ev->value == 1 && s->select_pressed && s->thumbr_pressed) {
            s->mouse_combo_pressed_at_ms = now_ms();
            s->mouse_combo_triggered = 0;
        }
        if (ev->value == 0 && (!s->select_pressed || !s->thumbr_pressed)) {
            s->mouse_combo_triggered = 0;
        }
    }
}

// Home-as-Back: swallow the Home button entirely (press, repeat and release) so the target never
// sees a Home-down without its up, and inject Back once, on release. Returns 1 if the event was
// consumed (must not be forwarded).
static int handle_home_as_back(struct mirror_state *s, const struct input_event *ev) {
    if (ev->type != EV_KEY || !is_home_button(ev->code)) {
        return 0;
    }
    // Keep swallowing a press we already swallowed even if the setting was turned off mid-hold: the
    // target never saw that key go down, so letting its release through would be a release out of
    // nowhere. The press finishes under the rules it started with.
    if (!s->home_as_back && !s->home_swallowed) {
        return 0;
    }
    if (ev->value == 1) {
        s->home_swallowed = 1;
    } else if (ev->value == 0) {
        s->home_swallowed = 0;
        run_detached("input keyevent 4");
    }
    return 1;
}

// Advance the Select+Start "close app" combo on a Select/Start event.
static void handle_kill_combo_event(struct mirror_state *s, const struct input_event *ev) {
    if (!s->combo_hold_kill_app || ev->type != EV_KEY ||
        !(is_select_button(ev->code) || is_start_button(ev->code))) {
        return;
    }
    if (ev->value == 1 && s->select_pressed && s->start_pressed) {
        s->combo_pressed_at_ms = now_ms();
        s->combo_triggered = 0;
    }
    if ((ev->value == 2 || ev->value == 0) && s->select_pressed && s->start_pressed &&
        !s->combo_triggered && now_ms() - s->combo_pressed_at_ms >= COMBO_HOLD_KILL_APP_MS) {
        force_stop_foreground_app();
        s->combo_triggered = 1;
    }
    if (ev->value == 0 && !s->select_pressed && !s->start_pressed) {
        s->combo_triggered = 0;
        s->combo_pressed_at_ms = 0;
    }
}

// Forward one event to the target: apply the Nintendo face-button swap, record what the target now
// believes held/deflected (for mouse-mode entry), and write it tolerantly. Returns 0, or -1 if the
// target write kept failing.
static int forward_event(struct mirror_state *s, const struct input_event *ev) {
    // One event in, several possibly out: a mapping may change an event's very type, and more than
    // one binding may name the same source.
    struct input_event mapped[MAX_MAPPED_EVENTS];
    int bound = 0;
    int count = mapping_apply(&s->mapping, s->source_axes, s->target_axes, ev,
                              mapped, MAX_MAPPED_EVENTS, &bound);

    // The face-button swap corrects a pad whose layout we had to GUESS at. A binding is the user
    // telling us, so it wins: swapping on top of one would invert exactly what they just set, which
    // is maddening precisely because the editor shows the binding they wanted, working, and the pad
    // does the opposite.
    int swap = s->swap_nintendo_layout && !bound;

    for (int i = 0; i < count; i++) {
        struct input_event *out = &mapped[i];
        if (swap && out->type == EV_KEY) {
            out->code = swap_nintendo_face_button(out->code);
        }
        // Post-swap: this is what the TARGET believes.
        if (out->type == EV_KEY && out->code < KEY_CNT) {
            s->key_down[out->code] = out->value != 0;
        } else if (out->type == EV_ABS && out->code < ABS_CNT) {
            s->abs_last[out->code] = out->value;
        }
        if (write_event_tolerant(s->target_fd, out) != 0) {
            return -1;
        }
    }
    return 0;
}

int main(int argc, char **argv) {
    // Heal mode (no mirroring): restore nodes a crashed session left hidden, then exit. Invoked as
    // `input_mirror --heal --hidden-state-file PATH`. Detected up front so the source/target
    // positional args aren't required.
    {
        int heal = 0;
        const char *state = NULL;
        for (int i = 1; i < argc; i++) {
            if (strcmp(argv[i], "--heal") == 0) {
                heal = 1;
            } else if (strcmp(argv[i], "--hidden-state-file") == 0 && i + 1 < argc) {
                state = argv[++i];
            }
        }
        if (heal) {
            restore_from_state_file(state);
            return EXIT_SUCCESS;
        }
    }

    if (argc < 3) {
        fprintf(stderr, "Usage: %s /dev/input/eventSOURCE /dev/input/eventTARGET [--home-as-back] [--combo-hold-kill-app] [--virtual-mouse] [--hide-node PATH]... [--hidden-state-file PATH] [--config-file PATH] [--control-fifo PATH] [--capture-file PATH] [--pid-file PATH] [--heartbeat-file PATH]\n       %s --heal --hidden-state-file PATH\n", argv[0], argv[0]);
        return EXIT_FAILURE;
    }

    const char *source_path = argv[1];
    const char *target_path = argv[2];
    const char *pid_file_path = NULL;
    const char *heartbeat_file_path = NULL;
    const char *hidden_state_path = NULL;
    const char *config_path = NULL;
    const char *control_fifo_path = NULL;
    const char *capture_file_path = NULL;
    int home_as_back = 0;
    int combo_hold_kill_app = 0;
    int virtual_mouse = 0;
    const char *hide_paths[MAX_HIDE_NODES];
    int hide_count = 0;

    for (int index = 3; index < argc; index++) {
        if (strcmp(argv[index], "--home-as-back") == 0) {
            home_as_back = 1;
            continue;
        }

        if (strcmp(argv[index], "--hidden-state-file") == 0 && index + 1 < argc) {
            hidden_state_path = argv[++index];
            continue;
        }

        if (strcmp(argv[index], "--hide-node") == 0 && index + 1 < argc) {
            if (hide_count < MAX_HIDE_NODES) {
                hide_paths[hide_count++] = argv[++index];
            } else {
                index++;  // drop the value; capacity reached
            }
            continue;
        }

        if (strcmp(argv[index], "--combo-hold-kill-app") == 0) {
            combo_hold_kill_app = 1;
            continue;
        }

        if (strcmp(argv[index], "--virtual-mouse") == 0) {
            virtual_mouse = 1;
            continue;
        }

        if (strcmp(argv[index], "--config-file") == 0 && index + 1 < argc) {
            config_path = argv[++index];
            continue;
        }

        if (strcmp(argv[index], "--control-fifo") == 0 && index + 1 < argc) {
            control_fifo_path = argv[++index];
            continue;
        }

        if (strcmp(argv[index], "--capture-file") == 0 && index + 1 < argc) {
            capture_file_path = argv[++index];
            continue;
        }

        if (strcmp(argv[index], "--pid-file") == 0 && index + 1 < argc) {
            pid_file_path = argv[++index];
            continue;
        }

        if (strcmp(argv[index], "--heartbeat-file") == 0 && index + 1 < argc) {
            heartbeat_file_path = argv[++index];
            continue;
        }

        fprintf(stderr, "Unknown argument: %s\n", argv[index]);
        return EXIT_FAILURE;
    }

    signal(SIGINT, handle_signal);
    signal(SIGTERM, handle_signal);
    signal(SIGHUP, handle_signal);
    // Auto-reap the short-lived children spawned by run_detached so they never linger as zombies.
    signal(SIGCHLD, SIG_IGN);

    struct mirror_state s;
    memset(&s, 0, sizeof(s));
    mouse_init(&s.mouse);
    s.control_fd = -1;
    s.heartbeat_fd = -1;
    s.heartbeat_file_path = heartbeat_file_path;
    s.home_as_back = home_as_back;
    s.combo_hold_kill_app = combo_hold_kill_app;
    s.virtual_mouse = virtual_mouse;
    s.capture.first_axis = -1;
    s.capture.log_path = capture_file_path;

    s.source_fd = open(source_path, O_RDONLY | O_CLOEXEC);
    if (s.source_fd < 0) {
        fprintf(stderr, "Failed to open source %s: %s\n", source_path, strerror(errno));
        return EXIT_FAILURE;
    }

    s.target_fd = open(target_path, O_RDWR | O_CLOEXEC);
    if (s.target_fd < 0) {
        fprintf(stderr, "Failed to open target %s: %s\n", target_path, strerror(errno));
        close(s.source_fd);
        return EXIT_FAILURE;
    }

    struct input_id target_id;
    memset(&target_id, 0, sizeof(target_id));
    int have_target_id = ioctl(s.target_fd, EVIOCGID, &target_id) == 0;
    if (have_target_id) {
        s.swap_nintendo_layout =
            target_id.vendor == ODIN_VENDOR_ID &&
            target_id.product == ODIN_NINTENDO_PRODUCT_ID;
    }
    const unsigned short hide_vendor = hide_vendor_for_target(have_target_id, target_id.vendor);

    // Virtual mouse setup: resolve which source axes drive the pointer, and capture the source's rest
    // state so release_target_holds can neutralise a held trigger/stick — a trigger's rest value is
    // its minimum, so a sampled-at-startup neutral avoids half-pressing it.
    //
    // Done unconditionally, even with the virtual mouse off. The setting can now be turned ON at
    // runtime through a config reload, and by then this is the only chance we had to read the
    // source's axis map — the pad is grabbed and its rest values long since moved.
    {
        mouse_bind_source_axes(&s.mouse, s.source_fd);

        unsigned long abs_bits[(ABS_CNT + 8 * sizeof(unsigned long) - 1) / (8 * sizeof(unsigned long))];
        memset(abs_bits, 0, sizeof(abs_bits));
        if (ioctl(s.source_fd, EVIOCGBIT(EV_ABS, sizeof(abs_bits)), abs_bits) >= 0) {
            for (int code = 0; code < ABS_CNT; code++) {
                size_t word = (size_t)code / (8 * sizeof(unsigned long));
                unsigned long bit = 1UL << ((size_t)code % (8 * sizeof(unsigned long)));
                if (!(abs_bits[word] & bit)) {
                    continue;
                }
                struct input_absinfo info;
                if (ioctl(s.source_fd, EVIOCGABS(code), &info) == 0) {
                    s.abs_present[code] = 1;
                    s.abs_neutral[code] = info.value;
                    s.abs_last[code] = info.value;
                }
            }
        }
    }

    // Both sides' axis travel, for rescaling a remapped axis between controllers that disagree about
    // their ranges (a trigger reporting 0..255 into one expecting -32767..32767, say).
    query_axis_ranges(s.source_fd, s.source_axes);
    query_axis_ranges(s.target_fd, s.target_axes);

    // A config file, when present, is the authority; the command-line flags are only what to start
    // from if it is missing or unreadable. Reading it here also means a daemon restarted for an
    // unrelated reason (a reconnect, a crash) comes back with whatever the user last chose rather
    // than with the flags of whichever launch happened to create it.
    if (config_path != NULL) {
        struct config from_flags = {
            .home_as_back = s.home_as_back,
            .combo_hold_kill_app = s.combo_hold_kill_app,
            .virtual_mouse = s.virtual_mouse,
            .generation = 0,
        };
        struct config initial = from_flags;
        if (parse_config(config_path, &from_flags, &initial) == 0) {
            apply_config(&s, &initial);
        }
    }

    s.control_fd = open_control_fifo(control_fifo_path);

    // Create the touch helper on every mirror start, regardless of the virtual-mouse setting: it is
    // what hides the cursor on mouse-mode exit when enabled, and its unavoidable "connected" toast
    // ("Docking Enhancer Mirror connected") doubles as a consistent mirror-activation notification on
    // each start. Created once and kept for the whole session so the toast doesn't repeat per toggle.
    mouse_create_touch_device(&s.mouse);

    if (ioctl(s.source_fd, EVIOCGRAB, 1) < 0) {
        fprintf(stderr, "Failed to grab source %s: %s\n", source_path, strerror(errno));
        mouse_destroy(&s.mouse);
        if (s.control_fd >= 0) {
            close(s.control_fd);
        }
        close(s.target_fd);
        close(s.source_fd);
        return EXIT_FAILURE;
    }

    write_pid_file(pid_file_path);
    write_heartbeat(&s);

    // Heal anything a previous crashed session left hidden before we hide this session's set —
    // self-cleaning across hard kills, independent of which controller is now connected.
    restore_from_state_file(hidden_state_path);

    // Hide requested duplicate nodes now that the mirror is committed (source grabbed). Done after
    // the grab so a failed start never disturbs the framework's device list. Record the hidden set
    // so a crash before clean exit can be healed.
    struct hidden_node hidden[MAX_HIDE_NODES];
    memset(hidden, 0, sizeof(hidden));
    for (int i = 0; i < hide_count; i++) {
        hide_node(hide_paths[i], &hidden[i], hide_vendor);
    }
    write_hidden_state(hidden_state_path, hidden, hide_count);

    while (keep_running) {
        int timeout_ms = compute_loop_timeout(&s);

        long long heartbeat_now_ms = now_ms();
        // A daemon left capturing forwards nothing, so an app that died mid-wizard would leave the
        // controller mute with no way to revive it from the controller itself.
        if (capture_expired(&s.capture, heartbeat_now_ms)) {
            fprintf(stderr, "capture: no command in 30s; resuming forwarding\n");
            capture_end(&s.capture);
        }
        if (heartbeat_now_ms - s.last_heartbeat_ms >= HEARTBEAT_INTERVAL_MS) {
            write_heartbeat(&s);
            s.last_heartbeat_ms = heartbeat_now_ms;
        }

        if (s.mouse.mode) {
            mouse_emit_frame(&s.mouse);
        }

        // Watch the source and the control fifo together. Adding the fifo here rather than checking
        // it on a timer is what keeps a settings change effectively instant while leaving the grab,
        // and the forwarding path, completely untouched.
        struct pollfd fds[2];
        memset(fds, 0, sizeof(fds));
        fds[0].fd = s.source_fd;
        fds[0].events = POLLIN;
        int poll_count = 1;
        int control_index = -1;
        if (s.control_fd >= 0) {
            fds[1].fd = s.control_fd;
            fds[1].events = POLLIN;
            control_index = 1;
            poll_count = 2;
        }

        int poll_result = poll(fds, (nfds_t)poll_count, timeout_ms);
        if (poll_result < 0) {
            if (errno == EINTR) {
                continue;
            }
            fprintf(stderr, "Poll error from %s: %s\n", source_path, strerror(errno));
            break;
        }

        // The mouse toggle may complete on a poll timeout with no new event, so check it every loop.
        maybe_toggle_mouse_mode(&s);

        // Settings first: applying them before this batch of events means the events are handled
        // under the config the user has already asked for, never under the one they just replaced.
        enum control_command command = CONTROL_NONE;
        if (control_index >= 0 && (fds[control_index].revents & POLLIN)) {
            command = drain_control(s.control_fd);
        }
        if (command == CONTROL_CAPTURE_BUTTON) {
            capture_begin(&s.capture, CAPTURE_BUTTON, now_ms());
        } else if (command == CONTROL_CAPTURE_STICK) {
            capture_begin(&s.capture, CAPTURE_STICK, now_ms());
        } else if (command == CONTROL_CAPTURE_OFF) {
            capture_end(&s.capture);
        }
        if (command == CONTROL_RELOAD) {
            struct config running = {
                .home_as_back = s.home_as_back,
                .combo_hold_kill_app = s.combo_hold_kill_app,
                .virtual_mouse = s.virtual_mouse,
                .generation = s.config_generation,
            };
            struct config next = running;
            if (parse_config(config_path, &running, &next) == 0) {
                apply_config(&s, &next);
            } else {
                // Keep running exactly as we were. The app notices because the generation it is
                // waiting for never shows up in the heartbeat, and can put its toggle back.
                fprintf(stderr, "reload: keeping the running config\n");
            }
        }

        // The controller went away: unplugged, powered off, or dropped its Bluetooth link.
        //
        // This has to end the run rather than be waited out. A dead source never reports POLLIN
        // again, only POLLERR/POLLHUP, so treating "not readable" as "nothing to do" left the daemon
        // spinning forever — alive, still beating its heartbeat, still holding the node hidden, and
        // forwarding nothing. The app read that heartbeat and reported a healthy mirror, which is the
        // worst possible answer: the user sees "on" and the pad does nothing. Leaving through the
        // normal shutdown puts the hidden node back and lets the supervisor start a fresh daemon on
        // whatever node the controller comes back as.
        if (fds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) {
            fprintf(stderr, "source %s went away (revents 0x%x); shutting down\n",
                    source_path, fds[0].revents);
            break;
        }

        if (!(fds[0].revents & POLLIN)) {
            maybe_kill_combo_on_timeout(&s);
            continue;
        }

        // Drain every event the source has queued in one read(). The mode is decided once per
        // batch (maybe_toggle_mouse_mode above); a batch spans microseconds, far below the 500ms
        // toggle hold, so processing it under a single mode matches the old event-at-a-time loop.
        struct input_event events[EVENT_BATCH_SIZE];
        ssize_t bytes_read = read(s.source_fd, events, sizeof(events));
        if (bytes_read == 0) {
            break;
        }
        if (bytes_read < 0) {
            if (errno == EINTR) {
                continue;
            }
            fprintf(stderr, "Read error from %s: %s\n", source_path, strerror(errno));
            break;
        }
        size_t event_count = (size_t)bytes_read / sizeof(struct input_event);
        if (event_count == 0) {
            fprintf(stderr, "Short read from %s: %zd bytes\n", source_path, bytes_read);
            continue;
        }

        int fatal_write_error = 0;
        for (size_t i = 0; i < event_count; i++) {
            struct input_event *event = &events[i];

            // Capture first and alone: while the wizard has a step open the controller drives the
            // wizard and nothing else — not the combos, not the mouse, and above all not the target.
            if (capture_feed(&s.capture, event, s.source_axes, s.abs_neutral, now_ms())) {
                continue;
            }

            // Track Select/Start/R3 for both combos, regardless of which feature is enabled.
            if (event->type == EV_KEY) {
                update_combo_tracking(&s, event);
            }

            if (handle_home_as_back(&s, event)) {
                continue;
            }

            handle_kill_combo_event(&s, event);

            if (s.mouse.mode) {
                // The pointer reports back what it cannot do itself. R1 asks for the notification
                // shade because a mouse has no way to drag it down; the commands are idempotent
                // enough that a stale view of it just costs the user one extra press.
                if (mouse_handle_event(&s.mouse, event) == MOUSE_ACTION_TOGGLE_SHADE) {
                    run_detached(s.shade_open ? "cmd statusbar collapse"
                                              : "cmd statusbar expand-notifications");
                    s.shade_open = !s.shade_open;
                }
                continue;
            }

            if (forward_event(&s, event) != 0) {
                fprintf(stderr, "Write error to %s: %s\n", target_path, strerror(errno));
                fatal_write_error = 1;
                break;
            }
        }
        if (fatal_write_error) {
            break;
        }
    }

    // Restore the hidden nodes FIRST — highest-priority cleanup, so a KILL escalation racing this
    // shutdown is least likely to leave them orphaned. Then drop the state file: a clean exit has
    // nothing left to heal.
    restore_hidden_nodes(hidden, hide_count);
    if (hidden_state_path != NULL) {
        // Drop the file only on a restore that actually settled everything. If a node is still
        // hidden, leave the record behind: the supervisor's next heal is the user's only remaining
        // path back to a visible controller.
        if (count_active_hidden(hidden, hide_count) > 0) {
            write_hidden_state(hidden_state_path, hidden, hide_count);
        } else {
            unlink(hidden_state_path);
        }
    }

    if (ioctl(s.source_fd, EVIOCGRAB, 0) < 0) {
        fprintf(stderr, "Failed to release source %s: %s\n", source_path, strerror(errno));
    }

    // Shut mouse mode down the same way the user's own toggle does. Destroying the pointer device
    // is not enough on its own: Android goes on drawing the cursor until it fades, so stopping the
    // mirror from within mouse mode used to strand it on screen. mouse_leave also flushes the touch
    // helper, which is what actually takes it away — hence this runs while that helper is still
    // alive, and before mouse_destroy below (which is then only cleaning up the helper itself).
    if (s.mouse.mode) {
        leave_mouse_mode(&s);
    }
    mouse_destroy(&s.mouse);
    if (s.control_fd >= 0) {
        close(s.control_fd);
    }
    close(s.target_fd);
    close(s.source_fd);
    if (pid_file_path != NULL) {
        unlink(pid_file_path);
    }
    if (s.heartbeat_fd >= 0) {
        close(s.heartbeat_fd);
    }
    if (heartbeat_file_path != NULL) {
        unlink(heartbeat_file_path);
    }
    return EXIT_SUCCESS;
}
