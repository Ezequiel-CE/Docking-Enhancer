<div align="center">

<img src="android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="88" alt="Docking Enhancer icon">

# Docking Enhancer

**Play docked with the controller in your hands — no remapping, no root.**

![Android](https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white)
![License](https://img.shields.io/badge/license-GPL--2.0-blue)
![Root](https://img.shields.io/badge/root-not%20required-brightgreen)

<br>

<img src="docking-enhancer-ui.png" alt="Docking Enhancer" width="820">

<br><br>

<img src="docking-enhancer-ui-2.png" alt="Controller mapping editor, showing a pad mapped from its bundled profile" width="820">

</div>

## What it does

Dock your handheld to a TV and games still expect its *built-in* controller, not the pad you're
actually holding — so every session starts with remapping. Docking Enhancer grabs your external
controller and replays its input onto the internal controller's input device, so everything just
sees the built-in pad. Nothing to remap, ever.

It runs without root, using a privileged service the stock firmware already ships.

## Features

- **Automatic** — starts on its own while docked, and survives reboots
- **Hides the external pad** while mirroring, so games see one controller instead of a phantom player 2
- **Per-controller button mapping** — press a control to bind it; triggers and stick halves can fill
  button slots, and untouched controls keep working. Pads the bundled
  [SDL database](third-party/gamecontrollerdb/NOTICE.md) knows arrive already mapped
- **Virtual mouse** — Select + R3 turns the pad into a pointer for tapping through Android UI
- **Home as Back**, and **Select + Start** (3s) to close the current app
- **Nintendo layout aware** — swaps A/B and X/Y when targeting the Odin's Nintendo profile
- **Gamepad-navigable UI** — set it up without touching the screen
- **~0.04 ms of added latency** — a fraction of one 60Hz frame ([measured](docs/TECHNICAL.md#latency))

## Install

1. Download the APK from [Releases](../../releases) and install it (allow "unknown sources" if
   prompted). It installs over any older version.
2. Open it once. Check that **Local Controller** shows your handheld's built-in pad — tap the card
   to fix it if not — then tap **Turn On Automatic Mirror** and allow notifications.
3. Connect a controller and dock. It takes over on its own.

That's the whole setup, and you only do it once.

## Supported devices

| Device | Status |
| --- | --- |
| **AYN Odin 2 / Portal** | Tested, primary target |
| **Retroid Pocket 5** | Supported since v0.1.3, tested with a Nintendo Switch Pro Controller |
| **Retroid Pocket 6** | Supported, tested with a Bluetooth pad that identifies as an Xbox controller |
| **AYN Odin 2 Mini** | Not supported — [ships the service but it doesn't respond](docs/TECHNICAL.md#the-odin-2-mini) |
| Other handhelds with `PServerBinder` | Likely work, untested |

Support is decided at runtime by whether the firmware's service actually answers — not by a model
list — so any handheld with a working one has a good chance. Devices without it are told so instead
of silently doing nothing.

## How it works

A small native daemon reads the external controller, grabs it exclusively, and replays every event
onto the internal controller's `/dev/input` node. While mirroring it also unlinks the external pad's
node so Android drops it from the device list, and puts it back on exit.

Privileged work goes through the stock firmware's `PServerBinder` service via a raw binder
transaction — the same no-root technique used by ClusterTune and PULSE.

Details, design notes and the build instructions live in **[docs/TECHNICAL.md](docs/TECHNICAL.md)**.

## Credits

The no-root PServer technique comes from [**ClusterTune**](https://github.com/AurelioB/ClusterTune),
and the `PServerBinder` access code is adapted from [**PULSE**](https://github.com/keiretrogaming/pulse).
Full third-party notices in [NOTICE.md](NOTICE.md).

Built with heavy help from an AI coding assistant (Anthropic's Claude), always directed and reviewed
by the maintainer — every change compiled, unit-tested and confirmed on a real handheld before
shipping. Spelled out here for transparency.

Not affiliated with AYN.

## License

[GNU General Public License v2.0](LICENSE). Please keep the attribution above intact in any fork —
the GPL requires it.
