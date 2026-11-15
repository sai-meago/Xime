package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private const val MAX_VISIBLE_CANDIDATES = 10
private const val ESTIMATED_CARD_HEIGHT_DP = 180

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HardwareKeyboardCandidateBar(
    inputText: String,
    preeditText: String,
    candidates: List<String>,
    hasNextPage: Boolean,
    hasPrevPage: Boolean,
    cursorX: Int,
    cursorY: Int,
    cursorVisible: Boolean,
    highlightIndex: Int,
    cardBackgroundColor: Color,
    candidateTextColor: Color,
    activeColor: Color,
    selectedTextColor: Color = activeColor,
    /** true=卡片固定屏幕底部（设置项），false=跟随光标定位。 */
    pinToBottom: Boolean = false,
    /** 候选字号（sp）：跟随设置「候选字号」，与软键盘候选栏共用同一设置。 */
    candidateTextSize: Int = 15,
    /** 候选点选上屏（index 与显示顺序一致，0..9）。 */
    onCandidateClick: ((Int) -> Unit)? = null,
    onPrevPage: (() -> Unit)? = null,
    onNextPage: (() -> Unit)? = null,
    /** 卡片窗口坐标矩形上报（onComputeInsets 触摸区）；卡片隐藏时回调 null。 */
    onCardBoundsChanged: ((android.graphics.Rect?) -> Unit)? = null,
) {
    if (candidates.isEmpty() && inputText.isEmpty()) {
        onCardBoundsChanged?.invoke(null)
        return
    }

    val density = LocalDensity.current
    val displayText = if (preeditText.isNotEmpty()) preeditText else inputText

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val screenWidthPx = with(density) { screenWidthDp.dp.toPx() }.roundToInt()
    val screenHeightPx = with(density) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }.roundToInt()

    val view = LocalView.current
    val viewLoc = remember { IntArray(2) }
    view.getLocationOnScreen(viewLoc)

    val marginPx = with(density) { 8.dp.toPx() }.roundToInt()
    val cardTopMarginPx = with(density) { 16.dp.toPx() }.roundToInt()
    val estCardHeightPx = with(density) { ESTIMATED_CARD_HEIGHT_DP.dp.toPx() }.roundToInt()
    val maxCardWidthDp = (screenWidthDp * 0.85f).roundToInt().coerceIn(260, 420)
    val halfEstPx = with(density) { (maxCardWidthDp / 2).dp.toPx() }.roundToInt()

    var actualCardHeight by remember { mutableIntStateOf(estCardHeightPx) }

    val cardXPx = with(density) {
        if (pinToBottom) {
            // 贴底模式：水平居中
            (screenWidthPx - halfEstPx * 2).coerceAtLeast(0) / 2
        } else {
            val relX = cursorX - viewLoc[0]
            val maxX = (screenWidthPx - halfEstPx * 2 - marginPx).coerceAtLeast(marginPx)
            if (cursorVisible && relX > 0) {
                (relX - halfEstPx).coerceIn(marginPx, maxX)
            } else {
                (screenWidthPx - halfEstPx * 2).coerceAtLeast(0) / 2
            }
        }
    }

    val cardYPx = with(density) {
        if (pinToBottom) {
            // 贴底模式：固定屏幕底部（预留 8dp 边距）
            (screenHeightPx - actualCardHeight - marginPx).coerceAtLeast(marginPx)
        } else {
            val maxY = (screenHeightPx - actualCardHeight - marginPx).coerceAtLeast(marginPx)
            if (cursorVisible && cursorY > 0) {
                val relY = cursorY - viewLoc[1]
                if (relY + actualCardHeight + cardTopMarginPx <= screenHeightPx) {
                    (relY + cardTopMarginPx).coerceIn(marginPx, maxY)
                } else if (relY - actualCardHeight - cardTopMarginPx >= marginPx) {
                    (relY - actualCardHeight - cardTopMarginPx).coerceIn(marginPx, maxY)
                } else {
                    maxY
                }
            } else {
                with(density) { 60.dp.toPx() }.roundToInt().coerceIn(marginPx, maxY)
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(cardXPx, cardYPx) }
                .widthIn(min = 160.dp, max = maxCardWidthDp.dp)
                .wrapContentWidth()
                .shadow(12.dp, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(cardBackgroundColor)
                .onSizeChanged { actualCardHeight = it.height }
                .onGloballyPositioned { coords ->
                    onCardBoundsChanged?.let { cb ->
                        val r = coords.boundsInWindow()
                        cb(
                            android.graphics.Rect(
                                r.left.toInt(), r.top.toInt(),
                                r.right.toInt(), r.bottom.toInt()
                            )
                        )
                    }
                }
        ) {
            Column(
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
                    .widthIn(max = maxCardWidthDp.dp - 24.dp)
            ) {
                if (displayText.isNotEmpty()) {
                    Text(
                        text = displayText,
                        fontSize = (candidateTextSize - 2).coerceAtLeast(11).sp,
                        color = activeColor,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }

                if (candidates.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        candidates.take(MAX_VISIBLE_CANDIDATES).forEachIndexed { index, candidate ->
                            val isActive = index == highlightIndex
                            val label = (index + 1) % 10
                            val labelText = if (index == 9) "0" else "$label"
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = onCandidateClick != null) {
                                        onCandidateClick?.invoke(index)
                                    }
                            ) {
                                Text(
                                    text = "$labelText $candidate",
                                    fontSize = candidateTextSize.sp,
                                    color = if (isActive) selectedTextColor else candidateTextColor,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    fontFamily = AppFonts.candidateFontFamily
                                )
                            }
                        }
                    }
                }
            }

            if (hasNextPage || hasPrevPage) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = 6.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (hasPrevPage) {
                            Text(
                                "◀",
                                fontSize = (candidateTextSize - 3).coerceAtLeast(10).sp,
                                color = candidateTextColor.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = onPrevPage != null) { onPrevPage?.invoke() }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                        if (hasNextPage) {
                            Text(
                                "▶",
                                fontSize = (candidateTextSize - 3).coerceAtLeast(10).sp,
                                color = candidateTextColor.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = onNextPage != null) { onNextPage?.invoke() }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
