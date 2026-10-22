# VolumeControl

Fine volume below Android’s usual minimum. Android 9+.

- 100 attenuation levels, plus mute. No boost or compression.
- Optional volume-button control and a Quick Settings shortcut.
- Remembers USB headphone models, Bluetooth devices when identifiable, and speaker/wired profiles.
- Stops quietly: media is muted before the effect is released.

Install the APK from [Releases](https://github.com/AbhayShah06/VolumeControl/releases). Enable control, then check it with your own headphones at low phone volume. Volume-button support is optional and needs Android Accessibility permission.

This uses Android’s shared-output effect. Some apps, equalizers and USB/Bluetooth paths bypass it. Calls and alarms pause control; music returns quietly on the same output. Short notifications leave music alone. It is not a hardware limiter: an OS process kill can remove attenuation. S22+ and actual USB-C earbuds still need physical testing. Don’t raise phone volume to compensate for an unsupported effect.

Reconnects load the saved level while muted; tap Unmute when ready. Identical USB models can share a setting. You can turn memory off in Controls.

No ads, recording or accounts. GitHub is contacted for update checks and downloads. Release builds have no microphone permission.

Build: JDK 17, Android SDK 35, `./gradlew assembleDebug`. See [QA.md](QA.md) for test coverage and limits.
