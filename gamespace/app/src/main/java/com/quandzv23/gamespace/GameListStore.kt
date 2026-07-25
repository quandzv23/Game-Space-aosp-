package com.quandzv23.gamespace

import android.content.Context

object GameListStore {
    private const val PREFS = "gamespace_prefs"
    private const val KEY_PACKAGES = "game_packages"

    fun getGames(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_PACKAGES, emptySet()) ?: emptySet()
    }

    fun addGame(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = getGames(context).toMutableSet()
        current.add(packageName)
        prefs.edit().putStringSet(KEY_PACKAGES, current).apply()
    }

    fun removeGame(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = getGames(context).toMutableSet()
        current.remove(packageName)
        prefs.edit().putStringSet(KEY_PACKAGES, current).apply()
    }

    fun isGame(context: Context, packageName: String): Boolean =
        getGames(context).contains(packageName)
}
