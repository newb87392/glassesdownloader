package com.open.glassesdownloader

import android.content.Context
import java.io.File

/** Private storage is the default. Neither mode requests permission to read personal media. */
enum class StorageTarget { PRIVATE, ANDROID_GALLERY;
    companion object {
        private const val PREFS = "settings"
        private const val KEY = "storage_target"

        fun selected(context: Context): StorageTarget {
            val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, PRIVATE.name)
            return values().firstOrNull { it.name == stored } ?: PRIVATE
        }

        fun select(context: Context, value: StorageTarget) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, value.name).apply()
        }
    }
}

object PrivateMediaStorage {
    fun root(context: Context): File = File(context.filesDir, "glasses-media")
    private fun archive(context: Context): File = File(context.filesDir, "media-archive")

    fun file(context: Context, relativeName: String): File =
        PrivateMediaCatalog.file(root(context), archive(context), relativeName)

    fun all(context: Context): List<Pair<String, File>> =
        PrivateMediaCatalog.all(root(context), archive(context))
}
