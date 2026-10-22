package com.abhayshah.volumecontrol;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Color;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Available-update banner; downloads are verified before opening Android's installer. */
final class UpdateManager {
    private static final String MANIFEST = "https://raw.githubusercontent.com/AbhayShah06/VolumeControl/main/update.json";
    private final Activity activity;
    private final TextView title;
    private final TextView detail;
    private final Button action;
    private final LinearLayout banner;
    private final java.util.function.Consumer<String> feedback;
    interface FeedSource { String read() throws Exception; }
    private final FeedSource feed;
    private boolean checking,downloading,manualCheck;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private JSONObject available;
    private boolean waitingForInstallPermission;
    private volatile boolean closed;
    private long lastCheck;

    static UpdateManager attach(Activity activity,ViewGroup parent,java.util.function.Consumer<String> feedback){return new UpdateManager(activity,parent,feedback,()->read(MANIFEST+"?t="+System.currentTimeMillis()/60000));}
    UpdateManager(Activity a,ViewGroup parent,java.util.function.Consumer<String> feedback,FeedSource feed){
        this.feed=feed;
        cleanupInstalledUpdate(a);
        activity=a;this.feedback=feedback;banner=new LinearLayout(a);banner.setOrientation(LinearLayout.VERTICAL);int padding=Math.round(14*a.getResources().getDisplayMetrics().density);banner.setPadding(padding,padding,padding,padding);banner.setBackgroundResource(R.drawable.bg_update);banner.setVisibility(android.view.View.GONE);
        title=new TextView(a);title.setTextColor(Color.WHITE);title.setTextSize(16);detail=new TextView(a);detail.setTextColor(0xffc2d7cc);detail.setTextSize(13);banner.addView(title);banner.addView(detail);
        LinearLayout buttons=new LinearLayout(a);action=new Button(a);action.setAllCaps(false);Button later=new Button(a);later.setText("Later");later.setAllCaps(false);buttons.addView(action,new LinearLayout.LayoutParams(0,-2,1));buttons.addView(later,new LinearLayout.LayoutParams(-2,-2));banner.addView(buttons);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=padding;parent.addView(banner,params);
        action.setOnClickListener(v->install());later.setOnClickListener(v->{banner.setVisibility(android.view.View.GONE);if(!downloading&&available!=null)a.getSharedPreferences("updates",0).edit().putInt("dismissed_version",available.optInt("versionCode")).apply();});check(false);
    }
    void checkManually(){check(true);}

    private void ui(Runnable task) {
        activity.runOnUiThread(() -> { if (!closed && !activity.isDestroyed()) task.run(); });
    }

    private void check(boolean manual){
        if(closed)return;if(downloading){banner.setVisibility(android.view.View.VISIBLE);return;}if(checking){manualCheck|=manual;return;}manualCheck=manual;checking=true;
        lastCheck = android.os.SystemClock.elapsedRealtime();
        if(manual)feedback.accept("Checking for updates…");
        worker.execute(()->{
            try{
                JSONObject manifest=new JSONObject(feed.read());int installed=activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionCode;
                if(manifest.getInt("versionCode")>installed){
                    UpdatePolicy.validate(manifest.getInt("versionCode"),installed,manifest.getString("apkUrl"),manifest.getString("sha256"));
                    ui(()->{checking=false;available=manifest;title.setText("Update "+manifest.optString("versionName")+" available");detail.setText(manifest.optString("notes","App improvements"));action.setEnabled(true);action.setText("Update now");
                        boolean dismissed=activity.getSharedPreferences("updates",0).getInt("dismissed_version",0)>=manifest.optInt("versionCode");banner.setVisibility(manualCheck||!dismissed?android.view.View.VISIBLE:android.view.View.GONE);if(manualCheck)feedback.accept(null);
                    });
                }else ui(()->{checking=false;available=null;banner.setVisibility(android.view.View.GONE);if(manualCheck)feedback.accept("You're up to date · "+BuildConfig.VERSION_NAME);});
            }catch(Exception error){ui(()->{checking=false;if(available==null)banner.setVisibility(android.view.View.GONE);if(manualCheck)feedback.accept("Couldn't check for updates. Check your connection and try again.");});}
        });
    }

    private void install() {
        if(closed||downloading||available==null)return;
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            waitingForInstallPermission = true;
            detail.setText("Allow updates from VolumeControl on the next screen. Download will continue when you return.");
            try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()))); }
            catch (RuntimeException e) { waitingForInstallPermission = false; detail.setText("Open Settings → Apps → Special app access → Install unknown apps, and allow VolumeControl."); }
            return;
        }
        downloading=true;action.setEnabled(false); action.setText("Downloading…");
        JSONObject manifest = available;
        worker.execute(() -> {
            File apk = new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "pending-update.apk");
            try {
                download(manifest.getString("apkUrl"), apk);
                if (!hash(apk).equalsIgnoreCase(manifest.getString("sha256"))) throw new SecurityException("Checksum mismatch");
                verifyPackage(apk, manifest.getInt("versionCode"));
                ui(() -> {
                    try {
                        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", apk);
                        activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                        activity.getSharedPreferences("updates", 0).edit().putInt("pending_version", manifest.getInt("versionCode")).apply();
                        detail.setText("Confirm Install in Android's installer. If you cancel, tap Update now to retry.");
                    } catch (Exception error) { detail.setText("Couldn't open the installer. Tap Update now to retry."); }
                    downloading=false;action.setText("Update now"); action.setEnabled(true);
                });
            } catch (Exception error) {
                apk.delete();
                ui(() -> { downloading=false;detail.setText("Update couldn't be downloaded or verified. Tap to retry."); action.setText("Retry update"); action.setEnabled(true); });
            }
        });
    }

    void onResume() {
        cleanupInstalledUpdate(activity);
        if (waitingForInstallPermission) {
            waitingForInstallPermission = false;
            if (activity.getPackageManager().canRequestPackageInstalls()) install();
            else detail.setText("Install permission wasn't granted. Tap Update now when you're ready.");
        } else if (!checking && !downloading && android.os.SystemClock.elapsedRealtime() - lastCheck > 60000) check(false);
    }

    void close() { closed = true; worker.shutdownNow(); }

    static void cleanupInstalledUpdate(Activity activity) {
        android.content.SharedPreferences prefs = activity.getSharedPreferences("updates", 0);
        int pending = prefs.getInt("pending_version", 0);
        if (pending <= 0) return;
        try {
            int installed = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionCode;
            File directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (installed < pending || directory == null) return;
            File apk = new File(directory, "pending-update.apk");
            if (!apk.exists() || apk.delete()) prefs.edit().remove("pending_version").apply();
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
    }

    private void verifyPackage(File apk, int expectedVersion) throws Exception {
        PackageManager pm = activity.getPackageManager();
        PackageInfo next = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        PackageInfo current = pm.getPackageInfo(activity.getPackageName(), PackageManager.GET_SIGNATURES);
        if (next == null || !activity.getPackageName().equals(next.packageName) || next.versionCode != expectedVersion
                || next.versionCode <= current.versionCode || next.signatures == null || current.signatures == null
                || !java.util.Arrays.equals(next.signatures, current.signatures)) throw new SecurityException("Unexpected package, version or signer");
    }

    private static HttpURLConnection connection(String address) throws Exception {
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol())) throw new SecurityException("HTTPS required");
        HttpURLConnection c = (HttpURLConnection)url.openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(15000);c.setUseCaches(false);c.setRequestProperty("Cache-Control","no-cache");
        c.setRequestProperty("User-Agent", "VolumeControl-Updater");
        if (c.getResponseCode() != 200) { c.disconnect(); throw new IOException("HTTP error"); }
        return c;
    }

    private static String read(String url) throws Exception {
        HttpURLConnection c = connection(url);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) { if (out.size() + count > 65536) throw new IOException("Manifest too large"); out.write(buffer, 0, count); }
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    private void download(String url, File apk) throws Exception {
        HttpURLConnection c = connection(url);
        long total = c.getContentLengthLong();
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
            byte[] buffer = new byte[16384]; int count; long received = 0; long lastUpdate = 0;
            while ((count = in.read(buffer)) != -1) {
                if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                received += count; if (received > 150L * 1024 * 1024) throw new IOException("APK too large");
                out.write(buffer, 0, count);
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastUpdate > 250) {
                    lastUpdate = now;
                    String progress = total > 0 ? "Downloading " + (received * 100 / total) + "%" : "Downloading " + received / 1024 + " KB";
                    ui(() -> action.setText(progress));
                }
            }
        } finally { c.disconnect(); }
    }

    private static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(java.util.Locale.US, "%02x", value & 255));
        return result.toString();
    }
}
