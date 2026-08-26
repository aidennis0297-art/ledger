package com.pushledger.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 이 앱의 입력칸.
 *
 * Material 기본 OutlinedTextField 를 쓰지 않는다. 라벨이 테두리 위로 떠올라
 * 선을 끊어 놓는 모양이 이 앱의 납작한 카드와 도트 격자에 섞이지 않는다.
 * 여기서는 라벨을 칸 위에 조용히 얹어 두고, 칸 자체는 카드와 같은 바닥을 쓴다.
 * 상태는 테두리 한 겹으로만 말한다 — 쉴 때는 없고, 쓸 때는 액센트, 틀리면 경고색.
 */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    hint: String = "",
    hintColor: Color = Sub,
    suffix: String = "",
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    big: Boolean = false,
    alignEnd: Boolean = false,
    error: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    // 테두리는 평소에 없다가 쓸 때만 나타난다. 늘 그어 두면 화면이 칸으로 뒤덮인다.
    val line by animateColorAsState(
        targetValue = when {
            error -> Warn
            focused -> Accent
            else -> Color.Transparent
        },
        label = "fieldLine"
    )
    val lineWidth by animateDpAsState(
        targetValue = if (focused || error) 1.5.dp else 0.dp,
        label = "fieldLineWidth"
    )

    Column(modifier) {
        if (label.isNotBlank()) {
            Text2(label, 11.sp, if (focused) Accent else Sub, FontWeight.Medium)
            Spacer(Modifier.height(5.dp))
        }

        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Card)
                .border(lineWidth, line, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = if (big) 12.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(8.dp))
            }

            Box(
                Modifier.weight(1f),
                if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                if (value.isEmpty() && placeholder.isNotBlank()) {
                    Text2(placeholder, if (big) 17.sp else 14.sp, Sub.copy(alpha = 0.7f))
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = singleLine,
                    // 입력칸 글자도 같은 글꼴이어야 한다. 여기만 다르면 금액을 치는 동안
                    // 화면에서 제일 자주 보는 글자가 혼자 딴 얼굴이 된다.
                    textStyle = TextStyle(
                        fontFamily = Pixel,
                        fontSize = if (big) 17.sp else 14.sp,
                        fontWeight = if (big) FontWeight.Bold else FontWeight.Normal,
                        color = Ink,
                        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start
                    ),
                    cursorBrush = SolidColor(Accent),
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    visualTransformation = visualTransformation,
                    interactionSource = interaction,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (suffix.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text2(suffix, if (big) 14.sp else 12.sp, Sub)
            }
            if (trailing != null) {
                Spacer(Modifier.width(6.dp))
                trailing()
            }
        }

        if (hint.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text2(hint, 11.sp, if (error) Warn else hintColor)
        }
    }
}

/** 라벨과 힌트처럼 한 줄짜리 글자를 같은 규격으로 찍기 위한 최소 래퍼. */
@Composable
private fun Text2(
    text: String,
    size: androidx.compose.ui.unit.TextUnit,
    color: Color,
    weight: FontWeight = FontWeight.Normal
) {
    Text(
        text = text,
        fontSize = size,
        color = color,
        fontWeight = weight,
        lineHeight = size * 1.35f
    )
}
