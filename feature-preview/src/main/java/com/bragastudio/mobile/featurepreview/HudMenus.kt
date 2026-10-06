package com.bragastudio.mobile.featurepreview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider

/** Lado do âncora em que o popover é aberto. */
internal enum class HudPopupSide { ABOVE, BELOW, BELOW_CENTER, BELOW_START, END }

/**
 * Posiciona popovers do HUD relativos ao âncora (o Box que os declara) usando
 * PIXELS já calculados a partir de dp — o `Popup(offset = IntOffset(160, 0))`
 * antigo misturava pixels fixos com dp e errava em densidades diferentes.
 * O popover é sempre mantido dentro da janela.
 */
internal class HudPopupPositionProvider(
    private val side: HudPopupSide,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val (x, y) = when (side) {
            HudPopupSide.ABOVE ->
                (anchorBounds.center.x - popupContentSize.width / 2) to (anchorBounds.top - popupContentSize.height - gapPx)

            HudPopupSide.BELOW ->
                (anchorBounds.right - popupContentSize.width) to (anchorBounds.bottom + gapPx)

            HudPopupSide.BELOW_CENTER ->
                (anchorBounds.center.x - popupContentSize.width / 2) to (anchorBounds.bottom + gapPx)

            HudPopupSide.BELOW_START ->
                anchorBounds.left to (anchorBounds.bottom + gapPx)

            HudPopupSide.END ->
                (anchorBounds.right + gapPx) to (anchorBounds.center.y - popupContentSize.height / 2)
        }
        return IntOffset(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
    }
}

@Composable
fun <T> TranslucentFloatingMenu(
    items: List<T>,
    selectedItem: T?,
    onItemSelected: (T) -> Unit,
    itemLabel: (T) -> String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.ui.window.Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismissRequest,
    ) {
        Box(
            modifier = modifier
                .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                .padding(8.dp),
        ) {
            androidx.compose.foundation.lazy.LazyColumn {
                items(items.size) { index ->
                    val item = items[index]
                    val isSelected = item == selectedItem
                    Text(
                        text = itemLabel(item),
                        color = if (isSelected) HudTheme.recordColor else Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = HudTheme.minTouchTarget)
                            .clickable {
                                onItemSelected(item)
                                onDismissRequest()
                            }
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 16.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}
