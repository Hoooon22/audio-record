package com.hoooon22.audiorecord

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * MediaProjection 과 녹음기를 들고 있는 포그라운드 서비스입니다.
 * 앱 화면을 닫아도 녹음이 계속되고, 알림에서 일시정지/중지할 수 있습니다.
 */
class RecorderService : Service() {

    enum class State { IDLE, RECORDING, PAUSED, SAVING }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        // 상태 표시줄의 "공유 중지" 등으로 시스템이 캡처를 끝낸 경우
        override fun onStop() = stopRecording()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            else -> if (state == State.IDLE) shutdown()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        recorder?.stop()
        releaseProjection()
        super.onDestroy()
    }

    private fun startRecording(intent: Intent) {
        // startForegroundService 로 시작됐으므로 무엇보다 먼저 포그라운드로 전환해야 합니다.
        // (Android 14+ 에서는 getMediaProjection 보다 먼저 호출돼야 합니다.)
        createChannel()
        goForeground(buildNotification(State.RECORDING))
        if (state != State.IDLE) {
            updateNotification()
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            fail(getString(R.string.projection_denied))
            return
        }

        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val mediaProjection = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection 을 가져올 수 없습니다")
            mediaProjection.registerCallback(projectionCallback, mainHandler)
            projection = mediaProjection

            val newRecorder = MediaVolumeRecorder(applicationContext, mediaProjection) { name, error ->
                mainHandler.post { onRecorderFinished(name, error) }
            }
            newRecorder.start()
            recorder = newRecorder
            state = State.RECORDING
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            releaseProjection()
            fail(getString(R.string.start_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun stopRecording() {
        val current = recorder
        if (current == null) {
            if (state == State.IDLE) shutdown()
            return
        }
        if (state == State.SAVING) return
        state = State.SAVING
        updateNotification()
        current.stop()
    }

    private fun setPaused(paused: Boolean) {
        val current = recorder ?: return
        if (state != State.RECORDING && state != State.PAUSED) return
        current.paused = paused
        state = if (paused) State.PAUSED else State.RECORDING
        updateNotification()
    }

    private fun onRecorderFinished(savedName: String?, error: Throwable?) {
        recorder = null
        releaseProjection()
        state = State.IDLE
        val message = when {
            savedName != null -> getString(R.string.saved, savedName)
            error is MediaVolumeRecorder.NoAudioException -> getString(R.string.no_audio)
            else -> getString(R.string.save_failed, error?.message ?: "")
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        shutdown()
    }

    private fun releaseProjection() {
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null
    }

    private fun fail(message: String) {
        state = State.IDLE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        shutdown()
    }

    private fun shutdown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                return
            } catch (e: Exception) {
                Log.w(TAG, "microphone FGS type rejected, using mediaProjection only", e)
            }
        }
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW
        )
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification() {
        if (state == State.IDLE) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: State): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(getString(R.string.app_name))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        when (state) {
            State.RECORDING, State.IDLE -> {
                builder.setContentText(getString(R.string.notification_recording))
                builder.addAction(action(R.drawable.ic_pause, R.string.action_pause, ACTION_PAUSE))
                builder.addAction(action(R.drawable.ic_stop, R.string.action_stop, ACTION_STOP))
            }
            State.PAUSED -> {
                builder.setContentText(getString(R.string.notification_paused))
                builder.addAction(action(R.drawable.ic_play, R.string.action_resume, ACTION_RESUME))
                builder.addAction(action(R.drawable.ic_stop, R.string.action_stop, ACTION_STOP))
            }
            State.SAVING -> builder.setContentText(getString(R.string.notification_saving))
        }
        return builder.build()
    }

    private fun action(icon: Int, title: Int, action: String): Notification.Action {
        val pending = PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, RecorderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), getString(title), pending).build()
    }

    companion object {
        private const val TAG = "RecorderService"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.hoooon22.audiorecord.action.START"
        const val ACTION_STOP = "com.hoooon22.audiorecord.action.STOP"
        const val ACTION_PAUSE = "com.hoooon22.audiorecord.action.PAUSE"
        const val ACTION_RESUME = "com.hoooon22.audiorecord.action.RESUME"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /** 화면(MainActivity)이 주기적으로 읽어 가는 현재 상태 */
        @Volatile
        var state: State = State.IDLE
            private set

        @Volatile
        private var recorder: MediaVolumeRecorder? = null

        val elapsedMs: Long
            get() = recorder?.elapsedMs ?: 0L

        fun send(context: Context, action: String) {
            context.startService(Intent(context, RecorderService::class.java).setAction(action))
        }
    }
}
