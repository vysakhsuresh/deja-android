package com.layerbit.deja.data

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.layerbit.deja.data.db.CategoryCount
import com.layerbit.deja.data.db.DejaDatabase
import com.layerbit.deja.data.db.ShotEntity
import com.layerbit.deja.data.index.SearchQuery
import com.layerbit.deja.data.model.Category
import com.layerbit.deja.data.model.CleanupGroup
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow

class ShotRepository(private val context: Context) {

    private val dao = DejaDatabase.get(context).shotDao()

    fun observeRecent(limit: Int = 300): Flow<List<ShotEntity>> = dao.observeRecent(limit)

    fun observeByCategory(category: Category, limit: Int = 300): Flow<List<ShotEntity>> =
        dao.observeByCategory(category.id, limit)

    fun observeCategoryCounts(): Flow<List<CategoryCount>> = dao.observeCategoryCounts()

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun byId(id: Long): ShotEntity? = dao.byId(id)

    /**
     * FTS gives recall, this gives order: a screenshot matching every term the user typed beats
     * one matching a single common word, and only then does recency break the tie.
     */
    suspend fun search(raw: String): List<ShotEntity> {
        val match = SearchQuery.toMatch(raw) ?: return emptyList()
        val tokens = SearchQuery.tokenise(raw)
        val candidates = runCatching { dao.search(match, CANDIDATE_LIMIT) }.getOrDefault(emptyList())

        return candidates
            .sortedWith(
                compareByDescending<ShotEntity> { shot ->
                    val haystack = shot.text.lowercase()
                    tokens.count { haystack.contains(it) }
                }.thenByDescending { it.dateTakenMillis }
            )
            .take(RESULT_LIMIT)
    }

    /**
     * Buckets of screenshots that are safe to offer for deletion. A screenshot is only ever put in
     * the first group that claims it, so the totals the user sees add up.
     */
    suspend fun cleanupGroups(): List<CleanupGroup> {
        val now = System.currentTimeMillis()
        val claimed = mutableSetOf<Long>()

        fun claim(shots: List<ShotEntity>): List<ShotEntity> =
            shots.filter { claimed.add(it.id) }

        val oldCodes = claim(
            dao.olderThanInCategory(Category.CODE.id, now - TimeUnit.DAYS.toMillis(30))
        )
        val oldForwards = claim(
            dao.oldUncategorised(now - TimeUnit.DAYS.toMillis(180))
        )
        val duplicates = claim(redundantDuplicates())
        val unreadable = claim(dao.withLittleText(MIN_USEFUL_TEXT))

        return listOf(
            CleanupGroup(
                id = "old_codes",
                title = "Old one-time codes",
                subtitle = "Captured more than a month ago",
                shots = oldCodes,
                selectedByDefault = true
            ),
            CleanupGroup(
                id = "old_forwards",
                title = "Old forwards & chatter",
                subtitle = "Nothing useful found, older than six months",
                shots = oldForwards,
                selectedByDefault = true
            ),
            CleanupGroup(
                id = "duplicates",
                title = "Exact duplicates",
                subtitle = "Same contents and size, newest copy kept",
                shots = duplicates,
                selectedByDefault = false
            ),
            CleanupGroup(
                id = "unreadable",
                title = "No readable text",
                subtitle = "Nothing Deja could read out of these",
                shots = unreadable,
                selectedByDefault = false
            )
        ).filter { it.shots.isNotEmpty() }
    }

    /** Every copy except the newest of each identical group. */
    private suspend fun redundantDuplicates(): List<ShotEntity> =
        dao.duplicateCandidates()
            .groupBy { it.textHash to it.sizeBytes }
            .values
            .filter { it.size > 1 }
            .flatMap { group -> group.sortedByDescending { it.dateTakenMillis }.drop(1) }

    /**
     * Moves screenshots to the system trash, where they stay recoverable, rather than deleting
     * them outright. The user confirms in a system dialog that Deja cannot bypass.
     */
    fun trashRequest(uris: List<Uri>): PendingIntent =
        MediaStore.createTrashRequest(context.contentResolver, uris, true)

    suspend fun forgetMediaIds(mediaIds: List<Long>) = dao.deleteByMediaIds(mediaIds)

    suspend fun clearIndex() = dao.clear()

    private companion object {
        const val CANDIDATE_LIMIT = 300
        const val RESULT_LIMIT = 60

        /** Below this many characters there is nothing to search for or act on. */
        const val MIN_USEFUL_TEXT = 12
    }
}
