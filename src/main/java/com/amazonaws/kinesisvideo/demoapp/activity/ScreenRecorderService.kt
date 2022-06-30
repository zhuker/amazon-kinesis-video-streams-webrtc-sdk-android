package com.amazonaws.kinesisvideo.demoapp.activity

import android.R
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.VideoCapturer


class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = Intent(context, ScreenRecorderService::class.java)
        context.stopService(service)
    }
}


class ScreenRecorderService : Service() {
    override fun onBind(intent: Intent): IBinder? = null


    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            startForeground(101, Notification())
            return Service.START_STICKY
        }
        val permissionData: Intent? = intent?.getParcelableExtra(PermissionData)

        val channelId = "001"
        val channelName = "RecordChannel"
        val channel =
            NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_NONE)
        channel.lightColor = Color.BLUE
        channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
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
                Icon.createWithResource(this, R.drawable.presence_video_online),
                "button",
                pendingIntent
            ).build()

            val notification: Notification =
                Notification.Builder(applicationContext, channelId).setOngoing(true)
                    .setSmallIcon(R.drawable.sym_def_app_icon)
                    .setContentTitle("notificationTitle")
                    .setContentText("notificationDescription").addAction(action).build()

            startForeground(101, notification)
        }
        WebRtcActivity.videoCapturer?.startCapture(
            WebRtcActivity.VIDEO_SIZE_WIDTH,
            WebRtcActivity.VIDEO_SIZE_HEIGHT,
            WebRtcActivity.VIDEO_FPS
        )

        return Service.START_STICKY
    }

    private fun createVideoCapturer(permissionData: Intent): VideoCapturer {
        return ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.e(TAG, "user has revoked permissions")
            }
        })
    }

    override fun stopService(name: Intent?): Boolean {
        Log.d("Gagaga", "stopService");
        return super.stopService(name)
    }

    companion object {
        const val TAG = "KVSScreenRecorderService"
        const val PermissionData = "permissionData"

    }
}