package io.github.igrvlhlb.secrt.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class DataDirectoryTest {

    private fun resolve(os: String, properties: Map<String, String> = emptyMap(), env: Map<String, String> = emptyMap()) =
        dataDirectory(
            property = { (mapOf("os.name" to os, "user.home" to "/home/me") + properties)[it] },
            env = { env[it] },
        )

    @Test
    fun usesTheOsConventions() {
        assertEquals(File("/home/me/Library/Application Support/secrt"), resolve("Mac OS X"))
        assertEquals(File("C:\\Users\\me\\AppData\\Roaming", "secrt"), resolve("Windows 11", env = mapOf("APPDATA" to "C:\\Users\\me\\AppData\\Roaming")))
        assertEquals(File("/home/me/.local/share/secrt"), resolve("Linux"))
        assertEquals(File("/data/secrt"), resolve("Linux", env = mapOf("XDG_DATA_HOME" to "/data")))
    }

    @Test
    fun canBeOverridden() {
        assertEquals(File("/tmp/env"), resolve("Mac OS X", env = mapOf(DATA_DIR_ENV to "/tmp/env")))
        assertEquals(
            File("/tmp/property"),
            resolve("Mac OS X", properties = mapOf(DATA_DIR_PROPERTY to "/tmp/property"), env = mapOf(DATA_DIR_ENV to "/tmp/env")),
        )
    }
}
