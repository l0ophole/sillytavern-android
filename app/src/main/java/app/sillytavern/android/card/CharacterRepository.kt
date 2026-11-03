package app.sillytavern.android.card

import app.sillytavern.android.data.StoragePaths
import app.sillytavern.android.data.writeAtomic
import java.io.File

/** A character on disk: the PNG file name (without extension) is the stable id, as in SillyTavern. */
data class StoredCharacter(val fileName: String, val card: CharacterCard, val file: File)

class CharacterRepository(private val paths: StoragePaths) {
    private fun fileFor(fileName: String) = File(paths.characters, "$fileName.png")

    fun list(): List<StoredCharacter> =
        (paths.characters.listFiles { f -> f.extension.equals("png", true) } ?: emptyArray())
            .mapNotNull { f -> runCatching { StoredCharacter(f.nameWithoutExtension, CardCodec.fromBytes(f.readBytes()), f) }.getOrNull() }
            .sortedBy { it.card.name.lowercase() }

    fun get(fileName: String): StoredCharacter? =
        fileFor(fileName).takeIf { it.exists() }?.let { StoredCharacter(fileName, CardCodec.fromBytes(it.readBytes()), it) }

    /** Saves a card, keeping the existing avatar image unless [avatarPng] is supplied. */
    fun save(fileName: String?, card: CharacterCard, avatarPng: ByteArray? = null): StoredCharacter {
        val name = fileName ?: uniqueName(card.name)
        val target = fileFor(name)
        val avatar = avatarPng ?: target.takeIf { it.exists() }?.readBytes() ?: PngChunks.BLANK
        target.writeAtomic(CardCodec.toPng(card, avatar))
        return StoredCharacter(name, card, target)
    }

    /** Imports a PNG or JSON card. For PNG, the image itself becomes the avatar. */
    fun import(bytes: ByteArray): StoredCharacter {
        val card = CardCodec.fromBytes(bytes)
        return save(null, card, bytes.takeIf { PngChunks.isPng(it) })
    }

    fun setFav(fileName: String, fav: Boolean) {
        val c = get(fileName) ?: return
        save(fileName, c.card.withFav(fav))
    }

    fun exportPng(fileName: String): ByteArray = fileFor(fileName).readBytes()

    fun exportJson(fileName: String): String = CardCodec.toJson(requireNotNull(get(fileName)).card)

    fun delete(fileName: String) {
        fileFor(fileName).delete()
    }

    private fun uniqueName(base: String): String {
        val clean = StoragePaths.sanitizeFileName(base)
        var candidate = clean
        var i = 1
        while (fileFor(candidate).exists()) candidate = "$clean ${i++}"
        return candidate
    }
}
