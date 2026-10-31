package io.github.igrvlhlb.secrt.sync

import io.github.igrvlhlb.secrt.model.Hlc

/** What the folder tree needs to know about one folder record, live or deleted. */
data class FolderNode(
    val id: String,
    val parentId: String?,
    val deleted: Boolean,
    /** Time of the folder's latest revision. */
    val hlc: Hlc,
)

/**
 * Resolves the folder tree so that it is always a valid tree, whatever happened on other devices:
 * - Contents of a deleted (or not yet received) folder move up to its nearest live ancestor.
 * - Concurrent moves that form a cycle (A into B on one device, B into A on another) are broken
 *   by moving the most recently changed folder of the cycle to the root.
 */
class FolderTree(nodes: Collection<FolderNode>) {

    private val nodes = nodes.associateBy { it.id }

    /** Effective parent of every live folder; `null` means the root. */
    val parents: Map<String, String?> = breakCycles(
        this.nodes.values.filterNot { it.deleted }.associateTo(HashMap()) { it.id to nearestLive(it.parentId) },
    )

    /** Folder that something stored in [folderId] should be shown in; `null` means the root. */
    fun locate(folderId: String?): String? = nearestLive(folderId)

    private fun nearestLive(start: String?): String? {
        var current = start
        val seen = HashSet<String>()
        while (current != null) {
            val node = nodes[current] ?: return null
            if (!node.deleted) return current
            if (!seen.add(current)) return null
            current = node.parentId
        }
        return null
    }

    private fun breakCycles(parents: MutableMap<String, String?>): Map<String, String?> {
        val newestFirst = compareBy<String>({ nodes.getValue(it).hlc }, { it }).reversed()
        do {
            val cycle = findCycle(parents)
            if (cycle != null) parents[cycle.sortedWith(newestFirst).first()] = null
        } while (cycle != null)
        return parents
    }

    private fun findCycle(parents: Map<String, String?>): List<String>? {
        val done = HashSet<String>()
        for (start in parents.keys.sorted()) {
            val path = ArrayList<String>()
            val onPath = HashSet<String>()
            var current: String? = start
            while (current != null && current !in done) {
                if (!onPath.add(current)) return path.subList(path.indexOf(current), path.size)
                path += current
                current = parents[current]
            }
            done += path
        }
        return null
    }
}
