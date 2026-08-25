package com.pushledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pushledger.Cat
import com.pushledger.Config
import com.pushledger.Fixed
import com.pushledger.Goal
import com.pushledger.Nvidia
import com.pushledger.Stats
import com.pushledger.StatusNotifier
import com.pushledger.Store
import java.time.LocalDate
import java.time.YearMonth

/**
 * 예산 화면. 여기에는 예산만 남긴다.
 *
 * 상태창 알림·데이터 내보내기·AI 키·예시 데이터는 전부 설정으로 옮겼다. 접어 둔
 * 카드 네 장이 예산 아래 길게 매달려 있으니, 정작 예산을 고치러 와서도 어디까지가
 * 예산인지 한눈에 보이지 않았다.
 * 1. 이번 달 총 예산 및 고정지출/추가수입 요약
 * 2. 항목별 예산
 * 3. 투자·저축 목표
 * 4. 고정지출 관리
 */
@Composable
fun BudgetScreen() {
    val cfg by Store.config.collectAsState()
    val ym = YearMonth.now()
    val month by Store.month.collectAsState()
    val ctx = LocalContext.current

    var addFixed by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Fixed?>(null) }

    val spent = Stats.total(month)
    val income = Stats.incomeTotal(month)
    val catSpent = remember(month) { Stats.byCat(month).toMap() }
    val overBudgets = Stats.overBudgetCats(cfg, month)
    val fixedSum = Stats.fixedTotal(cfg)
    val allocatedCatSum = Stats.allocatedCatBudgetTotal(cfg)
    val unallocated = Stats.unallocatedBudget(cfg)

    // 반복 결제 감지. 넉 달이면 세 달 연속을 세면서도 파일은 네 개만 읽는다.
    // 이번 달 거래가 바뀔 때만 다시 읽는다 — 화면이 다시 그려질 때마다 읽으면
    // 예산 칸에 숫자를 칠 때마다 월 파일 네 개를 읽게 된다.
    val recentMonths = remember(month) { Store.recentMonths(4) }
    val recurring = remember(recentMonths, cfg.fixed, cfg.ignoredRecurring) {
        Stats.recurring(recentMonths, cfg)
    }

    LazyColumn(Modifier.fillMaxWidth()) {

        // 1. 이번 달 총 예산
        item {
            Panel("이번 달 예산", Sym.WALLET) {
                MoneyField(cfg.monthlyBudget, "월 총 예산") {
                    Store.saveConfig(cfg.copy(monthlyBudget = it))
                }
                Spacer(Modifier.height(10.dp))
                BudgetBar(
                    spent, cfg.monthlyBudget + income,
                    reserved = Stats.investGoal(cfg), animate = true
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text("소비 ${won(spent)}", fontSize = T.Body, color = Sub)
                    if (income > 0) {
                        Text("추가 수입 +${won(income)}", fontSize = T.Body, color = Income, fontWeight = FontWeight.Medium)
                    }
                    Text("고정 ${won(fixedSum)}", fontSize = T.Body, color = Sub)
                }
            }
        }

        // 예산 초과 항목 배너
        if (overBudgets.isNotEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Card)
                        .padding(12.dp)
                ) {
                    Text("예산 초과 ${overBudgets.size}개", fontSize = T.Body, fontWeight = FontWeight.Bold, color = Warn)
                    Spacer(Modifier.height(4.dp))
                    overBudgets.forEach { (c, over) ->
                        val b = cfg.catBudget[c.name] ?: 0L
                        val s = catSpent[c] ?: 0L
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), Arrangement.SpaceBetween) {
                            Text("${c.label} · ${won(s)} / ${won(b)}", fontSize = T.Body, color = Ink)
                            Text("${won(over)} 초과", fontSize = T.Body, color = Warn, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 2. 항목별 예산 (정렬 및 실시간 차감 계산)
        item {
            Panel("항목별 예산", Sym.PIE) {
                // 상단: 배정 현황 및 미배정 자유 예산 요약 카드
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (unallocated >= 0) Card else Card)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text("항목 배정 합계", fontSize = T.Body, color = Sub)
                        Text(won(allocatedCatSum), fontSize = T.Body, fontWeight = FontWeight.SemiBold, color = Ink)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text(
                            if (unallocated >= 0) "미배정 자유 예산" else "배정 초과 (조정 필요)",
                            fontSize = T.Body,
                            fontWeight = FontWeight.Bold,
                            color = if (unallocated >= 0) Accent else Warn
                        )
                        Text(
                            if (unallocated >= 0) won(unallocated) else "-${won(-unallocated)}",
                            fontSize = T.Body,
                            fontWeight = FontWeight.Bold,
                            color = if (unallocated >= 0) Accent else Warn
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 각 카테고리별 정연한 예산 행
                // 고정지출은 여기 없다. 아래 고정지출 카드에서 항목을 등록하면 그 합이
                // 곧 고정지출 예산이 된다. 여기서 또 배정하면 같은 돈이 두 번 깎인다.
                val targetCats = listOf(
                    Cat.FOOD to "식당 · 카페 · 배달 · 마트 · 반찬",
                    Cat.LIVING to "생필품 · 병원/약국 · 미용",
                    Cat.LEISURE to "문화/컨텐츠 · 교통/차량 · 취미 · 쇼핑",
                    Cat.FINANCE to "대출이자 · 보험료 · 수수료"
                )

                // 고정지출은 배정 대신 계획과 실제를 나란히 보여 준다.
                val (fixedPlan, fixedDone) = Stats.fixedProgress(cfg, month)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Card).padding(10.dp),
                    Arrangement.SpaceBetween, Alignment.CenterVertically
                ) {
                    Row(Modifier.weight(1.1f), verticalAlignment = Alignment.CenterVertically) {
                        CatBadge(Cat.HOUSING)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(Cat.HOUSING.label, fontSize = T.Title,
                                fontWeight = FontWeight.Bold, color = Ink)
                            Text("아래에서 등록한 항목의 합", fontSize = T.Caption, color = Sub, maxLines = 1)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(won(fixedPlan), fontSize = T.Title,
                            fontWeight = FontWeight.Bold, color = Ink)
                        Text(
                            if (fixedDone > 0) "실제 ${won(fixedDone)} 나감" else "아직 안 나감",
                            fontSize = T.Caption,
                            color = if (fixedDone >= fixedPlan && fixedPlan > 0) Good else Sub
                        )
                    }
                }

                targetCats.forEach { (c, subHint) ->
                    val budget = cfg.catBudget[c.name] ?: 0L
                    val s = catSpent[c] ?: 0L
                    val isOver = budget > 0 && s > budget

                    Column(
                        Modifier.fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Card)
                            .padding(10.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            Arrangement.SpaceBetween,
                            Alignment.CenterVertically
                        ) {
                            Row(Modifier.weight(1.1f), verticalAlignment = Alignment.CenterVertically) {
                                CatBadge(c)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(c.label, fontSize = T.Title, fontWeight = FontWeight.Bold, color = Ink)
                                        if (isOver) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("초과", fontSize = T.Caption, fontWeight = FontWeight.Bold, color = Warn)
                                        }
                                    }
                                    Text(subHint, fontSize = T.Caption, color = Sub, maxLines = 1)
                                }
                            }

                            Box(Modifier.width(104.dp)) {
                                MoneyField(budget, "예산", compact = true) { newBudget ->
                                    Store.saveConfig(cfg.copy(catBudget = cfg.catBudget + (c.name to newBudget)))
                                }
                            }
                        }

                        if (budget > 0) {
                            Spacer(Modifier.height(6.dp))
                            BudgetBar(s, budget)
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Text("지출 ${won(s)}", fontSize = T.Caption, color = Sub)
                                Text(
                                    if (isOver) "+${won(s - budget)} 초과" else "잔여 ${won(budget - s)}",
                                    fontSize = T.Caption,
                                    color = if (isOver) Warn else Sub,
                                    fontWeight = if (isOver) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. 투자 · 저축 (자산 형성 목표)
        item {
            Panel("투자 · 저축 목표", Sym.COIN) {
                Text("자산 형성은 소비 지출과 따로 집계됩니다", fontSize = T.Caption, color = Sub)
                Spacer(Modifier.height(8.dp))
                val investGoal = cfg.catBudget["INVEST_GOAL"] ?: cfg.catBudget[Cat.FINANCE.name + "_INVEST"] ?: 0L
                MoneyField(investGoal, "월 투자·저축 목표") {
                    Store.saveConfig(cfg.copy(catBudget = cfg.catBudget + ("INVEST_GOAL" to it)))
                }
                Spacer(Modifier.height(10.dp))
                val invested = Stats.investTotal(month)
                BudgetBar(invested, investGoal)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text("이번 달 투자·저축 ${won(invested)}", fontSize = T.Body, color = Income, fontWeight = FontWeight.SemiBold)
                    if (investGoal > 0) {
                        Text("목표 ${won(investGoal)}", fontSize = T.Body, color = Sub)
                    }
                }
            }
        }

        // 4. 반복 결제 권유. 고정지출 카드 바로 위에 둔다. 여기서 등록하면 아래 목록에 붙는다.
        if (recurring.isNotEmpty()) {
            item {
                Panel("반복 결제 ${recurring.size}건 발견", Sym.SPARK) {
                    Text(
                        "매달 비슷한 금액이 나가고 있어요. 고정지출로 올리면 하루 예산이 그만큼 정확해집니다.",
                        fontSize = T.Body, color = Sub
                    )
                    Spacer(Modifier.height(4.dp))
                    recurring.forEach { r ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.merchant, fontSize = T.Title, fontWeight = FontWeight.Medium, color = Ink)
                                    Text(
                                        "최근 ${r.months}개월 · 매월 ${r.day}일쯤 · ${r.cat.label}",
                                        fontSize = T.Caption, color = Sub
                                    )
                                }
                                Text(won(r.amount), fontSize = T.Title, fontWeight = FontWeight.SemiBold, color = Ink)
                            }
                            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                                TextButton(onClick = {
                                    Store.saveConfig(
                                        cfg.copy(ignoredRecurring = cfg.ignoredRecurring + r.merchant)
                                    )
                                }) { Text("안 볼래요", fontSize = T.Body, color = Sub) }
                                TextButton(onClick = {
                                    Store.addFixed(
                                        Fixed(
                                            id = Store.newId(), name = r.merchant,
                                            amount = r.amount, day = r.day, category = r.cat.name
                                        )
                                    )
                                }) {
                                    Text("고정지출로 등록", fontSize = T.Body, color = Accent,
                                        fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. 고정지출
        item {
            Panel("고정지출", Sym.CLOCK, action = "+ 추가", onAction = { addFixed = true }) {
                if (cfg.fixed.isEmpty()) {
                    Text("월세·보험료·구독처럼 매달 나가는 돈", fontSize = T.Body, color = Sub)
                } else {
                    cfg.fixed.forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            Arrangement.SpaceBetween, Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(f.name, fontSize = T.Title, fontWeight = FontWeight.Medium, color = Ink)
                                Text("매월 ${f.day}일 · ${Cat.of(f.category).label}", fontSize = T.Caption, color = Sub)
                            }
                            Text(won(f.amount), fontSize = T.Title, fontWeight = FontWeight.SemiBold, color = Ink)
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = { deleteTarget = f }, modifier = Modifier.size(28.dp)) {
                                DotSym(Sym.TRASH, 15.dp, Sub)
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(30.dp)) }
    }

    if (addFixed) {
        FixedDialog { addFixed = false }
    }

    if (deleteTarget != null) {
        val target = deleteTarget!!
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("고정지출 삭제") },
            text = { Text("'${target.name}' (${won(target.amount)}) 항목을 고정지출에서 삭제하시겠습니까?") },
            confirmButton = {
                TextButton(onClick = {
                    Store.saveConfig(cfg.copy(fixed = cfg.fixed.filterNot { it.id == target.id }))
                    deleteTarget = null
                }) {
                    Text("삭제", color = Warn, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("취소", color = Sub)
                }
            }
        )
    }
}

/**
 * 예산 입력. 만원 단위로만 받는다.
 * 원/만원 토글이 있으면 지금 어느 단위로 적고 있는지 매번 확인해야 하고,
 * 예산을 1원 단위로 적을 일도 없다. 저장은 원 단위로 한다.
 */
@Composable
fun MoneyField(
    value: Long,
    label: String,
    compact: Boolean = false,
    onSave: (Long) -> Unit
) {
    var text by remember { mutableStateOf(if (value == 0L) "" else (value / 10_000L).toString()) }
    var focused by remember { mutableStateOf(false) }

    // 글자를 칠 때마다 저장하면 설정이 바뀌고, 그 바람에 화면이 다시 그려지면서
    // 입력창이 닫히거나 커서가 튄다. 편집이 끝난 순간에만 저장한다.
    fun commit() = onSave((text.toLongOrNull() ?: 0L) * 10_000L)

    // 편집 중이 아닐 때만 바깥 값을 따라간다.
    LaunchedEffect(value, focused) {
        if (!focused) text = if (value == 0L) "" else (value / 10_000L).toString()
    }

    val v = (text.toLongOrNull() ?: 0L) * 10_000L
    Field(
        value = text,
        onValueChange = { input -> text = input.filter { it.isDigit() }.take(6) },
        label = label,
        placeholder = "0",
        suffix = "만원",
        big = !compact,
        hint = if (!compact && v > 0L) wonKo(v) else "",
        hintColor = Accent,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        // 단위가 붙은 숫자는 오른쪽 끝에 붙어 있어야 한다. 왼쪽 정렬이면 자릿수가
        // 늘 때마다 숫자와 단위 사이가 벌어져서 둘이 한 덩어리로 읽히지 않는다.
        alignEnd = true,
        modifier = Modifier.fillMaxWidth().onFocusChanged { st ->
            if (focused && !st.isFocused) commit()
            focused = st.isFocused
        }
    )
}

@Composable
private fun FixedDialog(onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("1") }
    var cat by remember { mutableStateOf(Cat.HOUSING) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("고정지출 추가") },
        text = {
            Column {
                Field(
                    value = name, onValueChange = { name = it },
                    label = "이름",
                    placeholder = "예: 월세, 넷플릭스",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                val fixedVal = amount.toLongOrNull() ?: 0L
                Field(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() } },
                    label = "금액",
                    placeholder = "0",
                    suffix = "원",
                    big = true,
                    hint = if (fixedVal > 0L) wonKo(fixedVal) else "",
                    hintColor = Accent,
                    visualTransformation = ThousandsTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Field(
                    value = day,
                    onValueChange = { day = it.filter { c -> c.isDigit() }.take(2) },
                    label = "매월 결제일",
                    placeholder = "1",
                    suffix = "일",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val a = amount.toLongOrNull() ?: 0L
                    val d = day.toIntOrNull()?.coerceIn(1, 31) ?: 1
                    if (name.isNotBlank() && a > 0) {
                        Store.addFixed(
                            Fixed(
                                id = Store.newId(),
                                name = name.trim(),
                                amount = a,
                                day = d,
                                category = cat.name
                            )
                        )
                        onClose()
                    }
                }
            ) { Text("추가", fontWeight = FontWeight.Bold, color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("취소", color = Sub) }
        }
    )
}
