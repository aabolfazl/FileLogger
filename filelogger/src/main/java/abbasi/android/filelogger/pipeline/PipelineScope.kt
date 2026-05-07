/*
*
* Copyright (c) 2025 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.pipeline

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob

/**
 * Builds a fresh `CoroutineScope` for one FileLogger pipeline lifetime. Uses a single-threaded
 * view of `Dispatchers.IO` so every sink and producer sees an ordered event sequence; the
 * `SupervisorJob` keeps one failed child from cancelling the rest. Both `LogPipeline` and any
 * sink that needs to launch background work (periodic flush, retention sweep, disk-space poll)
 * receive the same scope so a single `scope.cancel()` tears the whole graph down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun newPipelineScope(): CoroutineScope = CoroutineScope(
    SupervisorJob() +
        Dispatchers.IO.limitedParallelism(1) +
        CoroutineName("FileLogger"),
)
