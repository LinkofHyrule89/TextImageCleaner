package com.ubermicrostudios.textimagecleaner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The Room database for the application.
 * Manages the persistence of metadata for items moved to the internal trash.
 */
@Database(entities = [TrashedItem::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    
    /** Provides access to the TrashDao. */
    abstract fun trashDao(): TrashDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Returns the singleton instance of the AppDatabase.
         */
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "trash_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
