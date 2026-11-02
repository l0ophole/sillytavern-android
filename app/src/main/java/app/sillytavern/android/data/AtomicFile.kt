package app.sillytavern.android.data

import java.io.File

/** Write via temp file + rename so a crash never leaves a half-written file. */
fun File.writeAtomic(bytes: ByteArray) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(this)) {
        tmp.copyTo(this, overwrite = true)
        tmp.delete()
    }
}

fun File.writeAtomic(text: String) = writeAtomic(text.toByteArray(Charsets.UTF_8))
