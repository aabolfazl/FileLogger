/*
*
* Copyright (c) 2022 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.config

import abbasi.android.filelogger.config.Constance.Companion.DEFAULT_PATTERN
import abbasi.android.filelogger.config.Constance.Companion.DEFAULT_TAG
import abbasi.android.filelogger.config.Constance.Companion.LOGCAT_ENABLE

class Config private constructor(
    val directory: String,
    val defaultTag: String,
    val logcatEnable: Boolean,
    val dataFormatterPattern: String,
) {

    class Builder(private val directory: String) {
        private var defaultTag: String = DEFAULT_TAG
        private var logcatEnable: Boolean = LOGCAT_ENABLE
        private var dataFormatterPattern: String = DEFAULT_PATTERN

        fun setDefaultTag(defaultTag: String) = apply { this.defaultTag = defaultTag }
        fun setLogcatEnable(logcatEnable: Boolean) = apply { this.logcatEnable = logcatEnable }
        fun setDataFormatterPattern(pattern: String) = apply {
            this.dataFormatterPattern = pattern.replace("/", "-")
                .replace(" ", "")
                .trim()

            if (pattern.isEmpty().or(pattern.contains("/"))) {
                this.dataFormatterPattern = DEFAULT_PATTERN
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