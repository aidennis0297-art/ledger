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
 * **공백 말고는 폰트로 그리는 글자가 없다.** 숫자는 3×5 격자로, 나머지 글자는
 * [DotFont] 로 찍는다. 카테고리 아이콘도 그래프도 전부 같은 격자 위에 있는데
 * 글자만 곡선 폰트로 남으면, 화면에서 제일 자주 읽히는 것이 혼자 다른 결이 된다.
 *
 * 처음에는 숫자만 도트로 찍었고, 다음에는 큰 글자만 도트로 바꿨다. 크기로 선을
 * 그으니 같은 화면 안에서 제목은 도트인데 그 아래 설명은 폰트가 되어 두 결이
 * 나란히 놓였다. 선을 없앴다.
 *
 * 공백만은 진짜 글자로 남긴다. 공백까지 그림으로 바꾸면 그 자리에서 줄이 끊기지
 * 않아 긴 문장이 화면 밖으로 밀려 나간다.
 *
 * 화면마다 글자 자리를 찾아 갈아 끼우지 않고 [Text] 자체를 바꾼 이유는,
 * 새 화면을 만들 때 이걸 기억하지 않아도 되게 하려는 것이다. 쓰는 쪽은
 * 평소처럼 `Text("남은 12,340원")` 이라고 적으면 된다.
 *
 * 쓰려면 그 파일에서 `import androidx.compose.material3.Text` 를 지워야 한다.
 * 명시적 import 가 같은 패키지 선언보다 우선하기 때문이다.
 */

/** 숫자와 그에 붙어 다니는 기호. 앞의 부호는 숫자에 바로 붙어 있을 때만 함께 먹는다. */
private val NUMERIC = Regex("[+\\-]?\\d+(?:[,:.]\\d+)*%?")

/**
 * 한 덩어리로 찍을 최대 글자 수.
 *
 * 인라인 그림 하나는 글자 하나처럼 다뤄져서 그 안에서는 줄이 끊기지도, 말줄임표가
 * 붙지도 않는다. "스타벅스강남역점" 을 통째로 한 덩어리로 두면 좁은 칸에서 그 줄이
 * 통째로 밀려 나간다. 그렇다고 한 글자씩 쪼개면 줄마다 그림 조각이 수십 개가 되어
 * 목록 스크롤이 무거워진다. 여덟이 그 사이다.
 */
private const val CHUNK = 8

/**
 * 도트 숫자 한 칸의 크기(글자 크기 대비). 5칸이 곧 숫자 높이라
 * 0.14 × 5 = 0.7em 이 되어 같은 크기 폰트의 대문자 높이와 얼추 맞는다.
 */
private const val CELL_EM = 0.14f

/**
 * 한글 도트 한 칸의 크기. 11칸이 0.79em 이 되어 같은 크기 폰트 글자와 키가 얼추 맞는다.
 * 숫자와 같은 0.14 를 쓰면 11칸이 1.5em 이 되어 줄 높이를 통째로 밀어낸다.
 */
private const val HAN_CELL_EM = 0.072f

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
 * 글자를 도트로 찍을 조각으로 자른다. 공백은 조각이 아니라 그대로 남는다.
 *
 * 숫자와 그 밖의 글자는 격자가 달라서(3×5 대 11칸) 조각마다 어느 쪽인지 들고 다닌다.
 * 자르는 순서가 곧 화면에 놓이는 순서라, 앞에서부터 한 번만 훑는다.
 */
private fun pieces(text: String): List<Triple<Int, Int, Boolean>> {
    val out = ArrayList<Triple<Int, Int, Boolean>>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isWhitespace()) { i++; continue }

        // 숫자는 붙어 다니는 기호(쉼표·소수점·퍼센트·부호)까지 한 조각으로 먹는다.
        val num = NUMERIC.matchAt(text, i)
        if (num != null) {
            out.add(Triple(i, num.range.last + 1, true))
            i = num.range.last + 1
            continue
        }

        // 나머지는 공백이나 숫자를 만날 때까지, 길어야 CHUNK 글자까지.
        var j = i
        while (j < text.length && j - i < CHUNK &&
            !text[j].isWhitespace() && NUMERIC.matchAt(text, j) == null
        ) j++
        out.add(Triple(i, j, false))
        i = j
    }
    return out
}

/**
 * 조각을 인라인 그림 자리로 바꾼다.
 *
 * 직접 Row 로 쪼개지 않고 인라인 콘텐츠를 쓰는 이유는 줄바꿈 때문이다.
 * "지난달보다 12,340원 더 씀" 같은 문장은 폭이 좁으면 접혀야 하는데,
 * Row 로 나눠 붙이면 그 자리에서 줄이 끊기지 않고 화면 밖으로 밀린다.
 */
private fun dotify(
    text: String,
    color: Color
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val hits = pieces(text)
    if (hits.isEmpty()) return AnnotatedString(text) to emptyMap()

    val inline = LinkedHashMap<String, InlineTextContent>()
    val built = buildAnnotatedString {
        var cursor = 0
        hits.forEachIndexed { i, (from, to, isNum) ->
            append(text.substring(cursor, from))
            val piece = text.substring(from, to)
            val id = "d$i"

            // 자리 크기는 조각마다 다르다. 숫자는 3×5 격자로 폭이 글자 수에 비례하고,
            // 나머지는 폰트를 찍어 낸 격자라 글자마다 폭이 다르다.
            // 그 폭을 미리 재서 자리를 잡아야 글자끼리 겹치지 않는다.
            val rows = if (isNum) 5f else DotFont.ROWS.toFloat()
            val cols = if (isNum) piece.length * 4f - 1f else DotFont.of(piece).cols.toFloat()
            val em = if (isNum) CELL_EM else HAN_CELL_EM

            inline[id] = InlineTextContent(
                Placeholder(
                    width = (cols * em).em,
                    height = (rows * em).em,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline
                )
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    // 칸을 정수 픽셀로 내려 맞춘다. 소수 자리에 걸치면 알갱이 가장자리가
                    // 흐려져서, 옆의 8×8 아이콘만 또렷하고 숫자는 번진 것처럼 보인다.
                    val px = kotlin.math.floor(size.height / rows).coerceAtLeast(1f)
                    val w = cols * px
                    val x = (size.width - w) / 2f
                    val y = (size.height - px * rows) / 2f
                    if (isNum) dotText(piece, x, y, px, color)
                    else dotString(piece, x, y, px, color)
                }
            }
            appendInlineContent(id, piece)
            cursor = to
        }
        append(text.substring(cursor))
    }
    return built to inline
}
