package app.terratrace.dem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.terratrace.geo.Bounds
import app.terratrace.geo.Terrarium
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class DemDownload(val done: Int, val total: Int, val failed: Int, val running: Boolean)

/**
 * Offline terrain model: Terrarium elevation tiles (zoom 12, ≈ 30 m per pixel) stored in app files.
 * Data: Mapzen/AWS Terrain Tiles (SRTM, GMTED, ETOPO1…). Lookups are fully offline.
 */
class TerrainStore(context: Context) {
    private val dir = File(context.filesDir, "dem/${Terrarium.ZOOM}").apply { mkdirs() }
    private val cache = LruCache<Long, FloatArray>(24) // ~6 MB
    private val missing = HashSet<Long>()
    private val _download = MutableStateFlow<DemDownload?>(null)
    val download: StateFlow<DemDownload?> = _download.asStateFlow()

    companion object {
        const val MAX_TILES = 3000
        private const val URL_TEMPLATE = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/%d/%d/%d.png"
    }

    private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
    private fun file(x: Int, y: Int) = File(dir, "${x}_$y.png")

    fun hasTile(x: Int, y: Int) = file(x, y).exists()

    /** Ground elevation in metres, or null when the tile hasn't been downloaded. Thread-safe. */
    fun elevation(lat: Double, lon: Double): Double? {
        val (tile, px, py) = Terrarium.locate(lat, lon)
        val data = tile(tile.first, tile.second) ?: return null
        return Terrarium.sample(data, px, py)
    }

    @Synchronized
    private fun tile(x: Int, y: Int): FloatArray? {
        val k = key(x, y)
        cache.get(k)?.let { return it }
        if (k in missing) return null
        val f = file(x, y)
        if (!f.exists()) { missing += k; return null }
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            inScaled = false
        }
        val bmp = BitmapFactory.decodeFile(f.path, opts) ?: run { f.delete(); missing += k; return null }
        val n = Terrarium.TILE_SIZE
        val px = IntArray(n * n)
        if (bmp.width != n || bmp.height != n) { bmp.recycle(); return null }
        bmp.getPixels(px, 0, n, 0, 0, n, n)
        bmp.recycle()
        val out = FloatArray(n * n) { Terrarium.decode(px[it]) }
        cache.put(k, out)
        return out
    }

    fun tilesNeeded(bounds: Bounds) = Terrarium.tilesFor(bounds).count { (x, y) -> !hasTile(x, y) }

    /** Downloads missing elevation tiles covering [bounds]. Returns the number of tiles now available. */
    suspend fun downloadArea(bounds: Bounds): Int = withContext(Dispatchers.IO) {
        val tiles = Terrarium.tilesFor(bounds).take(MAX_TILES)
        val todo = tiles.filterNot { (x, y) -> hasTile(x, y) }
        var done = 0; var failed = 0
        _download.value = DemDownload(0, todo.size, 0, true)
        try {
            for ((x, y) in todo) {
                coroutineContext.ensureActive()
                if (fetch(x, y)) done++ else failed++
                _download.value = DemDownload(done, todo.size, failed, true)
            }
        } finally {
            _download.value = DemDownload(done, todo.size, failed, false)
            synchronized(this@TerrainStore) { missing.clear() }
        }
        tiles.size - failed
    }

    private fun fetch(x: Int, y: Int): Boolean = runCatching {
        val conn = URL(URL_TEMPLATE.format(Terrarium.ZOOM, x, y)).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "TerraTrace")
        try {
            if (conn.responseCode != 200) return@runCatching false
            val tmp = File(dir, "${x}_$y.part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(file(x, y))
        } finally { conn.disconnect() }
    }.getOrDefault(false)

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L
    fun tileCount(): Int = dir.listFiles()?.count { it.name.endsWith(".png") } ?: 0

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        cache.evictAll(); missing.clear()
    }
}
