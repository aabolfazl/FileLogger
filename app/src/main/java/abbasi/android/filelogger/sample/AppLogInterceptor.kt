package abbasi.android.filelogger.sample

import abbasi.android.filelogger.file.LogLevel
import abbasi.android.filelogger.interceptor.LogInterceptor

class AppLogInterceptor : LogInterceptor {
    override fun intercept(
        level: LogLevel,
        tag: String,
        message: String,
        e: Throwable?
    ): String {
        return if (level == LogLevel.Info) {
            message
        } else {
            "****************************"
        }
    }
}