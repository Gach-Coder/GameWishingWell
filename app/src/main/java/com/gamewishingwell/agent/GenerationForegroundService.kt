package com.gamewishingwell.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gamewishingwell.MainActivity
import com.gamewishingwell.WishwellApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 生成期前台服务的启动入口（Agent 侧唯一接触点，幂等）。 */
object GenerationForeground {
    fun start(context: Context) {
        ContextCompat.startForegroundService(
            context, Intent(context, GenerationForegroundService::class.java)
        )
    }
}

/**
 * 生成期前台服务（dataSync）：让 Agent Loop 在切后台/息屏后继续运行。
 *
 * 生命周期完全由会话状态驱动：Agent 每次开始生成任务时启动本服务（GameAgent.trackGenerationJob），
 * 服务自行观察 session.isGenerating——变为 false（完成/失败/确认门等待/用户停止）即收尾；
 * Agent 侧不做主动 stop，避免上一回合的取消回调与下一回合的启动产生竞态。
 *
 * 通知内容即 agentStage（UI 零技术细节纪律对通知同样成立），带"停止"动作（接 GameAgent.stopGeneration）；
 * 生成期间持有 partial wakelock，防止息屏后 CPU 休眠导致 SSE 流停摆。
 * Android 15+ 的 dataSync 6 小时上限由 onTimeout 兜底：退前台但不终止生成（退回无前台服务的存活水平）。
 */
class GenerationForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** 会话观察协程；以存活状态判重（替代一次性 observing 标志）。 */
    private var observerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runCatching { container().gameAgent.stopGeneration() }
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        startInForeground(container().gameAgent.session.value.agentStage)
        observeSession()
        return START_NOT_STICKY
    }

    /** Android 15+：dataSync 前台服务超时（6h/24h 窗口）——退前台，不终止生成。 */
    override fun onTimeout(startId: Int) {
        stopForegroundAndSelf()
    }

    private fun startInForeground(stage: String) {
        val notification = buildNotification(stage)
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLock()
    }

    private fun observeSession() {
        // 实例复用（上一回合 stopSelf 尚未销毁时新回合的 start 先到达）会让本方法在
        // 同一实例上再次进入：旧观察协程已随上回合收尾取消，必须重新启动观察——
        // 曾用一次性 observing 标志判重，导致复用实例永远不再观察会话（通知冻结、
        // 服务无法收尾）。以协程存活状态判重即可。
        if (observerJob?.isActive == true) return
        observerJob = scope.launch {
            var lastStage: String? = null
            var lastUpdateAt = 0L
            container().gameAgent.session.collect { s ->
                if (!s.isGenerating) {
                    // 用最新状态复核后再收尾：回合间隙的 false 快照可能已被新一轮
                    // 生成（true）覆盖——若按旧快照停服务，新一轮会在后台失去前台
                    // 服务与 wakelock/WifiLock，表现为后台网络异常。
                    if (container().gameAgent.session.value.isGenerating) return@collect
                    stopForegroundAndSelf()
                    cancel()
                    return@collect
                }
                val now = SystemClock.elapsedRealtime()
                if (s.agentStage != lastStage && now - lastUpdateAt >= MIN_STAGE_INTERVAL_MS) {
                    lastStage = s.agentStage
                    lastUpdateAt = now
                    runCatching {
                        notificationManager().notify(NOTIFICATION_ID, buildNotification(s.agentStage))
                    }
                }
                // wakelock 续约：PARTIAL_WAKE_LOCK 带 1h 超时，精品档长修复轮可能超过；
                // 到期后 CPU 休眠会让后台 SSE 流停摆（读超时后整回合被判网络异常）。
                if (wakeLock?.isHeld != true) acquireWakeLock()
            }
        }
    }

    private fun buildNotification(stage: String): Notification {
        val launch = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentPi = PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, GenerationForegroundService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("许愿井 · 正在制作游戏")
            .setContentText(stage)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(contentPi)
            .addAction(0, "停止", stopPi)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "游戏生成进度", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台制作游戏时的进度通知"
                setShowBadge(false)
            }
            notificationManager().createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gamewishingwell:generation")
            .apply { runCatching { acquire(WAKELOCK_TIMEOUT_MS) } }
        // WifiLock：后台运行时保持 Wi-Fi 无线电高性能模式，降低长流式请求被网卡省电中断的概率。
        wifiLock = (getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "gamewishingwell:generation")
            .apply { runCatching { acquire() } }
    }

    private fun stopForegroundAndSelf() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun container() = (application as WishwellApplication).container

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "game_generation"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.gamewishingwell.agent.STOP_GENERATION"
        private const val MIN_STAGE_INTERVAL_MS = 900L
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L
    }
}
