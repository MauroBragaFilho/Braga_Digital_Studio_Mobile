package com.bragastudio.mobile.onboarding

import android.content.Context

/** Guarda se o guia de boas-vindas já foi concluído (leitura síncrona barata: decide a 1ª tela). */
object OnboardingPrefs {
    private const val PREFS = "bdsm_onboarding"
    private const val KEY_DONE = "done"

    fun isDone(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
    }
}
