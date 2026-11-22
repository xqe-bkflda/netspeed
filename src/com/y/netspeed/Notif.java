package com.y.netspeed;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 前台服务通知：最低优先级，不出横幅、不响。 */
final class Notif {

    private static final String CH = "netspeed_float";
    static final int NID = 0x51F;

    static Notification build(Context c, String text) {
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CH, "悬浮球", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(c, CH);
        } else {
            b = new Notification.Builder(c);
            b.setPriority(Notification.PRIORITY_MIN);
        }
        b.setContentTitle("测速")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true);
        return b.build();
    }

    private Notif() {}
}
