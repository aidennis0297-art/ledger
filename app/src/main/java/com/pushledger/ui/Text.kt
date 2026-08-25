package com.pushledger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em

/**
 * 이 패키지 안에서 [Text] 는 material3 의 것이 아니라 이것이다.
 *
 * 글자는 그대로 폰트로 두고, 숫자만 도트로 찍는다. 카테고리 아이콘도 그래프도
 * 전부 같은 격자 위에 있는데 숫자만 곡선 폰트로 남으면, 화면에서 제일 자주
 * 읽히는 것이 혼자 다른 결이 된다. 그렇다고 한글까지 3×5 격자에 욱여넣으면
 * 읽을 수 없는 얼룩이 되므로, 도트로 바꾸는 건 숫자와 그에 붙는 기호까지다.
 *
 * 화면마다 숫자 자리를 찾아 갈아 끼우지 않고 [Text] 자체를 바꾼 이유는,
 * 새 화면을 만들 때 이걸 기억하지 않아도 되게 하려는 것이다. 쓰는 쪽은
 * 평소처럼 `Text("남은 12,340원")` 이라고 적으면 된다.
 *
 * 쓰려면 그 파일에서 `import androidx.compose.material3.Text` 를 지워야 한다.
 * 명시적 import 가 같은 패키지 선언보다 우선하기 때문이다.
 */

/** 숫자와 그에 붙어 다니는 기호. 앞의 부호는 숫자에 바로 붙어 있을 때만 함께 먹는다. */
private val NUMERIC = Regex("[+\\-]?\\d+(?:[,:.]\\d+)*%?")

/**
 * 도트 숫자 한 칸의 크기(글자 크기 대비). 5칸이 곧 숫자 높이라
 * 0.14 × 5 = 0.7em 이 되어 같은 크기 폰트의 대문자 높이와 얼추 맞는다.
 */
private const val CELL_EM = 0.14f

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
    val ink = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val parts = remember(text, ink) { dotify(text, ink) }

    androidx.compose.material3.Text(
        text = parts.first,
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
        inlineContent = parts.second,
        onTextLayout = onTextLayout ?: {},
        style = style
    )
}

/**
 * 숫자 구간을 인라인 그림 자리로 바꾼다.
 *
 * 직접 Row 로 쪼개지 않고 인라인 콘텐츠를 쓰는 이유는 줄바꿈 때문이다.
 * "지난달보다 12,340원 더 씀" 같은 문장은 폭이 좁으면 접혀야 하는데,
 * Row 로 나눠 붙이면 그 자리에서 줄이 끊기지 않고 화면 밖으로 밀린다.
 */
private fun dotify(
    text: String,
    color: Color
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val hits = NUMERIC.findAll(text).toList()
    if (hits.isEmpty()) return AnnotatedString(text) to emptyMap()

    val inline = LinkedHashMap<String, InlineTextContent>()
    val built = buildAnnotatedString {
        var cursor = 0
        hits.forEachIndexed { i, m ->
            append(text.substring(cursor, m.range.first))
            val num = m.value
            val id = "d$i"
            inline[id] = InlineTextContent(
                Placeholder(
                    width = ((num.length * 4f - 1f) * CELL_EM).em,
                    height = (5f * CELL_EM).em,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline
                )
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    // 칸을 정수 픽셀로 내려 맞춘다. 소수 자리에 걸치면 알갱이 가장자리가
                    // 흐려져서, 옆의 8×8 아이콘만 또렷하고 숫자는 번진 것처럼 보인다.
                    val px = kotlin.math.floor(size.height / 5f).coerceAtLeast(1f)
                    val w = dotTextWidth(num, px)
                    dotText(num, (size.width - w) / 2f, (size.height - px * 5f) / 2f, px, color)
                }
            }
            appendInlineContent(id, num)
            cursor = m.range.last + 1
        }
        append(text.substring(cursor))
    }
    return built to inline
}
