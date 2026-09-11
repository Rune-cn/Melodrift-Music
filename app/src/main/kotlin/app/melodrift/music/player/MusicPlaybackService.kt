package app.melodrift.music.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.IBinder
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.melodrift.music.R
import app.melodrift.music.ui.MainActivity

/**
 * 前台媒体服务：后台播放 + 通知栏控制。
 * 通知按钮通过 PendingIntent.getService 回调本服务，再转发给 [PlayerController]。
 */
class MusicPlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_TOGGLE -> PlayerController.toggle()
            ACT_PREV -> PlayerController.prev()
            ACT_NEXT -> PlayerController.next()
            ACT_BACK -> PlayerController.seekBack()
            ACT_FWD -> PlayerController.seekForward()
        }
        // 前台通知（含播放状态）
        val notif = PlaybackNotifications.build(this)
        if (notif != null) {
            startForeground(NOTIFICATION_ID, notif)
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "playback"
        const val ACT_TOGGLE = "app.melodrift.music.ACTION_TOGGLE"
        const val ACT_PREV = "app.melodrift.music.ACTION_PREV"
        const val ACT_NEXT = "app.melodrift.music.ACTION_NEXT"
        const val ACT_BACK = "app.melodrift.music.ACTION_BACK"
        const val ACT_FWD = "app.melodrift.music.ACTION_FWD"

        /** 启动前台服务（仅当有当前歌曲） */
        fun start(context: Context) {
            if (PlayerController.current == null) return
            val intent = Intent(context, MusicPlaybackService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 停止服务并移除通知 */
        fun stop(context: Context) {
            context.stopService(Intent(context, MusicPlaybackService::class.java))
        }
    }
}

/** 通知构建与动作 */
object PlaybackNotifications {

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                MusicPlaybackService.CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Now playing" }
        )
    }

    /** 构建媒体通知；无当前歌曲返回 null */
    fun build(context: Context): Notification? {
        val song = PlayerController.current ?: return null
        ensureChannel(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun actionPi(action: String): PendingIntent =
            PendingIntent.getService(
                context, action.hashCode(),
                Intent(context, MusicPlaybackService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        val builder = NotificationCompat.Builder(context, MusicPlaybackService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(song.name)
            .setContentText(song.artistNames)
            .setSubText(song.album?.name ?: "")
            .setContentIntent(openPi)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(PlayerController.isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(PlayerController.mediaSessionToken())
                    .setShowActionsInCompactView(0, 2, 4)
            )
            .addAction(R.drawable.ic_notif_prev, "prev", actionPi(MusicPlaybackService.ACT_PREV))
            .addAction(R.drawable.ic_notif_back, "back", actionPi(MusicPlaybackService.ACT_BACK))
            .addAction(
                if (PlayerController.isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play,
                if (PlayerController.isPlaying) "pause" else "play",
                actionPi(MusicPlaybackService.ACT_TOGGLE)
            )
            .addAction(R.drawable.ic_notif_fwd, "fwd", actionPi(MusicPlaybackService.ACT_FWD))
            .addAction(R.drawable.ic_notif_next, "next", actionPi(MusicPlaybackService.ACT_NEXT))

        // 封面：优先用会话已加载的缓存，避免重复下载
        val cached = PlayerController.coverBitmap()
        if (cached != null) {
            builder.setLargeIcon(cached)
        } else {
            song.coverUrl?.let { url ->
                val bmp = loadBitmapSync(url)
                if (bmp != null) builder.setLargeIcon(bmp)
            }
        }

        return builder.build()
    }

    /** 同步加载网络图片为 Bitmap（IO 线程调用） */
    fun loadBitmapSync(url: String): Bitmap? = try {
        val conn = java.net.URL(url).openConnection()
        conn.setRequestProperty("User-Agent", app.melodrift.music.net.NcmApi.USER_AGENT)
        conn.connect()
        val stream = conn.getInputStream()
        try {
            android.graphics.BitmapFactory.decodeStream(stream)
        } finally {
            try { stream.close() } catch (_: Exception) {}
        }
    } catch (_: Exception) {
        null
    }

    /** 发送/刷新通知（若服务已启动） */
    fun notify(context: Context) {
        if (PlayerController.current == null) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        build(context)?.let {
            NotificationManagerCompat.from(context).notify(MusicPlaybackService.NOTIFICATION_ID, it)
        }
    }
}