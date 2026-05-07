package abbasi.android.filelogger.sample

import abbasi.android.filelogger.FileLogger
import abbasi.android.filelogger.config.FileRotationStrategy
import abbasi.android.filelogger.config.FormatterChoice
import abbasi.android.filelogger.config.RetentionPolicy
import abbasi.android.filelogger.dsl.fileLogger
import abbasi.android.filelogger.file.LogLevel
import abbasi.android.filelogger.mdc.Mdc
import abbasi.android.filelogger.util.FileIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private var zipFile: File? = null
    private var eventsJob: Job? = null
    private var eventsSeen: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val eventsCount = findViewById<TextView>(R.id.eventsCount)

        findViewById<Button>(R.id.init).setOnClickListener {
            val path = applicationContext.getExternalFilesDir(null)?.path ?: return@setOnClickListener

            val config = fileLogger(path) {
                defaultTag = "Sample"
                logcatEnabled = true
                minLevel = LogLevel.Debug
                tagOverrides = mapOf("Noisy" to LogLevel.Warning)
                formatter = FormatterChoice.PlainText
                rotation = FileRotationStrategy.TimeBased(intervalInMillis = 1000L * 60 * 60 * 24)
                retention = RetentionPolicy.TimeToLive(durationInMillis = 1000L * 60 * 60)
                interceptor = AppLogInterceptor()
                startupData = mapOf(
                    "App Version" to System.currentTimeMillis().toString(),
                    "Application Id" to BuildConfig.APPLICATION_ID,
                    "Version Code" to BuildConfig.VERSION_CODE.toString(),
                    "Version Name" to BuildConfig.VERSION_NAME,
                    "Build Type" to BuildConfig.BUILD_TYPE,
                    "Device" to Build.DEVICE,
                    "SDK" to Build.VERSION.SDK_INT.toString(),
                    "Manufacturer" to Build.MANUFACTURER,
                )
            }

            FileLogger.init(this, config)
        }

        findViewById<Button>(R.id.writeNormalLog).setOnClickListener {
            FileLogger.i(tag = "Custom", message = "This is normal Log with custom TAG")
            FileLogger.i(message = "This is normal Info Log")
            FileLogger.d(message = "This is normal Debug Log")
            FileLogger.w(message = "This is normal Warning Log")
            FileLogger.e(message = "This is normal Error Log")
            FileLogger.d(tag = "Noisy", message = "Filtered out by tagOverrides")
        }

        findViewById<Button>(R.id.writeLazyLog).setOnClickListener {
            FileLogger.d { "expensive computed value: ${heavyComputation()}" }
            FileLogger.isEnabled = false
            FileLogger.d { "this lambda is NOT invoked because isEnabled=false: ${heavyComputation()}" }
            FileLogger.isEnabled = true
        }

        findViewById<Button>(R.id.writeMdcLog).setOnClickListener {
            val requestId = UUID.randomUUID().toString()
            Mdc.with("requestId" to requestId, "userId" to "u-42") {
                FileLogger.i { "doing work under MDC" }
                FileLogger.w { "still under MDC" }
            }
            FileLogger.i { "back outside MDC" }
        }

        findViewById<Button>(R.id.writeExceptionLog).setOnClickListener {
            try {
                findViewById<ImageView>(R.id.writeNormalLog).imageAlpha
            } catch (e: Exception) {
                FileLogger.e(message = "exception captured", throwable = e)
            }
        }

        findViewById<Button>(R.id.observeEvents).setOnClickListener {
            val current = eventsJob
            if (current != null && current.isActive) {
                current.cancel()
                eventsJob = null
                Toast.makeText(this, "events subscriber stopped", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            eventsSeen = 0
            eventsCount.text = "events seen: 0"
            eventsJob = lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    FileLogger.events.collect {
                        eventsSeen++
                        eventsCount.text = "events seen: $eventsSeen"
                    }
                }
            }
        }

        findViewById<Button>(R.id.deleteLogs).setOnClickListener {
            FileLogger.deleteFiles()
        }

        findViewById<Button>(R.id.zipLogs).setOnClickListener {
            FileLogger.compressLogsInZipFile { result ->
                runOnUiThread {
                    zipFile = result
                    Toast.makeText(
                        this,
                        "Zip Log file created ${if (result?.exists() == true) "successfully" else "with error"}",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

        findViewById<Button>(R.id.emailLogs).setOnClickListener {
            val zip = zipFile
            if (zip == null) {
                Toast.makeText(this, "Make zip file first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = FileIntent.fromFile(this, zip, BuildConfig.APPLICATION_ID)
                ?: return@setOnClickListener
            intent.putExtra(Intent.EXTRA_SUBJECT, "Email Subject")
            try {
                startActivity(Intent.createChooser(intent, "Email App..."))
            } catch (e: Exception) {
                FileLogger.e(message = "email send failed", throwable = e)
            }
        }
    }

    private fun heavyComputation(): Long {
        var sum = 0L
        for (i in 0 until 1_000_000) {
            sum += i.toLong()
        }
        return sum
    }
}
