package abbasi.android.filelogger.sample

import abbasi.android.filelogger.FileLogger
import abbasi.android.filelogger.config.Config
import abbasi.android.filelogger.util.FileIntent
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {
    private var zipFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.init).setOnClickListener {
            applicationContext.getExternalFilesDir(null)?.let {
                val config = Config.Builder(it.path)
                    .setDefaultTag("TAG")
                    .setLogcatEnable(true)
                    .build()

                FileLogger.init(config)
            }
        }

        findViewById<Button>(R.id.writeNormalLog).setOnClickListener {
            FileLogger.i("Custom", "This is normal Log with custom TAG")
            FileLogger.i(msg = "This is normal Info Log")
            FileLogger.d(msg = "This is normal Debug Log")
            FileLogger.w(msg = "This is normal Warning Log")
            FileLogger.e(msg = "This is normal Error Log")
        }

        findViewById<Button>(R.id.writeExceptionLog).setOnClickListener {
            try {
                findViewById<ImageView>(R.id.writeNormalLog).imageAlpha // just for happening exception
            } catch (e: Exception) {
                FileLogger.e(msg = "This is normal Log with custom TAG", throwable = e)
            }
        }

        findViewById<Button>(R.id.deleteLogs).setOnClickListener {
            FileLogger.deleteFiles()
        }

        findViewById<Button>(R.id.zipLogs).setOnClickListener {
            FileLogger.compressLogsInZipFile {
                zipFile = it
                Toast.makeText(
                    this,
                    "Zip Log file created ${if (zipFile?.exists() == true) "successfully" else "with error"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        findViewById<Button>(R.id.emailLogs).setOnClickListener {
            if (zipFile != null) {
                FileIntent.fromFile(this, zipFile!!, BuildConfig.APPLICATION_ID)?.let { intent ->
                    intent.putExtra(Intent.EXTRA_SUBJECT, "Email Subject")
                    try {
                        startActivity(Intent.createChooser(intent, "Email App..."))
                    } catch (e: java.lang.Exception) {
                        FileLogger.e(throwable = e)
                    }
                }
            } else {
                Toast.makeText(this, "Make zip file first.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}