package de.aikitori.retagger.convert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.aikitori.retagger.MainActivity
import de.aikitori.retagger.R
import de.aikitori.retagger.data.Entry
import de.aikitori.retagger.data.Library
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import kotlin.coroutines.coroutineContext

data class ConversionState(val total: Int, val index: Int = 0, val name: String = "", val fileProgress: Float = 0f) {
    val overall get() = (index + fileProgress) / total
}

data class ConversionRequest(
    val tree: Uri,
    val folderId: String,
    val files: List<Entry>,
    val quality: Mp3Quality,
    val deleteOriginals: Boolean,
)

data class ConversionResult(val message: String, val deletedOriginals: Boolean)

/**
 * Runs the MP3 conversion as a foreground service with a progress notification,
 * so Android doesn't stop it while the app is in the background.
 */
class ConversionService : Service() {
    companion object {
        private const val CHANNEL = "conversion"
        private const val NOTIFICATION = 1
        private const val ACTION_CANCEL = "de.aikitori.retagger.CANCEL_CONVERSION"

        /** Progress while a conversion runs, null otherwise. Observed by the UI. */
        var state by mutableStateOf<ConversionState?>(null)
            private set

        /** Outcome of the last conversion; the UI shows it and resets it. */
        var result by mutableStateOf<ConversionResult?>(null)

        private var pending: ConversionRequest? = null

        fun start(context: Context, request: ConversionRequest) {
            if (state != null || request.files.isEmpty()) return
            pending = request
            state = ConversionState(request.files.size)
            ContextCompat.startForegroundService(context, Intent(context, ConversionService::class.java))
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ConversionService::class.java).setAction(ACTION_CANCEL))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            return START_NOT_STICKY
        }
        val request = pending
        pending = null
        // startForeground must always be called after startForegroundService
        startInForeground(notification(state ?: ConversionState(1)))
        if (request == null || job != null) {
            if (job == null) finish()
            return START_NOT_STICKY
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "retagger:conversion")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        job = scope.launch { run(request) }
        return START_NOT_STICKY
    }

    private suspend fun run(request: ConversionRequest) {
        val library = Library(applicationContext)
        val files = request.files
        var done = 0
        val errors = mutableListOf<String>()
        val job = coroutineContext[Job]
        for ((i, entry) in files.withIndex()) {
            state = ConversionState(files.size, i, entry.name)
            update()
            var lastNotified = 0f
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    library.convertToMp3(
                        entry, request.tree, request.folderId, request.quality, request.deleteOriginals,
                        progress = { p ->
                            state = state?.copy(fileProgress = p)
                            // the notification needs no more than a few updates per file
                            if (p - lastNotified >= 0.05f) {
                                lastNotified = p
                                update()
                            }
                        },
                        cancelled = { job?.isActive == false },
                    )
                }
            }
            if (outcome.exceptionOrNull() is CancellationException || job?.isActive == false) break
            outcome.onSuccess { done++ }
                .onFailure { errors += "${entry.name}: ${it.localizedMessage ?: it.javaClass.simpleName}" }
        }
        val message = when {
            job?.isActive == false -> getString(R.string.convert_cancelled, done)
            errors.isEmpty() -> getString(R.string.convert_done, done)
            else -> getString(R.string.convert_errors, done, errors.size, errors.first())
        }
        result = ConversionResult(message, request.deleteOriginals)
        finish()
    }

    private fun finish() {
        state = null
        job = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ limits foreground media processing to 6 hours a day
        job?.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        state = null
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        val type = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIFICATION, notification, type)
    }

    private fun update() {
        state?.let { notifications.notify(NOTIFICATION, notification(it)) }
    }

    private fun notification(state: ConversionState): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ConversionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.converting, state.index + 1, state.total))
            .setContentText(state.name)
            .setProgress(1000, (state.overall * 1000).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.cancel), cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
