package com.bragastudio.mobile.network

import android.content.Context
import com.bragastudio.mobile.network.auth.LinkAuthManager
import com.bragastudio.mobile.network.auth.PairedClient
import com.bragastudio.mobile.network.service.LinkServerService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Ponto único de controle do BDSM Link para a UI (Configurações, indicador na Home) e
 * para o ciclo de vida do serviço:
 *  - [enabled]: preferência persistida (padrão LIGADO) que o [LinkServerService] respeita
 *    em cada `onStartCommand` — inclusive no restart `START_STICKY` com intent nulo;
 *  - [serverRunning]: o servidor realmente escutando (não apenas "habilitado");
 *  - [paired], [revoke] e [revokeAll]: gestão dos dispositivos pareados. Revogar derruba
 *    os WebSockets abertos do cliente em até ~1 ciclo de envio (500 ms), pois o token é
 *    revalidado a cada envio.
 */
@Singleton
class LinkServerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: LinkAuthManager,
) {
    companion object {
        private const val PREFS = "bdsm_link_controller"
        private const val KEY_ENABLED = "enabled"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))

    /** Link habilitado pelo usuário (persistido; padrão: true). */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** true quando o servidor está de fato no ar (bind da porta concluído). */
    val serverRunning: StateFlow<Boolean> = LinkServerStatus.state
        .map { it is ServerState.Running }
        .stateIn(scope, SharingStarted.Eagerly, LinkServerStatus.state.value is ServerState.Running)

    /** Dispositivos pareados (tokens ativos). */
    val paired: StateFlow<List<PairedClient>> get() = auth.paired

    /** Liga/desliga o Link: persiste a preferência e inicia/para o [LinkServerService]. */
    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
        if (value) LinkServerService.start(context) else LinkServerService.stop(context)
    }

    /**
     * Inicia o serviço se o Link estiver habilitado. Chamar de um ponto em PRIMEIRO PLANO
     * (ex.: `MainActivity.onStart`): em background o Android 12+ proíbe iniciar o serviço.
     * Retorna false se não iniciou (desligado ou bloqueado pelo sistema).
     */
    fun startIfEnabled(): Boolean = _enabled.value && LinkServerService.start(context)

    /** Remove o pareamento de um cliente e derruba seus WebSockets. */
    fun revoke(clientId: String) = auth.revoke(clientId)

    /** Remove todos os pareamentos. */
    fun revokeAll() = auth.revokeAll()
}
