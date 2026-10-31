package io.github.firebirks.booruvault.util

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Moves this file onto [target] in one step, replacing [target] if it exists. False if it could
 * not. [File.renameTo] does the same on Android but leaves an existing target in place on
 * Windows, where the unit tests may run.
 */
fun File.moveOver(target: File): Boolean = try {
    Files.move(toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    true
} catch (_: IOException) {
    false
}
