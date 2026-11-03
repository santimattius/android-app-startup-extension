package com.santimattius.android.sample

import android.content.Context
import android.util.Log
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer

/**
 * Deliberately NOT declared in AndroidManifest.xml: it is only reached through
 * [SyncTestInitializer.dependencies]. AGP keeps manifest-declared classes on its own, so this
 * class exists to prove that core/consumer-rules.pro keeps (and does not rename) initializers
 * that are discovered by class literal and instantiated reflectively.
 * See .github/scripts/check-r8-initializers.sh.
 */
class DependencyOnlyInitializer : StartupSyncInitializer<Unit> {

    override fun create(context: Context) {
        Log.d("DependencyOnlyInit", "created")
    }
}
