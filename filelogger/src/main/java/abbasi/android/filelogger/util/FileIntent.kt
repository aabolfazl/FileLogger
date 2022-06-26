package abbasi.android.filelogger.util

import abbasi.android.filelogger.FileLogger
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

class FileIntent private constructor() {
    companion object {
        @JvmStatic
        fun uriFromFile(context: Context, file: File, appId: String): Uri? = try {
            if (Build.VERSION.SDK_INT >= 24) {
                FileProvider.getUriForFile(
                    context,
                    "${appId}.provider",
                    file
                )
            } else {
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            FileLogger.e(throwable = e)
            null
        }

        @JvmStatic
        fun fromUri(uri: Uri): Intent {
            val intent = Intent(Intent.ACTION_SEND)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            intent.type = "message/rfc822"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            return intent
        }

        @JvmStatic
        fun fromFile(context: Context, file: File, appId: String): Intent? {
            return uriFromFile(context, file, appId)?.let { return@let fromUri(it) }
        }
    }
}