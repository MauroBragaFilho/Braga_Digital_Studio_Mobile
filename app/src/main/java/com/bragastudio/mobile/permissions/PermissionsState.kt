package com.bragastudio.mobile.permissions

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

private const val PREFS = "bdsm_permissions"
private const val KEY_MIC_OFFER_DISMISSED = "mic_offer_dismissed"

private fun askedKey(permission: AppPermission) = "asked_${permission.name}"

/**
 * Estado observável das permissões + pedido individual (cada uma com seu texto de contexto na UI).
 * Os status são relidos ao voltar para o app (ON_RESUME), então conceder pelas configurações do
 * sistema reflete na hora.
 */
@Stable
class PermissionsState internal constructor(
    private val statuses: SnapshotStateMap<AppPermission, PermissionStatus>,
    private val requester: (AppPermission, () -> Unit) -> Unit,
    private val micDismissed: MutableState<Boolean>,
    private val persistMicDismissed: () -> Unit,
) {
    fun status(permission: AppPermission): PermissionStatus = statuses[permission] ?: PermissionStatus.Denied

    /** Mapa instantâneo (lê o estado observável: recompõe quando algum status muda). */
    fun snapshot(): Map<AppPermission, PermissionStatus> = AppPermission.entries.associateWith { status(it) }

    /** Pede a permissão; [onResult] roda depois que o usuário responde ao diálogo do sistema. */
    fun request(permission: AppPermission, onResult: () -> Unit = {}) = requester(permission, onResult)

    val micOfferDismissed: Boolean get() = micDismissed.value

    fun dismissMicOffer() {
        micDismissed.value = true
        persistMicDismissed()
    }
}

@Composable
fun rememberPermissionsState(): PermissionsState {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val statuses = remember { mutableStateMapOf<AppPermission, PermissionStatus>() }
    val micDismissed = remember { mutableStateOf(prefs.getBoolean(KEY_MIC_OFFER_DISMISSED, false)) }
    val pending = remember { mutableStateOf<Pair<AppPermission, () -> Unit>?>(null) }

    fun refresh() {
        val activity = context.findActivity()
        AppPermission.entries.forEach { permission ->
            statuses[permission] = readStatus(context, activity, prefs.getBoolean(askedKey(permission), false), permission)
        }
    }
    remember {
        refresh()
        true
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val (permission, callback) = pending.value ?: return@rememberLauncherForActivityResult
        pending.value = null
        prefs.edit().putBoolean(askedKey(permission), true).apply()
        refresh()
        callback()
    }

    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }

    return remember {
        PermissionsState(
            statuses = statuses,
            requester = { permission, callback ->
                if (permission == AppPermission.Notifications && !PermissionLogic.notificationsRuntime(Build.VERSION.SDK_INT)) {
                    callback()
                } else {
                    pending.value = permission to callback
                    launcher.launch(arrayOf(permission.manifestName))
                }
            },
            micDismissed = micDismissed,
            persistMicDismissed = { prefs.edit().putBoolean(KEY_MIC_OFFER_DISMISSED, true).apply() },
        )
    }
}

private fun readStatus(context: Context, activity: Activity?, askedBefore: Boolean, permission: AppPermission): PermissionStatus {
    if (permission == AppPermission.Notifications && !PermissionLogic.notificationsRuntime(Build.VERSION.SDK_INT)) {
        return PermissionStatus.Granted
    }
    val granted = ContextCompat.checkSelfPermission(context, permission.manifestName) == PackageManager.PERMISSION_GRANTED
    val rationale = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, permission.manifestName)
    return PermissionLogic.status(granted, askedBefore, rationale)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Abre a tela de detalhes do app nas configurações do sistema (para permissões negadas de vez). */
fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
