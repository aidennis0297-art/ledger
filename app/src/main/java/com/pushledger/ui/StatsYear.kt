package com.pushledger.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pushledger.Stats
import com.pushledger.Store
import java.time.LocalDate
import com.pushledger.won
import com.pushledger.wonShort

/**
 * 연간 통계.
 *
 * 월간 화면과 축이 하나 다르다. 한 달을 들여다볼 때는 "어느 날, 어느 시간에" 가
 * 궁금하지만, 한 해를 볼 때 궁금한 건 "어느 달에 무너졌는가" 하나다. 그래서
 * 시간대·요일·히트맵은 여기 없다. 열두 칸짜리 막대 하나와 항목별 비중이면 된다.
 *
 * 월 파일 열두 개(비교용 전년까지 스물넷)를 읽으므로 [remember] 로 해가 바뀔 때만 읽는다.
 */
@Composable
fun YearStats() {
    val thisYear = LocalDate.now().year
    var year by remember { mutableIntStateOf(thisYear) }

    // 이번 달 거래가 바뀌면 올해 값도 같이 바뀐다. 해만 열쇠로 잡으면 방금 들어온
    // 결제가 연간 화면에는 안 보이고, 탭을 나갔다 와야 나타난다.
    val cur by Store.month.collectAsState()
    val list = remember(year, cur) { Store.readYear(year) }
    val prev = remember(year) { Store.readYear(year - 1) }

    val months = Stats.byMonth(list)
    val total = Stats.total(list)
    val prevTotal = Stats.total(prev)
    val income = Stats.incomeTotal(list)
    val invested = Stats.investTotal(list)
    val cats = Stats.byCat(list)

    // 아직 오지 않은 달로 나누면 평균이 실제보다 낮게 나온다. 기록이 있는 달만 센다.
    val livedMonths = months.count { it > 0L }.coerceAtLeast(1)
    val avg = total / livedMonths
    val peak = months.indices.maxByOrNull { months[it] } ?: 0

    LazyColumn(Modifier.fillMaxWidth()) {

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                Arrangement.Center, Alignment.CenterVertically
            ) {
                IconButton({ year-- }) { DotSym(Sym.LEFT, 18.dp, Sub) }
                Text("${year}년", fontSize = T.Title, fontWeight = FontWeight.SemiBold, color = Ink)
                IconButton({ if (year < thisYear) year++ }) {
                    DotSym(Sym.RIGHT, 18.dp, if (year < thisYear) Sub else Faint)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
                Text(won(total), fontSize = T.Display, fontWeight = FontWeight.Bold, color = Ink)
                if (prevTotal > 0) {
                    val diff = total - prevTotal
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DotSym(
                            if (diff >= 0) Sym.UPTREND else Sym.DOWNTREND,
                            15.dp, if (diff >= 0) Warn else Good
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${year - 1}년보다 ${wonShort(kotlin.math.abs(diff))}원 " +
                                if (diff >= 0) "더 씀" else "덜 씀",
                            fontSize = T.Body, color = Sub
                        )
                    }
                }
            }
        }

        item {
            Panel("월별", Sym.BARS) {
                BarChart(
                    values = months,
                    labels = (1..12).map { "$it" },
                    height = 150.dp,
                    mark = if (year == thisYear) LocalDate.now().monthValue - 1 else -1,
                    budgetLine = 0L,
                    highlightMax = true
                )
                Spacer(Modifier.height(4.dp))
                Text("단위 만원 · 가장 많이 쓴 달 강조", fontSize = T.Caption, color = Sub)
            }
        }

        item {
            Panel("한 해 요약", Sym.DOC) {
                SumRow("월 평균", won(avg))
                SumRow("가장 많이 쓴 달", "${peak + 1}월 · ${won(months[peak])}")
                if (income > 0) SumRow("추가 수입", won(income), Income)
                if (invested > 0) SumRow("투자·저축", won(invested), Income)
                Spacer(Modifier.height(4.dp))
                Text(
                    "기록이 있는 ${livedMonths}개월로 나눈 값입니다",
                    fontSize = T.Caption, color = Sub
                )
            }
        }

        item {
            Panel("항목별", Sym.DONUT) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Donut(cats)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        if (cats.isEmpty()) EmptyState(
                            title = "이 해 지출이 없어요",
                            detail = "쓰기 시작하면 항목별로 나뉩니다",
                            compact = true
                        )
                        cats.take(7).forEach { (c, v) ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                Arrangement.SpaceBetween, Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).background(CatColor[c] ?: Sub))
                                    Spacer(Modifier.width(6.dp))
                                    Text(c.label, fontSize = T.Body, color = Ink)
                                }
                                Text(
                                    "${wonShort(v)} · ${if (total > 0) v * 100 / total else 0}%",
                                    fontSize = T.Caption, color = Sub
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Panel("올해 많이 쓴 곳", Sym.SHOP) {
                val top = Stats.topMerchants(list)
                if (top.isEmpty()) EmptyState(
                    title = "아직 순위가 없어요",
                    detail = "결제가 쌓이면 순위가 보입니다",
                    compact = true
                )
                else {
                    val max = top.first().second
                    top.forEachIndexed { i, (m, v) -> RankBar(m, v, max, rank = i + 1) }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SumRow(label: String, value: String, color: androidx.compose.ui.graphics.Color = Ink) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        Arrangement.SpaceBetween, Alignment.CenterVertically
    ) {
        Text(label, fontSize = T.Body, color = Sub)
        Text(value, fontSize = T.Title, fontWeight = FontWeight.SemiBold, color = color)
    }
}
