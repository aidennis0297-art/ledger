package com.pushledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pushledger.Cat
import com.pushledger.Parser
import com.pushledger.Stats
import com.pushledger.Store
import com.pushledger.Txn
import java.time.LocalDateTime
import java.time.YearMonth

@Composable
fun LedgerScreen() {
    var ym by remember { mutableStateOf(YearMonth.now()) }
    var query by remember { mutableStateOf("") }
    var catFilter by remember { mutableStateOf<Cat?>(null) }
    var editing by remember { mutableStateOf<Txn?>(null) }
    var adding by remember { mutableStateOf(false) }

    val cur by Store.month.collectAsState()
    // ponytail: 지난 달은 그때그때 파일에서 읽는다. 한 파일이 수백 건이라 체감되지 않는다.
    val list = if (ym == YearMonth.now()) cur else remember(ym) { Store.readMonth(ym) }

    val shown = list
        // 가맹점 이름만 뒤지면 "메모에 적어 둔 것"이나 "3만원짜리" 를 못 찾는다.
        .filter { t ->
            query.isBlank() ||
                t.merchant.contains(query, true) ||
                t.memo.contains(query, true) ||
                t.cat.label.contains(query, true) ||
                t.subCategory.contains(query, true) ||
                t.amount.toString().contains(query.filter { c -> c.isDigit() }.ifBlank { "\u0000" })
        }
        .filter { catFilter == null || it.cat == catFilter }
        .sortedByDescending { it.at }
    val groups = shown.groupBy { it.at.substring(0, 10) }

    Box(Modifier.fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxWidth()) {

            item {
                // 월 이동·합계·검색·필터는 한 덩어리다. 맨바닥에 얹어 두면 아래 목록과
                // 구분되지 않아, 내역 탭만 혼자 다른 앱처럼 보인다.
                Panel("") {
                    Row(
                        Modifier.fillMaxWidth(),
                        Arrangement.SpaceBetween, Alignment.CenterVertically
                    ) {
                        IconButton({ ym = ym.minusMonths(1) }) {
                            DotSym(Sym.LEFT, 18.dp, Sub)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${ym.year}년 ${ym.monthValue}월", fontSize = T.Title,
                                fontWeight = FontWeight.SemiBold, color = Ink
                            )
                            Text(
                                won(Stats.total(shown)), fontSize = T.Amount,
                                fontWeight = FontWeight.Bold, color = Ink
                            )
                        }
                        IconButton(
                            onClick = { if (ym < YearMonth.now()) ym = ym.plusMonths(1) }
                        ) {
                            DotSym(Sym.RIGHT, 18.dp, if (ym < YearMonth.now()) Sub else Faint)
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Field(
                        value = query, onValueChange = { query = it },
                        label = "",
                        placeholder = "가맹점 · 메모 · 금액 검색",
                        leading = {
                            DotSym(Sym.SEARCH, 17.dp, Sub)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    // 칩은 늘 같은 자리에 있어야 한다. 고른 것만 남기고 나머지를 치우면
                    // 다음에 무엇을 고르려 할 때 위치를 처음부터 다시 찾아야 한다.
                    // 목록에서 다른 항목이 빠지는 것과 칩이 사라지는 것은 다른 문제다.
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        Cat.entries.forEach { c ->
                            val on = catFilter == c
                            val tone = CatColor[c] ?: Accent
                            DotBurst(key = if (on) c else null, color = tone) {
                                FilterChip(
                                    selected = on,
                                    onClick = { catFilter = if (on) null else c },
                                    leadingIcon = {
                                        DotIcon(c, size = 12.dp, color = if (on) tone else Sub)
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = tone.copy(alpha = 0.16f),
                                        selectedLabelColor = tone
                                    ),
                                    label = {
                                        Text(
                                            c.label, fontSize = T.Caption,
                                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
            }

            if (groups.isEmpty()) {
                item {
                    EmptyState(
                        title = if (query.isBlank()) "이 달에는 기록이 없어요" else "찾는 내역이 없어요",
                        detail = if (query.isBlank()) "결제 알림이 오면 자동으로 쌓입니다"
                        else "가맹점·메모·금액으로 찾을 수 있어요",
                        actionLabel = if (query.isBlank()) "직접 추가하기" else "",
                        onAction = if (query.isBlank()) ({ adding = true }) else null
                    )
                }
            }

            groups.forEach { (date, txns) ->
                item(key = date) {
                    Column(Modifier.fillMaxWidth().background(Card)) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 5.dp),
                            Arrangement.SpaceBetween
                        ) {
                            Text(dayLabel(date), fontSize = T.Body,
                                fontWeight = FontWeight.SemiBold, color = Sub)
                            // 하루 소계는 위의 달 합계와 같은 기준으로 센다. 예전에는 여기서만
                            // 고정지출·투자·수입까지 싸잡아 더해서, 날짜 소계를 다 합치면
                            // 달 합계보다 커졌다. 같은 돈이 두 군데서 세어진 것처럼 보인 이유다.
                            val dayFixed = txns.filter { !it.canceled && it.by == "fixed" }
                                .sumOf { it.amount }
                            Text(
                                won(Stats.total(txns)) +
                                    if (dayFixed > 0) " · 고정 ${wonShort(dayFixed)}" else "",
                                fontSize = T.Body, color = Sub
                            )
                        }
                        // 날짜와 날짜 사이를 가르는 얇고 연한 선. 줄이 길어져도 하루 단위가 보인다.
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                .height(1.dp).background(Faint)
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
                items(txns, key = { it.id }) { t ->
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        TxnRow(t) { editing = t }
                    }
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }

        FloatingActionButton(
            onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = Accent, contentColor = Color.White
        ) { DotSym(Sym.PLUS, 24.dp, Color.White) }
    }

    editing?.let { t -> TxnDialog(t, onClose = { editing = null }) }
    if (adding) {
        TxnDialog(
            Txn(id = Store.newId(), amount = 0, merchant = "",
                at = LocalDateTime.now().format(Store.ts), by = "manual"),
            isNew = true, onClose = { adding = false }
        )
    }
}

/**
 * 거래 편집 및 직접 추가.
 * 대분류(식비, 교통, 쇼핑, 생활, 의료·문화, 금융, 투자, 기타)를 선택하면
 * 아래에 해당 카테고리의 세부 항목(카페, 배달, 주유, 대출이자, 주식/ETF 등)이 즉시 펼쳐진다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TxnDialog(txn: Txn, isNew: Boolean = false, onClose: () -> Unit) {
    var merchant by remember { mutableStateOf(txn.merchant) }
    var amount by remember { mutableStateOf(if (txn.amount == 0L) "" else txn.amount.toString()) }
    var cat by remember { mutableStateOf(txn.cat) }
    var subCat by remember {
        mutableStateOf(txn.subCategory.ifBlank { txn.cat.subs.firstOrNull().orEmpty() })
    }
    var memo by remember { mutableStateOf(txn.memo) }
    // 어제 쓴 걸 오늘 적는 일이 흔하다. 날짜를 못 고치면 통계가 그날로 밀린다.
    var date by remember { mutableStateOf(LocalDateTime.parse(txn.at, Store.ts).toLocalDate()) }
    var showPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Color.White,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CatBadge(cat)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        if (isNew) "직접 추가" else "내역 수정",
                        fontSize = T.Title, fontWeight = FontWeight.SemiBold, color = Ink
                    )
                    Text(
                        cat.label + if (subCat.isNotBlank()) " · $subCat" else "",
                        fontSize = T.Caption, color = CatColor[cat] ?: Sub,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        text = {
            Column {
                Field(
                    value = merchant,
                    onValueChange = {
                        merchant = it
                        if (isNew && it.length >= 2) {
                            val guessed = Parser.guessCat(it)
                            cat = guessed
                            subCat = Parser.guessSubCat(it, guessed)
                        }
                    },
                    label = "가맹점 · 내용",
                    placeholder = "예: 스타벅스",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                // 0 을 몇 개 눌렀는지 세지 않아도 되게 한글로 되읽어 준다.
                // 바뀌는 건 화면에 보이는 글자뿐이고, 저장은 저장 버튼에서만 한다.
                val amountVal = amount.toLongOrNull() ?: 0L
                Field(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() }.take(9) },
                    label = if (cat == Cat.FINANCE && subCat == "투자/저축") "투자·저축 금액" else "금액",
                    placeholder = "0",
                    suffix = "원",
                    big = true,
                    hint = if (amountVal > 0L) wonKo(amountVal) else "숫자만 입력하세요",
                    hintColor = if (amountVal > 0L) Accent else Sub,
                    visualTransformation = ThousandsTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("날짜", fontSize = T.Caption, color = Sub, modifier = Modifier.width(34.dp))
                    AssistChip(
                        onClick = { showPicker = true },
                        label = { Text("${date.monthValue}월 ${date.dayOfMonth}일", fontSize = T.Body) }
                    )
                    Spacer(Modifier.width(6.dp))
                    if (date != java.time.LocalDate.now()) {
                        TextButton(onClick = { date = java.time.LocalDate.now() }) {
                            Text("오늘로", fontSize = T.Caption, color = Accent)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("대분류", fontSize = T.Caption, color = Sub)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Cat.entries.forEach { c ->
                        val on = cat == c
                        val tone = CatColor[c] ?: Accent
                        DotBurst(key = if (on) c else null, color = tone) {
                            FilterChip(
                                selected = on,
                                onClick = {
                                    cat = c
                                    subCat = c.subs.firstOrNull().orEmpty()
                                },
                                leadingIcon = {
                                    DotIcon(c, size = 13.dp, color = if (on) tone else Sub)
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = tone.copy(alpha = 0.16f),
                                    selectedLabelColor = tone
                                ),
                                label = {
                                    Text(
                                        c.label, fontSize = T.Body,
                                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                }
                if (cat.subs.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("세부 항목", fontSize = T.Caption, color = Sub)
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        cat.subs.forEach { sub ->
                            FilterChip(
                                selected = subCat == sub,
                                onClick = { subCat = sub },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = (CatColor[cat] ?: Accent).copy(alpha = 0.12f),
                                    selectedLabelColor = CatColor[cat] ?: Ink
                                ),
                                label = { Text(sub, fontSize = T.Caption) }
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Field(
                    value = memo, onValueChange = { memo = it },
                    label = "메모",
                    placeholder = "선택 사항",
                    modifier = Modifier.fillMaxWidth()
                )

                if (!isNew) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {
                                Store.updateTxn(
                                    txn.copy(
                                        canceled = !txn.canceled,
                                        canceledAt = if (txn.canceled) null
                                        else LocalDateTime.now().format(Store.ts)
                                    )
                                )
                                onClose()
                            },
                            label = { Text(if (txn.canceled) "취소 되돌리기" else "결제 취소 처리", fontSize = T.Body) },
                            leadingIcon = {
                                DotSym(if (txn.canceled) Sym.UNDO else Sym.BAN, 15.dp, Warn)
                            },
                            colors = AssistChipDefaults.assistChipColors(labelColor = Warn, leadingIconContentColor = Warn)
                        )
                        AssistChip(
                            onClick = { Store.deleteTxn(txn); onClose() },
                            label = { Text("삭제", fontSize = T.Body) },
                            leadingIcon = { DotSym(Sym.TRASH, 15.dp, Sub) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Accent),
                onClick = {
                val v = amount.toLongOrNull() ?: 0L
                if (v > 0) {
                    // 날짜만 바꾸고 시각은 원래 것을 지킨다. 시간대 통계가 자정으로 몰리지 않게.
                    val keepTime = LocalDateTime.parse(txn.at, Store.ts).toLocalTime()
                    val next = txn.copy(
                        amount = v,
                        merchant = merchant.trim().ifBlank { subCat.ifBlank { cat.label } },
                        category = cat.name,
                        subCategory = subCat,
                        at = LocalDateTime.of(date, keepTime).format(Store.ts),
                        memo = memo.trim()
                    )
                    // 날짜가 다른 달로 옮겨졌으면 원래 달에서 지우고 새 달에 넣는다.
                    if (!isNew && next.at.substring(0, 7) != txn.at.substring(0, 7)) {
                        Store.deleteTxn(txn)
                        Store.addTxn(next)
                        onClose()
                        return@Button
                    }
                    if (isNew) Store.addTxn(next) else Store.updateTxn(next)
                }
                onClose()
            }) { Text("저장", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClose) { Text("닫기", color = Sub) } }
    )

    if (showPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        date = java.time.Instant.ofEpochMilli(it)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    }
                    showPicker = false
                }) { Text("선택") }
            },
            dismissButton = {
                TextButton({ showPicker = false }) { Text("취소", color = Sub) }
            }
        ) { DatePicker(state = state) }
    }
}


private fun dayLabel(date: String): String {
    val d = java.time.LocalDate.parse(date)
    val days = listOf("월", "화", "수", "목", "금", "토", "일")
    return "${d.monthValue}월 ${d.dayOfMonth}일 (${days[d.dayOfWeek.value - 1]})"
}
