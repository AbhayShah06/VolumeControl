package com.abhayshah.volumecontrol;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class UpdateExperienceTest {
    @Test public void installerCleanupWaitsForSuccessfulVersionAndKeepsOtherFiles() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                java.io.File directory = activity.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
                assertNotNull(directory);
                java.io.File apk = new java.io.File(directory, "pending-update.apk");
                java.io.File other = new java.io.File(directory, "keep.txt");
                android.content.SharedPreferences prefs = activity.getSharedPreferences("updates", 0);
                try {
                    assertTrue(apk.createNewFile()); assertTrue(other.createNewFile());
                    prefs.edit().putInt("pending_version", BuildConfig.VERSION_CODE + 1).commit();
                    UpdateManager.cleanupInstalledUpdate(activity);
                    assertTrue("Cancelled/uninstalled update must remain available", apk.exists());
                    prefs.edit().putInt("pending_version", BuildConfig.VERSION_CODE).commit();
                    UpdateManager.cleanupInstalledUpdate(activity);
                    assertFalse(apk.exists()); assertTrue(other.exists());
                    assertFalse(prefs.contains("pending_version"));
                } catch (IOException error) { throw new AssertionError(error); }
                finally { apk.delete(); other.delete(); prefs.edit().remove("pending_version").commit(); }
            });
        }
    }
    @Test public void onlyVerifiedNewerVersionsShowAndDismissalCanBeOverriddenManually() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            verify(scenario, "{\"versionCode\":" + BuildConfig.VERSION_CODE + "}", false);
            verify(scenario, "{\"versionCode\":1}", false);
            verify(scenario, "{\"versionCode\":999,\"versionName\":\"test\",\"apkUrl\":\"https://github.com/AbhayShah06/VolumeControl/releases/download/v999/app.apk\",\"sha256\":\"" + "a".repeat(64) + "\"}", true);
            verify(scenario, "{\"versionCode\":999,\"apkUrl\":\"http://bad\",\"sha256\":\"PENDING\"}", false);
            verify(scenario, null, false);
        }
    }

    private void verify(ActivityScenario<MainActivity> scenario, String json, boolean expected) throws Exception {
        AtomicReference<UpdateManager> manager = new AtomicReference<>();
        AtomicReference<LinearLayout> host = new AtomicReference<>();
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<String> feedback = new AtomicReference<>();
        scenario.onActivity(activity -> {
            activity.getSharedPreferences("updates", 0).edit().clear().commit();
            LinearLayout parent = new LinearLayout(activity); host.set(parent);
            manager.set(new UpdateManager(activity, parent, feedback::set, () -> {
                reads.incrementAndGet(); if (json == null) throw new IOException("offline"); return json;
            }));
        });
        try {
            waitForCheck(manager.get());
            scenario.onActivity(activity -> {
                assertEquals(expected ? View.VISIBLE : View.GONE, host.get().getChildAt(0).getVisibility());
                assertNull("Automatic checks should be silent", feedback.get());
                assertEquals(1, host.get().getChildCount());
                manager.get().onResume();
                if (expected) {
                    LinearLayout banner = (LinearLayout)host.get().getChildAt(0);
                    LinearLayout buttons = (LinearLayout)banner.getChildAt(2);
                    ((Button)buttons.getChildAt(1)).performClick();
                    assertEquals(View.GONE, banner.getVisibility());
                }
                manager.get().checkManually();
            });
            waitForCheck(manager.get());
            scenario.onActivity(activity -> {
                assertEquals(2, reads.get());
                assertEquals(1, host.get().getChildCount());
                assertEquals(expected ? View.VISIBLE : View.GONE, host.get().getChildAt(0).getVisibility());
                if (!expected) assertNotNull("Manual checks report current/offline/invalid state", feedback.get());
            });
        } finally {
            scenario.onActivity(activity -> {
                manager.get().close(); activity.getSharedPreferences("updates", 0).edit().clear().commit();
            });
        }
    }

    private void waitForCheck(UpdateManager manager) throws Exception {
        java.lang.reflect.Field checking = UpdateManager.class.getDeclaredField("checking");
        checking.setAccessible(true);
        AtomicReference<Boolean> busy = new AtomicReference<>(true);
        long end = System.currentTimeMillis() + 5000;
        do {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try { busy.set(checking.getBoolean(manager)); } catch (IllegalAccessException error) { throw new AssertionError(error); }
            });
            if (!busy.get()) return;
            Thread.sleep(30);
        } while (System.currentTimeMillis() < end);
        fail("Update check did not finish");
    }
}
