package io.github.igrvlhlb.secrt.sync

/**
 * Line-based three-way merge (diff3), like git does for text files.
 *
 * Lines unchanged on both sides anchor the merge. Between anchors, a region changed on one side
 * only takes that side's version; a region changed identically on both sides is taken once; a
 * region changed differently on both sides is a conflict.
 */
object TextMerge {

    /** Above this many line comparisons we give up and report a conflict instead. */
    private const val MAX_CELLS = 4_000_000L

    /** @return the merged text, or `null` if the two edits overlap. */
    fun merge(base: String, ours: String, theirs: String): String? {
        if (ours == theirs) return ours
        if (ours == base) return theirs
        if (theirs == base) return ours

        val b = base.split('\n')
        val o = ours.split('\n')
        val t = theirs.split('\n')
        val toOurs = matchLines(b, o) ?: return null
        val toTheirs = matchLines(b, t) ?: return null

        val out = ArrayList<String>()
        var i = 0
        var j = 0
        var k = 0
        while (true) {
            var anchor = i
            while (anchor < b.size && (toOurs[anchor] < 0 || toTheirs[anchor] < 0)) anchor++
            val ourEnd = if (anchor < b.size) toOurs[anchor] else o.size
            val theirEnd = if (anchor < b.size) toTheirs[anchor] else t.size

            val baseChunk = b.subList(i, anchor)
            val ourChunk = o.subList(j, ourEnd)
            val theirChunk = t.subList(k, theirEnd)
            when {
                ourChunk == baseChunk -> out += theirChunk
                theirChunk == baseChunk || ourChunk == theirChunk -> out += ourChunk
                else -> return null
            }

            if (anchor >= b.size) break
            out += b[anchor]
            i = anchor + 1
            j = ourEnd + 1
            k = theirEnd + 1
        }
        return out.joinToString("\n")
    }

    /**
     * For each line of [a], the index of the matching line in [b] (or -1), following a longest
     * common subsequence. Common prefix and suffix are matched first so the quadratic part only
     * covers the edited middle.
     */
    private fun matchLines(a: List<String>, b: List<String>): IntArray? {
        val match = IntArray(a.size) { -1 }
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) {
            match[prefix] = prefix
            prefix++
        }
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) {
            match[a.size - 1 - suffix] = b.size - 1 - suffix
            suffix++
        }

        val n = a.size - prefix - suffix
        val m = b.size - prefix - suffix
        if (n == 0 || m == 0) return match
        if (n.toLong() * m > MAX_CELLS) return null

        val width = m + 1
        val lcs = IntArray((n + 1) * width)
        for (x in n - 1 downTo 0) {
            for (y in m - 1 downTo 0) {
                lcs[x * width + y] = if (a[prefix + x] == b[prefix + y]) {
                    lcs[(x + 1) * width + y + 1] + 1
                } else {
                    maxOf(lcs[(x + 1) * width + y], lcs[x * width + y + 1])
                }
            }
        }
        var x = 0
        var y = 0
        while (x < n && y < m) {
            when {
                a[prefix + x] == b[prefix + y] -> {
                    match[prefix + x] = prefix + y
                    x++
                    y++
                }
                lcs[(x + 1) * width + y] >= lcs[x * width + y + 1] -> x++
                else -> y++
            }
        }
        return match
    }
}
