package com.pushledger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pushledger.Cat
import com.pushledger.Raw
import com.pushledger.Stats
import com.pushledger.Store
import com.pushledger.Txn
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import com.pushledger.won
import com.pushledger.wonShort

@Composable
fun HomeScreen(goInbox: () -> Unit, goStats: () -> Unit, goSettings: () -> Unit) {
    val month by Store.month.collectAsState()
    val cfg by Store.config.collectAsState()
    val inbox by Store.inbox.collectAsState()

    val ym = YearMonth.now()
    val today = LocalDate.now()
    val spent = Stats.total(month)
    // 저축 목표를 뺀 "정말 쓸 수 있는 돈" 으로 볼지, 예산 전체로 볼지 고를 수 있다.
    val savingGoal = Stats.investGoal(cfg)
    val remain = Stats.monthRemain(cfg, month)
    val active = Stats.active(month)

    val todaySpent = active.filter { it.at.startsWith(today.toString()) }.sumOf { it.amount }
    val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val weekSpent = active.filter {
        LocalDateTime.parse(it.at, Store.ts).toLocalDate() >= weekStart
    }.sumOf { it.amount }

    // 켠 앱에서 온 미처리만 센다. 안 켠 앱의 제안까지 홈 배너에 띄우면,
    // 정작 놓친 결제가 그 사이에 묻힌다.
    val pending = inbox.filter { it.state == Raw.PENDING }
    val days = Stats.byDay(month, ym)

    LazyColumn(Modifier.fillMaxWidth()) {

        item {
          Box(Modifier.fillMaxWidth()) {
            // 종이의 결처럼 아주 옅게. 큰 숫자 뒤에서만 보이고 나머지 화면은 깨끗하게 둔다.
            Canvas(Modifier.matchParentSize()) { dotField(Faint.copy(alpha = 0.7f)) }
            Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 4.dp)) {
                // 설정과 알림함은 매일 볼 것이 아니라 필요할 때만 여는 서랍이다.
                // 아래 탭 다섯 칸을 거기에 내주면 정작 매일 보는 화면이 밀린다.
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("${ym.monthValue}월", fontSize = T.Body, color = Sub)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            Box(
                                Modifier.clip(RoundedCornerShape(10.dp)).clickable { goInbox() }
                                    .padding(7.dp)
                            ) { DotSym(Sym.INBOX, 19.dp, Sub) }
                            if (pending.isNotEmpty()) Box(
                                Modifier.align(Alignment.TopEnd).padding(3.dp)
                                    .size(7.dp).background(Warn)
                            )
                        }
                        Box(
                            Modifier.clip(RoundedCornerShape(10.dp)).clickable { goSettings() }
                                .padding(7.dp)
                        ) { DotSym(Sym.GRID, 19.dp, Sub) }
                    }
                }
                Spacer(Modifier.height(2.dp))
                if (cfg.monthlyBudget > 0) {
                    Text(
                        if (remain >= 0) "${wonShort(remain)}원 남음" else "${wonShort(-remain)}원 초과",
                        fontSize = T.Display, fontWeight = FontWeight.Bold,
                        color = if (remain >= 0) Ink else Warn
                    )
                    Spacer(Modifier.height(10.dp))
                    // 저축 목표는 오른쪽 끝에서부터 초록으로 잠가 둔다.
                    // 왼쪽에서 자라는 지출이 그 초록에 닿으면 저축을 깨야 하는 달이다.
                    // 알갱이 한 줄이다. 여기서 도트를 여러 줄로 쌓으면 화면에서
                    // 제일 먼저 눈이 가는 자리를 띠가 통째로 차지한다.
                    BudgetBar(spent, cfg.monthlyBudget, reserved = savingGoal, animate = true)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text("${won(spent)} / ${won(cfg.monthlyBudget)}", fontSize = T.Body, color = Sub)
                        if (savingGoal > 0L) {
                            Text(
                                if (cfg.budgetExcludesSaving) "저축 제외됨" else "저축 포함",
                                fontSize = T.Caption,
                                color = if (cfg.budgetExcludesSaving) Good else Sub,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        Store.saveConfig(
                                            cfg.copy(budgetExcludesSaving = !cfg.budgetExcludesSaving)
                                        )
                                    }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                } else {
                    Text(won(spent), fontSize = T.Display, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(Modifier.height(4.dp))
                    Text("예산 탭에서 이번 달 예산을 정해 보세요", fontSize = T.Body, color = Sub)
                }
            }
          }
        }

        // 인식 못 한 알림이 있으면 가장 먼저 눈에 띄어야 한다. 놓친 지출이 여기 쌓인다.
        if (pending.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Card)
                        .clickable { goInbox() }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DotSym(Sym.SPARK, 20.dp, Accent)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("인식하지 못한 알림 ${pending.size}건", fontSize = T.Title,
                            fontWeight = FontWeight.SemiBold, color = Ink)
                        Text("알림함에서 AI 로 읽어 오세요", fontSize = T.Body, color = Sub)
                    }
                    DotSym(Sym.RIGHT, 18.dp, Sub)
                }
            }
        }

        val daily = Stats.dailyBudget(cfg, month, today)

        if (daily.overBudgetCats.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Card)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(6.dp).background(Warn))
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("예산 초과", fontSize = T.Body, fontWeight = FontWeight.Bold, color = Warn)
                        Text(
                            daily.overBudgetCats.joinToString(" · ") { "${it.first.label} +${wonShort(it.second)}" },
                            fontSize = T.Body, color = Ink
                        )
                    }
                }
            }
        }

        item {
            // 오른쪽 두 타일을 합친 높이에 왼쪽 카드를 맞춘다.
            // IntrinsicSize.Min 으로 줄 높이를 오른쪽 열에 맞추고, 왼쪽은 그 높이를 꽉 채운다.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                    .height(IntrinsicSize.Min)
            ) {
                Column(
                    Modifier.weight(1.1f).fillMaxHeight().clip(RoundedCornerShape(16.dp))
                        .background(Card)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text("오늘 가용 예산", fontSize = T.Body, color = Sub)
                        Text(
                            if (daily.isSuccess) "여유" else "초과",
                            fontSize = T.Caption, fontWeight = FontWeight.Bold,
                            color = if (daily.isSuccess) Accent else Warn
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(won(daily.dailyLimit), fontSize = T.Amount, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (daily.isSuccess) "잔여 ${won(daily.remaining)}" else "${won(-daily.remaining)} 초과",
                        fontSize = T.Caption, color = if (daily.isSuccess) Accent else Warn, fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.width(10.dp))
                if (daily.incomeTotal > 0) {
                    Column(Modifier.weight(0.9f).fillMaxHeight()) {
                        Tile("오늘 지출", daily.todaySpent, Modifier.fillMaxWidth().weight(1f))
                        Spacer(Modifier.height(6.dp))
                        Tile("추가 수입", daily.incomeTotal, Modifier.fillMaxWidth().weight(1f),
                            valueColor = Income)
                    }
                } else {
                    Tile("오늘 지출", daily.todaySpent, Modifier.weight(0.9f).fillMaxHeight())
                }
            }
        }



        item {
            Panel("이번 달 흐름", Sym.LINE, "자세히", goStats) {
                BarChart(
                    values = days,
                    labels = listOf("1일", "${ym.lengthOfMonth() / 2}일", "${ym.lengthOfMonth()}일"),
                    mark = today.dayOfMonth - 1,
                    height = 120.dp
                )
            }
        }

        if (cfg.goal != null && cfg.monthlyBudget > 0) {
            item {
                val spare = Stats.spare(cfg, month)
                val target = cfg.goal!!.target
                Panel(cfg.goal!!.name, Sym.FLAG) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text(won(spare.coerceAtLeast(0)), fontSize = T.Amount,
                            fontWeight = FontWeight.Bold, color = Ink)
                        Text("목표 ${wonShort(target)}원", fontSize = T.Body, color = Sub)
                    }
                    Spacer(Modifier.height(8.dp))
                    BudgetBar(spare.coerceAtLeast(0), target)
                }
            }
        }

        if (cfg.fixed.isNotEmpty()) {
            item {
                Panel("고정 지출", Sym.CLOCK) {
                    cfg.fixed.sortedBy { dday(it.day) }.forEach { f ->
                        val d = dday(f.day)
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(5.dp)
                                    .background(if (d <= 3) Warn else Faint))
                                Spacer(Modifier.width(8.dp))
                                Text(f.name, fontSize = T.Title, color = Ink)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (d == 0) "오늘" else "D-$d", fontSize = T.Body,
                                    color = if (d <= 3) Warn else Sub)
                                Spacer(Modifier.width(10.dp))
                                Text(won(f.amount), fontSize = T.Title, color = Ink)
                            }
                        }
                    }
                }
            }
        }

        item {
            Panel("최근 내역", Sym.CAL) {
                val recent = month.sortedByDescending { it.at }.take(5)
                if (recent.isEmpty()) EmptyState(
                    title = "아직 기록이 없어요",
                    detail = "결제 알림이 오면 여기에 쌓입니다",
                    compact = true
                )
                else recent.forEach { TxnRow(it) }
            }
        }

        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun Tile(label: String, value: Long, modifier: Modifier = Modifier, valueColor: Color = Ink) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp)
    ) {
        Text(label, fontSize = T.Body, color = Sub)
        Spacer(Modifier.height(4.dp))
        Text(won(value), fontSize = T.Amount, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

/** 이번 달 결제일이 지났으면 다음 달로 넘겨서 남은 날을 센다. */
private fun dday(day: Int): Int {
    val today = LocalDate.now()
    val safe = day.coerceIn(1, 28)
    var next = today.withDayOfMonth(safe)
    if (next.isBefore(today)) next = next.plusMonths(1)
    return (next.toEpochDay() - today.toEpochDay()).toInt()
}

/** 홈과 내역이 같은 줄 모양을 쓴다. 취소된 거래는 여기서 취소선으로 남는다. */
@Composable
fun TxnRow(t: Txn, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 7.dp),
        Arrangement.SpaceBetween, Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            CatBadge(t.cat, dim = t.canceled)
            Column(Modifier.padding(start = 10.dp)) {
                Text(
                    t.merchant.ifBlank { "이름 없음" },
                    fontSize = T.Title,
                    color = if (t.canceled) Sub else Ink,
                    textDecoration = if (t.canceled)
                        androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                )
                val sub = if (t.subCategory.isNotBlank()) " · ${t.subCategory}" else ""
                val mark = when {
                    t.canceled -> " · 취소됨"
                    t.isFixedPlan -> " · 아직 안 나감"
                    else -> ""
                }
                Text(
                    "${t.at.substring(11, 16)} · ${t.cat.label}$sub$mark",
                    fontSize = T.Caption, color = Sub
                )
            }
        }
        val isInvest = t.isInvestment
        val isIncome = t.cat == Cat.INCOME
        val isFixed = t.by == "fixed"
        val amtColor = when {
            t.canceled -> Sub
            isIncome -> Income
            isInvest -> Income
            isFixed -> Sub
            else -> Ink
        }
        val prefix = when {
            t.canceled -> ""
            isIncome -> "+ "
            isInvest -> "투자 "
            t.isFixedPlan -> "예정 "
            isFixed -> "고정 "
            else -> ""
        }
        Text(
            prefix + won(t.amount),
            fontSize = T.Title,
            fontWeight = FontWeight.Medium,
            color = amtColor,
            textDecoration = if (t.canceled)
                androidx.compose.ui.text.style.TextDecoration.LineThrough else null
        )
    }
}


