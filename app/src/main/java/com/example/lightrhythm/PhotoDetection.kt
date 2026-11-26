package com.example.lightrhythm

import android.graphics.Bitmap
import android.os.SystemClock
import com.example.lightrhythm.detection.detectBanding
import com.example.lightrhythm.detection.detectMoire
import com.example.lightrhythm.detection.MoireResult
import com.example.lightrhythm.model.BandingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class TimedDetectionResult(val result: BandingResult, val elapsedMs: Long, val moire: MoireResult? = null)

/** A/C adapter: operates on the decoded preview on a worker thread; never repairs the input. */
internal class PhotoDetectionPipeline(
    private val detector: (Bitmap) -> BandingResult = { detectBanding(it) },
) {
    suspend fun run(source: Bitmap): TimedDetectionResult = withContext(Dispatchers.Default) {
        ensureActive()
        val start = SystemClock.elapsedRealtime()
        // Both detectors only read the stable software preview and own their scratch buffers.
        val moireTask=async {
            ensureActive()
            detectMoire(source).also { ensureActive() }
        }
        val result = detector(source)
        ensureActive()
        val moire = moireTask.await()
        ensureActive()
        TimedDetectionResult(result, SystemClock.elapsedRealtime() - start, moire)
    }
}
