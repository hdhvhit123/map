package app.terratrace.io

import app.terratrace.geo.GeoPoint
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

data class ParsedWaypoint(val name: String, val lat: Double, val lon: Double, val ele: Double?, val time: Long?)

data class ParsedTrack(
    val name: String?,
    val points: List<GeoPoint>,
    val waypoints: List<ParsedWaypoint>,
)

class TrackParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class TrackFormat { GPX, KML;
    companion object {
        fun fromName(name: String?): TrackFormat? = when {
            name == null -> null
            name.endsWith(".gpx", true) -> GPX
            name.endsWith(".kml", true) -> KML
            else -> null
        }
    }
}

object TrackFiles {

    /** Parses GPX or KML, sniffing the root element when the format is unknown. Streaming (SAX). */
    fun parse(input: InputStream): ParsedTrack {
        val handler = Handler()
        try {
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
            val parser = factory.newSAXParser()
            parser.parse(InputSource(input), handler)
        } catch (e: Exception) {
            if (handler.points.isEmpty() && handler.waypoints.isEmpty()) {
                throw TrackParseException("File không đúng định dạng GPX/KML", e)
            }
            // Truncated file: keep what we could read.
        }
        if (handler.root == null) throw TrackParseException("File không đúng định dạng GPX/KML")
        if (handler.points.isEmpty() && handler.waypoints.isEmpty()) {
            throw TrackParseException("File không chứa điểm lộ trình nào")
        }
        return ParsedTrack(handler.trackName ?: handler.docName, handler.points, handler.waypoints)
    }

    fun parseTime(text: String?): Long? {
        val s = text?.trim().orEmpty()
        if (s.isEmpty()) return null
        return runCatching { Instant.parse(s).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            .recoverCatching { LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli() }
            .getOrNull()
    }

    private class Handler : DefaultHandler() {
        var root: String? = null
        val points = ArrayList<GeoPoint>()
        val waypoints = ArrayList<ParsedWaypoint>()
        var trackName: String? = null
        var docName: String? = null

        private val text = StringBuilder()
        private val stack = ArrayList<String>()
        private var segment = -1

        // GPX state
        private var curLat = 0.0; private var curLon = 0.0
        private var curEle: Double? = null; private var curTime: Long? = null; private var curName: String? = null

        // KML state
        private var placemarkName: String? = null
        private val gxWhens = ArrayList<Long?>()
        private val gxCoords = ArrayList<GeoPoint>()

        private val isKml get() = root == "kml"

        override fun startElement(uri: String?, localName: String?, qName: String?, attrs: Attributes) {
            val name = localName?.ifEmpty { null } ?: qName?.substringAfter(':') ?: ""
            if (root == null) {
                root = name.lowercase(Locale.ROOT).takeIf { it == "gpx" || it == "kml" }
                    ?: throw TrackParseException("Không phải file GPX/KML")
            }
            stack += name
            text.setLength(0)
            if (!isKml) {
                when (name) {
                    "trkseg" -> segment++
                    "rte" -> segment++
                    "trkpt", "rtept", "wpt" -> {
                        curLat = attrs.getValue("lat")?.toDoubleOrNull() ?: Double.NaN
                        curLon = attrs.getValue("lon")?.toDoubleOrNull() ?: Double.NaN
                        curEle = null; curTime = null; curName = null
                    }
                }
            } else {
                when (name) {
                    "Placemark" -> placemarkName = null
                    "Track" -> { gxWhens.clear(); gxCoords.clear() }
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = localName?.ifEmpty { null } ?: qName?.substringAfter(':') ?: ""
            val value = text.toString().trim()
            val parent = stack.getOrNull(stack.size - 2)
            if (!isKml) endGpx(name, value, parent) else endKml(name, value, parent)
            if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            text.setLength(0)
        }

        private fun endGpx(name: String, value: String, parent: String?) {
            when (name) {
                "ele" -> curEle = value.toDoubleOrNull()
                "time" -> if (parent in setOf("trkpt", "rtept", "wpt")) curTime = parseTime(value)
                "name" -> when (parent) {
                    "wpt", "trkpt", "rtept" -> curName = value
                    "trk", "rte" -> if (trackName == null && value.isNotEmpty()) trackName = value
                    "metadata" -> if (docName == null && value.isNotEmpty()) docName = value
                }
                "trkpt", "rtept" -> if (valid()) {
                    points += GeoPoint(curLat, curLon, curEle, curTime, segment.coerceAtLeast(0))
                }
                "wpt" -> if (valid()) {
                    waypoints += ParsedWaypoint(curName ?: "Điểm ${waypoints.size + 1}", curLat, curLon, curEle, curTime)
                }
            }
        }

        private fun valid() = !curLat.isNaN() && !curLon.isNaN() && curLat in -90.0..90.0 && curLon in -180.0..180.0

        private fun endKml(name: String, value: String, parent: String?) {
            when (name) {
                "name" -> when (parent) {
                    "Placemark" -> placemarkName = value
                    "Document", "Folder" -> if (docName == null && value.isNotEmpty()) docName = value
                }
                "coordinates" -> {
                    val coords = value.split(Regex("\\s+")).mapNotNull { tuple ->
                        val parts = tuple.split(',')
                        val lon = parts.getOrNull(0)?.toDoubleOrNull()
                        val lat = parts.getOrNull(1)?.toDoubleOrNull()
                        if (lat == null || lon == null) null else GeoPoint(lat, lon, parts.getOrNull(2)?.toDoubleOrNull())
                    }
                    when (parent) {
                        "LineString", "LinearRing" -> if (coords.isNotEmpty()) {
                            segment++
                            coords.forEach { points += it.copy(segment = segment) }
                            if (trackName == null) trackName = placemarkName
                        }
                        "Point" -> coords.firstOrNull()?.let {
                            waypoints += ParsedWaypoint(placemarkName ?: "Điểm ${waypoints.size + 1}", it.lat, it.lon, it.ele, null)
                        }
                    }
                }
                "when" -> if (parent == "Track") gxWhens += parseTime(value)
                "coord" -> if (parent == "Track") {
                    val parts = value.split(Regex("\\s+"))
                    val lon = parts.getOrNull(0)?.toDoubleOrNull()
                    val lat = parts.getOrNull(1)?.toDoubleOrNull()
                    if (lat != null && lon != null) gxCoords += GeoPoint(lat, lon, parts.getOrNull(2)?.toDoubleOrNull())
                }
                "Track" -> if (gxCoords.isNotEmpty()) {
                    segment++
                    gxCoords.forEachIndexed { i, p ->
                        points += p.copy(time = gxWhens.getOrNull(i), segment = segment)
                    }
                    if (trackName == null) trackName = placemarkName
                }
            }
        }
    }

    // ---------------- Writers ----------------

    private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    private fun iso(t: Long) = isoFormatter.format(Instant.ofEpochMilli(t))

    fun escape(s: String) = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&apos;")
            else -> if (c.code >= 0x20 || c == '\n' || c == '\t') append(c)
        }
    }

    private fun fmt(d: Double) = String.format(Locale.US, "%.7f", d)
    private fun fmtEle(d: Double) = String.format(Locale.US, "%.1f", d)

    fun writeGpx(name: String, points: List<GeoPoint>, waypoints: List<ParsedWaypoint> = emptyList()): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="TerraTrace" xmlns="http://www.topografix.com/GPX/1/1" """)
        append("""xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """)
        append("""xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">""").append('\n')
        append("  <metadata><name>").append(escape(name)).append("</name></metadata>\n")
        for (w in waypoints) {
            append("  <wpt lat=\"").append(fmt(w.lat)).append("\" lon=\"").append(fmt(w.lon)).append("\">")
            w.ele?.let { append("<ele>").append(fmtEle(it)).append("</ele>") }
            w.time?.let { append("<time>").append(iso(it)).append("</time>") }
            append("<name>").append(escape(w.name)).append("</name></wpt>\n")
        }
        append("  <trk>\n    <name>").append(escape(name)).append("</name>\n")
        var seg: Int? = null
        for (p in points) {
            if (p.segment != seg) {
                if (seg != null) append("    </trkseg>\n")
                append("    <trkseg>\n")
                seg = p.segment
            }
            append("      <trkpt lat=\"").append(fmt(p.lat)).append("\" lon=\"").append(fmt(p.lon)).append("\">")
            p.ele?.let { append("<ele>").append(fmtEle(it)).append("</ele>") }
            p.time?.let { append("<time>").append(iso(it)).append("</time>") }
            append("</trkpt>\n")
        }
        if (seg != null) append("    </trkseg>\n")
        append("  </trk>\n</gpx>\n")
    }

    fun writeKml(name: String, points: List<GeoPoint>, waypoints: List<ParsedWaypoint> = emptyList()): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<kml xmlns="http://www.opengis.net/kml/2.2">""").append('\n')
        append("<Document>\n  <name>").append(escape(name)).append("</name>\n")
        append("  <Style id=\"track\"><LineStyle><color>ff3b64c8</color><width>4</width></LineStyle></Style>\n")
        for (w in waypoints) {
            append("  <Placemark><name>").append(escape(w.name)).append("</name><Point><coordinates>")
            append(fmt(w.lon)).append(',').append(fmt(w.lat))
            w.ele?.let { append(',').append(fmtEle(it)) }
            append("</coordinates></Point></Placemark>\n")
        }
        val segments = points.groupBy { it.segment }
        for ((_, seg) in segments) {
            append("  <Placemark><name>").append(escape(name)).append("</name><styleUrl>#track</styleUrl>")
            append("<LineString><tessellate>1</tessellate><coordinates>\n")
            for (p in seg) {
                append(fmt(p.lon)).append(',').append(fmt(p.lat))
                p.ele?.let { append(',').append(fmtEle(it)) }
                append('\n')
            }
            append("</coordinates></LineString></Placemark>\n")
        }
        append("</Document>\n</kml>\n")
    }
}
