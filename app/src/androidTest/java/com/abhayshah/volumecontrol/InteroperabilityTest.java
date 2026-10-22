package com.abhayshah.volumecontrol;

import android.content.*;
import android.media.AudioManager;
import android.net.Uri;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import androidx.media3.common.*;
import androidx.media3.session.*;
import java.io.File;
import java.nio.*;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Optional real-app check, using LocalMusic's exported Media3 session and a granted test-only URI. */
@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public class InteroperabilityTest {
    @Rule public GrantPermissionRule permission = GrantPermissionRule.grant(android.os.Build.VERSION.SDK_INT >= 33
            ? new String[]{android.Manifest.permission.POST_NOTIFICATIONS} : new String[]{});
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Test public void localMusicKeepsPlayingWhileFineGainChanges() throws Exception {
        Assume.assumeTrue("Requires installed LocalMusic and external audio capture", "localmusic".equals(InstrumentationRegistry.getArguments().getString("externalPlayer")));
        AudioManager audio = context.getSystemService(AudioManager.class);
        context.stopService(new Intent(context, VolumeService.class));
        Thread.sleep(250);
        VolumeState.prefs(context).edit().clear().putBoolean("consent", true).commit();
        VolumeState.level(context, 100);
        File directory = new File(context.getCacheDir(), "interop"); assertTrue(directory.exists() || directory.mkdirs());
        File file = new File(directory, "volume-check.wav");
        int rate = 48000, samples = rate * 60;
        ByteBuffer bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes()).putInt(36 + samples * 2).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1)
                .putInt(rate).putInt(rate * 2).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(samples * 2);
        for (int n = 0; n < samples; n++) bytes.putShort((short)(8000 * Math.sin(2 * Math.PI * 440 * n / rate)));
        Files.write(file.toPath(), bytes.array());
        Uri uri = androidx.core.content.FileProvider.getUriForFile(context, context.getPackageName() + ".qa", file);
        context.grantUriPermission("com.abhayshah.localmusic", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        AtomicReference<MediaController> controller = new AtomicReference<>();
        Boolean previousEq = null;
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(VolumeService::start);
            await(() -> VolumeService.running(), "VolumeControl did not start");
            context.startActivity(new Intent().setComponent(new ComponentName("com.abhayshah.localmusic", "com.abhayshah.localmusic.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            AtomicReference<com.google.common.util.concurrent.ListenableFuture<MediaController>> future = new AtomicReference<>();
            main(() -> future.set(new MediaController.Builder(context, new SessionToken(context,
                    new ComponentName("com.abhayshah.localmusic", "com.abhayshah.localmusic.PlaybackService"))).buildAsync()));
            controller.set(future.get().get(20, TimeUnit.SECONDS));
            MediaController player = controller.get();
            MediaItem item = new MediaItem.Builder().setUri(uri).setMediaId("volumecontrol-qa")
                    .setMediaMetadata(new MediaMetadata.Builder().setTitle("VolumeControl compatibility check").setArtist("Synthetic tone").build()).build();
            main(() -> { player.setMediaItem(item); player.prepare(); player.play(); });
            await(() -> value(player::isPlaying), "LocalMusic did not play the granted audio URI");
            main(player::pause); await(() -> !value(player::isPlaying), "LocalMusic pause failed");
            main(() -> { player.seekTo(2000); player.play(); });
            await(() -> value(player::isPlaying), "LocalMusic did not resume after seeking");
            String eq = InstrumentationRegistry.getArguments().getString("externalEq");
            if (eq != null) previousEq = setLocalMusicEq(Boolean.parseBoolean(eq));
            // Test-only emulator output level, never set by production startup/reconnect code.
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.min(8, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0);
            for (int level : new int[]{100, 67, 34}) {
                VolumeState.level(context, level); VolumeService.update(context); Thread.sleep(300);
                assertTrue("VolumeControl lost its effect", VolumeService.running());
                assertTrue("LocalMusic stopped while changing volume", value(player::isPlaying));
                assertNull(value(player::getPlayerError));
                android.os.Bundle phase = new android.os.Bundle(); phase.putString("phase", String.valueOf(level));
                InstrumentationRegistry.getInstrumentation().sendStatus(2, phase);
                Thread.sleep(5000);
            }
            if (previousEq != null) { setLocalMusicEq(previousEq); previousEq = null; }
        } finally {
            try {
                if (previousEq != null) setLocalMusicEq(previousEq);
            } finally {
                try {
                    if (controller.get() != null) main(() -> { controller.get().stop(); controller.get().clearMediaItems(); controller.get().release(); });
                } finally {
                    context.stopService(new Intent(context, VolumeService.class));
                    context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    file.delete();
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
                }
            }
        }
    }
    private boolean setLocalMusicEq(boolean enabled) throws Exception {
        context.startActivity(new Intent().setComponent(new ComponentName("com.abhayshah.localmusic", "com.abhayshah.localmusic.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Thread.sleep(600);
        androidx.test.uiautomator.UiDevice device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        for (int n = 0; n < 3 && device.findObject(new androidx.test.uiautomator.UiSelector().description("Dismiss panel")).exists(); n++) {
            device.pressBack(); Thread.sleep(450);
        }
        androidx.test.uiautomator.UiObject more = device.findObject(new androidx.test.uiautomator.UiSelector().resourceId("com.abhayshah.localmusic:id/more"));
        assertTrue("LocalMusic menu unavailable", more.waitForExists(5000)); more.click(); Thread.sleep(450);
        device.findObject(new androidx.test.uiautomator.UiSelector().text("Settings")).click(); Thread.sleep(450);
        new androidx.test.uiautomator.UiScrollable(new androidx.test.uiautomator.UiSelector().scrollable(true)).scrollIntoView(new androidx.test.uiautomator.UiSelector().textStartsWith("Equalizer\n"));
        device.findObject(new androidx.test.uiautomator.UiSelector().textStartsWith("Equalizer\n")).click(); Thread.sleep(450);
        androidx.test.uiautomator.UiObject toggle = device.findObject(new androidx.test.uiautomator.UiSelector().description("Enable equalizer"));
        assertTrue(toggle.waitForExists(5000)); boolean before = toggle.isChecked();
        if (before != enabled) { toggle.click(); Thread.sleep(500); }
        assertEquals(enabled, toggle.isChecked());
        if (enabled) assertTrue("LocalMusic EQ must actually report On", device.findObject(new androidx.test.uiautomator.UiSelector().text("On")).waitForExists(3000));
        device.pressBack(); Thread.sleep(350); device.pressBack(); Thread.sleep(350);
        return before;
    }
    private static void main(Runnable task) { InstrumentationRegistry.getInstrumentation().runOnMainSync(task); }
    private static <T> T value(Supplier<T> read) { AtomicReference<T> value = new AtomicReference<>(); main(() -> value.set(read.get())); return value.get(); }
    private static void await(Supplier<Boolean> ready, String failure) throws Exception {
        long end = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < end) { if (ready.get()) return; Thread.sleep(100); }
        fail(failure);
    }
}
