package com.pushledger.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pushledger.Cat
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 차트는 전부 Canvas 에 도트로 직접 찍는다. 차트 라이브러리를 썼으면 라이브러리가
 * 허용하는 매끈한 모양 안에 갇혔을 것이다. 여기 있는 건 전부 같은 격자에 얹힌
 * 사각형이라 도트 크기 하나만 바꿔도 화면 전체의 결이 같이 움직인다.
 */

val Ink = Color(0xFF16161A)
val Faint = Color(0xFFE8E8ED)
val Sub = Color(0xFF8A8A94)
val Accent = Color(0xFF2F6BFF)
val Warn = Color(0xFFE5484D)
val Good = Color(0xFF3FA34D)
/** 지출이 저축 몫을 파고든 구간. 아직 초과는 아니지만 저축을 깨는 중이라는 뜻이다. */
val Clash = Color(0xFF8B5CF6)
/** 들어온 돈. 나가는 돈(Accent)과 헷갈리지 않게 하늘색 쪽으로 벌려 둔다. */
val Income = Color(0xFF0EA5E9)

/**
 * 카드와 배너의 공통 배경. 상태에 따라 배경색을 바꾸지 않는다.
 * 초과했다고 박스까지 붉어지면 화면이 소리를 지른다. 상태는 글자 색으로만 말한다.
 */
val Card = Color(0xFFF7F7F9)

/** 카테고리 색. 도트 팔레트를 바꾸려면 여기만 손대면 전체가 따라온다. */
val CatColor = mapOf(
    Cat.FOOD to Color(0xFFFF7A45),
    Cat.LIVING to Color(0xFF3FA34D),
    Cat.LEISURE to Color(0xFF8B5CF6),
    Cat.FINANCE to Color(0xFFD97706),
    Cat.HOUSING to Color(0xFF2F6BFF),
    Cat.INCOME to Color(0xFF0EA5E9),
    Cat.ETC to Color(0xFFA1A1AA)
)



fun won(v: Long): String = "%,d원".format(v)

/**
 * 12345 를 "1만2천3백4십5원" 으로 읽어 준다.
 * 숫자를 칠 때 0 을 몇 개 눌렀는지 세지 않아도 되게 하려는 것이다.
 */
fun wonKo(v: Long): String {
    if (v <= 0L) return "0원"
    val units = listOf(
        100_000_000L to "억", 10_000L to "만", 1_000L to "천", 100L to "백", 10L to "십", 1L to ""
    )
    var rest = v
    val sb = StringBuilder()
    for ((u, name) in units) {
        val q = rest / u
        if (q > 0) { sb.append(q).append(name); rest %= u }
    }
    return sb.append("원").toString()
}
fun wonShort(v: Long): String = when {
    v >= 100_000_000 -> "%.1f억".format(v / 100_000_000.0)
    v >= 10_000 -> "%,d만".format(v / 10_000)
    else -> "%,d".format(v)
}

/** 화면마다 반복되는 흰 카드. 제목·아이콘·오른쪽 액션 한 줄이 거의 항상 같은 모양이다. 접기/펼치기를 지원한다. */

@Composable
fun Panel(
    title: String,
    icon: Sym? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    collapsible: Boolean = false,
    collapsed: Boolean = false,
    onToggleCollapse: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (collapsible && onToggleCollapse != null) Modifier.clickable { onToggleCollapse() } else Modifier),
            Arrangement.SpaceBetween, Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    DotSym(icon, 15.dp, Sub)
                    Spacer(Modifier.width(7.dp))
                }
                Text(title, fontSize = T.Body, color = Sub, fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (action != null && onAction != null) {
                    TextButton(onClick = onAction, contentPadding = PaddingValues(4.dp)) {
                        Text(action, fontSize = T.Body, color = Accent)
                    }
                }
                if (collapsible && onToggleCollapse != null) {
                    DotSym(if (collapsed) Sym.DOWN else Sym.UP, 16.dp, Sub)
                }
            }
        }
        if (!collapsed) {
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}


/**
 * 그려질 때 0에서 1로 한 번 자란다. [key] 가 바뀌면 다시 자란다.
 *
 * 그래프가 완성된 채로 툭 나타나면 도트가 배경 무늬처럼 읽힌다. 아래에서 차오르는
 * 반 초 동안 알갱이가 하나씩 쌓이는 게 보이고, 그제야 개수가 곧 값이라는 것이 눈에 들어온다.
 *
 * [State] 를 그대로 돌려주는 것이 핵심이다. 여기서 `.value` 를 읽으면 매 프레임
 * 그 함수가 통째로 다시 불리고, `Canvas` 람다 안에서 읽으면 다시 그리기만 한다.
 * 목록 안에서 도는 애니메이션이라 이 차이가 곧 스크롤 부드러움이다.
 */
@Composable
fun grow(key: Any?, ms: Int = 520): State<Float> {
    val p = remember { Animatable(0f) }
    LaunchedEffect(key) {
        p.snapTo(0f)
        p.animateTo(1f, tween(ms, easing = FastOutSlowInEasing))
    }
    return p.asState()
}

@Composable
private fun EmptyBox(height: Dp, msg: String = "아직 기록이 없어요") {
    Box(Modifier.fillMaxWidth().heightIn(min = height), Alignment.Center) {
        EmptyState(title = msg, compact = true)
    }
}

/**
 * 도트 막대. 일별·주간·시간대·요일에 모두 쓴다.
 * 값이 도트 개수로 양자화되므로 눈금을 따로 그리지 않아도 알갱이를 세어 읽을 수 있다.
 */
@Composable
fun BarChart(
    values: List<Long>,
    labels: List<String>,
    height: Dp = 150.dp,
    mark: Int = -1,
    color: Color = Ink,
    budgetLine: Long = 0,
    showValues: Boolean = false,
    countMode: Boolean = false,
    highlightMax: Boolean = false
) {
    val maxV = (values.maxOrNull() ?: 0L).coerceAtLeast(budgetLine)
    if (maxV <= 0L) { EmptyBox(height); return }

    // 값이 같은 칸이 여럿이면 첫 칸만 강조한다. 여러 칸이 붉으면 강조가 아니라 소음이다.
    val maxIdx = if (highlightMax) values.indexOfFirst { it == maxV && it > 0L } else -1

    fun label(v: Long): String = when {
        v <= 0L -> ""
        countMode -> v.toString()
        else -> ((v + 5_000) / 10_000).let { if (it > 0) it.toString() else "" }
    }

    val g = grow(values)

    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val t = g.value
            // 물리 픽셀로 고정하면 고해상도 화면에서 깨알처럼 작아진다. dp 로 잡아야
            // 옆의 라벨 글씨와 비슷한 크기로 보인다.
            val px = 2.dp.toPx()
            val headroom = if (showValues) px * 5 + 5f else 0f   // 숫자가 앉을 자리
            val plotH = size.height - headroom
            val slot = size.width / values.size
            val bw = slot * 0.74f

            values.forEachIndexed { i, v ->
                val h = (v.toFloat() / maxV) * plotH * t
                val x = i * slot + (slot - bw) / 2f
                val over = budgetLine > 0 && v > budgetLine
                val hot = i == maxIdx || over
                dotBar(
                    x = x, w = bw, h = h,
                    color = when {
                        i == mark -> Accent
                        hot -> Warn
                        else -> color.copy(alpha = if (v == 0L) 0.16f else 0.88f)
                    }
                )
                // 최대 칸은 showValues 를 안 켜도 늘 금액을 달아 준다. 제일 많이 쓴 날이
                // 어디인지 눈으로 찾고 다시 표를 뒤지게 만들 이유가 없다.
                // 숫자는 막대가 거의 다 자란 뒤에 배어 나온다. 처음부터 붙여 두면
                // 자라는 내내 숫자가 같이 날아올라 어지럽다.
                if ((showValues || i == maxIdx) && t > 0.55f) {
                    val txt = label(v)
                    if (txt.isNotEmpty()) {
                        val tw = dotTextWidth(txt, px)
                        val a = ((t - 0.55f) / 0.45f).coerceIn(0f, 1f)
                        dotText(
                            txt, (x + (bw - tw) / 2f).coerceIn(0f, size.width - tw),
                            size.height - h - px * 5 - 3f, px,
                            (if (hot) Warn else Ink).copy(alpha = a)
                        )
                    }
                }
            }
            // 예산선도 도트로 끊어 찍어야 차트와 같은 결로 보인다.
            if (budgetLine > 0) {
                val y = size.height - (budgetLine.toFloat() / maxV) * plotH
                // 선만 그으면 그게 얼마인지 알 수 없다. 선 위에 금액을 얹는다.
                val bTxt = ((budgetLine + 5_000) / 10_000).toString()
                if (bTxt != "0") {
                    val bw2 = dotTextWidth(bTxt, px)
                    dotText(bTxt, size.width - bw2, y - px * 5 - 2f, px, Warn)
                }
                var x = 0f
                while (x < size.width) {
                    drawRect(Warn.copy(alpha = 0.75f), Offset(x, y), Size(DOT, 1.8f))
                    x += DOT * 2.6f
                }
            }
        }
        if (labels.isNotEmpty()) {
            Spacer(Modifier.height(5.dp))
            if (labels.size == values.size) {
                Row(Modifier.fillMaxWidth()) {
                    labels.forEach {
                        Box(Modifier.weight(1f), Alignment.Center) {
                            Text(it, fontSize = T.Caption, color = Sub)
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    labels.forEach { Text(it, fontSize = T.Caption, color = Sub) }
                }
            }
        }
    }
}

/** 도트로 이은 추이선. 급증/급락 스파이크 구간에서도 도트가 성글어지지 않고 균일한 간격으로 촘촘히 연결된다. */
@Composable
fun LineChart(
    values: List<Long>,
    height: Dp = 150.dp,
    color: Color = Accent,
    budgetLine: Long = 0
) {
    val maxV = (values.maxOrNull() ?: 0L).coerceAtLeast(budgetLine)
    if (maxV <= 0L || values.size <= 1) { EmptyBox(height); return }

    val g = grow(values)

    Canvas(Modifier.fillMaxWidth().height(height)) {
        val pitch = DOT + DOT_GAP
        val n = values.size

        // 각 날짜별 (x, y) 좌표 계산
        val points = values.indices.map { i ->
            val x = (i.toFloat() / (n - 1)) * size.width
            val y = size.height - (values[i].toFloat() / maxV) * size.height * 0.90f - DOT
            Offset(x, y)
        }

        // 추이선은 아래에서 자라는 대신 1일부터 오른쪽으로 그려 나간다.
        // 시간 축이 가로인 그림이라 그리는 방향이 곧 시간이 흐르는 방향이 된다.
        val shown = (1 + (n - 1) * g.value).toInt().coerceIn(1, n)
        val vis = points.take(shown)

        // 하루 예산을 가로지르는 도트 선. 금액은 선 위에 작게 얹어 눈금 노릇을 하게 둔다.
        if (budgetLine > 0) {
            val by = size.height - (budgetLine.toFloat() / maxV) * size.height * 0.90f - DOT
            var bx = 0f
            while (bx < size.width) {
                drawRect(Warn.copy(alpha = 0.6f), Offset(bx, by), Size(DOT, 1.8f))
                bx += DOT * 2.6f
            }
            val px = 2.dp.toPx()
            val txt = ((budgetLine + 5_000) / 10_000).toString()
            if (txt != "0") {
                val tw = dotTextWidth(txt, px)
                dotText(txt, (size.width - tw) / 2f, by - px * 5 - 3f, px, Warn)
            }
        }

        // 선 아래 채우기
        val fill = Path().apply {
            moveTo(vis.first().x, size.height)
            vis.forEach { lineTo(it.x, it.y) }
            lineTo(vis.last().x, size.height)
            close()
        }
        drawPath(fill, color.copy(alpha = 0.09f))

        // 선분의 실제 유클리드 거리를 기준으로 도트를 균등 배치하여 급등 구간도 촘촘히 렌더링
        for (i in 0 until shown - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val dist = kotlin.math.hypot(dx, dy)
            val segDots = max(1, (dist / pitch).toInt())
            for (s in 0 until segDots) {
                val frac = s.toFloat() / segDots
                val dotX = p1.x + dx * frac
                val dotY = p1.y + dy * frac
                drawRect(color, Offset(dotX - DOT / 2f, dotY - DOT / 2f), Size(DOT, DOT))
            }
        }
        val last = vis.last()
        drawRect(color, Offset(last.x - DOT / 2f, last.y - DOT / 2f), Size(DOT, DOT))

        // 제일 많이 쓴 날에 금액을 얹는다. 봉우리가 얼마짜리인지 바로 읽히게.
        // 선이 아직 그 날까지 오지 않았으면 숫자도 아직이다.
        val peak = values.indices.maxByOrNull { values[it] } ?: return@Canvas
        if (values[peak] > 0L && peak < shown) {
            val px = 2.dp.toPx()
            val txt = ((values[peak] + 5_000) / 10_000).toString()
            if (txt != "0") {
                val tw = dotTextWidth(txt, px)
                val p = points[peak]
                drawRect(Warn, Offset(p.x - DOT / 2f, p.y - DOT / 2f), Size(DOT, DOT))
                dotText(
                    txt, (p.x - tw / 2f).coerceIn(0f, size.width - tw),
                    (p.y - px * 5 - 5f).coerceAtLeast(0f), px, Warn
                )
            }
        }
    }
}


/** 도트로 찍은 도넛. 조각 사이를 한 칸 띄워 경계를 만든다. */
@Composable
fun Donut(slices: List<Pair<Cat, Long>>, size: Dp = 148.dp) {
    val sum = slices.sumOf { it.second }
    if (sum <= 0L) { EmptyBox(size); return }

    val g = grow(slices)

    Canvas(Modifier.size(size)) {
        val t = g.value
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val r = this.size.width / 2f - DOT

        // 조각마다 제자리에서 자란다. 링 전체를 한 방향으로 쓸어 그리면 뒤쪽 조각은
        // 애니메이션이 끝날 때까지 없는 것처럼 보인다.
        var start = -90f
        slices.forEach { (cat, v) ->
            val sweep = (v.toFloat() / sum) * 360f
            dotArc(cx, cy, r, start, ((sweep - 2f) * t).coerceAtLeast(0f), CatColor[cat] ?: Sub)
            start += sweep
        }

        // 링 안쪽은 와플 격자로 채운다. 한 칸이 대략 1% 가 되도록 칸 크기를 역산해서,
        // 항목마다 비중만큼 칸을 칠한다. 링만으로는 2·3위 비중이 눈에 잡히지 않는다.
        val inner = r - DOT * 2.6f
        if (inner <= 0f) return@Canvas
        // 안쪽 알갱이를 테두리 도트와 같은 크기로 맞춘다. 안쪽만 굵으면 두 그림처럼 보인다.
        // 간격은 최소로 둬서 원반이 촘촘하게 찬다. 성기면 비중이 아니라 점 몇 개로 읽힌다.
        val d = DOT
        val pitch = d + 2f

        val cells = ArrayList<Offset>(128)
        var y = cy - inner
        while (y + d <= cy + inner) {
            var x = cx - inner
            while (x + d <= cx + inner) {
                val ox = x + d / 2f - cx
                val oy = y + d / 2f - cy
                if (ox * ox + oy * oy <= inner * inner) cells.add(Offset(x, y))
                x += pitch
            }
            y += pitch
        }

        val n = cells.size
        if (n == 0) return@Canvas
        // 와플은 왼쪽 위부터 순서대로 색이 든다. 아직 차례가 안 온 칸은 빈 칸 색으로 둔다.
        val lit = (n * t).toInt()
        var i = 0
        slices.forEach { (cat, v) ->
            // 아주 작은 항목도 한 칸은 갖게 해서 링에만 있고 안에는 없는 일이 없게 한다.
            val take = Math.floor(n * (v.toDouble() / sum)).toInt().coerceAtLeast(if (v > 0L) 1 else 0)
            repeat(take) {
                if (i < n) {
                    drawRect(if (i < lit) (CatColor[cat] ?: Sub) else Faint, cells[i], Size(d, d)); i++
                }
            }
        }
        while (i < n) { drawRect(Faint, cells[i], Size(d, d)); i++ }
    }
}

/**
 * 요일 × 시간 히트맵. 언제 돈을 쓰는지가 이 격자 하나에 다 들어간다.
 * Y축은 월~일 요일, X축은 0시~23시 24개 시간대를 정밀하게 매핑한다.
 */
@Composable
fun HeatGrid(grid: List<List<Long>>, height: Dp = 110.dp) {
    val maxV = grid.flatten().maxOrNull() ?: 0L
    val days = listOf("월", "화", "수", "목", "금", "토", "일")
    if (maxV <= 0L) { EmptyBox(height); return }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Y축 요일 라벨
            Column(
                Modifier.width(20.dp).height(height),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                days.forEach {
                    Text(it, fontSize = T.Caption, color = Sub, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.width(4.dp))
            // 7x24 히트맵 캔버스
            Canvas(Modifier.weight(1f).height(height)) {
                val cw = size.width / 24f
                val ch = size.height / 7f
                val s = min(cw, ch) * 0.78f
                grid.forEachIndexed { d, hours ->
                    hours.forEachIndexed { h, v ->
                        val a = if (v == 0L) 0.06f else 0.20f + 0.80f * (v.toFloat() / maxV)
                        drawRect(
                            Accent.copy(alpha = a),
                            Offset(h * cw + (cw - s) / 2f, d * ch + (ch - s) / 2f),
                            Size(s, s)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        // 마지막 칸에 weight 를 주면 "23시" 가 그 폭을 넘어 삐져나온다. 양끝 정렬로 둔다.
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("0시", "6시", "12시", "18시", "23시").forEach {
                Text(it, fontSize = T.Caption, color = Sub)
            }
        }
    }
}


/** 가로 도트 랭킹. 가맹점 TOP 이나 카테고리 순위처럼 이름이 길 때 쓴다. */
@Composable
fun RankBar(
    name: String,
    value: Long,
    maxV: Long,
    rank: Int = 0,
    color: Color = Ink,
    countMode: Boolean = false
) {
    val f = if (maxV <= 0L) 0f else (value.toFloat() / maxV)
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (rank in 1..3) {
                    // 1~3 등만 채운 배지로 세우고, 등수에 따라 검정을 옅게 간다.
                    // 셋이 같은 검정이면 배지가 있다는 것만 보이고 순서는 안 보인다.
                    Box(
                        Modifier.size(17.dp).clip(RoundedCornerShape(4.dp))
                            .background(Ink.copy(alpha = when (rank) { 1 -> 1f; 2 -> 0.68f; else -> 0.42f })),
                        Alignment.Center
                    ) {
                        // 폰트 패딩을 끄지 않으면 숫자가 박스 아래쪽에 붙는다.
                        Text(
                            "$rank",
                            style = TextStyle(
                                fontSize = T.Caption,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            )
                        )
                    }
                    Spacer(Modifier.width(7.dp))
                } else if (rank > 0) {
                    Box(Modifier.size(17.dp), Alignment.Center) {
                        Text(
                            "$rank",
                            style = TextStyle(
                                fontSize = T.Caption,
                                color = Sub,
                                textAlign = TextAlign.Center,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            )
                        )
                    }
                    Spacer(Modifier.width(7.dp))
                }
                Text(name, fontSize = T.Body, color = Ink, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            Text(if (countMode) "${value}번" else won(value), fontSize = T.Body, color = Sub)
        }
        Spacer(Modifier.height(4.dp))
        val g = grow(value)
        Canvas(Modifier.fillMaxWidth().height(8.dp)) { dotTrack(f * g.value, color, Faint) }
    }
}

/** 예산 소진 도트. 넘으면 색이 뒤집혀 한눈에 걸린다. */
@Composable
fun BudgetBar(
    spent: Long,
    budget: Long,
    height: Dp = 10.dp,
    reserved: Long = 0L,
    animate: Boolean = false
) {
    val over = budget in 1..<spent
    val f = if (budget <= 0L) 0f else (spent.toFloat() / budget)
    // 저축 몫은 오른쪽 끝에서부터 초록으로 미리 떼어 둔다.
    // 왼쪽에서 자라는 지출과 오른쪽에 잠긴 저축이 만나면 그 달이 빡빡해진 것이다.
    val rf = if (budget <= 0L) 0f else (reserved.toFloat() / budget)

    // 아주 느리게 한 바퀴. 눈에 띄려고 도는 게 아니라 화면이 살아 있다는 기척만 남긴다.
    //
    // 값을 여기서 .value 로 읽으면 매 프레임 이 함수가 통째로 다시 불린다.
    // State 를 그대로 들고 있다가 Canvas 안에서 읽으면 다시 그리기만 하고 화면 구성은
    // 건드리지 않는다. 목록 안에서 도는 애니메이션이라 이 차이가 곧 스크롤 부드러움이 된다.
    val phase: State<Float>? = if (animate) {
        rememberInfiniteTransition(label = "budgetWave").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(6200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "budgetWavePhase"
        )
    } else null

    // 띠도 왼쪽에서 자라며 찬다. 지출과 저축 몫이 같은 속도로 양끝에서 다가온다.
    val g = grow(spent to budget)

    Canvas(Modifier.fillMaxWidth().height(height)) {
        val t = g.value
        dotTrack(
            f = f * t,
            color = if (over) Warn else Accent,
            bg = Faint,
            reserveF = rf * t,
            reserveColor = Good,
            clashColor = Clash,
            allOver = over,
            wave = phase?.value ?: -1f
        )
    }
}


/** 카테고리 도트 아이콘을 옅은 색판 위에 올린 배지. 내역 줄마다 쓰인다. */
@Composable
fun CatBadge(cat: Cat, dim: Boolean = false, box: Dp = 30.dp) {
    val c = CatColor[cat] ?: Sub
    Box(
        Modifier.size(box).clip(RoundedCornerShape(10.dp))
            .background(c.copy(alpha = if (dim) 0.07f else 0.13f)),
        Alignment.Center
    ) {
        DotIcon(cat, size = box * 0.62f, color = if (dim) Sub.copy(alpha = 0.6f) else c)
    }
}
