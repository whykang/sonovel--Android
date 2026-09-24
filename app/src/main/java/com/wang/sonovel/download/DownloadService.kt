package com.wang.sonovel.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.wang.sonovel.MainActivity
import com.wang.sonovel.R
import com.wang.sonovel.SoNovelApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * 前台服务：保证切到后台或锁屏时下载不被系统中断，并在通知栏显示进度
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val notified = HashSet<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    @OptIn(FlowPreview::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = (application as SoNovelApp).graph.downloads
        startInForeground(buildProgress(manager.tasks.value))
        acquireWakeLock()
        if (job == null) {
            job = scope.launch {
                launch {
                    manager.finished.collect { notifyFinished(it) }
                }
                manager.tasks.sample(500).collect { tasks ->
                    if (tasks.none { it.status.active }) {
                        // 留出时间让结束通知发出
                        delay(800)
                        if (manager.tasks.value.none { it.status.active }) stopSelfSafely()
                    } else {
                        runCatching {
                            NotificationManagerCompat.from(this@DownloadService).notify(NOTIFY_PROGRESS, buildProgress(tasks))
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(n: Notification) {
        runCatching {
            ServiceCompat.startForeground(
                this, NOTIFY_PROGRESS, n,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }
    }

    private fun contentIntent(bookPath: String? = null): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // 下载完成的通知直接进入阅读器
        if (bookPath != null) intent.putExtra(MainActivity.EXTRA_OPEN_BOOK, bookPath)
        else intent.putExtra(MainActivity.EXTRA_OPEN_TAB, "library")
        return PendingIntent.getActivity(
            this, bookPath?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildProgress(tasks: List<DownloadTask>): Notification {
        val active = tasks.filter { it.status.active }
        val cur = active.firstOrNull { it.status != TaskStatus.QUEUED } ?: active.firstOrNull()
        val b = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (cur == null) {
            return b.setContentTitle("So Novel").setContentText("准备下载…").build()
        }
        val queued = active.size - 1
        b.setContentTitle("《${cur.bookName}》${cur.status.label}")
        if (cur.status == TaskStatus.DOWNLOADING && cur.total > 0) {
            b.setProgress(cur.total, cur.done + cur.failed, false)
            b.setContentText("${cur.done + cur.failed}/${cur.total} 章" + if (queued > 0) " · 另有 $queued 本排队" else "")
        } else {
            b.setProgress(0, 0, true)
            b.setContentText(cur.message ?: cur.status.label)
        }
        return b.build()
    }

    private fun notifyFinished(t: DownloadTask) {
        if (t.status == TaskStatus.CANCELLED || !notified.add(t.id)) return
        val ok = t.status == TaskStatus.DONE
        val n = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (ok) "《${t.bookName}》下载完成" else "《${t.bookName}》下载失败")
            .setContentText(if (ok) "点击立即阅读" else (t.message?.lineSequence()?.firstOrNull() ?: ""))
            .setStyle(NotificationCompat.BigTextStyle().bigText(t.message ?: ""))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(t.output?.absolutePath.takeIf { ok }))
            .build()
        runCatching { NotificationManagerCompat.from(this).notify((1000 + t.id).toInt(), n) }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoNovel:download").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 对 dataSync 前台服务有时长限制
        stopSelfSafely()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_PROGRESS = "download_progress"
        const val CHANNEL_DONE = "download_done"
        private const val NOTIFY_PROGRESS = 1

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "下载进度", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "下载完成", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }
}
