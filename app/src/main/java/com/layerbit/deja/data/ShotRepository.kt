package com.layerbit.deja.data

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.layerbit.deja.data.db.AppCount
import com.layerbit.deja.data.db.CategoryCount
import com.layerbit.deja.data.db.DejaDatabase
import com.layerbit.deja.data.db.LibraryStats
import com.layerbit.deja.data.db.ShotEntity
import com.layerbit.deja.data.index.EngineVersion
import com.layerbit.deja.data.index.PerceptualHash
import com.layerbit.deja.data.index.Ranker
import com.layerbit.deja.data.index.SearchPlan
import com.layerbit.deja.data.index.SearchQuery
import com.layerbit.deja.data.model.Category
import com.layerbit.deja.data.model.CleanupGroup
import com.layerbit.deja.R
import com.layerbit.deja.ambient.ShotNotifier
import com.layerbit.deja.widget.Widgets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow

class ShotRepository(private val context: Context) {

    private val dao = DejaDatabase.get(context).shotDao()

    fun observeRecent(limit: Int = 400): Flow<List<ShotEntity>> = dao.observeRecent(limit)

    fun observeByCategory(category: Category, limit: Int = 400): Flow<List<ShotEntity>> =
        dao.observeByCategory(category.id, limit)

    fun observeByApp(app: String, limit: Int = 400): Flow<List<ShotEntity>> =
        dao.observeByApp(app, limit)

    fun observeCategoryCounts(): Flow<List<CategoryCount>> = dao.observeCategoryCounts()

    fun observeAppCounts(limit: Int = 12): Flow<List<AppCount>> = dao.observeAppCounts(limit)

    fun observeStats(): Flow<LibraryStats> = dao.observeStats()

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun count(): Int = dao.count()

    suspend fun byId(id: Long): ShotEntity? = dao.byId(id)

    fun observePinned(limit: Int = 200): Flow<List<ShotEntity>> = dao.observePinned(limit)

    fun observePinnedCount(): Flow<Int> = dao.observePinnedCount()

    suspend fun setPinned(id: Long, pinned: Boolean) {
        dao.setPinned(id, pinned)
        Widgets.refresh(context)
    }

    suspend fun setPinned(ids: Collection<Long>, pinned: Boolean) {
        if (ids.isEmpty()) return
        dao.setPinnedAll(ids.toList(), pinned)
        Widgets.refresh(context)
    }

    /**
     * How much work the engine still owes the library, in rows. Non-zero after an update that
     * knows more than the one that wrote the index.
     */
    suspend fun engineBacklog(): Int =
        dao.staleReadCount(EngineVersion.READ, EngineVersion.MAX_READ_ATTEMPTS) +
            dao.staleDeriveCount(EngineVersion.DERIVE, EngineVersion.READ)

    suspend fun search(raw: String): List<ShotEntity> = search(SearchQuery.plan(raw))

    /**
     * Precision first, recall second.
     *
     * The strict pass requires every word typed. That is the right answer whenever it finds
     * anything at all - "swiggy receipt" means the screenshot that is both, not the union of two
     * piles. Only when it comes back thin is the net widened to anything-matches, with the known
     * spelling variants thrown in, and the strict hits stay ahead of the loose ones because
     * [Ranker] rewards completeness far more than it rewards a single term.
     *
     * A query that is nothing but a date skips matching entirely: "last week" has no words to
     * find and does not need any.
     */
    suspend fun search(plan: SearchPlan): List<ShotEntity> {
        if (plan.isEmpty) return emptyList()

        val from = plan.window?.from ?: 0L
        val to = plan.window?.to ?: Long.MAX_VALUE

        if (plan.dateOnly) {
            val inWindow = runCatching { dao.inRange(from, to, RESULT_LIMIT) }
                .getOrDefault(emptyList())
            return Ranker.excludeRejects(inWindow, plan)
        }

        val strict = plan.strictMatch()
            ?.let { match -> runCatching { dao.search(match, from, to, CANDIDATE_LIMIT) }
                .getOrDefault(emptyList()) }
            .orEmpty()

        val candidates = if (strict.size >= ENOUGH) {
            strict
        } else {
            val loose = plan.looseMatch()
                ?.let { match -> runCatching { dao.search(match, from, to, CANDIDATE_LIMIT) }
                    .getOrDefault(emptyList()) }
                .orEmpty()
            // Distinct by id, strict first, so a row found both ways is kept once.
            (strict + loose).distinctBy { it.id }
        }

        return Ranker
            .rank(Ranker.excludeRejects(candidates, plan), plan, System.currentTimeMillis())
            .take(RESULT_LIMIT)
    }

    /**
     * Buckets of screenshots that are safe to offer for deletion. A screenshot is only ever put in
     * the first group that claims it, so the totals the user sees add up.
     */
    suspend fun cleanupGroups(): List<CleanupGroup> {
        val now = System.currentTimeMillis()
        val claimed = mutableSetOf<Long>()

        fun claim(shots: List<ShotEntity>): List<ShotEntity> = shots.filter { claimed.add(it.id) }

        val oldCodes = claim(
            dao.olderThanInCategory(Category.CODE.id, now - TimeUnit.DAYS.toMillis(30))
        )
        val oldChatter = claim(
            dao.olderThanInCategories(
                listOf(Category.OTHER.id, Category.CHAT.id, Category.SOCIAL.id, Category.MEDIA.id),
                now - TimeUnit.DAYS.toMillis(180)
            )
        )
        val duplicates = claim(redundantDuplicates())
        val nearDuplicates = claim(nearIdentical())
        val unreadable = claim(dao.withLittleText(MIN_USEFUL_TEXT))
        val ancient = claim(dao.olderThan(now - TimeUnit.DAYS.toMillis(365)))
        val large = claim(dao.largerThan(LARGE_BYTES))

        return listOf(
            CleanupGroup(
                id = "old_codes",
                title = context.getString(R.string.cleanup_group_old_codes_title),
                subtitle = context.getString(R.string.cleanup_group_old_codes_subtitle),
                shots = oldCodes,
                selectedByDefault = true
            ),
            CleanupGroup(
                id = "old_chatter",
                title = context.getString(R.string.cleanup_group_old_chatter_title),
                subtitle = context.getString(R.string.cleanup_group_old_chatter_subtitle),
                shots = oldChatter,
                selectedByDefault = true
            ),
            CleanupGroup(
                id = "duplicates",
                title = context.getString(R.string.cleanup_group_duplicates_title),
                subtitle = context.getString(R.string.cleanup_group_duplicates_subtitle),
                shots = duplicates,
                selectedByDefault = false
            ),
            CleanupGroup(
                id = "near_duplicates",
                title = context.getString(R.string.cleanup_group_near_duplicates_title),
                subtitle = context.getString(R.string.cleanup_group_near_duplicates_subtitle),
                shots = nearDuplicates,
                selectedByDefault = false
            ),
            CleanupGroup(
                id = "unreadable",
                title = context.getString(R.string.cleanup_group_unreadable_title),
                subtitle = context.getString(R.string.cleanup_group_unreadable_subtitle),
                shots = unreadable,
                selectedByDefault = false
            ),
            CleanupGroup(
                id = "ancient",
                title = context.getString(R.string.cleanup_group_ancient_title),
                subtitle = context.getString(R.string.cleanup_group_ancient_subtitle),
                shots = ancient,
                selectedByDefault = false
            ),
            CleanupGroup(
                id = "large",
                title = context.getString(R.string.cleanup_group_large_title),
                subtitle = context.getString(R.string.cleanup_group_large_subtitle),
                shots = large,
                selectedByDefault = false
            )
        ).filter { it.shots.isNotEmpty() }
    }

    /**
     * Every copy except the one worth keeping out of each identical group.
     *
     * A pinned copy is what gets kept when there is one, and no pinned copy is ever offered for
     * deletion even if a newer twin exists - otherwise pinning a screenshot would silently fail
     * to protect it the moment someone screenshotted the same thing twice.
     */
    private suspend fun redundantDuplicates(): List<ShotEntity> =
        dao.duplicateCandidates()
            .groupBy { it.textHash to it.sizeBytes }
            .values
            .filter { it.size > 1 }
            .flatMap { group ->
                group
                    .sortedWith(
                        compareByDescending<ShotEntity> { it.pinned }
                            .thenByDescending { it.dateTakenMillis }
                    )
                    .drop(1)
                    .filterNot { it.pinned }
            }

    /**
     * Screenshots that look the same but are not the same file.
     *
     * Exact matching catches a file copied verbatim and nothing else. What people actually
     * accumulate is the same screen captured twice seconds apart, or one image re-saved through
     * a share sheet - visually identical, different in every byte, and invisible to a hash of
     * the text.
     *
     * Two things have to agree before anything is offered up. The images have to look alike,
     * which a perceptual hash answers, and they have to say roughly the same thing, which a
     * token overlap answers. The second test is not belt and braces: pages of dark text on white
     * are the bulk of a screenshot library and they can land close together in dHash space
     * without being remotely related. Requiring both means a false positive needs two unlikely
     * things at once.
     *
     * The copy kept is pinned if any is, then the largest, then the newest - largest because
     * between two captures of one screen the bigger file is usually the less compressed.
     */
    private suspend fun nearIdentical(): List<ShotEntity> {
        val rows = dao.hashed(NEAR_DUP_SCAN)
        if (rows.size < 2) return emptyList()

        val tokens = arrayOfNulls<Set<String>>(rows.size)
        fun tokensAt(index: Int): Set<String> =
            tokens[index] ?: tokenSet(rows[index].text).also { tokens[index] = it }

        val taken = BooleanArray(rows.size)
        val drop = mutableListOf<ShotEntity>()

        for (i in rows.indices) {
            if (taken[i]) continue
            val group = mutableListOf(rows[i])
            for (j in i + 1 until rows.size) {
                if (taken[j]) continue
                if (PerceptualHash.distance(rows[i].phash, rows[j].phash) > NEAR_DUP_BITS) continue
                if (overlap(tokensAt(i), tokensAt(j)) < NEAR_DUP_OVERLAP) continue
                group += rows[j]
                taken[j] = true
            }
            taken[i] = true
            if (group.size < 2) continue

            drop += group
                .sortedWith(
                    compareByDescending<ShotEntity> { it.pinned }
                        .thenByDescending { it.sizeBytes }
                        .thenByDescending { it.dateTakenMillis }
                )
                .drop(1)
                .filterNot { it.pinned }
        }
        return drop
    }

    private fun tokenSet(text: String): Set<String> =
        text.lowercase()
            // \p{M} so Devanagari words survive the split - see TextQuality.
            .split(Regex("[^\\p{L}\\p{N}\\p{M}]+"))
            .asSequence()
            .filter { it.length >= 3 }
            .take(TOKENS_PER_SHOT)
            .toSet()

    /** Jaccard. Two empty texts overlap perfectly, which is correct and also harmless here. */
    private fun overlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val union = a.size + b.size - a.count { it in b }
        if (union <= 0) return 0.0
        return a.count { it in b }.toDouble() / union
    }

    /**
     * Moves screenshots to the system trash, where they stay recoverable, rather than deleting
     * them outright. The user confirms in a system dialog that Deja cannot bypass.
     */
    fun trashRequest(uris: List<Uri>): PendingIntent =
        MediaStore.createTrashRequest(context.contentResolver, uris, true)

    /**
     * Deletes screenshots outright - no trash, no recovery. Same system-confirmed flow as
     * [trashRequest] (the user still has to approve it in a dialog Deja cannot bypass), just
     * without the safety net. `createDeleteRequest` was added in the same API level this app
     * already requires, so no version check is needed.
     */
    fun deleteForeverRequest(uris: List<Uri>): PendingIntent =
        MediaStore.createDeleteRequest(context.contentResolver, uris)

    suspend fun forgetMediaIds(mediaIds: List<Long>) {
        dao.deleteByMediaIds(mediaIds)
        Widgets.refresh(context)
    }

    /** Everything goes, including what a widget or a notification is still showing. */
    suspend fun clearIndex() {
        dao.clear()
        ShotNotifier.cancelAll(context)
        Widgets.refresh(context)
    }

    suspend fun everything(): List<ShotEntity> = dao.all()

    private companion object {
        const val CANDIDATE_LIMIT = 400
        const val RESULT_LIMIT = 80

        /** Below this, the strict pass is treated as too thin to be the whole answer. */
        const val ENOUGH = 8

        /** Out of 64 bits. Six is tight enough that the images are the same screen. */
        const val NEAR_DUP_BITS = 6
        const val NEAR_DUP_OVERLAP = 0.6
        const val NEAR_DUP_SCAN = 4000
        const val TOKENS_PER_SHOT = 200

        /** Below this many characters there is nothing to search for or act on. */
        const val MIN_USEFUL_TEXT = 12

        const val LARGE_BYTES = 2L * 1024 * 1024
    }
}
