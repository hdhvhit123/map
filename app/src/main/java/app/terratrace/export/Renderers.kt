package app.terratrace.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import app.terratrace.data.PhotoEntity
import app.terratrace.geo.GeoMath
import app.terratrace.geo.GeoPoint
import app.terratrace.ui.format.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private object Palette {
    const val FOREST = 0xFF2F5D3A.toInt()
    const val FOREST_DARK = 0xFF1F3F27.toInt()
    const val TERRA = 0xFFC8643B.toInt()
    const val STONE50 = 0xFFF5F3EF.toInt()
    const val STONE100 = 0xFFECE8E1.toInt()
    const val STONE200 = 0xFFDAD5CC.toInt()
    const val STONE600 = 0xFF6B675F.toInt()
    const val INK = 0xFF2E2C28.toInt()
}

/** Shared drawing for poster and video frames. */
private class TrackPainter(points: List<GeoPoint>, project: (Double, Double) -> PointF, scale: Float) {
    val screen: List<PointF> = points.map { project(it.lat, it.lon) }
    val segments: List<Int> = points.map { it.segment }
    val cum: FloatArray = FloatArray(screen.size).also { c ->
        for (i in 1 until screen.size) {
            val joined = segments[i] == segments[i - 1]
            val dx = screen[i].x - screen[i - 1].x; val dy = screen[i].y - screen[i - 1].y
            c[i] = c[i - 1] + if (joined) kotlin.math.sqrt(dx * dx + dy * dy) else 0f
        }
    }
    val total get() = if (cum.isEmpty()) 0f else cum.last()

    private val casing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 14f * scale
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        setShadowLayer(8f * scale, 0f, 2f * scale, 0x55000000)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Palette.TERRA; strokeWidth = 7.5f * scale
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Path of the first [fraction] of the track (by on-screen length). Returns the head position. */
    fun draw(canvas: Canvas, fraction: Float = 1f): PointF? {
        if (screen.isEmpty()) return null
        val limit = total * fraction.coerceIn(0f, 1f)
        val path = Path()
        var head: PointF? = null
        var lastSeg = Int.MIN_VALUE
        for (i in screen.indices) {
            val p = screen[i]
            if (cum[i] > limit && i > 0 && segments[i] == segments[i - 1]) {
                val span = cum[i] - cum[i - 1]
                val f = if (span > 0) (limit - cum[i - 1]) / span else 0f
                val q = screen[i - 1]
                head = PointF(q.x + (p.x - q.x) * f, q.y + (p.y - q.y) * f)
                path.lineTo(head.x, head.y)
                break
            }
            if (cum[i] > limit) break
            if (segments[i] != lastSeg) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            lastSeg = segments[i]
            head = p
        }
        canvas.drawPath(path, casing)
        canvas.drawPath(path, line)
        return head
    }

    fun drawEndpoints(canvas: Canvas, scale: Float, showEnd: Boolean) {
        val start = screen.firstOrNull() ?: return
        ring(canvas, start, Palette.FOREST, scale)
        if (showEnd) ring(canvas, screen.last(), Palette.TERRA, scale)
    }

    fun ring(canvas: Canvas, p: PointF, color: Int, scale: Float, r: Float = 11f) {
        dot.style = Paint.Style.FILL
        dot.color = Color.WHITE; canvas.drawCircle(p.x, p.y, (r + 5f) * scale, dot)
        dot.color = color; canvas.drawCircle(p.x, p.y, r * scale, dot)
    }
}

private fun roundedBitmap(canvas: Canvas, bmp: Bitmap, dst: RectF, radius: Float, border: Float = 0f) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    if (border > 0) {
        paint.color = Color.WHITE
        paint.setShadowLayer(border * 2, 0f, border / 2, 0x66000000)
        canvas.drawRoundRect(RectF(dst.left - border, dst.top - border, dst.right + border, dst.bottom + border), radius + border, radius + border, paint)
        paint.clearShadowLayer()
    }
    val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    val s = max(dst.width() / bmp.width, dst.height() / bmp.height)
    val m = Matrix().apply {
        setScale(s, s)
        postTranslate(dst.left + (dst.width() - bmp.width * s) / 2, dst.top + (dst.height() - bmp.height * s) / 2)
    }
    shader.setLocalMatrix(m)
    paint.shader = shader
    canvas.drawRoundRect(dst, radius, radius, paint)
}

/** Fallback when no offline map tiles exist: soft topographic texture. */
private fun drawTopoFallback(canvas: Canvas, w: Int, h: Int) {
    canvas.drawColor(Palette.STONE100)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Palette.STONE200 }
    for (k in 0 until 22) {
        val path = Path()
        val base = h * k / 20f
        path.moveTo(0f, base)
        var x = 0f
        while (x <= w) {
            path.lineTo(x, base + 26f * sin(x / 140f + k * 0.7f).toFloat() + 14f * sin(x / 53f + k).toFloat())
            x += 12f
        }
        canvas.drawPath(path, p)
    }
}

private fun textPaint(size: Float, color: Int, bold: Boolean = false, tabular: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = size
    this.color = color
    typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    if (tabular) fontFeatureSettings = "tnum"
}

private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
    if (paint.measureText(text) <= maxWidth) return text
    var t = text
    while (t.isNotEmpty() && paint.measureText("$t…") > maxWidth) t = t.dropLast(1)
    return "$t…"
}

// ============================== POSTER ==============================

private fun timeStat(ms: Long): Triple<String, String, String> =
    if (ms >= 3_600_000) Triple("%d:%02d".format(ms / 3_600_000, (ms / 60_000) % 60), "giờ", "Thời gian di chuyển")
    else Triple("${ms / 60_000}", "phút", "Thời gian di chuyển")

object PosterRenderer {
    private const val W = 1080
    private const val H = 1350
    private const val MAP_H = 880

    suspend fun render(context: Context, tripId: Long): Uri {
        val scene = SceneBuilder.build(context, tripId)
        val backdrop = SceneBuilder.backdrop(context, scene, W, MAP_H, padTop = 0.05f, padBottom = 0.12f)
        return withContext(Dispatchers.Default) {
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Palette.STONE50)

            // Map
            c.save(); c.clipRect(0f, 0f, W.toFloat(), MAP_H.toFloat())
            backdrop.bitmap?.let { c.drawBitmap(it, null, RectF(0f, 0f, W.toFloat(), MAP_H.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG)) }
                ?: drawTopoFallback(c, W, MAP_H)
            val painter = TrackPainter(scene.points, backdrop.project, 1.4f)
            painter.draw(c)
            painter.drawEndpoints(c, 1.4f, showEnd = true)
            val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            scene.photos.forEach {
                val p = backdrop.project(it.lat, it.lon)
                pinPaint.color = Color.WHITE; c.drawCircle(p.x, p.y, 11f, pinPaint)
                pinPaint.color = Palette.FOREST; c.drawCircle(p.x, p.y, 7f, pinPaint)
            }
            c.restore()
            // Fade map into panel
            val fade = Paint().apply {
                shader = LinearGradient(0f, MAP_H - 160f, 0f, MAP_H.toFloat(), 0x00F5F3EF, Palette.STONE50, Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, MAP_H - 160f, W.toFloat(), MAP_H.toFloat(), fade)

            // Brand chip on map
            val chip = textPaint(28f, Color.WHITE, bold = true)
            val chipText = "TERRATRACE"
            val chipW = chip.measureText(chipText) + 48f
            c.drawRoundRect(RectF(48f, 48f, 48f + chipW, 104f), 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC1F3F27.toInt() })
            chip.letterSpacing = 0.12f
            c.drawText(chipText, 72f, 86f, chip)

            // Title
            var y = MAP_H + 20f
            val title = textPaint(66f, Palette.INK, bold = true)
            c.drawText(ellipsize(scene.trip.name, title, W - 112f), 56f, y, title)
            y += 52f
            val sub = textPaint(32f, Palette.STONE600)
            c.drawText(Fmt.dateRange(scene.trip.startTime, scene.trip.endTime), 56f, y, sub)

            // Stats
            y += 100f
            val stats = listOf(
                Fmt.distanceValue(scene.stats.distanceM).let { (v, u) -> Triple(v, u, "Quãng đường") },
                Triple("%,d".format(scene.stats.elevationGainM.toInt()), "m", "Độ cao tích luỹ"),
                timeStat(scene.stats.movingTimeMs.takeIf { it > 0 } ?: scene.stats.totalTimeMs),
            )
            val colW = (W - 112f) / 3
            val value = textPaint(76f, Palette.FOREST_DARK, bold = true, tabular = true)
            val unit = textPaint(30f, Palette.STONE600, bold = true)
            val label = textPaint(26f, Palette.STONE600)
            stats.forEachIndexed { i, (v, u, l) ->
                val x = 56f + i * colW
                c.drawText(v, x, y, value)
                if (u.isNotEmpty()) c.drawText(u, x + value.measureText(v) + 8f, y, unit)
                c.drawText(l, x, y + 40f, label)
                if (i > 0) c.drawRect(x - 20f, y - 70f, x - 18f, y + 44f, Paint().apply { color = Palette.STONE200 })
            }

            // Photo strip
            val picks = SceneBuilder.pickPhotos(scene.photos, 4)
            if (picks.isNotEmpty()) {
                val top = y + 80f
                val size = min(150f, H - 70f - top)
                var x = 56f
                for (p in picks) {
                    coroutineContext.ensureActive()
                    val b = SceneBuilder.loadPhoto(context, p.uri, 400) ?: continue
                    roundedBitmap(c, b, RectF(x, top, x + size * 1.3f, top + size), 22f)
                    x += size * 1.3f + 18f
                }
            }

            // Watermark
            val wm = textPaint(26f, Palette.STONE600)
            val wmText = "Created with TerraTrace · © OpenStreetMap"
            c.drawText(wmText, W - 56f - wm.measureText(wmText), H - 36f, wm)

            val file = File(Publisher.exportDir(context), "terratrace_poster_${System.currentTimeMillis()}.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Publisher.publish(context, file, "image/png", "TerraTrace")
        }
    }
}

// ============================== VIDEO ==============================

object VideoRenderer {
    private const val W = 720
    private const val H = 1280
    private const val FPS = 30
    private const val DRAW_SECONDS = 13
    private const val HOLD_SECONDS = 2
    private const val POP_FRAMES = 40

    /** Renders a vertical MP4 of the route drawing itself with photos popping up. */
    suspend fun render(context: Context, tripId: Long, onProgress: (Float) -> Unit): Uri {
        val scene = SceneBuilder.build(context, tripId)
        val backdrop = SceneBuilder.backdrop(context, scene, W, H, padTop = 0.35f, padBottom = 0.25f)
        val photos = SceneBuilder.pickPhotos(scene.photos, 8)
        val photoBitmaps = withContext(Dispatchers.IO) { photos.associate { it.id to SceneBuilder.loadPhoto(context, it.uri, 480) } }

        return withContext(Dispatchers.Default) {
            val painter = TrackPainter(scene.points, backdrop.project, 1f)
            val photoFraction = photoFractions(scene.points, painter, photos)
            val file = File(Publisher.exportDir(context), "terratrace_video_${System.currentTimeMillis()}.mp4")
            val encoder = Encoder(file, W, H, FPS)
            try {
                val drawFrames = FPS * DRAW_SECONDS
                val total = drawFrames + FPS * HOLD_SECONDS
                for (f in 0 until total) {
                    coroutineContext.ensureActive()
                    val t = min(1f, f / drawFrames.toFloat())
                    val eased = easeInOut(t)
                    encoder.frame { c -> drawFrame(c, scene, backdrop, painter, eased, f, drawFrames, photos, photoBitmaps, photoFraction) }
                    if (f % 10 == 0) onProgress(f / total.toFloat())
                }
                encoder.finish()
            } catch (e: Throwable) {
                encoder.release()
                file.delete()
                throw e
            }
            onProgress(1f)
            Publisher.publish(context, file, "video/mp4", "TerraTrace")
        }
    }

    private fun easeInOut(t: Float) = if (t < 0.5f) 2 * t * t else 1 - (-2 * t + 2).let { it * it } / 2

    /** Where along the drawn path (0..1) each photo should pop. */
    private fun photoFractions(points: List<GeoPoint>, painter: TrackPainter, photos: List<PhotoEntity>): Map<Long, Float> {
        if (points.isEmpty() || painter.total <= 0f) return photos.withIndex().associate { (i, p) -> p.id to (i + 1f) / (photos.size + 1) }
        return photos.associate { p ->
            val idx = if (points.all { it.time != null }) {
                points.indexOfFirst { it.time!! >= p.takenAt }.let { if (it < 0) points.lastIndex else it }
            } else GeoMath.nearestIndex(points, p.lat, p.lon)
            p.id to (painter.cum[idx] / painter.total).coerceIn(0.02f, 0.98f)
        }
    }

    private fun drawFrame(
        c: Canvas,
        scene: TrailScene,
        backdrop: Backdrop,
        painter: TrackPainter,
        progress: Float,
        frame: Int,
        drawFrames: Int,
        photos: List<PhotoEntity>,
        bitmaps: Map<Long, Bitmap?>,
        fractions: Map<Long, Float>,
    ) {
        backdrop.bitmap?.let { c.drawBitmap(it, 0f, 0f, null) } ?: drawTopoFallback(c, W, H)
        // Top and bottom scrims for legibility
        c.drawRect(0f, 0f, W.toFloat(), 360f, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, 360f, 0xCC0F1210.toInt(), 0x000F1210, Shader.TileMode.CLAMP)
        })
        c.drawRect(0f, H - 300f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, H - 300f, 0f, H.toFloat(), 0x000F1210, 0xDD0F1210.toInt(), Shader.TileMode.CLAMP)
        })

        val head = painter.draw(c, progress)
        painter.drawEndpoints(c, 1f, showEnd = progress >= 1f)
        if (head != null && progress < 1f) {
            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55C8643B }
            c.drawCircle(head.x, head.y, 22f + 6f * sin(frame / 4f), halo)
            painter.ring(c, head, Palette.TERRA, 1f, r = 9f)
        }

        // Header
        val title = textPaint(46f, Color.WHITE, bold = true)
        c.drawText(ellipsize(scene.trip.name, title, W - 96f), 48f, 110f, title)
        c.drawText(Fmt.dateRange(scene.trip.startTime, scene.trip.endTime), 48f, 156f, textPaint(26f, 0xCCFFFFFF.toInt()))

        // Counters
        val dist = scene.stats.distanceM * progress
        val gain = scene.stats.elevationGainM * progress
        val v = textPaint(64f, Color.WHITE, bold = true, tabular = true)
        val l = textPaint(24f, 0xB3FFFFFF.toInt())
        val (dv, du) = Fmt.distanceValue(dist)
        c.drawText("$dv $du", 48f, H - 120f, v)
        c.drawText("Quãng đường", 48f, H - 80f, l)
        val gainText = "%,d m".format(gain.toInt())
        c.drawText(gainText, W - 48f - v.measureText(gainText), H - 120f, v)
        c.drawText("Độ cao tích luỹ", W - 48f - l.measureText("Độ cao tích luỹ"), H - 80f, l)

        // Photo pins that have been passed + popup for the current one
        val pin = Paint(Paint.ANTI_ALIAS_FLAG)
        for (p in photos) {
            val f = fractions[p.id] ?: continue
            if (progress < f) continue
            val pt = backdrop.project(p.lat, p.lon)
            pin.color = Color.WHITE; c.drawCircle(pt.x, pt.y, 10f, pin)
            pin.color = Palette.FOREST; c.drawCircle(pt.x, pt.y, 6.5f, pin)
        }
        val currentFrameProgress = frame.toFloat()
        for (p in photos) {
            val f = fractions[p.id] ?: continue
            val bmp = bitmaps[p.id] ?: continue
            val popFrame = invEase(f) * drawFrames
            val age = currentFrameProgress - popFrame
            if (age < 0 || age > POP_FRAMES || frame >= drawFrames) continue
            val appear = min(1f, age / 8f)
            val vanish = min(1f, (POP_FRAMES - age) / 8f)
            val s = overshoot(appear) * vanish
            val pt = backdrop.project(p.lat, p.lon)
            val size = 300f * s
            val cx = pt.x.coerceIn(48f + size / 2, W - 48f - size / 2)
            val cy = (pt.y - 40f - size / 2).coerceIn(220f + size / 2, H - 340f - size / 2)
            if (size > 4f) {
                roundedBitmap(c, bmp, RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2), 26f * s, border = 7f * s)
                val tp = textPaint(24f * s, Color.WHITE, bold = true)
                val ts = Fmt.time(p.takenAt)
                val tw = tp.measureText(ts)
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC1F3F27.toInt() }
                c.drawRoundRect(RectF(cx - tw / 2 - 14f, cy + size / 2 - 50f * s, cx + tw / 2 + 14f, cy + size / 2 - 14f * s), 18f, 18f, bg)
                c.drawText(ts, cx - tw / 2, cy + size / 2 - 23f * s, tp)
            }
        }

        // Watermark
        val wm = textPaint(22f, 0x99FFFFFF.toInt())
        val wmText = "Created with TerraTrace · © OpenStreetMap"
        c.drawText(wmText, (W - wm.measureText(wmText)) / 2, H - 32f, wm)
    }

    /** Inverse of easeInOut, to find the frame at which the head reaches a given fraction. */
    private fun invEase(y: Float): Float {
        var lo = 0f; var hi = 1f
        repeat(24) { val m = (lo + hi) / 2; if (easeInOut(m) < y) lo = m else hi = m }
        return (lo + hi) / 2
    }

    private fun overshoot(t: Float): Float {
        val s = 1.70158f
        val x = t - 1
        return x * x * ((s + 1) * x + s) + 1
    }

    /** H.264 encoder fed by drawing on its input Surface with a hardware Canvas. */
    private class Encoder(file: File, w: Int, h: Int, private val fps: Int) {
        private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private val surface: android.view.Surface
        private val info = MediaCodec.BufferInfo()
        private var track = -1
        private var muxing = false
        private var written = 0L

        init {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
        }

        fun frame(draw: (Canvas) -> Unit) {
            val canvas = surface.lockHardwareCanvas()
            try { draw(canvas) } finally { surface.unlockCanvasAndPost(canvas) }
            drain(false)
        }

        fun finish() {
            codec.signalEndOfInputStream()
            drain(true)
            release()
        }

        fun release() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { surface.release() }
            runCatching { if (muxing) muxer.stop() }
            runCatching { muxer.release() }
            muxing = false
        }

        private fun drain(end: Boolean) {
            val deadline = System.nanoTime() + if (end) 5_000_000_000L else 0L
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end || System.nanoTime() > deadline) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start(); muxing = true
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buf != null && info.size > 0 && muxing && !config) {
                            // Surface timestamps follow wall-clock render speed; rewrite to a steady frame rate.
                            info.presentationTimeUs = written * 1_000_000L / fps
                            written++
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    }
}
