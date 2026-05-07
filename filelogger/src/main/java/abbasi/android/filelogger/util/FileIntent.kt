/*
*
* Copyright (c) 2022 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.util

import abbasi.android.filelogger.FileLogger
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/**
 * Sample-app helper for sharing log archives via the system share sheet. Wraps
 * `FileProvider.getUriForFile` and builds an `ACTION_SEND` intent with `EXTRA_STREAM`. Lives in
 * the library because the share flow is what users do with `compressLogsInZipFile`'s output —
 * not because the library itself depends on `FileProvider`.
 */
public class FileIntent private constructor() {
    public companion object {
        /** Resolve `file` to a content `Uri` via `FileProvider`. Returns null on failure. */
        @JvmStatic
        public fun uriFromFile(context: Context, file: File, appId: String): Uri? = try {
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
            FileLogger.e(message = "FileIntent uriFromFile failed", throwable = e)
            null
        }

        /** Build an `ACTION_SEND` intent (`message/rfc822`) with `uri` as the attachment. */
        @JvmStatic
        public fun fromUri(uri: Uri): Intent {
            val intent = Intent(Intent.ACTION_SEND)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            intent.type = "message/rfc822"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            return intent
        }

        /** Combination of `uriFromFile` + `fromUri`. Returns null when the URI cannot be built. */
        @JvmStatic
        public fun fromFile(context: Context, file: File, appId: String): Intent? {
            return uriFromFile(context, file, appId)?.let { return@let fromUri(it) }
        }
    }
}
