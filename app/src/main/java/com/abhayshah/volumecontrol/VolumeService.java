package com.abhayshah.volumecontrol;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import java.util.List;

public final class VolumeService extends Service {
    static final String STOP = "com.abhayshah.volumecontrol.STOP";
    static final String APPLY = "com.abhayshah.volumecontrol.APPLY";
    private static volatile VolumeService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager audio;
    private GainEngine engine;
    private OutputProfile profile;
    private boolean foreignAudio;
    private boolean suspended;
    private boolean resumeAfterInterruption;
    private int lastNative = -1;
    private boolean registered;
    private int effectGeneration;
    private final Runnable recheckRoute = this::routeChanged;

    static boolean running() { return instance != null && instance.engine != null && !instance.suspended; }
    static boolean started() { return instance != null; }
    static void start(Context c) {
        androidx.core.content.ContextCompat.startForegroundService(c, new Intent(c, VolumeService.class));
    }
    static void stop(Context c) { c.stopService(new Intent(c, VolumeService.class)); }
    static void update(Context c) {
        VolumeService s = instance;
        if (s != null) s.handler.post(s::apply);
    }
    static boolean adjust(int direction) {
        VolumeService s = instance;
        if (s == null || !Level.handlesKeys(true, running(), s.audio.isMusicActive(), s.audio.getMode())) return false;
        VolumeState.level(s, VolumeState.level(s) + direction);
        s.apply();
        return true;
    }

    private final AudioDeviceCallback devices = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { routeEvent(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { routeEvent(); }
    };
    private final AudioManager.AudioPlaybackCallback playback = new AudioManager.AudioPlaybackCallback() {
        @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            foreignAudio = configs.stream().anyMatch(c -> {
                int usage = c.getAudioAttributes().getUsage();
                return Level.interruptsMedia(usage);
            });
            checkInterruption();
        }
    };
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (instance != VolumeService.this) return;
            checkInterruption();
            int now = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (now != lastNative) { lastNative = now; status(suspended ? "Paused · media muted" : "Fine control on"); }
            handler.postDelayed(this, 700);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        audio = getSystemService(AudioManager.class);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel("control", "Volume control", NotificationManager.IMPORTANCE_LOW);
        ch.setSound(null, null); ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (instance == this) { apply(); return START_NOT_STICKY; }
        try {
            Notification notification = notification("Starting fine control");
            if (Build.VERSION.SDK_INT >= 34) startForeground(10, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(10, notification);
            if (audio.isVolumeFixed()) throw new IllegalStateException("This output has fixed volume");
            if (audio.getMode() != AudioManager.MODE_NORMAL) throw new IllegalStateException("Finish your call before enabling control");
            // Recall gain while muted, then restore only the lowest native step after this explicit Enable tap.
            boolean wasAudible = audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0;
            muteMedia();
            engine = createEngine();
            profile = OutputProfile.read(this, audio);
            int level = VolumeState.recalled(this, profile, VolumeState.level(this));
            VolumeState.level(this, level);
            instance = this;
            apply();
            if (engine == null) return START_NOT_STICKY;
            if (wasAudible && level > 0) resumeFromMute();
            registered = true;
            audio.registerAudioDeviceCallback(devices, handler);
            audio.registerAudioPlaybackCallback(playback, handler);
            handler.post(watch);
        } catch (RuntimeException e) { fail("Fine control unavailable on this output"); }
        // An OS restart must never silently restore a louder gain or claim a dead effect is active.
        return START_NOT_STICKY;
    }

    static String connectedProfile(AudioManager audio) {
        boolean usb = false, wired = false, bt = false;
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_USB_DEVICE: case AudioDeviceInfo.TYPE_USB_HEADSET: usb = true; break;
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: case AudioDeviceInfo.TYPE_WIRED_HEADSET: wired = true; break;
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: case AudioDeviceInfo.TYPE_BLE_HEADSET: bt = true; break;
                default: break;
            }
        }
        if ((usb ? 1 : 0) + (wired ? 1 : 0) + (bt ? 1 : 0) > 1) return "multiple";
        return usb ? "usb" : wired ? "wired" : bt ? "bluetooth" : "speaker";
    }
    private void routeEvent() {
        routeChanged();
        handler.removeCallbacks(recheckRoute);
        // USB descriptor enumeration can finish just after Android announces the audio port.
        handler.postDelayed(recheckRoute, 250);
    }
    private void routeChanged() {
        if (engine == null) return;
        OutputProfile next = OutputProfile.read(this, audio);
        if (profile != null && next.key.equals(profile.key)) return;
        // Mute before rebuilding: effects can be lost or bypassed during device changes.
        muteMedia(); closeEngine();
        VolumeState.level(this, VolumeState.recalled(this, next, VolumeState.level(this)));
        profile = next;
        try { engine = createEngine(); apply(); }
        catch (RuntimeException unavailable) { fail("Fine control unavailable after output change"); }
        status("Output changed · saved level loaded; tap Unmute when ready");
    }
    private void checkInterruption() {
        boolean pause = foreignAudio || audio.getMode() != AudioManager.MODE_NORMAL;
        if (pause == suspended) return;
        suspended = pause;
        if (pause) {
            resumeAfterInterruption = audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0;
            muteMedia(); closeEngine(); status("Paused for call or alarm · media muted");
        }
        else {
            try {
                OutputProfile current = OutputProfile.read(this, audio);
                boolean sameOutput = profile != null && current.key.equals(profile.key);
                if (!sameOutput) { profile = current; VolumeState.level(this, VolumeState.recalled(this, current, VolumeState.level(this))); }
                engine = createEngine(); apply();
                if (resumeAfterInterruption && sameOutput && VolumeState.level(this) > 0) resumeFromMute();
                resumeAfterInterruption = false;
            }
            catch (RuntimeException unavailable) { fail("Fine control unavailable after interruption"); }
        }
    }
    private void apply() {
        if (engine == null || suspended) return;
        try {
            int level = VolumeState.level(this);
            if (level == 0) muteMedia();
            engine.apply(level);
            if (VolumeState.prefs(this).getBoolean("remember", true)) VolumeState.prefs(this).edit()
                    .putInt("profile_" + profile.key, level).putString("label_" + profile.key, profile.label).apply();
            status("Fine control on");
        } catch (RuntimeException e) { fail("Audio effect stopped · media muted"); }
    }
    static void resumeFromMute() {
        VolumeService s = instance;
        if (s != null && running() && s.audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
                && VolumeState.level(s) > 0) {
            try {
                s.engine.apply(VolumeState.level(s));
                s.audio.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0);
            } catch (RuntimeException failure) { s.fail("Could not restore fine control"); }
        }
    }
    private void muteMedia() {
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        } catch (RuntimeException ignored) { /* Never crash another app over a vendor volume-policy error. */ }
    }
    private void status(String message) {
        VolumeState.prefs(this).edit().putString("status", message).apply();
        getSystemService(NotificationManager.class).notify(10, notification(message));
    }
    private void fail(String reason) {
        muteMedia(); closeEngine();
        VolumeState.prefs(this).edit().putString("status", reason).apply();
        stopSelf();
    }
    private GainEngine createEngine() {
        int generation = ++effectGeneration;
        return new GainEngine(0, () -> handler.post(() -> {
            if (generation == effectGeneration && engine != null) fail("Another audio effect took control");
        }));
    }
    private void closeEngine() {
        effectGeneration++;
        if (engine != null) { engine.close(); engine = null; }
    }
    private Notification notification(String text) {
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), pendingFlags);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, VolumeService.class).setAction(STOP), pendingFlags);
        return new Notification.Builder(this, "control").setSmallIcon(com.abhayshah.volumecontrol.R.drawable.ic_volume)
                .setContentTitle("VolumeControl · " + VolumeState.level(this)).setContentText(text)
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (registered) {
            try { audio.unregisterAudioDeviceCallback(devices); } catch (RuntimeException ignored) { }
            try { audio.unregisterAudioPlaybackCallback(playback); } catch (RuntimeException ignored) { }
        }
        muteMedia();
        closeEngine();
        if (instance == this) instance = null;
        android.service.quicksettings.TileService.requestListeningState(this, new ComponentName(this, VolumeTile.class));
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
