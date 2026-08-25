package com.pushledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pushledger.Stats
import com.pushledger.Store
import java.time.LocalDate
import java.time.YearMonth

/**
 * 통계. 월간과 연간 두 벌이고, 맨 위 칩으로 갈아 끼운다.
 * 연간은 [YearStats] 에 따로 있다 — 축이 아예 다르고, 한 화면에 다 넣으면
 * 어느 그래프가 어느 기간 것인지 매번 확인해야 한다.
 */
@Composable
fun StatsScreen() {
    var yearly by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 2.dp)
        ) {
            FilterChip(!yearly, { yearly = false }, { Text("월간", fontSize = T.Caption) })
            Spacer(Modifier.width(6.dp))
            FilterChip(yearly, { yearly = true }, { Text("연간", fontSize = T.Caption) })
        }
        if (yearly) YearStats() else MonthStats()
    }
}

/**
 * 한 달. 축을 여섯 가지로 나눠 본다.
 *   일별 · 주간 · 요일별 · 시간대 · 요일×시간 · 항목별
 * 취소된 거래는 Stats 가 전부 걸러 내므로 어느 그래프에도 나타나지 않는다.
 */
@Composable
private fun MonthStats() {
    var ym by remember { mutableStateOf(YearMonth.now()) }
    var dayMode by remember { mutableStateOf(0) }        // 0 막대, 1 꺾은선
    var hourMode by remember { mutableStateOf(0) }       // 0 금액, 1 건수
    var shopMode by remember { mutableStateOf(0) }       // 0 결제 금액순, 1 방문 횟수순

    val cur by Store.month.collectAsState()
    val cfg by Store.config.collectAsState()
    val list = if (ym == YearMonth.now()) cur else remember(ym) { Store.readMonth(ym) }
    val prev = remember(ym) { Store.readMonth(ym.minusMonths(1)) }

    val today = LocalDate.now()

    // 주 선택. 달 전체를 한 판에 뭉쳐 놓으면 요일이 축인 그래프에서 특정 주를
    // 떼어 볼 방법이 없다. 여기서 고른 구간이 아래 모든 그래프에 그대로 걸린다.
    val spans = remember(ym) { Stats.weeksOf(ym) }
    var week by remember(ym) { mutableStateOf<Int?>(null) }
    val span = week?.let { spans.getOrNull(it) }
    val scoped = remember(list, span) { Stats.inSpan(list, span) }

    val total = Stats.total(list)
    val scopedTotal = Stats.total(scoped)
    val investTotal = Stats.investTotal(list)
    val prevTotal = Stats.total(prev)
    val diff = total - prevTotal
    val days = Stats.byDay(list, ym)
    val cats = Stats.byCat(scoped)
    val hourDaily = Stats.byHourDaily(
        scoped,
        if (span == null) (if (ym == YearMonth.now()) today.dayOfMonth else ym.lengthOfMonth())
        else Stats.elapsedDays(span, today)
    )
    val hourCounts = Stats.byHourCount(scoped)
    val weekdays = Stats.byWeekday(scoped)

    // 하루 예산은 한 기준으로만 센다. 그래야 일별·추이·시간대 선이 서로 어긋나지 않는다.
    // 고정지출과 저축 몫을 뺀 "실제로 쓸 수 있는" 하루치다.
    val dayBudget = Stats.dailyVariableBudget(cfg, list, ym)


    LazyColumn(Modifier.fillMaxWidth()) {

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                Arrangement.Center, Alignment.CenterVertically
            ) {
                IconButton({ ym = ym.minusMonths(1) }) {
                    DotSym(Sym.LEFT, 18.dp, Sub)
                }
                Text("${ym.year}년 ${ym.monthValue}월", fontSize = T.Title,
                    fontWeight = FontWeight.SemiBold, color = Ink)
                IconButton({ if (ym < YearMonth.now()) ym = ym.plusMonths(1) }) {
                    DotSym(Sym.RIGHT, 18.dp, if (ym < YearMonth.now()) Sub else Faint)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
                Text(won(total), fontSize = T.Display, fontWeight = FontWeight.Bold, color = Ink)
                if (investTotal > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text("투자·저축 ${wonShort(investTotal)}원 제외", fontSize = T.Body, color = Sub)
                }
                if (prevTotal > 0) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DotSym(
                            if (diff >= 0) Sym.UPTREND else Sym.DOWNTREND,
                            15.dp, if (diff >= 0) Warn else Good
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "지난달보다 ${wonShort(kotlin.math.abs(diff))}원 " +
                                if (diff >= 0) "더 씀" else "덜 씀",
                            fontSize = T.Body, color = Sub
                        )
                    }
                }
            }
        }


        // 주 고르기. 칩 하나로 아래 전부가 같이 좁혀진다.
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 2.dp)
            ) {
                FilterChip(
                    selected = week == null,
                    onClick = { week = null },
                    label = { Text("이번 달 전체", fontSize = T.Caption) }
                )
                spans.forEach { w ->
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = week == w.index,
                        onClick = { week = if (week == w.index) null else w.index },
                        label = { Text(w.label, fontSize = T.Caption) }
                    )
                }
            }
            if (span != null) {
                Text(
                    "${span.range} · ${span.days}일치만 보는 중",
                    fontSize = T.Caption, color = Accent,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
                )
            }
        }

        val collapsed = cfg.collapsedStats

        item {
            Panel(
                title = "일별",
                icon = Sym.BARS,
                collapsible = true,
                collapsed = "daily" in collapsed,
                onToggleCollapse = { Store.toggleStatsCollapse("daily") }
            ) {
                Row(Modifier.padding(bottom = 8.dp)) {
                    FilterChip(dayMode == 0, { dayMode = 0 }, { Text("막대", fontSize = T.Caption) })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(dayMode == 1, { dayMode = 1 }, { Text("추이", fontSize = T.Caption) })
                }
                val from = span?.start?.dayOfMonth ?: 1
                val to = span?.end?.dayOfMonth ?: ym.lengthOfMonth()
                val dayValues = days.subList(from - 1, to)
                val dayLabels =
                    if (span == null) listOf("1일", "${ym.lengthOfMonth() / 2}일", "${ym.lengthOfMonth()}일")
                    else (from..to).map { "${it}일" }
                val dayMark =
                    if (ym == YearMonth.now() && today.dayOfMonth in from..to) today.dayOfMonth - from
                    else -1
                if (dayMode == 0) {
                    BarChart(
                        values = dayValues,
                        labels = dayLabels,
                        mark = dayMark,
                        budgetLine = dayBudget,
                        highlightMax = true
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "단위 만원 · 붉은 선은 고정지출과 저축을 뺀 하루 예산",
                        fontSize = T.Caption, color = Sub
                    )
                } else {
                    LineChart(dayValues, budgetLine = dayBudget)
                }
            }
        }

        item {
            Panel(
                title = "주간",
                icon = Sym.WEEK,
                collapsible = true,
                collapsed = "weekly" in collapsed,
                onToggleCollapse = { Store.toggleStatsCollapse("weekly") }
            ) {
                // 주간만은 늘 달 전체를 보여 준다. 여기가 주를 고르는 지도 노릇을 한다.
                val weeks = Stats.byWeek(list, ym)
                BarChart(
                    values = weeks,
                    labels = spans.map { it.label },
                    height = 120.dp, color = Accent,
                    mark = week ?: -1,
                    showValues = true, highlightMax = true
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (span == null) "단위 만원 · 월~일 기준 · 가장 많이 쓴 주 강조"
                    else "단위 만원 · 고른 ${span.label}는 파랗게 표시됩니다",
                    fontSize = T.Caption, color = Sub
                )
            }
        }

        item {
            Panel(
                title = "요일별",
                icon = Sym.WEEK,
                collapsible = true,
                collapsed = "weekday" in collapsed,
                onToggleCollapse = { Store.toggleStatsCollapse("weekday") }
            ) {
                BarChart(
                    values = weekdays,
                    labels = listOf("월", "화", "수", "목", "금", "토", "일"),
                    height = 120.dp, color = Good,
                    showValues = true, highlightMax = true
                )
                Spacer(Modifier.height(4.dp))
                Text("단위 만원 · 가장 많이 쓴 요일 강조", fontSize = T.Caption, color = Sub)
            }
        }

        item {
            Panel(
                title = "시간대",
                icon = Sym.CLOCK,
                collapsible = true,
                collapsed = "hourly" in collapsed,
                onToggleCollapse = { Store.toggleStatsCollapse("hourly") }
            ) {
                Row(Modifier.padding(bottom = 8.dp)) {
                    FilterChip(hourMode == 0, { hourMode = 0 }, { Text("금액별", fontSize = T.Caption) })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(hourMode == 1, { hourMode = 1 }, { Text("건수별", fontSize = T.Caption) })
                }
                // 금액 모드는 하루 평균으로 환산해서 하루 예산선과 단위를 맞춘다.
                val chartValues = if (hourMode == 0) hourDaily else hourCounts
                BarChart(
                    values = chartValues,
                    labels = listOf("0시", "6시", "12시", "18시", "23시"),
                    height = 120.dp,
                    color = if (hourMode == 0) Ink else Accent,
                    // 금액으로 볼 때만 하루 예산을 가로로 긋는다. 건수와는 단위가 달라 의미가 없다.
                    budgetLine = if (hourMode == 0) dayBudget else 0L,
                    showValues = true,
                    countMode = hourMode == 1
                )
                if (chartValues.any { it > 0 }) {
                    val peak = chartValues.indices.maxByOrNull { chartValues[it] } ?: 0
                    Spacer(Modifier.height(4.dp))
                    Text(
                        (if (span == null) "" else "${span.label} · ") +
                            if (hourMode == 0) "하루 평균 · 단위 만원 · 피크 ${peak}시 · 붉은 선은 하루 예산"
                            else "단위 건 · 피크 ${peak}시",
                        fontSize = T.Caption, color = Sub
                    )
                }
            }
        }


        item {
            Panel(
                title = "요일 × 시간",
                icon = Sym.GRID,
                collapsible = true,
                collapsed = "heat" in collapsed,
                onToggleCollapse = { Store.toggleStatsCollapse("heat") }
            ) {
                HeatGrid(Stats.heat(scoped))
            }
        }

        item {
            Panel(
                title = "항목별",
                icon = Sym.DONUT,
                collapsible = true,
                collapsed = "cat" in collapsed,
                onToggleCollapse = { Store.toggleStatsCollapse("cat") }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Donut(cats)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        if (cats.isEmpty()) EmptyState(
                            title = "이 달 지출이 없어요",
                            detail = "쓰기 시작하면 항목별로 나뉩니다",
                            compact = true
                        )
                        cats.take(6).forEach { (c, v) ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                Arrangement.SpaceBetween, Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(8.dp)
                                            .background(CatColor[c] ?: Sub)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(c.label, fontSize = T.Body, color = Ink)
                                }
                                Text(
                                    "${wonShort(v)} · ${if (scopedTotal > 0) v * 100 / scopedTotal else 0}%",
                                    fontSize = T.Caption, color = Sub
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Panel(
                title = if (shopMode == 0) "결제 금액순" else "자주 간 곳",
                icon = Sym.SHOP,
                collapsible = true,
                collapsed = "merchants" in collapsed,
                onToggleCollapse = { Store.toggleStatsCollapse("merchants") }
            ) {
                Row(Modifier.padding(bottom = 8.dp)) {
                    FilterChip(shopMode == 0, { shopMode = 0 }, { Text("결제 금액순", fontSize = T.Caption) })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(shopMode == 1, { shopMode = 1 }, { Text("자주 간 곳", fontSize = T.Caption) })
                }
                val top = if (shopMode == 0) Stats.topMerchants(scoped)
                else Stats.topMerchantsByCount(scoped)
                if (top.isEmpty()) EmptyState(
                    title = "아직 순위가 없어요",
                    detail = "두 건 이상 쌓이면 순위가 보입니다",
                    compact = true
                )
                else {
                    val max = top.first().second
                    top.forEachIndexed { i, (m, v) ->
                        RankBar(m, v, max, rank = i + 1, countMode = shopMode == 1)
                    }
                }
            }
        }


        item { Spacer(Modifier.height(24.dp)) }
    }
}
