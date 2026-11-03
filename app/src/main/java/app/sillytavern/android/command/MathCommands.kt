package app.sillytavern.android.command

import kotlinx.serialization.json.*
import kotlin.math.*

object MathCommands {
    fun numbers(s: String): List<Double> {
        val items = runCatching { (Json.parseToJsonElement(s) as? JsonArray)?.map { (it as? JsonPrimitive)?.content.orEmpty() } }.getOrNull()
            ?: s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return items.map { it.toDoubleOrNull()?.takeIf { d -> d.isFinite() } ?: 0.0 }
    }

    fun fmt(d: Double): String = if (d.isNaN() || d.isInfinite()) "0" else if (d % 1.0 == 0.0 && abs(d) < 1e15) d.toLong().toString() else d.toString()

    private fun op(name: String, vararg aliases: String, f: (List<Double>) -> Double) =
        cmd(name, *aliases, help = "$name numbers...") { c -> fmt(f(numbers(c.unnamed))) }

    fun register() {
        op("add") { it.sum() }
        op("mul") { it.fold(1.0) { a, b -> a * b } }
        op("sub") { n -> if (n.isEmpty()) 0.0 else n.drop(1).fold(n[0]) { a, b -> a - b } }
        op("div") { n -> if (n.size < 2) 0.0 else n.drop(1).fold(n[0]) { a, b -> if (b == 0.0) return@op 0.0 else a / b } }
        op("mod") { n -> if (n.size < 2 || n[1] == 0.0) 0.0 else n[0] % n[1] }
        op("pow") { n -> if (n.size < 2) 0.0 else n[0].pow(n[1]) }
        op("min") { it.minOrNull() ?: 0.0 }
        op("max") { it.maxOrNull() ?: 0.0 }
        op("abs") { abs(it.firstOrNull() ?: 0.0) }
        op("round") { Math.round(it.firstOrNull() ?: 0.0).toDouble() }
        op("sqrt") { n -> n.firstOrNull()?.takeIf { it >= 0 }?.let(::sqrt) ?: 0.0 }
        cmd("rand", "random", help = "rand from= to=", usesPipe = false) { c ->
            val from = c.named["from"]?.toDoubleOrNull() ?: 0.0; val to = c.named["to"]?.toDoubleOrNull() ?: 1.0
            val lo = min(from, to); val hi = max(from, to)
            fmt(lo + c.host.macroContext().random.nextDouble() * (hi - lo))
        }
    }
}
