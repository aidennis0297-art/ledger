package com.pushledger.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/**
 * 이 패키지 안에서 [Text] 는 material3 의 것이 아니라 이것이다.
 *
 * 지금은 하는 일이 없다. 글꼴은 [Pixel] 이 앱 전체에 걸려 있어서 여기서 손댈 것이 없다.
 *
 * 그래도 남겨 두는 이유는 두 가지다. 하나는 화면 파일 이백 곳이 이 이름을 부르고
 * 있어서고, 다른 하나는 글자에 무엇을 걸 일이 또 생기면 여기 한 곳만 고치면 되기
 * 때문이다. 실제로 한 번 그랬다 — 숫자를 도트로 찍던 것도, 한글을 격자로 그리던 것도
 * 전부 이 함수 안에서 오갔고, 화면 파일은 한 줄도 안 바뀌었다.
 *
 * 그 격자 그리기는 이제 없다. 시스템 폰트를 작게 그려 격자로 읽어 내는 방식이었는데,
 * 읽을 수는 있어도 볼 만하지 않았다. 획 굵기가 제멋대로였고 받침이 붙었다.
 * 한글 11,172자를 사람이 하나씩 그려 둔 픽셀 폰트가 모든 면에서 낫다.
 *
 * 쓰려면 그 파일에서 `import androidx.compose.material3.Text` 를 지워야 한다.
 * 명시적 import 가 같은 패키지 선언보다 우선하기 때문이다.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current
) {
    androidx.compose.material3.Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout ?: {},
        style = style
    )
}
