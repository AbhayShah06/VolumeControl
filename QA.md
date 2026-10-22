# QA

Built with JDK 17 and Android SDK 35. Unit tests, debug/release lint and signed release assembly pass.

Android 10/API 29 and Android 16/API 36 emulators cover enable/stop, mute/unmute, rotation, menus, output memory, effect loss, interruptions and large text. Real kernel volume-key events exercise Accessibility control while another app is foreground and verify release after stopping. Tests do not require root on a user's phone; kernel injection is confined to test emulators.

Audio checks measure actual emulator speaker output after Android processing. Levels 100, 67 and 34 reduce a synthetic signal by about 20 dB per step. LocalMusic 0.12.11 plays, pauses, seeks and continues playing while gain changes, including with its equalizer enabled.

GitHub Actions runs unit/lint/build checks and native tests on APIs 29, 35 and 36. External LocalMusic/audio tests are opt-in local checks, not silently counted as CI coverage.

Limits: no connected physical S22+, USB earbuds or Bluetooth hardware. OEM routing, offload, protected audio and competing effects can bypass shared-output processing. This is attenuation, not a guaranteed safety limiter. YouTube playback testing was blocked by automatic approval review ("blocked by policy"); it is not verified.

Run `./gradlew testDebugUnitTest lintDebug lintRelease assembleRelease connectedDebugAndroidTest`. Optional harnesses in `tools/` verify physical-key routing and authenticated emulator speaker capture; use their `--help` for arguments. Never run their synthetic-tone checks with headphones in your ears.
