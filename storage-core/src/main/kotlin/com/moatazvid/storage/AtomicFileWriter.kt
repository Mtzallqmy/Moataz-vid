package com.moatazvid.storage

import java.io.OutputStream
import java.nio.file.*
import java.nio.file.StandardCopyOption.*

class AtomicFileWriter {
    fun write(target: Path, block: (OutputStream) -> Unit) {
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING).use { stream ->
                block(stream)
                stream.flush()
            }
            try {
                Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

