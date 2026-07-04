package io.github.santimattius.android.startup

/**
 * Scheduling priority for a [io.github.santimattius.android.startup.initializer.StartupAsyncInitializer].
 *
 * Priorities are ordered from most-eager to least-eager by declaration order, so
 * [Enum.ordinal] doubles as an eagerness rank where the **minimum ordinal is the most eager**:
 *
 * | Priority   | Ordinal | Meaning                                                        |
 * |------------|---------|----------------------------------------------------------------|
 * | [CRITICAL] | 0       | Most eager. In v1 behaves **identically** to [NORMAL].         |
 * | [NORMAL]   | 1       | Default. Launched immediately on the eager critical path.      |
 * | [DEFERRED] | 2       | Launched only **after the first frame** (or a timeout fallback).|
 *
 * ## v1 semantics (important)
 *
 * Only [DEFERRED] is special in v1: it is the one value that changes scheduling behavior.
 * [CRITICAL] is defined for forward compatibility (a future strict "critical-before-normal"
 * scheduler) but currently partitions as **eager**, exactly like [NORMAL] — it does not bypass
 * the concurrency cap and is not ordered ahead of [NORMAL]. The ordinal distinction exists so the
 * priority-inversion clamp can pick the "most-eager transitive dependent" cheaply via `minOf(ordinal)`.
 *
 * @see io.github.santimattius.android.startup.initializer.StartupAsyncInitializer.priority
 */
enum class StartupPriority {
    /** Most-eager priority. Forward-compat only — behaves identically to [NORMAL] in v1. */
    CRITICAL,

    /** Default priority. The initializer launches immediately on the eager critical path. */
    NORMAL,

    /** The initializer is launched only after the first frame is drawn (or the deferred timeout elapses). */
    DEFERRED,
}
