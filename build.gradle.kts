plugins {
    // Loads the build-logic classpath (Kotlin, AGP, SQLDelight plugins) once, in the root
    // classloader, so every subproject shares the same plugin instances.
    id("secrt.root")
}
