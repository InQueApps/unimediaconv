package ab.inque.converter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ConversionWork(
    val tempFiles: List<File>,
    val targetFormat: FileFormat,
    val sourceFormat: FileFormat?,
    val outputAsZip: Boolean,
    val scale: Float?,
    val quality: QualityPreset,
    val toolOptions: MediaToolOptions
)

sealed class ServiceResult {
    object Idle : ServiceResult()
    data class Running(val progress: Float, val batchIndex: Int, val batchTotal: Int) : ServiceResult()
    data class Success(val file: File?, val files: List<File>?, val totalFiles: Int) : ServiceResult()
    data class Failure(val error: String) : ServiceResult()
}

/**
 * Foreground service that owns the conversion coroutine scope. The actual conversion
 * work runs here, surviving Activity/ViewModel destruction. Progress and results are
 * published via the companion [result] StateFlow so any ViewModel can observe them.
 */
class ConversionForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "conversion_progress"
        private const val NOTIFICATION_ID = 1001
        private const val DONE_NOTIFICATION_ID = 1002

        const val ACTION_START = "ab.inque.converter.START"
        const val ACTION_STOP  = "ab.inque.converter.STOP"
        const val EXTRA_FILE_COUNT = "file_count"

        @Volatile
        var instance: ConversionForegroundService? = null
            private set

        @Volatile
        var pendingWork: ConversionWork? = null

        private val _result = MutableStateFlow<ServiceResult>(ServiceResult.Idle)
        val result: StateFlow<ServiceResult> = _result.asStateFlow()

        fun resetResult() {
            _result.value = ServiceResult.Idle
        }

        fun start(context: Context, fileCount: Int) {
            val intent = Intent(context, ConversionForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_FILE_COUNT, fileCount)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ConversionForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }
    }

    private lateinit var notifManager: NotificationManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var conversionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (instance === this) instance = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val fileCount = intent.getIntExtra(EXTRA_FILE_COUNT, 1)
                val notification = buildNotification(fileCount, 0, "Starting…")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                pendingWork?.let { work ->
                    pendingWork = null
                    startConversion(work)
                }
            }
            ACTION_STOP -> {
                conversionJob?.cancel()
                conversionJob = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // ── Conversion orchestration ────────────────────────────────────────

    private fun startConversion(work: ConversionWork) {
        conversionJob?.cancel()
        conversionJob = serviceScope.launch {
            val job = coroutineContext[Job]!!
            val totalFiles = work.tempFiles.size
            val timeoutMs = (1_800_000L * totalFiles).coerceAtMost(7_200_000L)

            _result.value = ServiceResult.Running(0f, 0, totalFiles)

            try {
                withTimeout(timeoutMs) {
                    val converter = UniversalConverter()
                    val progressCallback: (Float) -> Unit = { p ->
                        if (!job.isActive) throw CancellationException()
                        val pct = (p * 100).toInt()
                        val current = (_result.value as? ServiceResult.Running)?.batchIndex ?: 0
                        val statusText = progressToStatus(p, current, totalFiles)
                        _result.value = ServiceResult.Running(p, current, totalFiles)
                        updateNotification(totalFiles, pct, statusText)
                    }

                    val convResult = if (totalFiles == 1) {
                        ConvertResult.Single(
                            converter.convertFile(
                                work.tempFiles[0], work.targetFormat,
                                this@ConversionForegroundService,
                                progressCallback, work.scale, work.quality, work.toolOptions
                            )
                        )
                    } else {
                        convertMultiple(work, converter, job, progressCallback)
                    }

                    _result.value = when (convResult) {
                        is ConvertResult.Single -> ServiceResult.Success(convResult.file, null, totalFiles)
                        is ConvertResult.Multiple -> ServiceResult.Success(null, convResult.files, totalFiles)
                    }

                    postResultNotification(
                        title = if (totalFiles > 1) "$totalFiles files converted" else "Conversion complete",
                        text = "Tap to view your converted file",
                        success = true
                    )
                }
            } catch (_: CancellationException) {
                // Cancelled by user or system — silently stop
            } catch (e: Exception) {
                val errorMsg = when (e) {
                    is TimeoutCancellationException -> "Conversion timed out. Try a smaller file or batch."
                    else -> e.message ?: "Conversion failed"
                }
                _result.value = ServiceResult.Failure(errorMsg)
                postResultNotification(title = "Conversion failed", text = errorMsg, success = false)
            } finally {
                work.tempFiles.forEach { if (it.exists()) it.delete() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun convertMultiple(
        work: ConversionWork,
        converter: UniversalConverter,
        job: Job,
        progressCallback: (Float) -> Unit
    ): ConvertResult {
        val tempFiles = work.tempFiles
        val total = tempFiles.size

        val allImages = tempFiles.all { f ->
            FileFormat.fromExtension(f.extension)?.category == FormatCategory.IMAGE
        }
        if (allImages && work.targetFormat == FileFormat.PDF) {
            val file = converter.convertImagesToPdf(tempFiles, this@ConversionForegroundService) { p ->
                if (!job.isActive) throw CancellationException()
                _result.value = ServiceResult.Running(0.2f + 0.8f * p, total, total)
                updateNotification(total, ((0.2f + 0.8f * p) * 100).toInt(), "Merging into PDF…")
            }
            return ConvertResult.Single(file)
        }

        val outputs = mutableListOf<File>()
        for ((i, file) in tempFiles.withIndex()) {
            if (!job.isActive) throw CancellationException()
            _result.value = ServiceResult.Running(i.toFloat() / total, i + 1, total)
            updateNotification(total, (i * 100 / total), "File ${i + 1} of $total")

            val p0 = i.toFloat() / total
            val p1 = (i + 1).toFloat() / total
            val out = converter.convertFile(
                file, work.targetFormat, this@ConversionForegroundService,
                { p -> progressCallback(p0 + (p1 - p0) * p) },
                work.scale, work.quality, work.toolOptions
            )
            outputs.add(out)
        }

        if (outputs.size == 1) return ConvertResult.Single(outputs[0])
        if (!work.outputAsZip) return ConvertResult.Multiple(outputs.toList())

        val zipFile = File(cacheDir, "converted_${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            outputs.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        outputs.forEach { it.delete() }
        return ConvertResult.Single(zipFile)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun progressToStatus(p: Float, batchCurrent: Int, batchTotal: Int): String {
        if (batchTotal > 1) return "File $batchCurrent of $batchTotal"
        return when {
            p < 0.2f -> "Starting…"
            p < 0.5f -> "Processing…"
            p < 0.9f -> "Converting…"
            else -> "Finalizing…"
        }
    }

    // ── Notifications ───────────────────────────────────────────────────

    fun updateNotification(fileCount: Int, progressPct: Int, statusText: String) {
        notifManager.notify(NOTIFICATION_ID, buildNotification(fileCount, progressPct, statusText))
    }

    private fun postResultNotification(title: String, text: String, success: Boolean) {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val icon = if (success) android.R.drawable.stat_sys_download_done
                   else android.R.drawable.stat_notify_error
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notifManager.notify(DONE_NOTIFICATION_ID, notification)
    }

    private fun buildNotification(fileCount: Int, progressPct: Int, statusText: String): android.app.Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (fileCount > 1) "Converting $fileCount files" else "Converting file"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(statusText)
            .setProgress(100, progressPct, progressPct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tapIntent)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "File Conversion",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progress notifications while files are being converted"
            setShowBadge(false)
        }
        notifManager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
