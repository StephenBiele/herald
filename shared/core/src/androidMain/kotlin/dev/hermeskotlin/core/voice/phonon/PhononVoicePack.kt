package dev.hermeskotlin.core.voice.phonon

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Verifies the exact English q8 checkpoint and Zoey conditioning before native code reads them. */
internal object PhononVoicePack {
    const val VOICE = "NbpkqMVS3CJeq2j8"
    private val expected = mapOf(
        "model.q8.gguf" to "a7d9aad2cd6dc66473f24a08bc6d2939030ef4d751ef82349361fbef3272dbae",
        "config.json" to "b5d0f88353638370d94035b7d73d7d6577c0554d78f8d7ecfd40490aae39ae85",
        "tokenizer.json" to "691a00068fa7b989f2f3e5ae5daa2d1a5bb319f2d5360deafcfb19a8bb6e7936",
        "voices/$VOICE.safetensors" to "97c7f8c253413574406ab80dd23f49c3ebb2517163b00a9bf86b9c4cf43e7e23",
    )

    fun install(context: Context): File {
        val directory = File(context.noBackupFilesDir, "zoey-92741c5b-0ee17205")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create Zoey model directory." }
        for ((name, digest) in expected) {
            val destination = File(directory, name)
            if (matches(destination, digest)) continue
            check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
            val temporary = File(destination.parentFile, destination.name + ".tmp")
            try {
                context.assets.open("phonon/$name").use { source ->
                    temporary.outputStream().use { source.copyTo(it) }
                }
                check(matches(temporary, digest)) { "Zoey asset failed SHA-256 verification: $name" }
                check(temporary.renameTo(destination)) { "Cannot install Zoey asset: $name" }
            } finally {
                temporary.delete()
            }
        }
        return directory
    }

    private fun matches(file: File, expected: String): Boolean {
        if (!file.isFile) return false
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            var count = stream.read(buffer)
            while (count >= 0) {
                hash.update(buffer, 0, count)
                count = stream.read(buffer)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) } == expected
    }
}
