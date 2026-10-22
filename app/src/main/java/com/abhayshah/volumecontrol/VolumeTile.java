package com.abhayshah.volumecontrol;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.*;

public final class VolumeTile extends TileService {
    @Override public void onStartListening() {
        Tile t = getQsTile();
        if (t != null) { t.setState(VolumeService.running() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE); t.updateTile(); }
    }
    @Override public void onClick() {
        if (VolumeService.started()) { VolumeService.stop(this); onStartListening(); return; }
        // Starting always goes through the visible activity: no background FGS launch restrictions or surprise unmute.
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        else openOnLegacyAndroid(intent);
    }
    // PendingIntent overload exists only on API 34+. This path is restricted to API 28–33.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private void openOnLegacyAndroid(Intent intent) { startActivityAndCollapse(intent); }
}
