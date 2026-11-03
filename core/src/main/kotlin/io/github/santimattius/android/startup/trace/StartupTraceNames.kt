package io.github.santimattius.android.startup.trace

/**
 * Builds trace section names following the `Startup::<phase>::<FQN>` convention so the wildcard
 * `Startup::%` always matches. Names are capped at [MAX_LENGTH] chars (the `android.os.Trace`
 * limit); when over budget only the FQN is shortened, from the left, keeping its trailing segment.
 */
internal object StartupTraceNames {

    const val MAX_LENGTH = 127
    private const val ROOT = "Startup::"
    private const val ELLIPSIS = ".."

    /**
     * @param phase section phase (e.g. `create`), or `null` for the initializer node `Startup::<FQN>`.
     * @param fqn fully-qualified class name, or `null` for phase-only sections (e.g. `Startup::discover`).
     */
    fun of(phase: String?, fqn: String?): String {
        val prefix = if (phase == null) ROOT else "$ROOT$phase${if (fqn == null) "" else "::"}"
        if (fqn == null) return prefix.take(MAX_LENGTH)
        if (prefix.length + fqn.length <= MAX_LENGTH) return prefix + fqn
        val tailBudget = (MAX_LENGTH - prefix.length - ELLIPSIS.length).coerceAtLeast(0)
        return (prefix + ELLIPSIS + fqn.takeLast(tailBudget)).take(MAX_LENGTH)
    }
}
