/*
*
* Copyright (c) 2022 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.file

internal sealed class LogLevel {
    object Info : LogLevel()
    object Error : LogLevel()
    object Warning : LogLevel()
    object Debug : LogLevel()

    override fun toString(): String {
        return when (this) {
            is Info -> {
                "I"
            }
            is Error -> {
                "E"
            }
            is Warning -> {
                "W"
            }
            is Debug -> {
                "D"
            }
        }
    }
}