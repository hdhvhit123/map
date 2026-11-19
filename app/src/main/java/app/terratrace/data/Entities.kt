package app.terratrace.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.terratrace.geo.GeoPoint

enum class TripKind { RECORDED, IMPORTED, PHOTO_JOURNAL }

enum class LocationSource { EXIF, INTERPOLATED }

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: TripKind,
    val startTime: Long? = null,
    val endTime: Long? = null,
    val distanceM: Double = 0.0,
    val movingTimeMs: Long = 0,
    val elevationGainM: Double = 0.0,
    val elevationLossM: Double = 0.0,
    val maxElevationM: Double? = null,
    val maxSpeedMps: Double = 0.0,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isRecording: Boolean = false,
    val pinnedOnMap: Boolean = false,
)

@Entity(
    tableName = "track_points",
    foreignKeys = [ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId", "time")],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val segment: Int,
    val time: Long?,
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    val accuracy: Float? = null,
    val speed: Float? = null,
) {
    fun toGeo() = GeoPoint(lat, lon, ele, time, segment)
}

@Entity(
    tableName = "photos",
    foreignKeys = [ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["tripId", "uri"], unique = true)],
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val uri: String,
    val takenAt: Long,
    val lat: Double,
    val lon: Double,
    val locationSource: LocationSource,
    val favorite: Boolean = false,
)

@Entity(
    tableName = "waypoints",
    foreignKeys = [ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class WaypointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    val time: Long?,
)

@Entity(tableName = "privacy_zones")
data class PrivacyZone(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Double,
)

/** Trip row plus the number of photos, for list cards. */
data class TripWithCount(
    val id: Long,
    val name: String,
    val kind: TripKind,
    val startTime: Long?,
    val endTime: Long?,
    val distanceM: Double,
    val movingTimeMs: Long,
    val elevationGainM: Double,
    val isRecording: Boolean,
    val pinnedOnMap: Boolean,
    val photoCount: Int,
)
