package com.geochina.app.data

import android.content.Context

class SearchHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)

    fun read(): List<String> =
        preferences.getString(KEY_HISTORY, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun add(name: String): List<String> {
        val updated = (listOf(name) + read().filterNot { it == name }).take(8)
        preferences.edit().putString(KEY_HISTORY, updated.joinToString(SEPARATOR)).apply()
        return updated
    }

    companion object {
        private const val KEY_HISTORY = "history"
        private const val SEPARATOR = "\u001F"
    }
}

