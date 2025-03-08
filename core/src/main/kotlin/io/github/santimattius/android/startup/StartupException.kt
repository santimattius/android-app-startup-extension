package io.github.santimattius.android.startup

/**
 * An exception that is thrown during the startup process of an extension.
 *
 * This exception is designed to encapsulate any errors or issues encountered while
 * an extension is being initialized or loaded. It provides a way to propagate
 * information about the failure, including a descriptive message and the underlying
 * cause if one is available.
 *
 */
class StartupExtensionException(
    message: String?, cause: Throwable?
) : Throwable(message, cause) {

    constructor(message: String?) : this(message, null)

    constructor(cause: Throwable?) : this(cause?.toString(), cause)

    constructor() : this(null, null)
}

