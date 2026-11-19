package app.terratrace.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query(
        """SELECT t.id, t.name, t.kind, t.startTime, t.endTime, t.distanceM, t.movingTimeMs, t.elevationGainM,
                  t.isRecording, t.pinnedOnMap, (SELECT COUNT(*) FROM photos p WHERE p.tripId = t.id) AS photoCount
           FROM trips t ORDER BY COALESCE(t.startTime, t.createdAt) DESC"""
    )
    fun observeTrips(): Flow<List<TripWithCount>>

    @Query("SELECT * FROM trips WHERE id = :id")
    fun observeTrip(id: Long): Flow<Trip?>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getTrip(id: Long): Trip?

    @Query("SELECT * FROM trips WHERE isRecording = 1 LIMIT 1")
    suspend fun getRecordingTrip(): Trip?

    @Query("SELECT * FROM trips WHERE isRecording = 1 LIMIT 1")
    fun observeRecordingTrip(): Flow<Trip?>

    @Query("SELECT * FROM trips WHERE pinnedOnMap = 1")
    fun observePinned(): Flow<List<Trip>>

    @Insert suspend fun insert(trip: Trip): Long
    @Update suspend fun update(trip: Trip)
    @Delete suspend fun delete(trip: Trip)

    @Query("UPDATE trips SET name = :name WHERE id = :id") suspend fun rename(id: Long, name: String)
    @Query("UPDATE trips SET pinnedOnMap = :pinned WHERE id = :id") suspend fun setPinned(id: Long, pinned: Boolean)
    @Query("DELETE FROM trips") suspend fun deleteAll()
}

@Dao
interface PointDao {
    @Insert suspend fun insert(p: TrackPointEntity): Long
    @Insert suspend fun insertAll(p: List<TrackPointEntity>)

    @Query("SELECT * FROM track_points WHERE tripId = :tripId ORDER BY id")
    suspend fun pointsFor(tripId: Long): List<TrackPointEntity>

    @Query("SELECT * FROM track_points WHERE tripId = :tripId ORDER BY id")
    fun observePoints(tripId: Long): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM track_points WHERE tripId = :tripId ORDER BY id DESC LIMIT 1")
    suspend fun lastPoint(tripId: Long): TrackPointEntity?
}

@Dao
interface PhotoDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(p: List<PhotoEntity>): List<Long>

    @Query("SELECT * FROM photos WHERE tripId = :tripId ORDER BY takenAt")
    fun observePhotos(tripId: Long): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE tripId = :tripId ORDER BY takenAt")
    suspend fun photosFor(tripId: Long): List<PhotoEntity>

    @Query("UPDATE photos SET favorite = :fav WHERE id = :id") suspend fun setFavorite(id: Long, fav: Boolean)
    @Query("DELETE FROM photos WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface WaypointDao {
    @Insert suspend fun insertAll(w: List<WaypointEntity>)
    @Query("SELECT * FROM waypoints WHERE tripId = :tripId") suspend fun waypointsFor(tripId: Long): List<WaypointEntity>
    @Query("SELECT * FROM waypoints WHERE tripId = :tripId") fun observe(tripId: Long): Flow<List<WaypointEntity>>
}

@Dao
interface PrivacyZoneDao {
    @Query("SELECT * FROM privacy_zones") fun observe(): Flow<List<PrivacyZone>>
    @Query("SELECT * FROM privacy_zones") suspend fun all(): List<PrivacyZone>
    @Insert suspend fun insert(z: PrivacyZone): Long
    @Delete suspend fun delete(z: PrivacyZone)
    @Query("DELETE FROM privacy_zones") suspend fun deleteAll()
}

class Converters {
    @TypeConverter fun kindToString(k: TripKind) = k.name
    @TypeConverter fun stringToKind(s: String) = TripKind.valueOf(s)
    @TypeConverter fun srcToString(k: LocationSource) = k.name
    @TypeConverter fun stringToSrc(s: String) = LocationSource.valueOf(s)
}

@Database(
    entities = [Trip::class, TrackPointEntity::class, PhotoEntity::class, WaypointEntity::class, PrivacyZone::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun points(): PointDao
    abstract fun photos(): PhotoDao
    abstract fun waypoints(): WaypointDao
    abstract fun privacyZones(): PrivacyZoneDao

    companion object {
        fun build(context: Context) =
            Room.databaseBuilder(context, AppDatabase::class.java, "terratrace.db").build()
    }
}
