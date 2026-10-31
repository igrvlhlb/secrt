package io.github.igrvlhlb.secrt.sync

import io.github.igrvlhlb.secrt.model.ConflictKind
import io.github.igrvlhlb.secrt.model.Revision

/** Result of merging two concurrent versions of a payload. */
data class Merged<P>(val value: P, val clean: Boolean = true)

/** Three-way merge of one payload type. [newer] wins when both sides changed the same field. */
fun interface PayloadMerger<P> {
    fun merge(base: P?, older: P, newer: P): Merged<P>
}

/** The current state of a record, derived from all of its revisions. */
data class Resolution<P : Any>(
    /** Current value, `null` if the record is deleted. */
    val value: P?,
    /** [value], or for a deleted record the last value it had before deletion. */
    val lastKnownValue: P?,
    val heads: List<Revision>,
    val conflict: ConflictKind?,
) {
    val deleted: Boolean get() = value == null

    /** The most recent head; its timestamp is the record's "last modified". */
    val latest: Revision get() = heads.last()
}

/**
 * Turns a record's revisions into its current state.
 *
 * Merged results are computed, never stored: two devices holding the same revisions always derive
 * the same state, so they converge without exchanging extra "merge" revisions. Only a user edit
 * collapses the heads, by creating a revision whose parents are all current heads.
 */
object RecordResolver {

    fun <P : Any> resolve(
        revisions: Collection<Revision>,
        decode: (String) -> P,
        merger: PayloadMerger<P>,
    ): Resolution<P> {
        require(revisions.isNotEmpty()) { "A record needs at least one revision" }
        val graph = RevisionGraph(revisions)
        val heads = graph.heads
        val live = heads.filterNot { it.deleted }

        if (live.isEmpty()) {
            val lastLive = revisions.filter { !it.deleted && it.payload != null }.maxWithOrNull(RevisionGraph.ORDER)
            return Resolution(null, lastLive?.payload?.let(decode), heads, conflict = null)
        }

        var conflict = if (live.size < heads.size) ConflictKind.EDITED_AFTER_DELETE else null
        var value = decode(live.first().payload!!)
        val merged = mutableListOf(live.first().id)
        for (head in live.drop(1)) {
            val base = graph.lowestCommonAncestor(merged, head.id)?.takeUnless { it.deleted }?.payload?.let(decode)
            val result = merger.merge(base, value, decode(head.payload!!))
            value = result.value
            if (!result.clean) conflict = ConflictKind.CONCURRENT_EDIT
            merged += head.id
        }
        return Resolution(value, value, heads, conflict)
    }
}
