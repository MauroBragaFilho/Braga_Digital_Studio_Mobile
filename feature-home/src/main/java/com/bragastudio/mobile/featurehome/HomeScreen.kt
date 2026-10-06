package com.bragastudio.mobile.featurehome

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.bragastudio.mobile.core.domain.HardwareMetrics
import com.bragastudio.mobile.corecapture.status.CameraStatus
import java.util.Calendar
import kotlinx.coroutines.delay

/** Escala tipográfica própria da Home (menor que os tokens globais; os demais ecrãs não mudam). */
private object HomeType {
    val brandLine = TextStyle(fontSize = 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    val product = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
    val greeting = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val heroTitle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val heroBody = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val heroMetric = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum")
    val button = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
    val metric = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum")
}

private val LensSize = 96.dp
private val HeroMinHeight = 140.dp

/**
 * Home v4 (mescla da Home original com a UX v3). Topo (só leitura): marca "BRAGA DIGITAL | STUDIO
 * MOBILE" e faixa de métricas do aparelho; centro: grade 2x2 de módulos gerada pelo registro;
 * fundo (alcance do polegar): hero do Monitor com a lente e o botão "Abrir Monitor". Em tela larga
 * vira duas colunas (marca, métricas e hero à esquerda; grade à direita).
 * Nada técnico aqui: sem IP, porta, bitrate ou codec (ficam em NDI > Detalhes e em Ajustes).
 */
@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val host = LocalModuleHost.current
    val primary = remember(host) { host.registry.at(ModulePlacement.HOME_PRIMARY).firstOrNull() }
    val cards = remember(host) { host.registry.at(ModulePlacement.HOME_CARD) }
    val spacing = BdsmTheme.spacing
    val wide = bdsmWidthClass() != BdsmWidthClass.Compact

    // Ao voltar para a Home (permissão concedida, câmera USB conectada) a câmera é sondada de novo.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshCamera() }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            val fullHeight = maxHeight
            when {
                !state.loaded -> LoadingState(modifier = Modifier.fillMaxSize(), message = stringResource(R.string.home_loading))

                wide -> Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = spacing.screenMargin),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xl),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = fullHeight)
                            .padding(vertical = spacing.lg),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
                            HomeHeader()
                            MetricsStrip(metrics = state.metrics)
                        }
                        HeroSection(state = state, primary = primary, host = host)
                    }
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = spacing.lg),
                    ) {
                        ModuleGrid(cards = cards, host = host)
                    }
                }

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = fullHeight)
                        .padding(horizontal = spacing.screenMargin),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                        HomeHeader(modifier = Modifier.padding(top = spacing.md))
                        MetricsStrip(metrics = state.metrics)
                        ModuleGrid(cards = cards, host = host)
                    }
                    HeroSection(state = state, primary = primary, host = host, modifier = Modifier.padding(vertical = spacing.lg))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Topo: marca (identidade da Home original) + saudação curta
// ---------------------------------------------------------------------------

@Composable
private fun HomeHeader(modifier: Modifier = Modifier) {
    val period = remember { HomeStatus.dayPeriod(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    val greeting = stringResource(
        when (period) {
            DayPeriod.MORNING -> R.string.home_greeting_morning
            DayPeriod.AFTERNOON -> R.string.home_greeting_afternoon
            DayPeriod.NIGHT -> R.string.home_greeting_night
        },
    )
    val brandDescription = stringResource(R.string.home_brand_description)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = "$greeting. $brandDescription"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
    ) {
        Column {
            BrandLine(stringResource(R.string.home_brand_line1))
            BrandLine(stringResource(R.string.home_brand_line2))
        }
        Text(
            text = stringResource(R.string.home_brand_product).uppercase(),
            style = HomeType.product,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = greeting,
            style = HomeType.greeting,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun BrandLine(text: String) {
    Text(
        text = text.uppercase(),
        color = MaterialTheme.colorScheme.primary,
        style = HomeType.brandLine,
    )
}

// ---------------------------------------------------------------------------
// Faixa de métricas: uma linha discreta (ícone + valor); só destaca o que pede atenção
// ---------------------------------------------------------------------------

@Composable
private fun MetricsStrip(metrics: HardwareMetrics) {
    val alerts = HomeStatus.metricAlerts(metrics)
    val temp = HomeStatus.formatTemperature(metrics.temperatureCelsius)
    val storage = HomeStatus.formatStorageFree(metrics.storageFreeGB, metrics.storageTotalGB)
    val battery = "${metrics.batteryPercentage}%"
    val wifiDescription = stringResource(if (metrics.isWifiConnected) R.string.home_cd_wifi_on else R.string.home_cd_wifi_off)
    val groupDescription = stringResource(R.string.home_metrics_group)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BdsmTheme.shapes.item)
            .background(BdsmTheme.colors.card)
            .padding(horizontal = BdsmTheme.spacing.md, vertical = BdsmTheme.spacing.xs)
            .semantics { contentDescription = groupDescription },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetricItem(
            icon = Icons.Filled.Thermostat,
            value = temp,
            description = stringResource(R.string.home_cd_temp, temp),
            alert = MetricKind.TEMPERATURE in alerts,
            alertText = stringResource(R.string.home_title_temperature),
        )
        MetricItem(
            icon = Icons.Filled.SdStorage,
            value = storage,
            description = stringResource(R.string.home_cd_storage, storage),
            alert = MetricKind.STORAGE in alerts,
            alertText = stringResource(R.string.home_title_storage),
        )
        MetricItem(
            icon = if (metrics.isCharging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull,
            value = battery,
            description = stringResource(
                if (metrics.isCharging) R.string.home_cd_battery_charging else R.string.home_cd_battery,
                metrics.batteryPercentage,
            ),
            alert = MetricKind.BATTERY in alerts,
            alertText = stringResource(R.string.home_title_battery),
        )
        MetricItem(
            icon = if (metrics.isWifiConnected) Icons.Filled.Wifi else Icons.Filled.WifiOff,
            value = stringResource(if (metrics.isWifiConnected) R.string.home_wifi_on else R.string.home_wifi_off),
            description = wifiDescription,
            alert = MetricKind.WIFI in alerts,
            alertText = stringResource(R.string.home_alert_wifi),
        )
    }
}

/** Ícone + valor. Em alerta: pastilha âmbar, ícone e triângulo (estado nunca só por cor). */
@Composable
private fun MetricItem(
    icon: ImageVector,
    value: String,
    description: String,
    alert: Boolean,
    alertText: String,
) {
    val colors = BdsmTheme.colors
    val content = if (alert) colors.onWarningContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(BdsmTheme.shapes.pill)
            .then(if (alert) Modifier.background(colors.warningContainer) else Modifier)
            .padding(horizontal = if (alert) BdsmTheme.spacing.sm else BdsmTheme.spacing.xs, vertical = BdsmTheme.spacing.xs)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (alert) stateDescription = alertText
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.xs),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Text(
            text = value,
            style = HomeType.metric,
            color = if (alert) colors.onWarningContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        if (alert) {
            Icon(imageVector = Icons.Filled.Warning, contentDescription = null, tint = content, modifier = Modifier.size(12.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Centro: grade 2x2 gerada pelo registro de módulos (cores por módulo, fallback por categoria)
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
private fun ModuleGrid(cards: List<BdsmModule>, host: ModuleHost) {
    Column(verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md)) {
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
// Fundo: hero (Monitor + páginas extras reais) e indicador de páginas
// ---------------------------------------------------------------------------

@Composable
private fun HeroSection(
    state: HomeUiState,
    primary: BdsmModule?,
    host: ModuleHost,
    modifier: Modifier = Modifier,
) {
    MonitorHero(state = state, primary = primary, host = host, modifier = modifier)
}

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
            .heightIn(min = BdsmTheme.spacing.touchTarget)
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
