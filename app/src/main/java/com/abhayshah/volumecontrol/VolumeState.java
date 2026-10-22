package com.abhayshah.volumecontrol;

import android.content.Context;
import android.content.SharedPreferences;

final class VolumeState {
    private VolumeState() {}
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("volume", Context.MODE_PRIVATE); }
    static int level(Context c) { return Level.clamp(prefs(c).getInt("level", 25)); }
    static void level(Context c, int n) { prefs(c).edit().putInt("level", Level.clamp(n)).apply(); }
    static int recalled(Context c, OutputProfile profile, int fallback) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("remember", true)) return Level.clamp(fallback);
        return Level.clamp(p.getInt("profile_" + profile.key, Math.min(fallback, 25)));
    }
}
