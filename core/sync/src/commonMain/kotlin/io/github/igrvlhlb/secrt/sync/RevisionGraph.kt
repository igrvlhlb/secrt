package io.github.igrvlhlb.secrt.sync

import io.github.igrvlhlb.secrt.model.Revision

/** The history of one record: its revisions linked to their parents. */
class RevisionGraph(revisions: Collection<Revision>) {

    val byId: Map<String, Revision> = revisions.associateBy { it.id }

    /** Revisions nobody builds on yet, oldest first. More than one means concurrent edits. */
    val heads: List<Revision> = run {
        val referenced = revisions.flatMapTo(HashSet()) { it.parents }
        revisions.filter { it.id !in referenced }.sortedWith(ORDER)
    }

    /** [ids] and all of their known ancestors. */
    fun ancestors(ids: Collection<String>): Set<String> {
        val seen = HashSet<String>()
        val queue = ArrayDeque(ids.filter { it in byId })
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (seen.add(id)) byId[id]?.parents?.filterTo(queue) { it in byId && it !in seen }
        }
        return seen
    }

    /**
     * The most recent revision that both [left] (a set of already-merged revisions) and [right]
     * descend from: the "base" of a three-way merge. `null` if their histories don't meet.
     */
    fun lowestCommonAncestor(left: Collection<String>, right: String): Revision? {
        val common = ancestors(left) intersect ancestors(listOf(right))
        if (common.isEmpty()) return null
        val dominated = ancestors(common.flatMap { byId.getValue(it).parents })
        return (common - dominated).map(byId::getValue).maxWithOrNull(ORDER)
    }

    companion object {
        /** Deterministic order shared by every device, so they all merge identically. */
        val ORDER: Comparator<Revision> = compareBy<Revision>({ it.hlc }, { it.id })
    }
}
