package com.abhayshah.volumecontrol;

/** Constant signal attenuation. This never normalizes, compresses or amplifies music. */
final class Level {
    private Level() {}
    static int clamp(int level) { return Math.max(0, Math.min(100, level)); }
    static float decibels(int level) {
        int n = clamp(level);
        return n == 0 ? -96f : -60f + (n - 1) * 60f / 99f;
    }
    static boolean handlesKeys(boolean enabled, boolean active, boolean music, int mode) {
        return enabled && active && music && mode == 0;
    }
    static boolean interruptsMedia(int usage) {
        return usage == android.media.AudioAttributes.USAGE_ALARM
                || usage == android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                || usage == android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
                || usage == android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING;
    }
}
