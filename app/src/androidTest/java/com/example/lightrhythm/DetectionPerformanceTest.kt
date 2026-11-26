package com.example.lightrhythm

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lightrhythm.detection.*
import com.example.lightrhythm.model.DetectionConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit on-device benchmark, excluded from ordinary regression runs. Decode is not timed. */
class DetectionPerformanceTest {
    @Test fun actualPreviewPipelineAndStageTimes() {
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue("Enable runDetectionBenchmark",arguments.getString("runDetectionBenchmark")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=arguments.getString("detectionSampleDirectory") ?: error("Provide private sample directory")
        val report=File(context.filesDir,"detection-performance.csv")
        report.writeText("kind,file,iteration,width,height,totalMs,wallMs,globalMs,localMs,regionMs,moireMs,banding,moire\n")
        val totals=mutableListOf<Long>()
        fun decode(name: String)=ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(directory,name))) { decoder,info,_ ->
            val scale=minOf(1.0,1600.0/maxOf(info.size.width,info.size.height))
            decoder.setTargetSize((info.size.width*scale).toInt(),(info.size.height*scale).toInt())
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
        }
        fun measure(name: String,iteration: Int) {
            val bitmap=decode(name)
            try {
                val started=SystemClock.elapsedRealtime()
                val result=runBlocking { PhotoDetectionPipeline().run(bitmap) }
                val wall=SystemClock.elapsedRealtime()-started
                totals+=wall
                val line="pipeline,$name,$iteration,${bitmap.width},${bitmap.height},${result.elapsedMs},$wall,,,,,${result.result.status},${result.moire!!.isSuspected}"
                report.appendText(line+"\n"); Log.i("DetectionPerf",line)
            } finally { bitmap.recycle() }
        }
        // First detector invocation in this fresh instrumentation process, then two complete passes.
        measure("20.jpg",-1)
        for(iteration in 0..1) for(number in 1..20) measure("%02d.jpg".format(number),iteration)
        for(name in listOf("07.jpg","18.jpg","20.jpg")) {
            val bitmap=decode(name)
            try {
                val reader: PixelTileReader={ x,y,w,h,out -> bitmap.getPixels(out,0,w,x,y,w,h) }
                var start=SystemClock.elapsedRealtime()
                val global=analyzeDetectionProfiles(readDetectionProfiles(bitmap.width,bitmap.height) { y,out ->
                    bitmap.getPixels(out,0,bitmap.width,0,y,bitmap.width,1)
                })
                val globalMs=SystemClock.elapsedRealtime()-start
                start=SystemClock.elapsedRealtime()
                val local=if(!global.isSuspected) analyzeLocalBandingTiles(bitmap.width,bitmap.height,DetectionConfig(),reader) else null
                val localMs=SystemClock.elapsedRealtime()-start
                start=SystemClock.elapsedRealtime()
                if(!global.isSuspected && local==null) analyzeLocalizedStripes(bitmap.width,bitmap.height,DetectionConfig(),reader)
                val regionMs=SystemClock.elapsedRealtime()-start
                start=SystemClock.elapsedRealtime(); detectMoire(bitmap)
                val moireMs=SystemClock.elapsedRealtime()-start
                report.appendText("phases,$name,0,${bitmap.width},${bitmap.height},${globalMs+localMs+regionMs+moireMs},,$globalMs,$localMs,$regionMs,$moireMs,,\n")
            } finally { bitmap.recycle() }
        }
        val budget=arguments.getString("detectionBudgetMs")?.toLong()
        if(budget!=null) assertTrue("Slowest total ${totals.max()} ms exceeds $budget ms; see ${report.name}",totals.all { it<budget })
    }
}
