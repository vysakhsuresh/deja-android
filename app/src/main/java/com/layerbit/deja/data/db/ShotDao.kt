package com.layerbit.deja.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class CategoryCount(val category: String, val count: Int)

data class AppCount(val sourceApp: String, val count: Int)

data class LibraryStats(
    val total: Int,
    val totalBytes: Long,
    val oldestMillis: Long,
    val newestMillis: Long,
    val withText: Int
)

@Dao
interface ShotDao {

    /**
     * Room wraps a list insert in one transaction, which is the entire point of it here. The
     * first engine committed once per screenshot: four thousand separate transactions, each
     * paying for the FTS triggers and a flush to disk. Writing in batches turns that into a
     * couple of hundred.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(shots: List<ShotEntity>)

    /** Used by the re-read and re-derive passes, which already hold the whole row. */
    @Update
    suspend fun updateAll(shots: List<ShotEntity>)

    @Query("SELECT mediaId FROM shots")
    suspend fun indexedMediaIds(): List<Long>

    @Query("SELECT COUNT(*) FROM shots")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM shots")
    suspend fun count(): Int

    @Query("SELECT * FROM shots ORDER BY dateTakenMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ShotEntity>>

    @Query("SELECT * FROM shots WHERE category = :category ORDER BY dateTakenMillis DESC LIMIT :limit")
    fun observeByCategory(category: String, limit: Int): Flow<List<ShotEntity>>

    @Query("SELECT * FROM shots WHERE sourceApp = :app ORDER BY dateTakenMillis DESC LIMIT :limit")
    fun observeByApp(app: String, limit: Int): Flow<List<ShotEntity>>

    @Query("SELECT category, COUNT(*) AS count FROM shots GROUP BY category")
    fun observeCategoryCounts(): Flow<List<CategoryCount>>

    @Query(
        """
        SELECT sourceApp, COUNT(*) AS count FROM shots
        WHERE sourceApp <> '' GROUP BY sourceApp ORDER BY count DESC LIMIT :limit
        """
    )
    fun observeAppCounts(limit: Int): Flow<List<AppCount>>

    @Query(
        """
        SELECT COUNT(*) AS total,
               COALESCE(SUM(sizeBytes), 0) AS totalBytes,
               COALESCE(MIN(dateTakenMillis), 0) AS oldestMillis,
               COALESCE(MAX(dateTakenMillis), 0) AS newestMillis,
               COALESCE(SUM(CASE WHEN length(text) >= 12 THEN 1 ELSE 0 END), 0) AS withText
        FROM shots
        """
    )
    fun observeStats(): Flow<LibraryStats>

    @Query("SELECT * FROM shots WHERE id = :id")
    suspend fun byId(id: Long): ShotEntity?

    @Query("SELECT * FROM shots WHERE mediaId = :mediaId")
    suspend fun byMediaId(mediaId: Long): ShotEntity?

    @Query("SELECT mediaId FROM shots WHERE mediaId IN (:mediaIds)")
    suspend fun indexedAmong(mediaIds: List<Long>): List<Long>

    /**
     * Recent rows that extracted something of [entityTypeId]. A LIKE over the JSON is crude, but
     * it only ever runs over the newest rows for a widget, and it saves a joined table that
     * nothing else would use.
     */
    @Query(
        """
        SELECT * FROM shots WHERE entitiesJson LIKE '%"t":"' || :entityTypeId || '"%'
        ORDER BY dateTakenMillis DESC LIMIT :limit
        """
    )
    suspend fun recentWithEntity(entityTypeId: String, limit: Int): List<ShotEntity>

    @Query("SELECT * FROM shots WHERE pinned = 1 ORDER BY dateTakenMillis DESC LIMIT :limit")
    suspend fun pinned(limit: Int): List<ShotEntity>

    @Query("SELECT * FROM shots ORDER BY dateTakenMillis DESC")
    suspend fun all(): List<ShotEntity>

    // --- Engine upkeep -------------------------------------------------------------------
    //
    // Rows carry the engine version that produced them, so an improved engine can find exactly
    // the work it needs to redo. Re-derives are ordered oldest-stamp-first and re-reads
    // newest-screenshot-first: a backfill should reach the screenshots someone took last week
    // before the ones from two years ago.

    @Query("SELECT COUNT(*) FROM shots WHERE readVersion < :version AND readAttempts < :maxAttempts")
    suspend fun staleReadCount(version: Int, maxAttempts: Int): Int

    @Query(
        """
        SELECT * FROM shots
        WHERE readVersion < :version AND readAttempts < :maxAttempts
        ORDER BY dateTakenMillis DESC
        LIMIT :limit
        """
    )
    suspend fun staleReads(version: Int, maxAttempts: Int, limit: Int): List<ShotEntity>

    @Query(
        """
        SELECT COUNT(*) FROM shots
        WHERE deriveVersion < :deriveVersion AND readVersion >= :readVersion
        """
    )
    suspend fun staleDeriveCount(deriveVersion: Int, readVersion: Int): Int

    /**
     * Only rows whose text is already current. A row waiting to be re-read would be derived
     * twice otherwise - once from the old text and again from the new - for no benefit.
     */
    @Query(
        """
        SELECT * FROM shots
        WHERE deriveVersion < :deriveVersion AND readVersion >= :readVersion
        LIMIT :limit
        """
    )
    suspend fun staleDerives(deriveVersion: Int, readVersion: Int, limit: Int): List<ShotEntity>

    @Query("SELECT * FROM shots WHERE phash <> 0 ORDER BY dateTakenMillis DESC LIMIT :limit")
    suspend fun hashed(limit: Int): List<ShotEntity>

    @Query("UPDATE shots SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE shots SET pinned = :pinned WHERE id IN (:ids)")
    suspend fun setPinnedAll(ids: List<Long>, pinned: Boolean)

    @Query("SELECT * FROM shots WHERE pinned = 1 ORDER BY dateTakenMillis DESC LIMIT :limit")
    fun observePinned(limit: Int): Flow<List<ShotEntity>>

    @Query("SELECT COUNT(*) FROM shots WHERE pinned = 1")
    fun observePinnedCount(): Flow<Int>

    /**
     * Room maps the FTS rowid onto [ShotEntity.id], so the join is what carries the match back to
     * the full row. The caller is responsible for turning a typed query into FTS MATCH syntax.
     */
    @Query(
        """
        SELECT shots.* FROM shots
        JOIN shots_fts ON shots.id = shots_fts.rowid
        WHERE shots_fts MATCH :match
          AND shots.dateTakenMillis BETWEEN :from AND :to
        ORDER BY shots.dateTakenMillis DESC
        LIMIT :limit
        """
    )
    suspend fun search(match: String, from: Long, to: Long, limit: Int): List<ShotEntity>

    /**
     * For a query that is nothing but a date - "last week" on its own is a perfectly good thing
     * to ask a screenshot app, and there is no text in it to match against.
     */
    @Query(
        """
        SELECT * FROM shots WHERE dateTakenMillis BETWEEN :from AND :to
        ORDER BY dateTakenMillis DESC LIMIT :limit
        """
    )
    suspend fun inRange(from: Long, to: Long, limit: Int): List<ShotEntity>

    @Query(
        """
        SELECT * FROM shots WHERE pinned = 0 AND category = :category AND dateTakenMillis < :before
        ORDER BY dateTakenMillis DESC
        """
    )
    suspend fun olderThanInCategory(category: String, before: Long): List<ShotEntity>

    @Query(
        """
        SELECT * FROM shots
        WHERE pinned = 0 AND category IN (:categories) AND dateTakenMillis < :before
        ORDER BY dateTakenMillis DESC
        """
    )
    suspend fun olderThanInCategories(categories: List<String>, before: Long): List<ShotEntity>

    @Query(
        """
        SELECT * FROM shots WHERE pinned = 0 AND dateTakenMillis < :before
        ORDER BY dateTakenMillis DESC
        """
    )
    suspend fun olderThan(before: Long): List<ShotEntity>

    @Query("SELECT * FROM shots WHERE pinned = 0 AND sizeBytes >= :minBytes ORDER BY sizeBytes DESC")
    suspend fun largerThan(minBytes: Long): List<ShotEntity>

    @Query(
        """
        SELECT * FROM shots WHERE pinned = 0 AND length(text) < :minChars
        ORDER BY dateTakenMillis DESC
        """
    )
    suspend fun withLittleText(minChars: Int): List<ShotEntity>

    /**
     * Every row whose text and byte size both match at least one other row. Choosing which copy to
     * keep is the repository's job - which is also why pinned rows stay in the result here rather
     * than being filtered out in SQL: a pinned copy has to be visible to the "which one do we
     * keep" decision before it is dropped from what gets offered.
     */
    @Query(
        """
        SELECT * FROM shots WHERE textHash <> '' AND textHash IN (
            SELECT textHash FROM shots WHERE textHash <> ''
            GROUP BY textHash, sizeBytes HAVING COUNT(*) > 1
        )
        ORDER BY textHash, sizeBytes, dateTakenMillis DESC
        """
    )
    suspend fun duplicateCandidates(): List<ShotEntity>

    @Query("DELETE FROM shots WHERE mediaId IN (:mediaIds)")
    suspend fun deleteByMediaIds(mediaIds: List<Long>)

    @Query("DELETE FROM shots")
    suspend fun clear()
}
