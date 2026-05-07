package abbasi.android.filelogger.util

import abbasi.android.filelogger.config.Config
import abbasi.android.filelogger.config.DIRECTORY
import abbasi.android.filelogger.internal.FileLoggerInternalLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class FileZipper(private val scope: CoroutineScope) {

    fun compressFiles(
        config: Config,
        zipFileName: String? = null,
        callback: ((file: File?) -> Unit),
    ) {
        scope.launch {
            try {
                val fileName = zipFileName?.replace(".", "_")?.replace("/", "_")
                val logFileDirectory = File(config.directory)
                val logDirectory = File(logFileDirectory.absolutePath + DIRECTORY)
                val zipFile = File(logDirectory, "${fileName ?: "Logs"}.zip")
                if (zipFile.exists()) {
                    zipFile.delete()
                }
                val logFiles = logDirectory.listFiles()?.filter { f ->
                    f.isFile && !f.name.endsWith(".zip", ignoreCase = true)
                }
                var inputStream: BufferedInputStream? = null
                var zipOutputStream: ZipOutputStream? = null
                var innerFailed = false
                try {
                    val zipFileStream = FileOutputStream(zipFile)
                    zipOutputStream = ZipOutputStream(BufferedOutputStream(zipFileStream))
                    val data = ByteArray(1024 * 64)
                    logFiles?.forEach { currentFile ->
                        val fileInputStream = FileInputStream(currentFile)
                        var count: Int

                        inputStream = BufferedInputStream(fileInputStream, data.size).also { stream ->
                            val entry = ZipEntry(currentFile.name)
                            zipOutputStream.putNextEntry(entry)
                            while (stream.read(data, 0, data.size).also { count = it } != -1) {
                                zipOutputStream.write(data, 0, count)
                            }
                        }

                        inputStream?.close()
                        inputStream = null
                    }
                } catch (e: Exception) {
                    FileLoggerInternalLog.warn("FileZipper inner compress failed", e)
                    innerFailed = true
                } finally {
                    inputStream?.close()
                    zipOutputStream?.close()
                }
                callback(if (innerFailed) null else zipFile)
            } catch (e: Exception) {
                FileLoggerInternalLog.warn("FileZipper compress failed", e)
                callback(null)
            }
        }
    }
}
