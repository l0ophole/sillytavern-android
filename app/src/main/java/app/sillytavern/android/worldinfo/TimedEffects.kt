package app.sillytavern.android.worldinfo

enum class TimedKind { STICKY, COOLDOWN, DELAY }

/** Per-chat sticky/cooldown state (filled in by the timed-effects task). */
class TimedState {
    fun isActive(kind: TimedKind, entry: ScanEntry, chatLength: Int): Boolean = false
}
