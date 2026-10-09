package com.bragastudio.mobile.featurehome

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bragastudio.mobile.common.components.BdsmLargeTitleScaffold
import com.bragastudio.mobile.common.components.LoadingState
import com.bragastudio.mobile.common.components.ModuleCard
import com.bragastudio.mobile.common.components.accent
import com.bragastudio.mobile.common.components.bdsmClickable
import com.bragastudio.mobile.common.module.BdsmModule
import com.bragastudio.mobile.common.module.LocalModuleHost
import com.bragastudio.mobile.common.module.ModuleHost
import com.bragastudio.mobile.common.module.ModulePlacement
import com.bragastudio.mobile.common.ui.theme.BdsmTheme
import com.bragastudio.mobile.common.ui.theme.BdsmWidthClass
import com.bragastudio.mobile.common.ui.theme.bdsmWidthClass
import com.bragastudio.mobile.corecapture.status.CameraStatus
import java.util.Calendar
import kotlinx.coroutines.delay

/** Escala tipográfica própria do cartão do Monitor (os demais ecrãs não mudam). */
private object HomeType {
    val heroTitle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val heroBody = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val heroMetric = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum")
    val button = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
}

private val LensSize = 72.dp
private val HeroMinHeight = 128.dp

/** Largura máxima do conteúdo da Home em telas largas (Monitor à esquerda, grade à direita). */
private val WideContentWidth = 960.dp

/**
 * Home v5: mesma estrutura de Ajustes (One UI). Cabeçalho grande expansível com o nome do app
 * ("Braga Digital Studio") que recolhe ao rolar; logo abaixo a saudação "Bom dia, <nome>" (nome de exibição
 * escolhido em Ajustes ou nome do aparelho) e, depois dela, TODOS os cards: Monitor (ação principal,
 * primeiro, ao alcance do polegar) e a grade 2x2 de módulos gerada pelo registro. Em tela larga o
 * Monitor fica à esquerda e a grade à direita. Sem faixa de métricas do aparelho. Nada técnico aqui:
 * sem IP, porta, bitrate ou codec (ficam em NDI > Detalhes e em Ajustes).
 */
@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val host = LocalModuleHost.current
    val primary = remember(host) { host.registry.at(ModulePlacement.HOME_PRIMARY).firstOrNull() }
    val cards = remember(host) { host.registry.at(ModulePlacement.HOME_CARD) }
    val wide = bdsmWidthClass() != BdsmWidthClass.Compact

    // Hora lida de novo ao voltar para a Home (a saudação acompanha o período do dia).
    var hour by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }

    // Ao voltar para a Home (permissão concedida, câmera USB conectada) a câmera é sondada de novo.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        viewModel.refreshCamera()
    }

    val brand = stringResource(R.string.home_brand_name)
    val salutation = stringResource(
        when (HomeStatus.dayPeriod(hour)) {
            DayPeriod.MORNING -> R.string.home_greeting_morning
            DayPeriod.AFTERNOON -> R.string.home_greeting_afternoon
            DayPeriod.NIGHT -> R.string.home_greeting_night
        },
    )

    BdsmLargeTitleScaffold(
        title = brand,
        expandedTitleContent = { BrandLockup(description = brand) },
        collapsedTitleContent = { BrandLockup(description = brand, compact = true) },
        onNavigateUp = {},
        showBack = false,
        maxContentWidth = if (wide) WideContentWidth else BdsmTheme.spacing.contentMaxWidth,
    ) {
        if (!state.loaded) {
            item(key = "loading", contentType = "loading") {
                LoadingState(modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp), message = stringResource(R.string.home_loading))
            }
        } else {
            item(key = "greeting", contentType = "greeting") {
                Text(
                    text = HomeStatus.greeting(salutation, state.greetingName),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(vertical = BdsmTheme.spacing.sm),
                )
            }
            if (wide) {
                item(key = "cards", contentType = "cards-wide") {
                    Row(horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md)) {
                        MonitorHero(state = state, primary = primary, host = host, modifier = Modifier.weight(1f))
                        ModuleGrid(cards = cards, host = host, modifier = Modifier.weight(1f))
                    }
                }
            } else {
                item(key = "monitor", contentType = "monitor") {
                    MonitorHero(state = state, primary = primary, host = host)
                }
                item(key = "grid", contentType = "grid") {
                    ModuleGrid(cards = cards, host = host)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Grade 2x2 gerada pelo registro de módulos (cores por módulo, fallback por categoria)
// ---------------------------------------------------------------------------

/** Cor do ícone do cartão: NDI ciano, Gravações violeta, LUTs laranja, Ajustes azul; outros pela categoria. */
@Composable
private fun BdsmModule.homeAccent(): Color = when (id) {
    "ndi" -> BdsmTheme.colors.accentNetwork
    "recordings" -> BdsmTheme.colors.accentMonitor
    "luts" -> BdsmTheme.colors.accentLibrary
    "settings" -> BdsmTheme.colors.accentVideo
    else -> category.accent()
}

@Composable
private fun ModuleGrid(cards: List<BdsmModule>, host: ModuleHost, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md)) {
        cards.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md)) {
                pair.forEach { module ->
                    ModuleCard(
                        title = stringResource(module.titleRes),
                        icon = module.icon,
                        status = module.statusLabel() ?: module.subtitleRes?.let { stringResource(it) },
                        accent = module.homeAccent(),
                        onClick = { module.route?.let(host::navigate) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) Column(modifier = Modifier.weight(1f)) {}
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Cartão do Monitor (estado da câmera + botão "Abrir monitor")
// ---------------------------------------------------------------------------

/** Cartão hero genérico: texto + botão pílula à esquerda, [trailing] (lente/ícone) à direita. */
@Composable
private fun HeroCard(
    title: String,
    buttonText: String,
    onClick: () -> Unit,
    icon: ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
    status: @Composable () -> Unit,
) {
    val spacing = BdsmTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(BdsmTheme.shapes.container)
            .background(BdsmTheme.colors.card)
            .bdsmClickable(onClick = onClick, role = Role.Button)
            .heightIn(min = HeroMinHeight)
            .padding(spacing.xl)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
                Text(
                    text = title,
                    style = HomeType.heroTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column { status() }
            PillButton(text = buttonText)
        }
        trailing()
    }
}

/** Botão pílula visual (o cartão inteiro é o alvo de toque; o texto fica no mesmo nó de acessibilidade). */
@Composable
private fun PillButton(text: String) {
    Row(
        modifier = Modifier
            .heightIn(min = 56.dp)
            .clip(BdsmTheme.shapes.pill)
            .background(MaterialTheme.colorScheme.primary)
            // Margem de 24 dp (guia One UI) em vez de 16 dp.
            .padding(horizontal = BdsmTheme.spacing.screenMargin),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm),
    ) {
        Text(
            text = text,
            style = HomeType.button,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 2,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Lente (WebP de 512 px, sem sombra nem blur) em ladrilho arredondado. */
@Composable
private fun LensImage() {
    Image(
        painter = painterResource(R.drawable.camera_lens),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(LensSize)
            .clip(BdsmTheme.shapes.chip)
            .border(1.dp, BdsmTheme.colors.cardBorder, BdsmTheme.shapes.chip),
    )
}

@Composable
private fun MonitorHero(state: HomeUiState, primary: BdsmModule?, host: ModuleHost, modifier: Modifier = Modifier) {
    val camera = state.camera
    val recording = state.recordingStartedAt != null
    val noPermission = !recording && !state.cameraActive && camera is CameraStatus.NoPermission
    HeroCard(
        title = stringResource(R.string.home_monitor_title),
        buttonText = stringResource(if (noPermission) R.string.home_grant_permission else R.string.home_open_monitor),
        onClick = { primary?.route?.let(host::navigate) },
        icon = Icons.Filled.Videocam,
        iconTint = MaterialTheme.colorScheme.primary,
        modifier = modifier,
        trailing = { LensImage() },
        status = {
            when {
                recording -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.FiberManualRecord,
                        contentDescription = null,
                        tint = BdsmTheme.colors.recording,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = " " + stringResource(R.string.home_recording) + " ",
                        style = HomeType.heroBody,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    RecordingTimer(startedAt = state.recordingStartedAt ?: 0L)
                }

                state.cameraActive || camera is CameraStatus.Ready -> {
                    Text(
                        text = stringResource(R.string.home_camera_active),
                        style = HomeType.heroBody,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = (camera as? CameraStatus.Ready)?.badge ?: state.formatSummary,
                        style = HomeType.heroMetric,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                camera is CameraStatus.Loading -> CameraSkeleton()

                noPermission -> Text(
                    text = stringResource(R.string.home_camera_no_permission),
                    style = HomeType.heroBody,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                else -> Text(
                    text = stringResource(R.string.home_camera_none),
                    style = HomeType.heroBody,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** Cronômetro: único ponto que muda a cada segundo (o resto da tela não recompõe). */
@Composable
private fun RecordingTimer(startedAt: Long) {
    val now by produceState(initialValue = System.currentTimeMillis(), startedAt) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    Text(
        text = HomeStatus.formatElapsed(now - startedAt),
        style = HomeType.heroMetric,
        color = BdsmTheme.colors.recording,
    )
}

/** Esqueleto curto enquanto a câmera é sondada (nunca bloqueia a tela). */
@Composable
private fun CameraSkeleton() {
    Column(
        modifier = Modifier.semantics { contentDescription = "" },
        verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm),
    ) {
        Spacer(Modifier.size(2.dp))
        SkeletonBar(width = 120.dp)
        SkeletonBar(width = 80.dp)
    }
}

@Composable
private fun SkeletonBar(width: Dp) {
    Box(
        modifier = Modifier
            .size(width = width, height = 14.dp)
            .clip(BdsmTheme.shapes.chip)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

/**
 * Marca do app no cabeçalho grande: "BRAGA DIGITAL" em duas linhas, na cor de destaque, ao lado de
 * "STUDIO MOBILE" em uma linha só, em branco (como a identidade original). Logotipo: fica em caixa alta.
 */
@Composable
private fun BrandLockup(description: String, compact: Boolean = false) {
    Row(
        modifier = Modifier.semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (compact) Arrangement.Start else Arrangement.Center,
    ) {
        Column {
            Text(text = "BRAGA", style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
            Text(text = "DIGITAL", style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
        }
        Spacer(Modifier.width(BdsmTheme.spacing.md))
        Text(
            text = "STUDIO MOBILE",
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
