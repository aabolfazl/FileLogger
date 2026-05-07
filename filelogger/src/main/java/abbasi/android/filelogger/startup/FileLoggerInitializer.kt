/*
*
* Copyright (c) 2025 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.startup

import abbasi.android.filelogger.FileLogger
import abbasi.android.filelogger.Logger
import abbasi.android.filelogger.config.Config
import abbasi.android.filelogger.config.DEFAULT_TAG
import abbasi.android.filelogger.config.FormatterChoice
import abbasi.android.filelogger.dsl.fileLogger
import abbasi.android.filelogger.file.LogLevel
import abbasi.android.filelogger.internal.FileLoggerInternalLog
import android.content.Context
import android.content.pm.PackageManager
import androidx.startup.Initializer

/**
 * AndroidX App Startup [Initializer] that boots `FileLogger` from `<meta-data>` entries in the
 * host application's manifest. Auto-init is **opt-in**: without `filelogger.autoInit = true` the
 * initializer returns immediately without calling [FileLogger.init], preserving v1.x behaviour
 * for consumers who have not read the docs.
 *
 * Recognised `<meta-data>` keys (all `android:value`):
 *  - `filelogger.autoInit` (boolean, **required** to enable). When absent or `false`, the
 *    initializer is a no-op and the consumer must call `FileLogger.init` manually.
 *  - `filelogger.tag` (string). Default tag for events without an explicit tag.
 *  - `filelogger.minLevel` (string, one of `Debug`/`Info`/`Warning`/`Error`). Defaults to `Debug`.
 *  - `filelogger.logcat` (boolean). Whether to attach a `LogcatSink`. Defaults to `true`.
 *  - `filelogger.formatter` (string, one of `PlainText`/`Json`). Defaults to `PlainText`.
 *
 * The initializer writes into `context.filesDir` because at startup time the app does not have a
 * chance to choose its own directory; if you need a different root, opt out of auto-init and call
 * [FileLogger.init] yourself.
 */
public class FileLoggerInitializer : Initializer<Logger> {

    public override fun create(context: Context): Logger {
        if (!isAutoInitEnabled(context)) return FileLogger
        val config = readConfig(context) ?: return FileLogger
        FileLogger.init(context, config)
        return FileLogger
    }

    public override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    private fun isAutoInitEnabled(context: Context): Boolean =
        readMetaData(context, KEY_AUTO_INIT)?.toBooleanStrictOrNull() == true

    private fun readConfig(context: Context): Config? {
        val tag = readMetaData(context, KEY_TAG) ?: DEFAULT_TAG
        val minLevel = readMetaData(context, KEY_MIN_LEVEL)
            ?.let { name -> LogLevel.entries.firstOrNull { it.name == name } }
            ?: LogLevel.Debug
        val logcat = readMetaData(context, KEY_LOGCAT)?.toBooleanStrictOrNull() ?: true
        val formatterChoice = readMetaData(context, KEY_FORMATTER)
            ?.let { name -> FormatterChoice.entries.firstOrNull { it.name == name } }
            ?: FormatterChoice.PlainText
        return fileLogger(context.filesDir.absolutePath) {
            this.defaultTag = tag
            this.minLevel = minLevel
            this.logcatEnabled = logcat
            this.formatter = formatterChoice
        }
    }

    private fun readMetaData(context: Context, key: String): String? {
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            val md = ai.metaData ?: return null
            if (!md.containsKey(key)) return null
            md.getString(key) ?: md.getBoolean(key, false).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            FileLoggerInternalLog.warn("FileLoggerInitializer: applicationInfo not found", e)
            null
        }
    }

    private companion object {
        const val KEY_AUTO_INIT = "filelogger.autoInit"
        const val KEY_TAG = "filelogger.tag"
        const val KEY_MIN_LEVEL = "filelogger.minLevel"
        const val KEY_LOGCAT = "filelogger.logcat"
        const val KEY_FORMATTER = "filelogger.formatter"
    }
}
