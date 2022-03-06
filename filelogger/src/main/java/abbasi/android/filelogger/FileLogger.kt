/*
*
* Copyright (c) 2022 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger

import abbasi.android.filelogger.config.Config
import abbasi.android.filelogger.file.FileWriter
import abbasi.android.filelogger.file.LogLevel
import abbasi.android.filelogger.threading.ThreadQueue
import android.util.Log

object FileLogger {

    private var initialized = false
    private var isEnable: Boolean = true

    private var config: Config? = null
    private var fileWriter: FileWriter? = null
    private var logQueue: ThreadQueue = ThreadQueue()

    fun init(config: Config) {
        if (initialized) {
            return
        }

        this.config = config
        fileWriter = FileWriter(config.directory, config.dataFormatterPattern)
        initialized = true
    }

    fun i(tag: String? = config?.defaultTag, msg: String) = checkBlock {
        if (config?.logcatEnable == true) {
            Log.i(tag, msg)
        }

        postLog(LogLevel.Info, tag, msg)
    }

    fun e(
        tag: String? = config?.defaultTag,
        msg: String,
        throwable: Throwable? = null
    ) = checkBlock {
        if (config?.logcatEnable == true) {
            Log.e(tag, msg, throwable)
        }

        postLog(logLevel = LogLevel.Error, tag = tag, msg = msg, throwable = throwable)
    }

    fun e(
        tag: String? = config?.defaultTag,
        throwable: Throwable
    ) = checkBlock {
        if (config?.logcatEnable == true) {
            Log.e(tag, "", throwable)
        }

        postLog(logLevel = LogLevel.Error, tag = tag, throwable = throwable)
    }

    fun w(
        tag: String? = config?.defaultTag,
        msg: String
    ) = checkBlock {
        if (config?.logcatEnable == true) {
            Log.w(tag, msg)
        }

        postLog(logLevel = LogLevel.Warning, tag = tag, msg = msg)
    }

    fun d(
        tag: String? = config?.defaultTag,
        msg: String
    ) = checkBlock {
        if (config?.logcatEnable == true) {
            Log.d(tag, msg)
        }

        postLog(logLevel = LogLevel.Debug, tag = tag, msg = msg)
    }

    private fun postLog(
        logLevel: LogLevel,
        tag: String? = config?.defaultTag,
        msg: String? = "",
        throwable: Throwable? = null
    ) = fileWriter?.let { writer ->
        logQueue.postRunnable {
            val stringBuilder = StringBuilder("$logLevel/$tag: $msg \n")

            throwable?.let { exception ->
                exception.stackTrace.forEach { element ->
                    stringBuilder.append("\t $element\n")
                }
            }

            writer.write(stringBuilder.toString())
        }
    }

    fun setEnable(isEnable: Boolean) {
        this.isEnable = isEnable
    }

    fun deleteFiles() = checkBlock {
        i(msg = "FileLogger delete files called")
        fileWriter?.deleteLogsDir()
    }

    private fun checkBlock(block: () -> Unit) {
        if (initialized && isEnable) {
            block()
        } else if (isEnable) {
            Log.e(
                javaClass.simpleName,
                "SDK not initialized maybe forgot call FileLogger.init(config: Config)"
            )
        }
    }
}