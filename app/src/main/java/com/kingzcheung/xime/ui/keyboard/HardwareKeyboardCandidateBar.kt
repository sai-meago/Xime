package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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

private const val ESTIMATED_CARD_HEIGHT_DP = 180

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HardwareKeyboardCandidateBar(
    inputText: String,
    preeditText: String,
    candidates: List<String>,
    /** 候选注释（如五笔编码/Z键反查编码），长度与 candidates 对齐；空列表则不渲染注释。 */
    candidateComments: List<String> = emptyList(),
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
    /** 每页候选数量：跟随设置「每页候选数」，替代原先硬编码的 10 个。 */
    pageSize: Int = 3,
    /** 候选点选上屏（index 与显示顺序一致，0..9）。 */
    onCandidateClick: ((Int) -> Unit)? = null,
    onPrevPage: (() -> Unit)? = null,
    onNextPage: (() -> Unit)? = null,
    /** 卡片窗口坐标矩形上报（onComputeInsets 触摸区）；卡片隐藏时回调 null。 */
    onCardBoundsChanged: ((android.graphics.Rect?) -> Unit)? = null,
) {
    // 空闲（无候选/无编码）时不再隐藏候选卡：保持渲染以显示光标竖线，点击输入框即有焦点反馈。

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

    // 空闲态：无编码且无候选。此时只显示一根「光标竖线」提示输入法已启用、及当前打字位置，
    // 不画卡片背景/阴影/边框，避免在宿主输入框里出现突兀的小黑块。
    val isIdle = displayText.isEmpty() && candidates.isEmpty()
    val idleLineHeightPx = with(density) { 24.dp.toPx() }.roundToInt()

    // 空闲竖线仅在宿主上报了有效光标坐标（onUpdateCursorAnchorInfo）时显示；
    // 无坐标（宿主未上报/未聚焦）直接不渲染，避免竖线落在错误位置（如输入框外、屏幕中部）。
    if (isIdle && !(cursorVisible && cursorY > 0 && cursorX > 0)) {
        onCardBoundsChanged?.invoke(null)
        return
    }
    // cursorY 为宿主光标【底部】坐标（Service 取 insertionMarkerBottom / characterBounds.bottom）：
    // 竖线以其为底端、向上绘制，正好覆盖宿主光标行高，不会再穿出输入框下边界。
    val idleXPx = with(density) {
        val maxX = (screenWidthPx - marginPx).coerceAtLeast(marginPx)
        (cursorX - viewLoc[0]).coerceIn(marginPx, maxX)
    }
    val idleYPx = with(density) {
        val maxY = (screenHeightPx - idleLineHeightPx - marginPx).coerceAtLeast(marginPx)
        (cursorY - viewLoc[1] - idleLineHeightPx).coerceIn(marginPx, maxY)
    }

    val cardXPx = with(density) {
        if (isIdle) {
            idleXPx
        } else if (pinToBottom) {
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
        if (isIdle) {
            idleYPx
        } else if (pinToBottom) {
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
                .then(
                    if (isIdle) {
                        // 空闲态：仅光标竖线，无卡片背景/阴影/圆角，避免压黑宿主输入框
                        Modifier
                    } else {
                        Modifier
                            .shadow(12.dp, RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp))
                            .background(cardBackgroundColor)
                    }
                )
                .onSizeChanged { actualCardHeight = it.height }
                .onGloballyPositioned { coords ->
                    onCardBoundsChanged?.let { cb ->
                        // 空闲态（仅光标竖线、无背景）不占触摸区：回调 null 让触摸穿透，
                        // 避免透明卡片拦截宿主输入框的点击。
                        if (isIdle) {
                            cb(null)
                        } else {
                            val r = coords.boundsInWindow()
                            cb(
                                android.graphics.Rect(
                                    r.left.toInt(), r.top.toInt(),
                                    r.right.toInt(), r.bottom.toInt()
                                )
                            )
                        }
                    }
                }
        ) {
            Column(
                modifier = Modifier
                    .padding(
                        start = if (isIdle) 0.dp else 12.dp,
                        end = if (isIdle) 0.dp else 12.dp,
                        top = if (isIdle) 0.dp else 4.dp,
                        bottom = if (isIdle) 0.dp else 4.dp
                    )
                    .widthIn(max = maxCardWidthDp.dp - 24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 2.dp)
                ) {
                    if (displayText.isNotEmpty()) {
                        Text(
                            text = displayText,
                            fontSize = (candidateTextSize - 2).coerceAtLeast(11).sp,
                            color = activeColor,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 光标竖线：无论是否已有编码都显示，提示当前可输入位置（空输入也能看到光标）
                    Box(
                        modifier = Modifier
                            .padding(start = if (displayText.isEmpty()) 0.dp else 3.dp)
                            .width(2.dp)
                            .height(22.dp)
                            .background(activeColor)
                    )
                }

                if (candidates.isNotEmpty()) {
                    // 单行横向滚动：候选词不自动换行（长词/数量多时可横滑），
                    // 首位候选始终固定在最左，避免换行把首个候选挤到下一行影响选字。
                    val hScrollState = rememberScrollState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(hScrollState),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        candidates.take(pageSize.coerceIn(1, 30)).forEachIndexed { index, candidate ->
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "$labelText $candidate",
                                        fontSize = candidateTextSize.sp,
                                        color = if (isActive) selectedTextColor else candidateTextColor,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        fontFamily = AppFonts.candidateFontFamily
                                    )
                                    if (index < candidateComments.size) {
                                        val comment = candidateComments[index]
                                        if (comment.isNotBlank()) {
                                            Text(
                                                text = comment,
                                                fontSize = (candidateTextSize - 4).coerceAtLeast(9).sp,
                                                color = candidateTextColor.copy(alpha = 0.6f),
                                                maxLines = 1,
                                                fontFamily = AppFonts.commentFontFamily
                                            )
                                        }
                                    }
                                }
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
