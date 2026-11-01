package com.btcsignal.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SignalEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun signalDao(): SignalDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** Adds the price-path snapshot column (see PricePathSnapshot.kt / HistoryScreen)
         *  without wiping existing signal history. Nullable with no default needed beyond
         *  SQLite's implicit NULL, since old rows simply have no snapshot to show. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE signals ADD COLUMN pricePathJson TEXT")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "btc_signal.db"
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
