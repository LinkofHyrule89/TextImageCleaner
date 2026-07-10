package com.ubermicrostudios.textimagecleaner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * Generates random demo images (and video-flagged thumbnails) under app cache.
 * Used only for marketing screenshots — never touches real MMS storage.
 */
object DemoMediaFactory {

    fun createDemoMedia(context: Context, count: Int = 24): List<MediaItem> {
        val dir = File(context.cacheDir, "demo_media").apply {
            deleteRecursively()
            mkdirs()
        }
        val zone = ZoneId.systemDefault()
        val rnd = Random(42) // reproducible colorful set
        val monthsBack = listOf(0, 0, 1, 1, 2, 3, 5, 8, 11, 14)

        return (0 until count).map { i ->
            val isVideo = i % 4 == 0
            val file = File(dir, if (isVideo) "demo_$i.mp4.jpg" else "demo_$i.jpg")
            writeRandomJpeg(file, i, isVideo, rnd)

            val monthsAgo = monthsBack[i % monthsBack.size]
            val day = 1 + (i % 27)
            val date = LocalDate.now(zone)
                .minusMonths(monthsAgo.toLong())
                .withDayOfMonth(day.coerceAtMost(28))
                .atTime(10 + (i % 8), (i * 7) % 60)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()

            MediaItem(
                uri = file.toUri(),
                mimeType = if (isVideo) "video/mp4" else "image/jpeg",
                size = file.length(),
                date = date,
                body = if (i % 3 == 0) "Demo message #${i + 1} — not real SMS." else null,
                partId = 10_000L + i,
                msgId = 20_000L + i
            )
        }.sortedByDescending { it.date }
    }

    private fun writeRandomJpeg(file: File, index: Int, isVideo: Boolean, rnd: Random) {
        val w = 400
        val h = 400
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val c1 = Color.rgb(rnd.nextInt(60, 220), rnd.nextInt(60, 220), rnd.nextInt(60, 220))
        val c2 = Color.rgb(rnd.nextInt(40, 180), rnd.nextInt(40, 180), rnd.nextInt(40, 180))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                c1, c2, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        // Decorative circles
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 255, 255, 255)
        }
        repeat(6) {
            canvas.drawCircle(
                rnd.nextFloat() * w,
                rnd.nextFloat() * h,
                rnd.nextFloat() * 80f + 20f,
                dot
            )
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 42f
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }
        val label = if (isVideo) "Demo video ${index + 1}" else "Demo photo ${index + 1}"
        canvas.drawText(label, 24f, h / 2f, textPaint)

        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        }
        bmp.recycle()
    }
}
