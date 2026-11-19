package app.terratrace.io

import app.terratrace.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GpxKmlTest {
    private fun parse(s: String) = TrackFiles.parse(s.byteInputStream())

    @Test fun parsesGpx11WithSegmentsAndWaypoints() {
        val gpx = """<?xml version="1.0"?>
            <gpx version="1.1" creator="x" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata><name>Doc</name></metadata>
              <wpt lat="21.1" lon="105.1"><ele>12</ele><name>Đỉnh &amp; suối</name></wpt>
              <trk><name>Tà Xùa</name>
                <trkseg>
                  <trkpt lat="21.0" lon="105.0"><ele>1000.5</ele><time>2024-10-15T01:00:00Z</time></trkpt>
                  <trkpt lat="21.001" lon="105.0"><ele>1010</ele><time>2024-10-15T01:01:00.500Z</time></trkpt>
                </trkseg>
                <trkseg>
                  <trkpt lat="21.01" lon="105.0"/>
                </trkseg>
              </trk>
            </gpx>"""
        val t = parse(gpx)
        assertEquals("Tà Xùa", t.name)
        assertEquals(3, t.points.size)
        assertEquals(listOf(0, 0, 1), t.points.map { it.segment })
        assertEquals(1000.5, t.points[0].ele!!, 1e-9)
        assertEquals(1728954000000L, t.points[0].time)
        assertEquals(1728954060500L, t.points[1].time)
        assertNull(t.points[2].time)
        assertEquals("Đỉnh & suối", t.waypoints.single().name)
    }

    @Test fun parsesGpxRoute() {
        val t = parse("""<gpx><rte><name>R</name><rtept lat="1" lon="2"/><rtept lat="1.1" lon="2.1"/></rte></gpx>""")
        assertEquals(2, t.points.size)
        assertEquals("R", t.name)
    }

    @Test fun parsesKmlLineStringAndGxTrack() {
        val kml = """<?xml version="1.0"?>
            <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">
            <Document><name>Trip</name>
              <Placemark><name>Line</name><LineString><coordinates>
                105.0,21.0,100 105.001,21.001,110
              </coordinates></LineString></Placemark>
              <Placemark><name>Cafe</name><Point><coordinates>105.5,21.5</coordinates></Point></Placemark>
              <Placemark><gx:Track>
                <when>2024-10-15T01:00:00Z</when><when>2024-10-15T01:00:10Z</when>
                <gx:coord>105.1 21.1 5</gx:coord><gx:coord>105.2 21.2 6</gx:coord>
              </gx:Track></Placemark>
            </Document></kml>"""
        val t = parse(kml)
        assertEquals("Line", t.name)
        assertEquals(4, t.points.size)
        assertEquals(listOf(0, 0, 1, 1), t.points.map { it.segment })
        assertEquals(105.0, t.points[0].lon, 1e-9)
        assertNotNull(t.points[3].time)
        assertEquals("Cafe", t.waypoints.single().name)
    }

    @Test(expected = TrackParseException::class)
    fun rejectsGarbage() { parse("hello world") }

    @Test(expected = TrackParseException::class)
    fun rejectsEmptyGpx() { parse("<gpx></gpx>") }

    @Test fun gpxRoundTrip() {
        val pts = listOf(
            GeoPoint(21.0, 105.0, 10.0, 1728954000000, 0),
            GeoPoint(21.001, 105.001, null, null, 0),
            GeoPoint(21.002, 105.002, 12.0, 1728954060000, 1),
        )
        val back = parse(TrackFiles.writeGpx("A <b> & c", pts))
        assertEquals("A <b> & c", back.name)
        assertEquals(pts, back.points)
    }

    @Test fun kmlRoundTrip() {
        val pts = listOf(GeoPoint(21.0, 105.0, 10.0, segment = 0), GeoPoint(21.001, 105.001, 11.0, segment = 0))
        val back = parse(TrackFiles.writeKml("K", pts))
        assertEquals(pts, back.points)
    }
}
