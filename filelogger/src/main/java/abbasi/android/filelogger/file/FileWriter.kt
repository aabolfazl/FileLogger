/*
*
* Copyright (c) 2022 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.file

import abbasi.android.filelogger.time.FastDateFormat
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.*

internal class FileWriter(
    directory: String, dataFormatterPattern: String
) {
    private var streamWriter: OutputStreamWriter? = null
    private var dateFormat: FastDateFormat? = null
    private var logFile: File? = null

    init {
        dateFormat = FastDateFormat.getInstance(dataFormatterPattern, Locale.US)
        try {
            val file = File(directory)
            val logDir = File(file.absolutePath + "/fileLogs")
            if (logDir.exists().not()) {
                logDir.mkdirs()
            }

            logFile =
                File(logDir, dateFormat?.format(System.currentTimeMillis()).toString() + ".txt")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            logFile?.createNewFile()
            val stream = FileOutputStream(logFile)
            streamWriter = OutputStreamWriter(stream).apply {
                write("File logger initialized at ${dateFormat?.format(System.currentTimeMillis())} \n\n\n")
                flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun write(message: String) {
        streamWriter.takeIf { dateFormat != null }?.let { writer ->
            try {
                writer.write("${dateFormat?.format(System.currentTimeMillis())} $message")
                writer.flush()
            } catch (e: Exception) {
                Log.e(javaClass.simpleName, "e:", e)
            }
        }
    }

    fun deleteCurrentFile() {

    }
}