/*
*
* Copyright (c) 2022 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.file

import abbasi.android.filelogger.time.FastDateFormat
import android.util.Log
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

internal class FileWriter(
    private var dateFormat: FastDateFormat,
    logFile: File,
    startLogs: Map<String, String>?,
) {
    private var streamWriter: DataOutputStream? = null
    public var totalBytes:ULong = 0u;

    init {
        try {
            val stream = FileOutputStream(logFile)
            streamWriter = DataOutputStream(stream)
            var message = "File logger started at ${dateFormat.format(System.currentTimeMillis())}\n"
            startLogs?.forEach {
                message += "${it.key}: ${it.value}\n"
            }
            message += "\n\n"
            writeNoTag(message)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun writeNoTag(message: String) {
        streamWriter?.let { writer ->
            try {
                val bytes = message.toByteArray(Charsets.UTF_8)
                totalBytes += bytes.count().toUInt()
                writer.write(bytes)
                writer.flush()
            } catch (e: Exception) {
                Log.e(javaClass.simpleName, "e:", e)
            }
        }
    }

    fun write(message: String) {
        val formattedMessage = "${dateFormat.format(System.currentTimeMillis())} $message"
        writeNoTag(formattedMessage)
    }

    fun close() {
        try {
            streamWriter?.close()
            streamWriter = null
            totalBytes = 0u
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}