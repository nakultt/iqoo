package com.veritransit.inspector.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ShipmentDao {
    @Query("SELECT * FROM shipment ORDER BY ref DESC")
    fun observeAll(): Flow<List<ShipmentEntity>>

    @Query("SELECT * FROM shipment WHERE ref = :ref")
    suspend fun byRef(ref: String): ShipmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<ShipmentEntity>)

    @Query("DELETE FROM shipment")
    suspend fun clear()

    /** §12 — a cache older than a day cannot be trusted to gate a release. */
    @Query("SELECT MIN(cachedAt) FROM shipment")
    suspend fun oldestCacheAt(): Long?
}

@Dao
interface PackageDao {
    @Query("SELECT * FROM package_record WHERE shipmentRef = :ref ORDER BY kind DESC, packageCode")
    fun observeForShipment(ref: String): Flow<List<PackageEntity>>

    @Query("SELECT * FROM package_record WHERE packageCode = :code")
    suspend fun byCode(code: String): PackageEntity?

    @Query("SELECT * FROM package_record WHERE parentCode = :master ORDER BY packageCode")
    suspend fun childrenOf(master: String): List<PackageEntity>

    @Query("SELECT * FROM package_record WHERE parentCode = :master ORDER BY packageCode")
    fun observeChildrenOf(master: String): Flow<List<PackageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<PackageEntity>)

    @Query("UPDATE package_record SET localStatus = :status WHERE packageCode = :code")
    suspend fun setLocalStatus(code: String, status: String)

    /** §2.1 — loading a master ticks its whole subtree. */
    @Query("UPDATE package_record SET localStatus = :status WHERE parentCode = :master")
    suspend fun setSubtreeStatus(master: String, status: String)

    @Query("""SELECT COUNT(*) FROM package_record
              WHERE shipmentRef = :ref AND parentCode IS NULL
                AND (localStatus IN ('LOADED','RECEIVED') OR status IN ('LOADED','RECEIVED'))""")
    fun observeAccounted(ref: String): Flow<Int>

    @Query("""SELECT * FROM package_record
              WHERE shipmentRef = :ref AND parentCode IS NULL
                AND localStatus IS NULL AND status NOT IN ('LOADED','RECEIVED')""")
    suspend fun notYetScanned(ref: String): List<PackageEntity>

    @Query("DELETE FROM package_record")
    suspend fun clear()
}

@Dao
interface ScanOutboxDao {
    /** Ignore, not replace: a replayed event must never overwrite the original. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(event: ScanOutboxEntity): Long

    @Query("SELECT * FROM scan_outbox WHERE syncState = 'PENDING' ORDER BY clientTs LIMIT :limit")
    suspend fun pending(limit: Int = 200): List<ScanOutboxEntity>

    @Query("SELECT COUNT(*) FROM scan_outbox WHERE syncState = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM scan_outbox WHERE shipmentRef = :ref ORDER BY clientTs DESC LIMIT :limit")
    fun observeForShipment(ref: String, limit: Int = 100): Flow<List<ScanOutboxEntity>>

    @Query("SELECT COUNT(*) FROM scan_outbox WHERE packageCode = :code AND kind = :kind")
    suspend fun timesSeen(code: String, kind: String): Int

    @Query("""UPDATE scan_outbox SET syncState = 'SENT', serverResult = :result,
                     serverReasons = :reasons WHERE clientEventId = :id""")
    suspend fun markSent(id: String, result: String?, reasons: String?)

    @Query("UPDATE scan_outbox SET attempts = attempts + 1, lastError = :error WHERE clientEventId = :id")
    suspend fun markFailed(id: String, error: String?)

    @Transaction
    suspend fun markAllSent(acks: List<Triple<String, String?, String?>>) {
        acks.forEach { (id, result, reasons) -> markSent(id, result, reasons) }
    }
}

@Dao
interface DocumentDao {
    @Query("SELECT * FROM document_fact WHERE shipmentRef = :ref")
    fun observeForShipment(ref: String): Flow<List<DocumentFactEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<DocumentFactEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DocumentFactEntity)

    @Query("DELETE FROM document_fact")
    suspend fun clear()
}

@Dao
interface PodDao {
    @Query("SELECT * FROM pod_draft WHERE shipmentRef = :ref")
    suspend fun draft(ref: String): PodDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(draft: PodDraftEntity)

    @Query("SELECT * FROM pod_draft WHERE submitted = 0")
    suspend fun unsubmitted(): List<PodDraftEntity>
}

@Dao
interface InspectionDao {
    @Query("SELECT * FROM inspection_record ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<InspectionRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: InspectionRecordEntity)

    @Query("SELECT COUNT(*) FROM inspection_record")
    suspend fun count(): Int
}

@Dao
interface KeyConfigDao {
    @Query("SELECT * FROM key_config")
    suspend fun all(): List<KeyConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(keys: List<KeyConfigEntity>)

    @Query("SELECT * FROM friction_band")
    suspend fun bands(): List<FrictionBandEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBands(bands: List<FrictionBandEntity>)
}
