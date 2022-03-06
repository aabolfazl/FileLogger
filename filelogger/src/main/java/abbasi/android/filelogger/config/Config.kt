/*
*
* Copyright (c) 2022 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.config

class Config private constructor(
    val directory: String,
    val defaultTag: String,
    val logcatEnable: Boolean,
    val dataFormatterPattern: String,
) {

    class Builder(private val directory: String) {
        private var defaultTag: String = "FileLogger"
        private var logcatEnable: Boolean = true
        private var dataFormatterPattern: String = "dd-MM-yyyy-HH:mm:ss"

        fun setDefaultTag(defaultTag: String) = apply { this.defaultTag = defaultTag }
        fun setLogcatEnable(logcatEnable: Boolean) = apply { this.logcatEnable = logcatEnable }
        fun setDataFormatterPattern(pattern: String) = apply {
            this.dataFormatterPattern = pattern.replace("/", "-")
                .replace(" ", "")
                .trim()

            if (pattern.isEmpty().or(pattern.contains("/"))) {
                this.dataFormatterPattern = "dd-MM-yyyy-HH:mm:ss"
            }
        }

        fun build() = Config(
            directory,
            defaultTag,
            logcatEnable,
            dataFormatterPattern
        )
    }
}