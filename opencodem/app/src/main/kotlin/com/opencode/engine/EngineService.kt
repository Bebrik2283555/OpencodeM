package com.opencode.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.opencode.Brain
import com.opencode.R
import com.opencode.tr
import com.opencode.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Owns the engine, jobs and observable state independently of Activities. */
class EngineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var brain: Brain
        private set
    private val binder = LocalBinder()
    private var foreground = false
    private var lastNotice = ""
    private var wasWorking = false
    private var closed = false

    inner class LocalBinder : Binder() { val service get() = this@EngineService }
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "OpencodeM", NotificationManager.IMPORTANCE_LOW))
        promote(notification(tr("Запуск движка…", "Starting engine…"), null, false))
        brain = Brain(application, scope, ::update)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (closed) { closed = false; foreground = false }
        if (brain.engineStopped) brain.restart()
        if (!foreground) promote(notification(tr("OpencodeM готов", "OpencodeM ready"), brain.open, false))
        val id = intent?.getStringExtra("sessionID")
        when (intent?.action) {
            STOP -> if (id != null) brain.stopSession(id)
            RESUME -> if (id != null) brain.resumeSession(id)
            CLOSE -> {
                brain.stopEngine()
                closed = true
                lastNotice = ""
                getSystemService(NotificationManager::class.java).cancel(2)
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
                foreground = false
                return START_NOT_STICKY
            }
        }
        update(brain)
        return START_STICKY
    }

    private fun promote(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(2, n)
        foreground = true
    }

    private fun action(action: String, id: String?): PendingIntent = PendingIntent.getService(this,
        (action + id).hashCode(), Intent(this, EngineService::class.java).setAction(action).putExtra("sessionID", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notification(text: String, id: String?, working: Boolean): Notification {
        val open = PendingIntent.getActivity(this, id?.hashCode() ?: 0,
            Intent(this, MainActivity::class.java).putExtra("sessionID", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("OpencodeM").setContentText(text).setContentIntent(open).setOnlyAlertOnce(true)
            .setOngoing(working).apply {
                addAction(Notification.Action.Builder(null, tr("Открыть", "Open"), open).build())
                if (id != null) addAction(Notification.Action.Builder(null,
                    if (working) tr("Стоп", "Stop") else tr("Продолжить", "Continue"),
                    action(if (working) STOP else RESUME, id)).build())
                if (!working) addAction(Notification.Action.Builder(null, tr("Закрыть движок", "Close engine"), action(CLOSE, null)).build())
            }.build()
    }

    fun update(state: Brain) {
        if (closed && !state.engineStopped) { closed = false; foreground = false }
        if (closed) return
        val waiting = state.asks.firstOrNull()?.session ?: state.forms.firstOrNull { state.hiddenForms[it.optString("id")] != true }?.optString("sessionID")
        val working = state.busy.values.any { it } || state.downloads.isNotEmpty() || state.terminals.any { it.running }
        val id = waiting ?: state.busy.entries.firstOrNull { it.value }?.key ?: state.open
        val text = when {
            waiting != null -> tr("Нужен ответ · нажмите, чтобы открыть вопрос", "Reply needed · tap to open the question")
            state.downloads.isNotEmpty() -> tr("Скачиваются модели: ${state.downloads.size}", "Downloading models: ${state.downloads.size}")
            working -> tr("Выполняются задачи: ${state.busy.values.count { it } + state.terminals.count { it.running }}", "Running tasks: ${state.busy.values.count { it } + state.terminals.count { it.running }}")
            wasWorking -> tr("Задача завершена", "Task finished")
            else -> tr("Движок готов", "Engine ready")
        }
        val key = "$text:$id:$working"
        if (lastNotice != key) {
            getSystemService(NotificationManager::class.java).notify(2, notification(text, id, working))
            lastNotice = key
        }
        wasWorking = working
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (::brain.isInitialized) brain.engineTimedOut()
        stopSelf()
    }
    override fun onDestroy() { if (::brain.isInitialized) brain.close(); super.onDestroy() }

    companion object {
        const val CHANNEL = "work"
        const val STOP = "com.opencode.STOP"
        const val RESUME = "com.opencode.RESUME"
        const val CLOSE = "com.opencode.CLOSE"
        fun start(context: Context) = context.startForegroundService(Intent(context, EngineService::class.java))
    }
}
