package com.pushledger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 아무것도 없을 때 보여 주는 화면.
 *
 * 정비 전에는 화면마다 회색 한 줄이 다른 문장으로 떠 있었고, 다음에 뭘 하라는 말은
 * 어디에도 없었다. 앱을 처음 켠 사람이 가장 오래 보게 될 화면인데도 그랬다.
 * 문장은 화면마다 달라도 되지만 생김새와 다음 행동은 한 벌이어야 한다.
 */
@Composable
fun EmptyState(
    title: String,
    detail: String = "",
    actionLabel: String = "",
    onAction: (() -> Unit)? = null,
    compact: Boolean = false
) {
    // 부모가 높이를 정해 주는 자리(차트 빈 칸)와 내용만큼만 차지하는 자리(목록 아래)가
    // 섞여 있다. 바깥 Box 가 남는 공간을 받아 가운데로 몰아 주면 양쪽에서 같은 모양이 된다.
    Box(
        Modifier.fillMaxSize().padding(vertical = if (compact) 14.dp else 28.dp),
        contentAlignment = Alignment.Center
    ) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        DotMark(compact)
        Spacer(Modifier.height(14.dp))
        Text(
            title,
            fontSize = T.Title,
            fontWeight = FontWeight.SemiBold,
            color = Ink,
            textAlign = TextAlign.Center
        )
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(
                detail,
                fontSize = T.Caption,
                color = Sub,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
        if (actionLabel.isNotBlank() && onAction != null) {
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onAction) {
                Text(actionLabel, fontSize = T.Body, color = Accent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    }
}

/**
 * 비어 있음을 도트로 그린다. 아직 아무것도 안 채워진 막대 세 개 —
 * 이 앱에서 데이터가 쌓이면 생길 모양을 미리 옅게 보여 주는 셈이다.
 */
@Composable
private fun DotMark(compact: Boolean) {
    val box = if (compact) 44.dp else 58.dp
    Canvas(Modifier.size(box)) {
        val pitch = DOT + DOT_GAP
        val cols = 3
        val rows = 4
        val usedW = cols * (pitch * 2) - DOT_GAP
        val ox = (size.width - usedW) / 2f
        val oy = (size.height - rows * pitch) / 2f
        // 왼쪽 기둥은 한 알, 가운데는 두 알, 오른쪽은 세 알. 자라날 자리만 남겨 둔다.
        val heights = listOf(1, 2, 3)
        heights.forEachIndexed { c, h ->
            for (r in 0 until h) {
                drawRect(
                    Faint,
                    Offset(ox + c * (pitch * 2), oy + (rows - 1 - r) * pitch),
                    Size(DOT, DOT)
                )
            }
        }
    }
}
