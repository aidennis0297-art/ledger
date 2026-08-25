package com.pushledger.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * 눌렀을 때 도트가 부스러져 튀는 효과.
 *
 * 알갱이 위치는 [key] 를 씨앗으로 한 번만 정해 두고, 애니메이션은 진행도 하나만 굴린다.
 * 파티클마다 상태를 두면 알갱이 수만큼 애니메이션이 돌아 목록에서 바로 버벅인다.
 *
 * 그리기는 [drawWithContent] 안에서만 일어나므로 화면 구성은 다시 계산되지 않는다.
 */
@Composable
fun DotBurst(
    key: Any?,
    color: Color,
    modifier: Modifier = Modifier,
    count: Int = 14,
    content: @Composable () -> Unit
) {
    val progress = remember { Animatable(1f) }

    // 씨앗이 같으면 같은 모양이 나온다. 매 프레임 난수를 뽑으면 알갱이가 떨리기만 한다.
    val seeds = remember(key, count) {
        val rnd = java.util.Random((key?.hashCode() ?: 0).toLong() * 31 + count)
        List(count) {
            Triple(
                // 방향: 위쪽으로 살짝 치우치게 흩는다. 아래로만 쏟아지면 부스러기가 아니라 낙하다.
                (-20f + rnd.nextFloat() * 220f) * (Math.PI.toFloat() / 180f),
                0.45f + rnd.nextFloat() * 0.75f,   // 세기
                0.7f + rnd.nextFloat() * 0.6f      // 알갱이 크기 배수
            )
        }
    }

    LaunchedEffect(key) {
        if (key == null) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(620, easing = LinearEasing))
    }

    Box(
        modifier.drawWithContent {
            drawContent()
            val t = progress.value
            if (t >= 1f) return@drawWithContent

            val cx = size.width / 2f
            val cy = size.height / 2f
            val reach = size.minDimension * 1.15f
            // 초반에 빠르게 퍼지고 끝에서 잦아든다.
            val ease = 1f - (1f - t) * (1f - t)
            val fade = (1f - t) * (1f - t)

            seeds.forEach { (angle, power, scale) ->
                val d = reach * power * ease
                val x = cx + cos(angle) * d
                // 살짝 가라앉게 해서 튄 것이 떨어지는 느낌을 만든다.
                val y = cy - sin(angle) * d + (reach * 0.35f * t * t)
                val s = DOT * scale * (0.4f + fade * 0.9f)
                drawRect(
                    color.copy(alpha = fade.coerceIn(0f, 1f)),
                    Offset(x - s / 2f, y - s / 2f),
                    Size(s, s)
                )
            }
        }
    ) { content() }
}
