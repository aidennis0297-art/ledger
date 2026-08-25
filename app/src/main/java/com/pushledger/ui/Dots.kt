package com.pushledger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pushledger.Cat
import kotlin.math.cos
import kotlin.math.sin

const val DOT = 6f
const val DOT_GAP = 3f

/**
 * 8×8 도트 아이콘 패턴.
 */
val DotIcons: Map<Cat, List<String>> = mapOf(
    Cat.FOOD to listOf(
        "#..#..##",
        "#..#...#",
        "####...#",
        "..#....#",
        "..#....#",
        "..#....#",
        "..#....#",
        "########"
    ),
    Cat.LIVING to listOf(
        ".##.##..",
        "#######.",
        "#######.",
        ".#####..",
        "..###...",
        "...#....",
        "........",
        "########"
    ),
    Cat.LEISURE to listOf(
        "...####.",
        "...#..#.",
        "...#..#.",
        ".###.###",
        "####.###",
        ".##...##",
        "........",
        "########"
    ),
    Cat.FINANCE to listOf(
        "..####..",
        ".######.",
        "##.##.##",
        "##.##.##",
        "##.##.##",
        ".######.",
        "..####..",
        "########"
    ),
    Cat.HOUSING to listOf(
        "...##...",
        "..####..",
        ".######.",
        "########",
        ".##..##.",
        ".##..##.",
        ".######.",
        "########"
    ),
    Cat.INCOME to listOf(
        "...##...",
        "...##...",
        ".######.",
        ".######.",
        "...##...",
        "...##...",
        ".######.",
        "........"
    ),
    Cat.ETC to listOf(
        "........",
        "........",
        "........",
        "##.##.##",
        "##.##.##",
        "........",
        "........",
        "........"
    )
)

/**
 * 카테고리 말고 화면 여기저기에 필요한 기호들.
 *
 * 이 앱에는 이제 벡터 아이콘이 하나도 없다. Material 아이콘은 곡선과
 * 안티에일리어싱으로 그려져서 같은 줄에 놓인 도트 격자와 결이 맞지 않았다 —
 * 아이콘만 매끈하고 그 옆의 그래프는 각져 있으니 둘이 다른 앱에서 온 것처럼 보였다.
 * 카테고리 아이콘이 쓰던 8×8 격자를 그대로 넓혀서 전부 여기로 옮겼다.
 */
enum class Sym {
    DASH, LIST, INBOX, CHART, BARS, WALLET, COIN, SHIELD, SPARK,
    RIGHT, LEFT, UP, DOWN, CAL, FLAG, LINE, UPTREND, DOWNTREND,
    DOC, SEARCH, PERSON, WEEK, SHOP, GRID, DONUT, PIE, CLOCK,
    BELL, KEY, PENCIL, TRASH, PLUS, BAN, UNDO
}

val DotSyms: Map<Sym, List<String>> = mapOf(
    Sym.DASH to listOf(
        "........", ".###.###", ".###.###", ".###.###",
        "........", ".###.###", ".###.###", ".###.###"
    ),
    Sym.LIST to listOf(
        "##.#####", "##.#####", "........", "##.#####",
        "##.#####", "........", "##.#####", "##.#####"
    ),
    Sym.INBOX to listOf(
        "...##...", "...##...", ".######.", "..####..",
        "...##...", "#......#", "#......#", "########"
    ),
    Sym.CHART to listOf(
        "##......", "##......", "##...##.", "##...##.",
        "##.#.##.", "##.#.##.", "##.#.##.", "########"
    ),
    Sym.BARS to listOf(
        "........", ".....##.", ".....##.", "..##.##.",
        "..##.##.", "##.##.##", "##.##.##", "##.##.##"
    ),
    Sym.WALLET to listOf(
        "########", "#......#", "#......#", "#...####",
        "#...#..#", "#...####", "#......#", "########"
    ),
    Sym.COIN to listOf(
        ".######.", "##....##", ".######.", "........",
        ".######.", "##....##", ".######.", "........"
    ),
    Sym.SHIELD to listOf(
        ".######.", "########", "########", "########",
        ".######.", "..####..", "...##...", "........"
    ),
    Sym.SPARK to listOf(
        "...##...", "...##...", ".#.##.#.", "..####..",
        "..####..", ".#.##.#.", "...##...", "...##..."
    ),
    Sym.RIGHT to listOf(
        "..##....", "...##...", "....##..", ".....##.",
        ".....##.", "....##..", "...##...", "..##...."
    ),
    Sym.LEFT to listOf(
        "....##..", "...##...", "..##....", ".##.....",
        ".##.....", "..##....", "...##...", "....##.."
    ),
    Sym.UP to listOf(
        "........", "........", "...##...", "..####..",
        ".##..##.", "##....##", "........", "........"
    ),
    Sym.DOWN to listOf(
        "........", "........", "##....##", ".##..##.",
        "..####..", "...##...", "........", "........"
    ),
    Sym.CAL to listOf(
        ".#....#.", "########", "#......#", "#.#.##.#",
        "#......#", "#.##.#.#", "#......#", "########"
    ),
    Sym.FLAG to listOf(
        "##......", "##......", "#######.", "#######.",
        "#######.", "##......", "##......", "##......"
    ),
    Sym.LINE to listOf(
        "........", "......##", ".....##.", "....##..",
        ".#.##...", ".###....", "##......", "........"
    ),
    Sym.UPTREND to listOf(
        "....####", "....####", "......##", ".....##.",
        "....##..", ".###....", "##......", "........"
    ),
    Sym.DOWNTREND to listOf(
        "........", "##......", ".###....", "....##..",
        ".....##.", "......##", "....####", "....####"
    ),
    Sym.DOC to listOf(
        "######..", "#....##.", "#....###", "#......#",
        "#.####.#", "#......#", "#.####.#", "########"
    ),
    Sym.SEARCH to listOf(
        ".####...", "##..##..", "#....#..", "##..##..",
        ".####.#.", ".....###", "......##", "........"
    ),
    Sym.PERSON to listOf(
        "..####..", "..####..", "..####..", "........",
        ".######.", "########", "########", "########"
    ),
    Sym.WEEK to listOf(
        "########", "##.##.##", "##.##.##", "##.##.##",
        "##.##.##", "##.##.##", "##.##.##", "########"
    ),
    Sym.SHOP to listOf(
        "########", "##.##.##", "########", "#......#",
        "#.####.#", "#.#..#.#", "#.#..#.#", "########"
    ),
    Sym.GRID to listOf(
        "##.##.##", "##.##.##", "........", "##.##.##",
        "##.##.##", "........", "##.##.##", "##.##.##"
    ),
    Sym.DONUT to listOf(
        "..####..", ".##..##.", "##....##", "##....##",
        "##....##", "##....##", ".##..##.", "..####.."
    ),
    Sym.PIE to listOf(
        "..####..", ".##.###.", "##..####", "##..####",
        "##....##", "##....##", ".######.", "..####.."
    ),
    Sym.CLOCK to listOf(
        "..####..", ".#....#.", "#..#...#", "#..#...#",
        "#..####.", "#......#", ".#....#.", "..####.."
    ),
    Sym.BELL to listOf(
        "...##...", "..####..", ".######.", ".######.",
        "########", "########", "........", "...##..."
    ),
    Sym.KEY to listOf(
        ".####...", "##..##..", "##..##..", ".####...",
        "..##....", "..##....", "..####..", "..##...."
    ),
    Sym.PENCIL to listOf(
        ".....###", "....####", "...###.#", "..###...",
        ".###....", "###.....", "##......", "#......."
    ),
    Sym.TRASH to listOf(
        "..####..", "########", "........", ".######.",
        ".#.##.#.", ".#.##.#.", ".#.##.#.", ".######."
    ),
    Sym.PLUS to listOf(
        "........", "...##...", "...##...", ".######.",
        ".######.", "...##...", "...##...", "........"
    ),
    Sym.BAN to listOf(
        "..####..", ".##..##.", "##....##", "########",
        "########", "##....##", ".##..##.", "..####.."
    ),
    Sym.UNDO to listOf(
        "...#....", "..##....", ".###....", "####....",
        "..#####.", "......##", ".....##.", "..####.."
    )
)

/**
 * 8×8 패턴을 안티에일리어싱 없이 픽셀 블록으로 렌더링.
 *
 * 칸 크기를 정수로 내림해서 격자를 화면 픽셀에 맞춘다. 소수점 자리에 걸치면
 * 가장자리가 흐려져서 도트가 아니라 뭉갠 사각형으로 보인다.
 */
@Composable
private fun Dot8(pattern: List<String>, size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val cell = kotlin.math.floor(this.size.width / 8f).coerceAtLeast(1f)
        val off = (this.size.width - cell * 8f) / 2f
        pattern.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                if (c == '#') drawRect(
                    color,
                    Offset(off + x * cell, off + y * cell),
                    Size(cell * 0.86f, cell * 0.86f)
                )
            }
        }
    }
}

@Composable
fun DotIcon(cat: Cat, size: Dp = 22.dp, color: Color = Ink) {
    Dot8(DotIcons[cat] ?: DotIcons[Cat.ETC]!!, size, color)
}

/** 카테고리 아이콘과 같은 격자, 같은 알갱이. 아이콘 자리에는 전부 이걸 쓴다. */
@Composable
fun DotSym(sym: Sym, size: Dp = 16.dp, color: Color = Sub) {
    Dot8(DotSyms[sym]!!, size, color)
}

/**
 * 3×5 픽셀 숫자. 막대 안에 값을 직접 찍을 때, 그리고 [Text] 안의 숫자에 쓴다.
 * 일반 폰트로 숫자를 얹으면 도트 그래프 위에 다른 결이 겹쳐 지저분해진다.
 */
val DIGITS: Map<Char, List<String>> = mapOf(
    '0' to listOf("###", "#.#", "#.#", "#.#", "###"),
    '1' to listOf(".#.", "##.", ".#.", ".#.", "###"),
    '2' to listOf("###", "..#", "###", "#..", "###"),
    '3' to listOf("###", "..#", "###", "..#", "###"),
    '4' to listOf("#.#", "#.#", "###", "..#", "..#"),
    '5' to listOf("###", "#..", "###", "..#", "###"),
    '6' to listOf("###", "#..", "###", "#.#", "###"),
    '7' to listOf("###", "..#", "..#", "..#", "..#"),
    '8' to listOf("###", "#.#", "###", "#.#", "###"),
    '9' to listOf("###", "#.#", "###", "..#", "###"),
    '.' to listOf("...", "...", "...", "...", ".#."),
    ',' to listOf("...", "...", "...", ".#.", "#.."),
    ':' to listOf("...", ".#.", "...", ".#.", "..."),
    '-' to listOf("...", "...", "###", "...", "..."),
    '+' to listOf("...", ".#.", "###", ".#.", "..."),
    '/' to listOf("..#", "..#", ".#.", "#..", "#.."),
    '%' to listOf("#.#", "..#", ".#.", "#..", "#.#")
)

/** 도트 숫자 한 줄의 폭. 가운데 정렬하려면 그리기 전에 이걸로 자리를 잡는다. */
fun dotTextWidth(text: String, px: Float): Float =
    if (text.isEmpty()) 0f else text.length * 4f * px - px

/** 좌상단 기준으로 도트 숫자를 찍는다. 높이는 항상 5칸이다. */
fun DrawScope.dotText(text: String, left: Float, top: Float, px: Float, color: Color) {
    var x = left
    text.forEach { ch ->
        DIGITS[ch]?.forEachIndexed { r, row ->
            row.forEachIndexed { c, p ->
                if (p == '#') drawRect(color, Offset(x + c * px, top + r * px), Size(px, px))
            }
        }
        x += 4f * px
    }
}

/**
 * 바닥부터 위로 쌓는 도트 기둥. 막대 하나가 도트 격자로 채워진다.
 * 칸 전체를 가로지르는 통짜 사각형을 쌓으면 도트가 아니라 줄무늬 막대가 된다.
 */
fun DrawScope.dotBar(x: Float, w: Float, h: Float, color: Color) {
    val pitch = DOT + DOT_GAP
    val cols = ((w + DOT_GAP) / pitch).toInt().coerceAtLeast(1)
    val rows = ((h + DOT_GAP) / pitch).toInt().coerceAtLeast(if (h > 0f) 1 else 0)
    val used = cols * pitch - DOT_GAP
    val ox = x + (w - used) / 2f
    for (r in 0 until rows) {
        val y = size.height - (r + 1) * pitch + DOT_GAP
        for (c in 0 until cols) {
            drawRect(color, Offset(ox + c * pitch, y), Size(DOT, DOT))
        }
    }
}

/**
 * 가로로 채우는 도트 띠. 알갱이 한 줄이다.
 *
 * 전에는 칸 높이만큼 여러 줄을 쌓았는데, 그러면 띠가 굵어지는 대신 화면에서
 * 제일 먼저 눈에 띄는 자리를 도트 덩어리가 차지해 버렸다. 한 줄이면 알갱이를
 * 세어 읽을 수 있고, 옆에 놓인 8×8 아이콘과도 같은 굵기로 앉는다.
 *
 * [reserveF] 만큼은 오른쪽 끝에서부터 [reserveColor] 로 떼어 둔다. 저축처럼
 * 쓰기 전에 이미 임자가 정해진 몫을 표시하는 자리다.
 */
fun DrawScope.dotTrack(
    f: Float,
    color: Color,
    bg: Color,
    reserveF: Float = 0f,
    reserveColor: Color = color,
    clashColor: Color = reserveColor,
    allOver: Boolean = false,
    wave: Float = -1f,
    waveColor: Color = Color.White
) {
    val pitch = DOT + DOT_GAP
    val cols = ((size.width + DOT_GAP) / pitch).toInt().coerceAtLeast(1)

    val filled = (f * cols).toInt().coerceIn(0, cols)
    val reserved = (reserveF * cols).toInt().coerceIn(0, cols)
    val oy = (size.height - DOT) / 2f

    for (i in 0 until cols) {
        val fromRight = cols - 1 - i
        val inReserve = fromRight < reserved
        val inSpent = i < filled
        val base = when {
            // 예산을 통째로 넘겼으면 띠 전체가 붉다. 부분 색으로 나누면 상태가 흐려진다.
            allOver -> Warn
            // 지출이 저축 몫까지 파고든 칸. 초과 전이지만 저축을 깨는 중이다.
            inReserve && inSpent -> clashColor
            inReserve -> reserveColor
            inSpent -> color
            else -> bg
        }

        // 알갱이는 제자리에 있고 음영만 지나간다. 한 줄이라 파도가 왼쪽에서 오른쪽으로 흐른다.
        val c = if (wave >= 0f && (inSpent || inReserve)) {
            val phase = (i.toFloat() / cols - wave) * (2f * Math.PI.toFloat())
            val lift = (kotlin.math.sin(phase) + 1f) / 2f
            // 마루에서만 살짝 밝아진다. 0.42 를 넘기면 색이 하얗게 떠 버린다.
            androidx.compose.ui.graphics.lerp(base, waveColor, lift * lift * 0.42f)
        } else base

        drawRect(c, Offset(i * pitch, oy), Size(DOT, DOT))
    }
}


fun DrawScope.dotField(color: Color) {
    val pitch = DOT + DOT_GAP
    val cols = (size.width / pitch).toInt().coerceAtLeast(1)
    val rows = (size.height / pitch).toInt().coerceAtLeast(1)
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            drawRect(color, Offset(c * pitch, r * pitch), Size(DOT, DOT))
        }
    }
}

fun DrawScope.dotArc(cx: Float, cy: Float, radius: Float, startAngle: Float, sweepAngle: Float, color: Color) {
    val perimeter = 2 * Math.PI * radius * (Math.abs(sweepAngle) / 360f)
    val pitch = DOT + DOT_GAP
    val count = (perimeter / pitch).toInt().coerceAtLeast(1)
    for (i in 0 until count) {
        val frac = i.toFloat() / count
        val deg = Math.toRadians((startAngle + sweepAngle * frac).toDouble())
        val x = cx + (radius * cos(deg)).toFloat()
        val y = cy + (radius * sin(deg)).toFloat()
        drawRect(color, Offset(x - DOT / 2f, y - DOT / 2f), Size(DOT, DOT))
    }
}
