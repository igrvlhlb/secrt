package io.github.igrvlhlb.secrt.desktop

import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Dependencies may be compiled for a newer Java than ours; the app then only fails when one of
 * their classes is first used (e.g. the Markdown preview). Tests run on the app's runtime JDK, so
 * check every jar on the classpath against it up front.
 */
class RuntimeCompatibilityTest {

    @Test
    fun everyDependencyCanBeLoadedByTheRuntime() {
        val runtimeMajor = 44 + Runtime.version().feature()
        val tooNew = System.getProperty("java.class.path").split(File.pathSeparator)
            .filter { it.endsWith(".jar") }
            .mapNotNull { jar -> highestClassVersion(File(jar))?.takeIf { it > runtimeMajor }?.let { "${File(jar).name} (class version $it)" } }

        assertTrue(tooNew.isEmpty(), "Runtime supports class version $runtimeMajor, but these need newer: $tooNew")
    }

    /** Highest class-file major version in [jar], ignoring multi-release variants for newer JDKs. */
    private fun highestClassVersion(jar: File): Int? = ZipFile(jar).use { zip ->
        zip.entries().asSequence()
            .filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/versions/") }
            .mapNotNull { entry ->
                DataInputStream(zip.getInputStream(entry)).use { input ->
                    if (input.readInt() != 0xCAFEBABE.toInt()) return@use null
                    input.readUnsignedShort() // minor
                    input.readUnsignedShort()
                }
            }
            .maxOrNull()
    }
}
