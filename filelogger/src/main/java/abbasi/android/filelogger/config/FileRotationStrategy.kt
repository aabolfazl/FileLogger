package abbasi.android.filelogger.config

sealed interface FileRotationStrategy {
    object None : FileRotationStrategy
    data class TimeBased(val intervalInMillis: Long) : FileRotationStrategy
    data class SizeBased(val bytes: ULong) : FileRotationStrategy
}