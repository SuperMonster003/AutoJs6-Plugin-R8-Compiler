package io.github.supermonster003.autojs6.plugin.r8compiler

import org.autojs.plugin.r8compiler.api.R8Sha256
import java.io.File
import java.security.MessageDigest

internal object R8Hashes {
    fun sha256(file: File): R8Sha256 {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return R8Sha256.fromBytes(digest.digest())
    }
}
