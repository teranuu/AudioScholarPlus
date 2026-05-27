package edu.cit.audioscholar.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import edu.cit.audioscholar.data.local.dao.RecordingMetadataDao
import edu.cit.audioscholar.data.local.dao.UserNoteDao
import edu.cit.audioscholar.data.local.model.RecordingMetadata
import edu.cit.audioscholar.data.local.model.UserNoteEntity

@Database(
    entities = [
        RecordingMetadata::class,
        UserNoteEntity::class
    ],
    version = 9,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordingMetadataDao(): RecordingMetadataDao
    abstract fun userNoteDao(): UserNoteDao

    companion object {
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recording_metadata ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recording_metadata ADD COLUMN outputType TEXT")
                db.execSQL("ALTER TABLE recording_metadata ADD COLUMN cachedQualityReport TEXT")
            }
        }
    }
}
