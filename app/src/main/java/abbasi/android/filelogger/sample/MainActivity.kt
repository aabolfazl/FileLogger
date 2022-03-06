package abbasi.android.filelogger.sample

import abbasi.android.filelogger.FileLogger
import abbasi.android.filelogger.config.Config
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
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

    }
}