package app.sillytavern.android.prompt

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType

fun interface TokenCounter {
    fun count(text: String): Int
}

/** Fast estimate (~3.6 chars/token) used for non-OpenAI models until model-specific tokenizers land. */
object ApproxTokenCounter : TokenCounter {
    override fun count(text: String) = if (text.isEmpty()) 0 else Math.ceil(text.length / 3.6).toInt()
}

/** Exact counts for OpenAI-family models via jtokkit (o200k_base). */
class TiktokenCounter : TokenCounter {
    private val enc by lazy { Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE) }
    override fun count(text: String) = if (text.isEmpty()) 0 else enc.countTokensOrdinary(text)
}
