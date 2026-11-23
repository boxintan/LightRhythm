package com.example.lightrhythm

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** C's internal integration boundary, not A/B's final BandingResult contract.
 * Implementations must not mutate/recycle the input and must return a distinct output.
 * The future adapter will call A's detector then B's repairer here.
 */
internal fun interface PhotoProcessor {
    suspend fun process(source: Bitmap, strength: Float): ProcessingResult
}

internal sealed interface ProcessingResult {
    data object NotIntegrated : ProcessingResult
    data class NotApplied(val message: String) : ProcessingResult
    data class Preview(val bitmap: Bitmap) : ProcessingResult
}

/** Explicit placeholder: produces no repaired image and no invented detection score. */
internal object UnavailablePhotoProcessor : PhotoProcessor {
    override suspend fun process(source: Bitmap, strength: Float) = ProcessingResult.NotIntegrated
}

internal data class TimedProcessingResult(val result: ProcessingResult, val elapsedMs: Long)

internal class PhotoProcessingPipeline(
    private val processor: PhotoProcessor = UnavailablePhotoProcessor
) {
    suspend fun run(source: Bitmap, strength: Float): TimedProcessingResult = withContext(Dispatchers.Default) {
        require(strength.isFinite() && strength in 0f..1f)
        val started = SystemClock.elapsedRealtime()
        val result = processor.process(source, strength)
        ensureActive()
        if (result is ProcessingResult.Preview) {
            require(result.bitmap !== source) { "Processor must return a separate image" }
        }
        TimedProcessingResult(result, SystemClock.elapsedRealtime() - started)
    }
}
