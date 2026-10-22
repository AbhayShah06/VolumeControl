package com.abhayshah.volumecontrol;

import android.content.Context;
import android.media.*;
import android.media.audiofx.Visualizer;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;

@RunWith(AndroidJUnit4.class)
public class ExperienceTest {
    @Rule public GrantPermissionRule permission = GrantPermissionRule.grant(android.os.Build.VERSION.SDK_INT >= 33
            ? new String[]{android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS}
            : new String[]{android.Manifest.permission.RECORD_AUDIO});
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Before public void reset() throws Exception {
        shell("input keyevent KEYCODE_WAKEUP");
        shell("wm dismiss-keyguard");
        context.stopService(new android.content.Intent(context, VolumeService.class));
        for (int i = 0; i < 50 && VolumeService.started(); i++) Thread.sleep(20);
        context.getSharedPreferences("volume", 0).edit().clear().putBoolean("consent", true).commit();
        context.getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0);
    }
    @After public void stop() { context.stopService(new android.content.Intent(context, VolumeService.class)); }
    @Test public void nativeGainAcceptsAllLevelsAndReleasesCleanly() {
        AudioTrack track = tone();
        try (GainEngine engine = new GainEngine(track.getAudioSessionId(), () -> {})) {
            for (int n = 1; n <= 100; n++) { engine.apply(n); assertEquals(Level.decibels(n), engine.gain(), 0.02f); }
        } finally { track.release(); }
    }
    @Test public void closingEffectNeverReusesDeadNativeHandle() {
        AudioTrack track = tone(); GainEngine engine = new GainEngine(track.getAudioSessionId(), () -> {});
        engine.close(); engine.close();
        try { engine.apply(25); fail("Released effect must reject changes"); }
        catch (IllegalStateException expected) { }
        finally { track.release(); }
    }
    @Test public void audioProcessingActuallyReducesPcmInOutputMix() throws Exception {
        AudioManager am = context.getSystemService(AudioManager.class);
        int before = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        AudioTrack track = tone();
        // Visualizer is INSERT_FIRST on the global mix, ahead of global INSERT_LAST effects.
        // Attach gain to the source session so this probe observes processed PCM, not its input.
        try (GainEngine engine = new GainEngine(track.getAudioSessionId(), () -> {})) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.min(8, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0);
            Visualizer visualizer = new Visualizer(0);
            try {
                visualizer.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS);
                visualizer.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED);
                visualizer.setEnabled(true);
                track.play();
                engine.apply(100); Thread.sleep(1800);
                int full = measure(visualizer);
                engine.apply(67); Thread.sleep(1800);
                int reduced = measure(visualizer);
                android.util.Log.i("VolumeQA", "Measured output RMS mB: full=" + full + " reduced=" + reduced);
                assertTrue("The test signal must be audible to the measurement path: " + full, full > -7000);
                assertTrue("Expected real attenuation, not just gain readback: " + full + " vs " + reduced, reduced < full - 1500);
            } finally { visualizer.release(); }
        } finally { track.stop(); track.release(); am.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0); }
    }
    private void shell(String command) {
        // Normal UiAutomation suppresses other accessibility services, including our key filter.
        try (android.os.ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation(
                     android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command);
             java.io.FileInputStream input = new java.io.FileInputStream(descriptor.getFileDescriptor())) {
            byte[] drain = new byte[1024];
            while (input.read(drain) != -1) { /* Drain shell output on Android 9+ too. */ }
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }
    @Test public void sharedOutputProbeForExternalCapture() throws Exception {
        org.junit.Assume.assumeTrue("External post-output capture is run by tools/measure_emulator_audio.py",
                "true".equals(InstrumentationRegistry.getArguments().getString("externalAudioProbe")));
        AudioManager am = context.getSystemService(AudioManager.class);
        int before = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        AudioTrack track = tone();
        try (GainEngine engine = new GainEngine(0, () -> {})) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.min(8, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0);
            track.play();
            for (int n : new int[]{100, 67, 34}) {
                engine.apply(n);
                android.os.Bundle phase = new android.os.Bundle(); phase.putString("phase", String.valueOf(n));
                InstrumentationRegistry.getInstrumentation().sendStatus(2, phase);
                Thread.sleep(5000);
            }
        } finally { track.stop(); track.release(); am.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0); }
    }
    @Test public void physicalKeysAdjustFineLevelFromAnotherAppAndReleaseOnStop() throws Exception {
        org.junit.Assume.assumeTrue("Run through tools/check_hardware_keys.py for actual emulated hardware events",
                "true".equals(InstrumentationRegistry.getArguments().getString("hardwareKeyBridge")));
        String before = android.provider.Settings.Secure.getString(context.getContentResolver(), "enabled_accessibility_services");
        String enabled = android.provider.Settings.Secure.getString(context.getContentResolver(), "accessibility_enabled");
        String saved = before == null ? "" : before;
        assertTrue("Unexpected accessibility component format", saved.matches("[a-zA-Z0-9_.$:/]*"));
        AudioTrack track = tone();
        try {
            shell("settings put secure enabled_accessibility_services " + context.getPackageName() + "/.VolumeKeys");
            shell("settings put secure accessibility_enabled 1");
            for (int i = 0; i < 30 && !VolumeKeys.connected; i++) Thread.sleep(100);
            assertTrue("Accessibility key filter must connect", VolumeKeys.connected);
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                onView(withText("Enable fine control")).perform(scrollTo(), click()); Thread.sleep(700);
                assertTrue(VolumeService.running());
                track.play(); shell("input keyevent KEYCODE_HOME"); Thread.sleep(400);
                AudioManager audio = context.getSystemService(AudioManager.class);
                android.os.Bundle diagnostic = new android.os.Bundle();
                diagnostic.putString("device_state", "active=" + VolumeService.running() + " music=" + audio.isMusicActive()
                        + " mode=" + audio.getMode() + " services=" + context.getSystemService(android.view.accessibility.AccessibilityManager.class)
                        .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK));
                InstrumentationRegistry.getInstrumentation().sendStatus(2, diagnostic);
                int start = VolumeState.level(context);
                requestHardwareKey("UP"); Thread.sleep(300);
                assertEquals("Events seen=" + VolumeKeys.volumeEventsSeen + " native=" + audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        + " active=" + VolumeService.running() + " music=" + audio.isMusicActive(), start + 1, VolumeState.level(context));
                requestHardwareKey("DOWN"); Thread.sleep(300);
                assertEquals(start, VolumeState.level(context));
                VolumeService.stop(context); Thread.sleep(400);
                for (int i = 0; i < 50 && VolumeService.started(); i++) Thread.sleep(20);
                assertFalse(VolumeService.started());
                int seen = VolumeKeys.volumeEventsSeen, consumed = VolumeKeys.volumeEventsConsumed;
                requestHardwareKey("UP"); Thread.sleep(200);
                assertEquals("Stopped control must not swallow normal buttons", start, VolumeState.level(context));
                assertTrue("The physical press must reach the filter", VolumeKeys.volumeEventsSeen >= seen + 2);
                // Android owns which native stream to adjust after control stops.
                assertEquals("Stopped control must return both key events to Android", consumed, VolumeKeys.volumeEventsConsumed);
            }
        } finally {
            track.release();
            if (saved.isEmpty()) shell("settings delete secure enabled_accessibility_services");
            else shell("settings put secure enabled_accessibility_services " + saved);
            shell("settings put secure accessibility_enabled " + ("1".equals(enabled) ? "1" : "0"));
        }
    }
    private void requestHardwareKey(String key) throws Exception {
        android.os.Bundle request = new android.os.Bundle(); request.putString("hardware_key", key);
        InstrumentationRegistry.getInstrumentation().sendStatus(2, request);
        Thread.sleep(1200);
    }
    @Test public void enableMutePlusRotateStopSequence() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withText("Enable fine control")).perform(scrollTo(), click()); Thread.sleep(1000);
            // Vendor rejection must be visible, never a fake active 100-step slider.
            assertTrue(VolumeState.prefs(context).getString("status", "No service status"), VolumeService.running());
            onView(withId(R.id.mute_control)).perform(scrollTo(), click()); Thread.sleep(200);
            assertEquals(0, VolumeState.level(context));
            onView(withText("Unmute")).perform(click()); Thread.sleep(250);
            assertEquals(25, VolumeState.level(context));
            assertEquals(1, context.getSystemService(AudioManager.class).getStreamVolume(AudioManager.STREAM_MUSIC));
            onView(withId(R.id.mute_control)).perform(click()); Thread.sleep(200);
            onView(withContentDescription("Increase fine level")).perform(click()); Thread.sleep(250);
            assertEquals(1, VolumeState.level(context));
            assertEquals(1, context.getSystemService(AudioManager.class).getStreamVolume(AudioManager.STREAM_MUSIC));
            scenario.recreate(); Thread.sleep(400);
            assertTrue(VolumeService.running());
            onView(withText("Stop & mute media")).perform(scrollTo(), click()); Thread.sleep(400);
            assertFalse(VolumeService.running());
            assertEquals(0, context.getSystemService(AudioManager.class).getStreamVolume(AudioManager.STREAM_MUSIC));
        }
    }
    @Test public void rememberedHeadphoneModelsStaySeparateAndCanBeTurnedOff() {
        OutputProfile first = new OutputProfile("usb", "1234:5678:1.0", "Earbuds");
        OutputProfile second = new OutputProfile("usb", "1234:9999:1.0", "Speakers");
        VolumeState.prefs(context).edit().putInt("profile_" + first.key, 63).putInt("profile_" + second.key, 12).commit();
        assertEquals(63, VolumeState.recalled(context, first, 25));
        assertEquals(12, VolumeState.recalled(context, second, 25));
        assertEquals(25, VolumeState.recalled(context, new OutputProfile("usb", "4321:0001:1.0", "New earbuds"), 63));
        VolumeState.prefs(context).edit().putBoolean("remember", false).commit();
        assertEquals(25, VolumeState.recalled(context, first, 25));
    }
    @Test public void dialogsAndBackNeverEnableControl() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withText("Check compatibility")).perform(scrollTo(), click());
            onView(withText("Check your audio output")).check(matches(isDisplayed())); androidx.test.espresso.Espresso.pressBack();
            onView(withText("About")).perform(scrollTo(), click());
            onView(withText("VolumeControl 0.1.0")).check(matches(isDisplayed())); androidx.test.espresso.Espresso.pressBack();
            onView(withText("Volume buttons")).perform(scrollTo(), click()); androidx.test.espresso.Espresso.pressBack();
            assertFalse(VolumeService.running());
        }
    }
    @Test public void competingAudioEffectFailsQuietlyInsteadOfBoosting() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withText("Enable fine control")).perform(scrollTo(), click()); Thread.sleep(900);
            assertTrue(VolumeService.running());
            android.media.audiofx.DynamicsProcessing.Config config = new android.media.audiofx.DynamicsProcessing.Config.Builder(
                    0, 2, false, 0, false, 0, false, 0, false).build();
            android.media.audiofx.DynamicsProcessing other = new android.media.audiofx.DynamicsProcessing(100, 0, config);
            try {
                Thread.sleep(700);
                assertFalse("Lost control must stop the service", VolumeService.running());
                assertEquals("Losing an effect must mute media", 0, context.getSystemService(AudioManager.class).getStreamVolume(AudioManager.STREAM_MUSIC));
            } finally { other.release(); }
        }
    }
    @Test public void alarmPausesSharedEffectAndDoesNotChangeAlarmVolume() throws Exception {
        AudioManager am = context.getSystemService(AudioManager.class);
        int alarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
        AudioTrack alarm = tone(AudioAttributes.USAGE_ALARM);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withText("Enable fine control")).perform(scrollTo(), click()); Thread.sleep(900);
            assertTrue(VolumeService.running());
            alarm.play(); Thread.sleep(900);
            assertFalse("Shared effect must pause for alarm", VolumeService.running());
            assertEquals(alarmVolume, am.getStreamVolume(AudioManager.STREAM_ALARM));
            assertEquals(0, am.getStreamVolume(AudioManager.STREAM_MUSIC));
            alarm.stop(); Thread.sleep(900);
            assertTrue("Effect should return before quietly restoring the same output", VolumeService.running());
            assertEquals(1, am.getStreamVolume(AudioManager.STREAM_MUSIC));
            VolumeState.level(context, 0); VolumeService.update(context); Thread.sleep(250);
            alarm.play(); Thread.sleep(700); alarm.stop(); Thread.sleep(900);
            assertEquals("A previously muted listener must stay muted", 0, am.getStreamVolume(AudioManager.STREAM_MUSIC));
        } finally { alarm.release(); }
    }
    @Test public void stoppingWhilePausedStillMutesMedia() throws Exception {
        AudioManager am = context.getSystemService(AudioManager.class);
        AudioTrack alarm = tone(AudioAttributes.USAGE_ALARM);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withText("Enable fine control")).perform(scrollTo(), click()); Thread.sleep(700);
            alarm.play(); Thread.sleep(700);
            assertFalse(VolumeService.running());
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0);
            VolumeService.stop(context);
            for (int i = 0; i < 50 && VolumeService.started(); i++) Thread.sleep(20);
            assertFalse(VolumeService.started());
            assertEquals(0, am.getStreamVolume(AudioManager.STREAM_MUSIC));
        } finally { alarm.release(); }
    }
    @Test public void interfaceSoundsDoNotMuteMusic() throws Exception {
        AudioManager am = context.getSystemService(AudioManager.class);
        AudioTrack music = tone(), click = tone(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withText("Enable fine control")).perform(scrollTo(), click()); Thread.sleep(700);
            music.play(); click.play(); Thread.sleep(700);
            assertTrue("A UI click must not interrupt fine control", VolumeService.running());
            assertEquals(1, am.getStreamVolume(AudioManager.STREAM_MUSIC));
        } finally { music.release(); click.release(); }
    }
    private int measure(Visualizer v) throws Exception {
        int sum = 0;
        for (int n = 0; n < 6; n++) {
            Visualizer.MeasurementPeakRms m = new Visualizer.MeasurementPeakRms();
            assertEquals(Visualizer.SUCCESS, v.getMeasurementPeakRms(m)); sum += m.mRms; Thread.sleep(100);
        }
        return sum / 6;
    }
    private AudioTrack tone() { return tone(AudioAttributes.USAGE_MEDIA); }
    private AudioTrack tone(int usage) {
        int rate = 48000; short[] pcm = new short[rate];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short)(8000 * Math.sin(2 * Math.PI * 440 * i / rate));
        AudioTrack track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(usage).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.length * 2).setTransferMode(AudioTrack.MODE_STATIC).build();
        track.write(pcm, 0, pcm.length); track.setLoopPoints(0, pcm.length, -1); return track;
    }
}
