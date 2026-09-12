package com.veritransit.inspector.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ShipmentEntity::class, PackageEntity::class, ScanOutboxEntity::class,
        DocumentFactEntity::class, PodDraftEntity::class, InspectionRecordEntity::class,
        KeyConfigEntity::class, FrictionBandEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class VeriTransitDatabase : RoomDatabase() {
    abstract fun shipments(): ShipmentDao
    abstract fun packages(): PackageDao
    abstract fun outbox(): ScanOutboxDao
    abstract fun documents(): DocumentDao
    abstract fun pod(): PodDao
    abstract fun inspections(): InspectionDao
    abstract fun keys(): KeyConfigDao

    companion object {
        @Volatile private var instance: VeriTransitDatabase? = null

        fun get(context: Context): VeriTransitDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, VeriTransitDatabase::class.java, "veritransit.db",
            )
                // A destructive migration would discard unsynced scan events, which
                // are the one thing on this device that exists nowhere else. Schema
                // changes get real migrations.
                .build()
                .also { instance = it }
        }
    }
}
