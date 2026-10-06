package com.bragastudio.mobile.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bragastudio.mobile.R
import com.bragastudio.mobile.common.components.BdsmBigButton
import com.bragastudio.mobile.common.components.BdsmCard
import com.bragastudio.mobile.common.components.BdsmPrimaryButton
import com.bragastudio.mobile.common.components.BdsmSecondaryButton
import com.bragastudio.mobile.common.components.BdsmTextButton
import com.bragastudio.mobile.common.components.IconBadge
import com.bragastudio.mobile.common.components.StatusChip
import com.bragastudio.mobile.common.components.StatusKind
import com.bragastudio.mobile.common.components.rememberBdsmHaptics
import com.bragastudio.mobile.common.ui.theme.BdsmTheme
import com.bragastudio.mobile.permissions.AppPermission
import com.bragastudio.mobile.permissions.PermissionStatus
import com.bragastudio.mobile.permissions.PermissionsState
import com.bragastudio.mobile.permissions.openAppSettings
import kotlinx.coroutines.launch

private const val PAGE_COUNT = 3
private const val PERMISSIONS_PAGE = 1

/**
 * Boas-vindas em 3 etapas: o que é o app, permissões explicadas (cada uma com botão próprio, todas
 * opcionais para entrar) e privacidade/rede. Nada é pedido antes do usuário tocar em "Permitir".
 */
@Composable
fun OnboardingScreen(
    permissions: PermissionsState,
    onFinished: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val haptics = rememberBdsmHaptics()
    val isLast = pagerState.currentPage == PAGE_COUNT - 1
    val pageDescription = stringResource(R.string.onb_page_of, pagerState.currentPage + 1, PAGE_COUNT)

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                key = { it },
            ) { page ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = BdsmTheme.spacing.contentMaxWidth)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = BdsmTheme.spacing.xl, vertical = BdsmTheme.spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.lg),
                    ) {
                        when (page) {
                            0 -> WelcomePage()
                            PERMISSIONS_PAGE -> PermissionsPage(permissions)
                            else -> PrivacyPage()
                        }
                    }
                }
            }

            // Zona do polegar: indicador, botão grande (56 dp) e, fora da última página, "Pular" logo abaixo.
            Column(
                modifier = Modifier
                    .widthIn(max = BdsmTheme.spacing.contentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = BdsmTheme.spacing.screenMargin, vertical = BdsmTheme.spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
            ) {
                PageDots(
                    count = PAGE_COUNT,
                    current = pagerState.currentPage,
                    modifier = Modifier.semantics { contentDescription = pageDescription },
                )
                BdsmBigButton(
                    text = stringResource(if (isLast) R.string.onb_start else R.string.onb_next),
                    onClick = {
                        if (isLast) {
                            onFinished()
                        } else {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        }
                    },
                )
                if (!isLast) {
                    BdsmTextButton(
                        onClick = {
                            haptics.tick()
                            onFinished()
                        },
                        modifier = Modifier.height(BdsmTheme.spacing.touchTarget),
                    ) { Text(stringResource(R.string.onb_skip), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val selected = index == current
            val width = animateDpAsState(
                targetValue = if (selected) 22.dp else 8.dp,
                animationSpec = tween(durationMillis = 180),
                label = "dotWidth",
            )
            Box(
                modifier = Modifier
                    .size(width = width.value, height = 8.dp)
                    .clip(BdsmTheme.shapes.chip)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else BdsmTheme.colors.ledOff,
                    )
                    .clearAndSetSemantics { },
            )
        }
    }
}

@Composable
private fun PageHeader(overline: Int, title: Int, body: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm)) {
        Text(
            text = stringResource(overline),
            style = BdsmTheme.type.overline,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PointRow(icon: ImageVector, text: Int, tint: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
    ) {
        IconBadge(icon = icon, color = tint)
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ColumnScope.WelcomePage() {
    Image(
        painter = painterResource(R.drawable.ic_brand_mark),
        contentDescription = null,
        modifier = Modifier.size(132.dp).align(Alignment.CenterHorizontally),
    )
    PageHeader(R.string.onb_welcome_overline, R.string.onb_welcome_title, R.string.onb_welcome_body)
    BdsmCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(BdsmTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
        ) {
            PointRow(Icons.Filled.Videocam, R.string.onb_welcome_point_monitor, BdsmTheme.colors.accentMonitor)
            PointRow(Icons.Filled.Check, R.string.onb_welcome_point_record, BdsmTheme.colors.accentLibrary)
            PointRow(Icons.Filled.Router, R.string.onb_welcome_point_ndi, BdsmTheme.colors.accentNetwork)
        }
    }
}

@Composable
private fun PermissionsPage(permissions: PermissionsState) {
    PageHeader(R.string.onb_perm_overline, R.string.onb_perm_title, R.string.onb_perm_body)
    PermissionCard(
        permissions = permissions,
        permission = AppPermission.Camera,
        icon = Icons.Filled.Videocam,
        tint = BdsmTheme.colors.accentVideo,
        title = R.string.perm_camera_title,
        why = R.string.perm_camera_why,
    )
    PermissionCard(
        permissions = permissions,
        permission = AppPermission.Microphone,
        icon = Icons.Filled.Mic,
        tint = BdsmTheme.colors.accentAudio,
        title = R.string.perm_mic_title,
        why = R.string.perm_mic_why,
    )
    PermissionCard(
        permissions = permissions,
        permission = AppPermission.Notifications,
        icon = Icons.Filled.Notifications,
        tint = BdsmTheme.colors.accentSystem,
        title = R.string.perm_notifications_title,
        why = R.string.perm_notifications_why,
    )
}

@Composable
private fun PermissionCard(
    permissions: PermissionsState,
    permission: AppPermission,
    icon: ImageVector,
    tint: Color,
    title: Int,
    why: Int,
) {
    val context = LocalContext.current
    val status = permissions.status(permission)
    BdsmCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(BdsmTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
            ) {
                IconBadge(icon = icon, color = tint)
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(
                    text = stringResource(
                        when (status) {
                            PermissionStatus.Granted -> R.string.perm_status_granted
                            PermissionStatus.Denied -> R.string.perm_status_denied
                            PermissionStatus.PermanentlyDenied -> R.string.perm_status_blocked
                        },
                    ),
                    kind = when (status) {
                        PermissionStatus.Granted -> StatusKind.Success
                        PermissionStatus.Denied -> StatusKind.Neutral
                        PermissionStatus.PermanentlyDenied -> StatusKind.Warning
                    },
                )
            }
            Text(
                text = stringResource(why),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (status) {
                PermissionStatus.Granted -> Unit

                PermissionStatus.Denied -> BdsmSecondaryButton(
                    text = stringResource(R.string.perm_action_allow),
                    onClick = { permissions.request(permission) },
                    modifier = Modifier.fillMaxWidth(),
                )

                PermissionStatus.PermanentlyDenied -> BdsmSecondaryButton(
                    text = stringResource(R.string.perm_action_open_settings),
                    onClick = { openAppSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.PrivacyPage() {
    Icon(
        imageVector = Icons.Filled.Shield,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(72.dp).align(Alignment.CenterHorizontally),
    )
    PageHeader(R.string.onb_privacy_overline, R.string.onb_privacy_title, R.string.onb_privacy_body)
    BdsmCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(BdsmTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
        ) {
            PointRow(Icons.Filled.Lock, R.string.onb_privacy_point_local, BdsmTheme.colors.accentStorage)
            PointRow(Icons.Filled.Check, R.string.onb_privacy_point_pair, BdsmTheme.colors.accentNetwork)
            PointRow(Icons.Filled.Check, R.string.onb_privacy_point_settings, BdsmTheme.colors.accentSystem)
        }
    }
}
