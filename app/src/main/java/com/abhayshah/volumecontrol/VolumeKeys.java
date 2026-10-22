package com.abhayshah.volumecontrol;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.util.HashSet;

public final class VolumeKeys extends AccessibilityService {
    static volatile boolean connected;
    static volatile int volumeEventsSeen;
    static volatile int volumeEventsConsumed;
    private final HashSet<Integer> consumed = new HashSet<>();
    @Override protected void onServiceConnected() { super.onServiceConnected(); connected = true; }
    @Override public void onDestroy() { connected = false; consumed.clear(); super.onDestroy(); }
    @Override protected boolean onKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key != KeyEvent.KEYCODE_VOLUME_DOWN && key != KeyEvent.KEYCODE_VOLUME_UP) return false;
        volumeEventsSeen++;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            boolean handled = consumed.remove(key);
            if (handled) volumeEventsConsumed++;
            return handled;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        boolean handled = VolumeState.prefs(this).getBoolean("keys", true)
                && VolumeService.adjust(key == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1);
        if (handled) {
            consumed.add(key);
            if (key == KeyEvent.KEYCODE_VOLUME_UP) VolumeService.resumeFromMute();
        }
        boolean result = handled || consumed.contains(key);
        if (result) volumeEventsConsumed++;
        return result;
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* No content inspected. */ }
    @Override public void onInterrupt() { consumed.clear(); }
}
