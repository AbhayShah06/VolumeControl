package com.abhayshah.volumecontrol;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(17, 18, 17), CARD = Color.rgb(30, 31, 29);
    private static final int WHITE = Color.rgb(245, 244, 238), MUTED = Color.rgb(174, 178, 170), ACCENT = Color.rgb(255, 121, 63);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView level, status, detail, route;
    private Button toggle, minus, plus, mute;
    private SeekBar slider;
    private boolean rendering;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 500); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = column(); root.setBackgroundColor(BG);
        root.setPadding(dp(22), dp(16), dp(22), dp(22));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars;
            if (Build.VERSION.SDK_INT >= 30) {
                bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(dp(22) + bars.left, dp(16) + bars.top, dp(22) + bars.right, dp(22) + bars.bottom);
            } else v.setPadding(dp(22) + insets.getSystemWindowInsetLeft(), dp(16) + insets.getSystemWindowInsetTop(), dp(22) + insets.getSystemWindowInsetRight(), dp(22) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(root); setContentView(scroll);
        LinearLayout title = new LinearLayout(this); title.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this); icon.setImageResource(R.drawable.brand_mark); icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        title.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView name = text("VolumeControl", 25, WHITE); name.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, -2); nameParams.setMarginStart(dp(12)); title.addView(name, nameParams); root.addView(title);
        route = text("", 13, MUTED); space(root, 20); root.addView(route);
        LinearLayout dial = card(); space(root, 10); root.addView(dial);
        dial.addView(text("FINE LEVEL", 12, MUTED));
        level = text("25", 80, WHITE); level.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); level.setGravity(Gravity.CENTER); dial.addView(level);
        level.setMaxLines(1);
        level.setAutoSizeTextTypeUniformWithConfiguration(32, 80, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        detail = text("", 14, MUTED); detail.setGravity(Gravity.CENTER); dial.addView(detail);
        slider = new SeekBar(this); slider.setId(View.generateViewId()); slider.setMax(100); slider.setContentDescription("Fine volume level");
        LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(-1, dp(64)); sliderParams.topMargin = dp(12); dial.addView(slider, sliderParams);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int n, boolean fromUser) { if (fromUser && !rendering) change(n, true); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.CENTER);
        minus = button("−", () -> change(VolumeState.level(this) - 1, false)); minus.setContentDescription("Decrease fine level");
        mute = button("Mute", this::toggleMute); mute.setId(R.id.mute_control);
        plus = button("+", () -> change(VolumeState.level(this) + 1, true)); plus.setContentDescription("Increase fine level");
        for (Button b : new Button[]{minus, mute, plus}) actions.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        dial.addView(actions);
        space(root, 16); status = text("Off", 16, WHITE); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); root.addView(status);
        space(root, 10); toggle = button("Enable fine control", this::toggle); toggle.setBackground(tint(ACCENT)); toggle.setTextColor(BG); root.addView(toggle);
        space(root, 10); root.addView(text("Fine control reduces your phone’s media volume. It never boosts audio.", 14, MUTED));
        space(root, 24); LinearLayout settings = card(); root.addView(settings);
        settings.addView(text("CONTROLS", 12, MUTED));
        Switch memory = new Switch(this); memory.setText("Remember fine levels"); memory.setTextColor(WHITE); memory.setTextSize(16); memory.setMinHeight(dp(56));
        memory.setChecked(VolumeState.prefs(this).getBoolean("remember", true));
        memory.setOnCheckedChangeListener((v, checked) -> { VolumeState.prefs(this).edit().putBoolean("remember", checked).apply(); VolumeService.update(this); });
        settings.addView(memory);
        settings.addView(button("Saved headphones", this::savedHeadphones));
        settings.addView(button("Volume buttons", this::keySettings));
        settings.addView(button("Phone volume", () -> open(new Intent(Settings.ACTION_SOUND_SETTINGS))));
        settings.addView(button("Check compatibility", this::compatibility));
        settings.addView(button("About", () -> new AlertDialog.Builder(this).setTitle("VolumeControl 0.1.1")
                .setMessage("A quieter range for sensitive headphones.\n\nNo ads, network access, recording, or audio boost. Preferences stay on this phone.\n\nOutput changes and stopping control mute media before releasing the effect. After an Android process kill, the effect can disappear; this app is not a hardware volume limiter.")
                .setPositiveButton("Got it", null).show()));
        render(); root.requestApplyInsets();
    }
    private void change(int n, boolean unmute) {
        if (n > 0) VolumeState.prefs(this).edit().putInt("before_mute", Level.clamp(n)).apply();
        VolumeState.level(this, n); VolumeService.update(this);
        if (unmute) handler.post(VolumeService::resumeFromMute);
        render();
    }
    private void toggleMute() {
        AudioManager audio = getSystemService(AudioManager.class);
        int n = VolumeState.level(this);
        if (n == 0 || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            change(n > 0 ? n : VolumeState.prefs(this).getInt("before_mute", 25), true);
        } else {
            VolumeState.prefs(this).edit().putInt("before_mute", n).apply(); change(0, false);
        }
    }
    private void savedHeadphones() {
        android.content.SharedPreferences p = VolumeState.prefs(this);
        java.util.ArrayList<String> entries = new java.util.ArrayList<>();
        for (String key : p.getAll().keySet()) if (key.startsWith("label_device_")) {
            String device = key.substring("label_".length());
            entries.add(p.getString(key, "Headphones") + " · " + p.getInt("profile_" + device, 25));
        }
        java.util.Collections.sort(entries);
        String message = entries.isEmpty() ? "No saved outputs yet. Enable control and choose a fine level." : android.text.TextUtils.join("\n", entries);
        new AlertDialog.Builder(this).setTitle("Saved fine levels")
                .setMessage(message + "\n\nUSB headphones are identified by model IDs. Identical models may share a setting. Bluetooth uses a device address when Android exposes one; otherwise the model name. Analog headphones share one wired setting.\n\nReconnects load the saved fine level while media stays muted. Tap Unmute when ready.")
                .setPositiveButton("Done", null).show();
    }
    private void toggle() {
        if (VolumeService.started()) { VolumeService.stop(this); handler.postDelayed(this::render, 100); return; }
        if (!VolumeState.prefs(this).getBoolean("consent", false)) {
            new AlertDialog.Builder(this).setTitle("Start quietly")
                    .setMessage("Keep your phone volume low. Fine control uses a shared-output audio effect; some apps and USB/Bluetooth paths bypass it.\n\nCalls and alarms pause control and mute media. Brief system sounds share the reduction. Control cannot guarantee protection if Android kills it.\n\nTest with your own headphones before relying on it. Stopping control mutes media.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Enable", (d, w) -> {
                        VolumeState.prefs(this).edit().putBoolean("consent", true).apply(); enable();
                    }).show();
        } else enable();
    }
    private void enable() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        try { VolumeService.start(this); }
        catch (RuntimeException unavailable) { VolumeState.prefs(this).edit().putString("status", "Android couldn’t start control. Try again while this screen is open.").apply(); }
        handler.postDelayed(this::render, 250);
    }
    private void keySettings() {
        new AlertDialog.Builder(this).setTitle("Use your volume buttons")
                .setMessage("Enable VolumeControl buttons in Android’s accessibility settings. It only filters volume keys while media is playing and fine control is active. It does not read your screen.\n\nOn sideloaded apps, Android may first require App info → menu → Allow restricted settings.")
                .setNegativeButton("Cancel", null).setPositiveButton("Open settings", (d,w) -> {
                    Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    open(i);
                }).show();
    }
    private void compatibility() {
        new AlertDialog.Builder(this).setTitle("Check your audio output")
                .setMessage("1. Start YouTube or your music app at the lowest phone volume.\n2. Enable fine control here.\n3. Move the fine level from 20 to 60. It should get gradually louder while staying below the original signal.\n\nIf nothing changes, stop control. Your app or output may bypass Android’s shared effects. Don’t raise the phone volume to compensate.\n\nOther equalizers, Dolby processing, USB DACs and Bluetooth devices can change compatibility. The status confirms an attached effect, not measured loudness.\n\n100 means no extra reduction. It does not mean a safe listening level.")
                .setPositiveButton("Got it", null).show();
    }
    private void open(Intent i) {
        try { startActivity(i); }
        catch (ActivityNotFoundException | SecurityException e) { Toast.makeText(this, "These settings aren’t available on this device", Toast.LENGTH_SHORT).show(); }
    }
    private void render() {
        if (level == null) return;
        rendering = true;
        int n = VolumeState.level(this); boolean active = VolumeService.running();
        level.setText(String.valueOf(n)); slider.setProgress(n);
        AudioManager audio = getSystemService(AudioManager.class);
        boolean silent = audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0;
        detail.setText(n == 0 || silent ? "Media muted" : String.format(Locale.getDefault(), "%.1f dB reduction", -Level.decibels(n)));
        route.setText(getString(R.string.connected_profile, OutputProfile.read(this, audio).label));
        mute.setText(n == 0 || silent ? "Unmute" : "Mute");
        status.setText(active ? "Effect attached · output compatibility varies" : VolumeState.prefs(this).getString("status", "Off"));
        if (!active && "Fine control on".contentEquals(status.getText())) status.setText(R.string.control_off);
        toggle.setText(VolumeService.started() ? "Stop & mute media" : "Enable fine control");
        slider.setEnabled(active); minus.setEnabled(active); plus.setEnabled(active); mute.setEnabled(active);
        rendering = false;
    }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (VolumeService.adjust(key == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1)) {
                if (key == KeyEvent.KEYCODE_VOLUME_UP) VolumeService.resumeFromMute();
                render(); return true;
            }
        }
        return super.onKeyDown(key, event);
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout card() { LinearLayout l = column(); l.setPadding(dp(16), dp(18), dp(16), dp(18)); l.setBackground(tint(CARD)); return l; }
    private GradientDrawable tint(int color) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(22)); return d; }
    private TextView text(String s, int size, int color) { TextView v = new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(color); v.setLineSpacing(dp(3), 1); return v; }
    private Button button(String s, Runnable action) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(16); b.setMinHeight(dp(52)); b.setTextColor(WHITE);
        b.setOnClickListener(v -> action.run()); return b;
    }
    private void space(LinearLayout l, int height) { l.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
}
