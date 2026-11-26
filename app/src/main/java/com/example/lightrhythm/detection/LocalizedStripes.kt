package com.example.lightrhythm.detection

import com.example.lightrhythm.model.DetectionConfig
import com.example.lightrhythm.model.LocalizedStripeRegion
import com.example.lightrhythm.model.StripeAxis
import kotlin.math.abs

internal data class ColorRectangle(val x: Int,val y: Int,val width: Int,val height: Int,val cells: Int,
    val color: Int=-1)

/** Color components propose regions only; a region still needs four-cycle, cross-strip recurrence.
 * This avoids treating gray ceiling slats/reflections as a source of evidence for a small display.
 */
internal fun analyzeLocalizedStripes(width: Int,height: Int,config: DetectionConfig,
    readTile: PixelTileReader): List<LocalizedStripeRegion> {
    if(width < 64 || height < 64) return emptyList()
    return findColorRectangles(width,height,readTile).flatMap { rectangle ->
        val interior=if(rectangle.width >= 96 && rectangle.height >= 96)
            rectangle.copy(x=rectangle.x+16,y=rectangle.y+16,width=rectangle.width-32,height=rectangle.height-32)
            else rectangle
        StripeAxis.entries.mapNotNull { axis ->
            analyzeRectangle(interior,axis,config,readTile) ?: analyzeRectangle(rectangle,axis,config,readTile)
        }
    }
}

internal fun findColorRectangles(width: Int,height: Int,readTile: PixelTileReader): List<ColorRectangle> {
    val cell=16
    val columns=(width+cell-1)/cell; val rows=(height+cell-1)/cell
    val counts=Array(3) { IntArray(columns*rows) }
    val pixels=IntArray(width)
    for(y in 0 until height) {
        readTile(0,y,width,1,pixels)
        for(x in 0 until width) {
            val p=pixels[x]
            if(p ushr 24 != 255) continue
            val r=(p ushr 16) and 255; val g=(p ushr 8) and 255; val b=p and 255
            val high=maxOf(r,g,b); val low=minOf(r,g,b)
            if(high < 50 || high-low < maxOf(32,high*2/5)) continue
            val channel=if(r==high) 0 else if(g==high) 1 else 2
            counts[channel][(y/cell)*columns+x/cell]++
        }
    }
    val labels=IntArray(columns*rows) { index ->
        val area=minOf(cell,width-index%columns*cell)*minOf(cell,height-index/columns*cell)
        val channel=(0..2).maxBy { counts[it][index] }
        if(counts[channel][index] >= area*.4) channel else -1
    }
    // Close one-cell gaps, such as dark scan bands across an otherwise connected color display.
    val originalLabels=labels.copyOf()
    for(y in 1 until rows-1) for(x in 1 until columns-1) {
        val index=y*columns+x
        if(originalLabels[index] >= 0) continue
        val above=originalLabels[index-columns]; val left=originalLabels[index-1]
        if(above >= 0 && above==originalLabels[index+columns]) labels[index]=above
        else if(left >= 0 && left==originalLabels[index+1]) labels[index]=left
    }
    val visited=BooleanArray(labels.size)
    val queue=IntArray(labels.size)
    val rectangles=mutableListOf<ColorRectangle>()
    for(start in labels.indices) {
        if(visited[start] || labels[start] < 0) continue
        var head=0; var tail=1
        queue[0]=start; visited[start]=true
        var left=columns; var right=0; var top=rows; var bottom=0
        while(head < tail) {
            val index=queue[head++]; val x=index%columns; val y=index/columns
            left=minOf(left,x); right=maxOf(right,x); top=minOf(top,y); bottom=maxOf(bottom,y)
            fun add(next: Int) {
                if(!visited[next] && labels[next]==labels[start]) {
                    visited[next]=true; queue[tail++]=next
                }
            }
            if(x>0) add(index-1)
            if(x+1<columns) add(index+1)
            if(y>0) add(index-columns)
            if(y+1<rows) add(index+columns)
        }
        if(tail < 6 || tail < (right-left+1)*(bottom-top+1)*.35) continue
        val x=maxOf(0,left*cell-cell); val y=maxOf(0,top*cell-cell)
        val endX=minOf(width,(right+2)*cell); val endY=minOf(height,(bottom+2)*cell)
        if(endX-x >= 32 && endY-y >= 32) rectangles += ColorRectangle(x,y,endX-x,endY-y,tail,labels[start])
    }
    return rectangles.sortedByDescending { it.cells }.take(8)
}

internal data class RectangleStripeProfiles(val profiles: List<List<DoubleArray>>,
    val widths: List<Int>,val usable: List<IntArray>)

internal fun readRectangleProfiles(rectangle: ColorRectangle,axis: StripeAxis,
    readTile: PixelTileReader): RectangleStripeProfiles? {
    val horizontal=axis==StripeAxis.HORIZONTAL
    val length=if(horizontal) rectangle.height else rectangle.width
    val cross=if(horizontal) rectangle.width else rectangle.height
    if(length < 32 || cross < 32) return null
    val boundaries=IntArray(5) { cross*it/4 }
    val widths=List(4) { boundaries[it+1]-boundaries[it] }
    val profiles=List(6) { List(4) { DoubleArray(length) } }
    val pixels=IntArray(cross)
    val histograms=Array(3) { IntArray(256) }
    val usable=List(4) { IntArray(length) }
    for(position in 0 until length) {
        if(horizontal) readTile(rectangle.x,rectangle.y+position,cross,1,pixels)
        else readTile(rectangle.x+position,rectangle.y,1,cross,pixels)
        for(region in 0..3) {
            histograms.forEach { it.fill(0) }
            var valid=0
            for(offset in boundaries[region] until boundaries[region+1]) {
                val pixel=pixels[offset]
                if(pixel ushr 24 != 255) continue
                val r=(pixel ushr 16) and 255; val g=(pixel ushr 8) and 255; val b=pixel and 255
                val high=maxOf(r,g,b); val low=minOf(r,g,b)
                val color=if(r==high) 0 else if(g==high) 1 else 2
                val spread=maxOf(16,high/4)
                if(rectangle.color >= 0 && (color != rectangle.color || high-low < spread)) continue
                valid++
                histograms[0][pixelLuminance(pixel).toInt()]++
                histograms[1][(r-g+255)/2]++
                histograms[2][(b-g+255)/2]++
            }
            usable[region][position]=valid
            for(channel in 0..2) for(quantile in 0..1) {
                var count=0; var value=0
                while(value < 255) {
                    count += histograms[channel][value]
                    if(count > valid*(if(quantile==0) 1 else 3)/4) break
                    value++
                }
                profiles[channel*2+quantile][region][position]=if(valid==0) 0.0 else value.toDouble()
            }
        }
    }
    return RectangleStripeProfiles(profiles,widths,usable)
}

private fun analyzeRectangle(rectangle: ColorRectangle,axis: StripeAxis,config: DetectionConfig,
    readTile: PixelTileReader): LocalizedStripeRegion? {
    val data=readRectangleProfiles(rectangle,axis,readTile) ?: return null
    val profiles=data.profiles; val widths=data.widths; val usable=data.usable
    val votes=List(4) { region -> profiles.indices.mapNotNull { profileIndex ->
            val profile=profiles[profileIndex][region]
            val first=usable[region].indexOfFirst { it >= maxOf(4,widths[region]/4) }
            val last=usable[region].indexOfLast { it >= maxOf(4,widths[region]/4) }
            if(first < 0 || last-first+1 < 32) null else stripeProfilePeriod(profile.copyOfRange(first,last+1),config,
                maxOf(.5,config.minAutocorrelation-.15))?.let { it to profileIndex/2 }
        } }
    for(anchor in votes.flatten()) {
        val matches=votes.mapNotNull { strip -> strip.filter {
            abs(it.first.pixels-anchor.first.pixels) <= maxOf(1.0,anchor.first.pixels*.1)
        }.maxByOrNull { it.first.correlation } }
        if(matches.size/4.0 < config.minRegionSupport) continue
        // Quantiles/channels from one strip are alternatives, not extra spatial votes.
        // Retain two strict votes and a strict mean even when one strip is partly obscured.
        if(matches.count { it.first.correlation >= config.minAutocorrelation } < 2 ||
            matches.map { it.first.correlation }.average() < config.minAutocorrelation) continue
        val channels=matches.map { it.second }.distinct()
        return LocalizedStripeRegion(rectangle.x,rectangle.y,rectangle.width,rectangle.height,axis,
            matches.map { it.first.pixels }.sorted()[matches.size/2],if(channels.size==1) listOf("luminance","R-G","B-G")[channels[0]] else "mixed",
            matches.minOf { it.first.correlation })
    }
    return null
}
