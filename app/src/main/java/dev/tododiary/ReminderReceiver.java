package dev.tododiary;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        TodoScheduler.createChannel(context);

        long todoId = intent.getLongExtra(TodoScheduler.EXTRA_TODO_ID, -1L);
        String title = intent.getStringExtra(TodoScheduler.EXTRA_TITLE);
        String notes = intent.getStringExtra(TodoScheduler.EXTRA_NOTES);
        String time = intent.getStringExtra(TodoScheduler.EXTRA_TIME);

        if (title == null || title.isEmpty()) {
            title = "To-do reminder";
        }
        if (notes == null || notes.isEmpty()) {
            notes = "Scheduled at " + (time == null ? "" : time);
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            Intent activityIntent = new Intent(context, MainActivity.class);
            PendingIntent contentIntent = PendingIntent.getActivity(
                    context,
                    0,
                    activityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            Notification notification = new Notification.Builder(context, TodoScheduler.CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_tododiary)
                    .setContentTitle(title)
                    .setContentText(notes)
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .build();
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify((int) Math.max(1L, todoId), notification);
            }
        }

        TodoScheduler.scheduleAll(context);
    }
}
