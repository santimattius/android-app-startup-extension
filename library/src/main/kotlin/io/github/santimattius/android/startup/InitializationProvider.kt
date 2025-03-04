package io.github.santimattius.android.startup

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri


class InitializationProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val context = context
        if (context != null) {
            val applicationContext = context.applicationContext
            if (applicationContext != null) {
                AppInitializer.getInstance(context).discoverAndInitialize(javaClass)
            } else {
                StartupExtensionLogger.w("Deferring initialization because `applicationContext` is null.")
            }
        } else {
            throw StartupExtensionException("Context cannot be null")
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        throw IllegalStateException("Not allowed.")
    }

    override fun getType(uri: Uri): String? {
        throw IllegalStateException("Not allowed.")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw IllegalStateException("Not allowed.")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw IllegalStateException("Not allowed.")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        throw IllegalStateException("Not allowed.")
    }
}