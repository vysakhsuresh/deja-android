package com.layerbit.deja.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class CategoryCount(val category: String, val count: Int)

@Dao
interface ShotDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(shot: ShotEntity)

    @Query("SELECT mediaId FROM shots")
    suspend fun indexedMediaIds(): List<Long>

    @Query("SELECT COUNT(*) FROM shots")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM shots ORDER BY dateTakenMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ShotEntity>>

    @Query("SELECT * FROM shots WHERE category = :category ORDER BY dateTakenMillis DESC LIMIT :limit")
    fun observeByCategory(category: String, limit: Int): Flow<List<ShotEntity>>

    @Query("SELECT category, COUNT(*) AS count FROM shots GROUP BY category")
    fun observeCategoryCounts(): Flow<List<CategoryCount>>

    @Query("SELECT * FROM shots WHERE id = :id")
    suspend fun byId(id: Long): ShotEntity?

    /**
     * Room maps the FTS rowid onto [ShotEntity.id], so the join is what carries the match back to
     * the full row. The caller is responsible for turning a typed query into FTS MATCH syntax.
     */
    @Query(
        """
        SELECT shots.* FROM shots
        JOIN shots_fts ON shots.id = shots_fts.rowid
        WHERE shots_fts MATCH :match
        ORDER BY shots.dateTakenMillis DESC
        LIMIT :limit
        """
    )
    suspend fun search(match: String, limit: Int): List<ShotEntity>

    @Query("SELECT * FROM shots WHERE category = :category AND dateTakenMillis < :before ORDER BY dateTakenMillis DESC")
    suspend fun olderThanInCategory(category: String, before: Long): List<ShotEntity>

    @Query("SELECT * FROM shots WHERE category IN ('other', 'chat') AND dateTakenMillis < :before ORDER BY dateTakenMillis DESC")
    suspend fun oldUncategorised(before: Long): List<ShotEntity>

    @Query("SELECT * FROM shots WHERE length(text) < :minChars ORDER BY dateTakenMillis DESC")
    suspend fun withLittleText(minChars: Int): List<ShotEntity>

    /**
     * Every row whose text and byte size both match at least one other row. Choosing which copy to
     * keep is the repository's job.
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
