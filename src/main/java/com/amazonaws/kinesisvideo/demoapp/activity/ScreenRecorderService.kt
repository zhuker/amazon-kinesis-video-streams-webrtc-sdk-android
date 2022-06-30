package com.amazonaws.kinesisvideo.demoapp.activity

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.amazonaws.kinesisvideo.demoapp.R


class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = Intent(context, ScreenRecorderService::class.java)
        context.stopService(service)
    }
}

class ScreenRecorderService : Service() {
    override fun onBind(intent: Intent): IBinder? = null

    var streamSession: StreamSession? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val intent = intent ?: return START_NOT_STICKY

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            startForeground(101, Notification())
        } else {

            val channelId = "001"
            val channelName = "RecordChannel"
            val channel =
                NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
            channel.lightColor = Color.BLUE
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager?
            if (manager != null) {
                manager.createNotificationChannel(channel)

                val myIntent = Intent(this, NotificationReceiver::class.java)
                val pendingIntent = if (Build.VERSION.SDK_INT >= 31) {
                    PendingIntent.getBroadcast(this, 0, myIntent, PendingIntent.FLAG_IMMUTABLE)
                } else {
                    PendingIntent.getBroadcast(this, 0, myIntent, 0)
                }
                val action = Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_menu_share),
                    "button",
                    pendingIntent
                ).build()

                val notification: Notification =
                    Notification.Builder(applicationContext, channelId).setOngoing(true)
                        .setSmallIcon(R.drawable.ic_menu_share)
                        .setContentTitle("My Title")
                        .setContentText("My Description").addAction(action).build()

                startForeground(101, notification)
            }
        }
        streamSession = StreamSession(intent, applicationContext)

        return Service.START_STICKY
    }


    override fun stopService(name: Intent?): Boolean {
        Log.d(TAG, "stopService");
        return super.stopService(name)
    }

    companion object {
        const val TAG = "KVSScreenRecorderService"
        const val PermissionData = "permissionData"

    }
}