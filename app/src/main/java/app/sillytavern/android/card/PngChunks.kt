package app.sillytavern.android.card

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/** Minimal PNG chunk reader/writer, enough to read and embed tEXt metadata (character cards). */
object PngChunks {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    class Chunk(val type: String, val data: ByteArray)

    fun isPng(bytes: ByteArray) =
        bytes.size >= 8 && SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }

    fun read(bytes: ByteArray): List<Chunk> {
        require(isPng(bytes)) { "Not a PNG file" }
        val buf = ByteBuffer.wrap(bytes).also { it.position(8) }
        val chunks = mutableListOf<Chunk>()
        while (buf.remaining() >= 12) {
            val len = buf.int
            require(len >= 0 && len <= buf.remaining() - 8) { "Corrupt PNG chunk length" }
            val typeBytes = ByteArray(4).also { buf.get(it) }
            val data = ByteArray(len).also { buf.get(it) }
            buf.int // CRC, not verified on read
            val type = String(typeBytes, Charsets.ISO_8859_1)
            chunks += Chunk(type, data)
            if (type == "IEND") break
        }
        return chunks
    }

    /** Returns tEXt chunks as keyword -> text. */
    fun textChunks(bytes: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (c in read(bytes)) {
            if (c.type != "tEXt") continue
            val sep = c.data.indexOf(0.toByte())
            if (sep <= 0) continue
            out.putIfAbsent(
                String(c.data, 0, sep, Charsets.ISO_8859_1),
                String(c.data, sep + 1, c.data.size - sep - 1, Charsets.ISO_8859_1),
            )
        }
        return out
    }

    /** Rewrites the PNG, dropping existing tEXt chunks whose keyword is in [replace.keys] and adding new ones before IEND. */
    fun withText(bytes: ByteArray, replace: Map<String, String>): ByteArray {
        val chunks = read(bytes).filterNot { c ->
            c.type == "tEXt" && c.data.indexOf(0.toByte()).let { i ->
                i > 0 && String(c.data, 0, i, Charsets.ISO_8859_1) in replace.keys
            }
        }
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        for (c in chunks) {
            if (c.type == "IEND") {
                replace.forEach { (k, v) ->
                    write(out, "tEXt", k.toByteArray(Charsets.ISO_8859_1) + 0 + v.toByteArray(Charsets.ISO_8859_1))
                }
            }
            write(out, c.type, c.data)
        }
        return out.toByteArray()
    }

    private fun write(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }

    /** A valid 1x1 transparent PNG used as the default avatar for characters without an image. */
    val BLANK: ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
    )
}
