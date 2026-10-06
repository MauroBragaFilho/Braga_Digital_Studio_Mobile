package com.bragastudio.mobile.network.service

import android.app.Activity
import android.app.Application
import android.os.Bundle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sabe se o app tem alguma Activity visível (sem depender de lifecycle-process).
 * Usado para decidir entre o diálogo de pareamento (primeiro plano) e a notificação
 * com ações (segundo plano). Registre uma vez com [attach] no `Application.onCreate`.
 */
@Singleton
class AppVisibility @Inject constructor() : Application.ActivityLifecycleCallbacks {

    private var startedCount = 0
    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    fun attach(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedCount++
        _foreground.value = true
    }

    override fun onActivityStopped(activity: Activity) {
        startedCount = (startedCount - 1).coerceAtLeast(0)
        _foreground.value = startedCount > 0
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
