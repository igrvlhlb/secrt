package io.github.igrvlhlb.secrt.sync

import io.github.igrvlhlb.secrt.model.Hlc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FolderTreeTest {

    private fun node(id: String, parent: String?, deleted: Boolean = false, time: Long = 1) =
        FolderNode(id, parent, deleted, Hlc(time, 0, "dev"))

    @Test
    fun contentsOfDeletedFolderMoveUp() {
        val tree = FolderTree(listOf(node("root-a", null), node("mid", "root-a", deleted = true), node("leaf", "mid")))

        assertEquals("root-a", tree.parents["leaf"])
        assertEquals("root-a", tree.locate("mid"))
        assertEquals("leaf", tree.locate("leaf"))
    }

    @Test
    fun unknownFolderResolvesToRoot() {
        val tree = FolderTree(listOf(node("a", "missing")))

        assertNull(tree.parents["a"])
        assertNull(tree.locate("missing"))
    }

    @Test
    fun moveCycleIsBrokenAtTheNewestChange() {
        // Device 1 moved A into B, device 2 moved B into A; B's move happened later.
        val tree = FolderTree(listOf(node("A", "B", time = 1), node("B", "A", time = 2)))

        assertNull(tree.parents["B"])
        assertEquals("B", tree.parents["A"])
    }

    @Test
    fun selfParentIsBroken() {
        assertNull(FolderTree(listOf(node("A", "A"))).parents["A"])
    }
}
