package com.pushledger.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pushledger.AiQueue
import com.pushledger.AiReport
import com.pushledger.CoachRun
import com.pushledger.ReportKind
import com.pushledger.DEFAULT_PAY_APPS
import com.pushledger.Nvidia
import com.pushledger.Raw
import com.pushledger.Stats
import com.pushledger.Store
import com.pushledger.UserProfile
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * 알림함. 받은 결제 알림 원본과, 어떤 앱의 알림을 볼지를 정하는 자리다.
 * 매일 볼 화면이 아니라 하단 탭을 한 칸 차지하지 않고 홈 상단에서 열린다.
 */
@Composable
fun InboxScreen() {
    var seg by remember { mutableIntStateOf(0) }
    val inbox by Store.inbox.collectAsState()

    val pendingCount = inbox.count { it.state == Raw.PENDING || it.state == Raw.SUGGEST }

    Column(Modifier.fillMaxWidth()) {
        TabRow(seg, containerColor = Color.White, contentColor = Accent) {
            Tab(seg == 0, { seg = 0 }, text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("알림 내역", fontSize = T.Body, fontWeight = if (seg == 0) FontWeight.Bold else FontWeight.Normal)
                    if (pendingCount > 0) {
                        Spacer(Modifier.width(4.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(10.dp)).background(Warn).padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(pendingCount.toString(), fontSize = T.Caption, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            })
            Tab(seg == 1, { seg = 1 }, text = {
                Text("앱 관리", fontSize = T.Body, fontWeight = if (seg == 1) FontWeight.Bold else FontWeight.Normal)
            })
        }

        when (seg) {
            0 -> NotifHistoryTab()
            else -> AppSettingsTab()
        }
    }
}

/** 알림 내역: 미처리 + 처리됨 통합 관리 */
@Composable
private fun NotifHistoryTab() {
    val inbox by Store.inbox.collectAsState()
    val cfg by Store.config.collectAsState()
    val running by AiQueue.running.collectAsState()
    val done by AiQueue.done.collectAsState()
    val total by AiQueue.total.collectAsState()
    val msg by AiQueue.lastMsg.collectAsState()
    val ctx = LocalContext.current

    var filter by remember { mutableStateOf("PENDING") }

    val filteredList = remember(inbox, filter) {
        when (filter) {
            "PENDING" -> inbox.filter { it.state == Raw.PENDING || it.state == Raw.SUGGEST }
            "DONE" -> inbox.filter { it.state == Raw.DONE }
            "FAILED" -> inbox.filter { it.state == Raw.FAILED }
            "IGNORED" -> inbox.filter { it.state == Raw.IGNORED }
            "NOISE" -> inbox.filter { it.state == Raw.NOISE }
            else -> inbox
        }
    }

    // AI 에 넘길 것: 못 읽은 것과 실패한 것. 실패한 건이야말로 다시 걸어 봐야 할 것들인데,
    // 예전에는 일괄 버튼이 미처리만 집어서 한 번 실패하면 낱개로 스무 번 눌러야 했다.
    val queueItems = inbox.filter {
        it.state == Raw.PENDING || it.state == Raw.SUGGEST || it.state == Raw.FAILED
    }

    LazyColumn(Modifier.fillMaxWidth()) {

        // 상단 AI 일괄 처리 진행 바
        if (running) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                    LinearProgressIndicator(
                        progress = { if (total > 0) done.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = Accent
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("AI 처리 중... $done / $total  ($msg)", fontSize = T.Caption, color = Sub)
                }
            }
        }

        // 지난 분석 결과. 큐가 끝난 뒤에도 사용자가 닫을 때까지 남는다.
        if (!running && cfg.lastAiRun.isNotBlank()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Card)
                        .padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f)) {
                        Text(cfg.lastAiRun, fontSize = T.Body, color = Ink)
                    }
                    TextButton(onClick = { Store.setLastAiRun("") }) {
                        Text("닫기", fontSize = T.Caption, color = Sub)
                    }
                }
            }
        }

        // 일괄 작업 및 필터 칩
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                // 일괄 작업은 "지금 보고 있는 칸" 에 건다. 그래야 무시됨이든 실패든
                // 금액 없음이든, 어느 칸에 들어간 알림도 통째로 다시 돌릴 수 있다.
                // 기록됨만 뺀다 — 이미 거래가 된 것을 다시 읽어 봐야 중복으로 걸린다.
                // 기록됨 칸에서는 AI 가 만든 것만 통째로 되돌릴 수 있게 한다.
                // 규칙이 읽은 건 잘 맞으므로 같이 지우면 멀쩡한 가계부가 날아간다.
                if (filter == "DONE") {
                    val aiMade = filteredList.filter { it.note.startsWith("AI:") }
                    if (aiMade.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Text(
                                "AI 가 기록한 ${aiMade.size}건",
                                fontSize = T.Body, fontWeight = FontWeight.Bold, color = Ink
                            )
                            TextButton(onClick = {
                                var gone = 0
                                aiMade.forEach { r ->
                                    gone += Store.deleteTxnByDedup(r.dedup)
                                    Store.setRawState(r.id, Raw.PENDING, "기록 취소됨 (일괄)")
                                }
                                Toast.makeText(
                                    ctx, "거래 ${gone}건을 지우고 미처리로 되돌렸습니다", Toast.LENGTH_SHORT
                                ).show()
                                filter = "PENDING"
                            }) { Text("전부 되돌리기", fontSize = T.Caption, color = Warn) }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                val batch = if (filter == "DONE") emptyList()
                else if (filter == "ALL") queueItems
                else filteredList
                if (batch.isNotEmpty() && !running) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text(
                            when (filter) {
                                "FAILED" -> "실패 ${batch.size}건"
                                "IGNORED" -> "무시된 ${batch.size}건"
                                "NOISE" -> "금액 없는 ${batch.size}건"
                                else -> "AI 로 읽을 알림 ${batch.size}건"
                            },
                            fontSize = T.Body, fontWeight = FontWeight.Bold, color = Ink
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 무시·잡담 칸은 "되돌리기" 가 먼저다. AI 를 부르지 않고
                            // 미처리로만 돌려놓고 싶을 때가 있다.
                            if (filter == "IGNORED" || filter == "NOISE") {
                                TextButton(onClick = {
                                    val n = Store.restoreAll(
                                        if (filter == "IGNORED") Raw.IGNORED else Raw.NOISE
                                    )
                                    Toast.makeText(ctx, "${n}건을 미처리로 되돌렸습니다", Toast.LENGTH_SHORT).show()
                                    filter = "PENDING"
                                }) { Text("전부 되돌리기", fontSize = T.Caption, color = Accent) }
                                Spacer(Modifier.width(4.dp))
                            }
                            Button(
                                onClick = {
                                    if (!AiQueue.enqueue(ctx, batch.map { it.id })) {
                                        Toast.makeText(ctx, "AI 키가 없습니다. 설정에서 넣어 주세요.", Toast.LENGTH_LONG).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent)
                            ) {
                                DotSym(Sym.SPARK, 15.dp, Color.White)
                                Spacer(Modifier.width(6.dp))
                                Text(if (filter == "FAILED") "다시 시도" else "모두 AI로", fontSize = T.Body)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    // 칸 이름 옆의 숫자가 곧 목록이다. 미처리가 0이면 볼 것이 없다는 뜻이고,
                    // 실패가 쌓이면 그 칸에서 통째로 다시 걸 수 있다.
                    listOf(
                        "PENDING" to ("미처리" to inbox.count { it.state == Raw.PENDING || it.state == Raw.SUGGEST }),
                        "DONE" to ("기록됨" to inbox.count { it.state == Raw.DONE }),
                        "FAILED" to ("실패" to inbox.count { it.state == Raw.FAILED }),
                        "IGNORED" to ("무시됨" to inbox.count { it.state == Raw.IGNORED }),
                        "NOISE" to ("금액 없음" to inbox.count { it.state == Raw.NOISE }),
                        "ALL" to ("전체" to inbox.size)
                    ).forEach { (keyName, pair) ->
                        val (label, count) = pair
                        FilterChip(
                            selected = filter == keyName,
                            onClick = { filter = keyName },
                            label = { Text("$label ($count)", fontSize = T.Body) }
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
                if (filter == "NOISE") {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "켜 둔 앱에서 왔지만 금액이 안 보이는 알림입니다. 결제인데 여기 들어와 있으면 그 줄에서 AI 로 읽히세요.",
                        fontSize = T.Caption, color = Sub
                    )
                }
            }
        }

        if (filteredList.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    // 칸마다 비어 있다는 뜻이 다르다. 미처리가 비면 좋은 일이고,
                    // 전체가 비면 알림이 아직 안 들어온 것이다.
                    EmptyState(
                        title = when (filter) {
                            "PENDING" -> "못 읽은 알림이 없어요"
                            "FAILED" -> "실패한 알림이 없어요"
                            "DONE" -> "아직 기록된 알림이 없어요"
                            else -> "알림이 없어요"
                        },
                        detail = when (filter) {
                            "PENDING" -> "들어온 결제 알림을 전부 읽어 냈습니다"
                            else -> "결제 앱을 켜 두면 알림이 여기로 들어옵니다"
                        },
                        compact = true
                    )
                }
            }
        } else {
            items(filteredList, key = { it.id }) { raw ->
                RawCard(raw)
            }
        }

        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun RawCard(raw: Raw) {
    val ctx = LocalContext.current

    val stateBadgeColor = when (raw.state) {
        Raw.PENDING, Raw.SUGGEST, Raw.FAILED -> Warn
        Raw.DONE -> Good
        else -> Sub
    }
    val stateBadgeText = when (raw.state) {
        Raw.PENDING -> "미처리"
        Raw.SUGGEST -> "추천"
        Raw.DONE -> "기록됨"
        Raw.IGNORED -> "무시"
        Raw.FAILED -> "실패"
        Raw.NOISE -> "금액 없음"
        else -> raw.state
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp)).background(Card).padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(4.dp))
                        .background(stateBadgeColor.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(stateBadgeText, fontSize = T.Caption, fontWeight = FontWeight.Bold, color = stateBadgeColor)
                }
                Spacer(Modifier.width(6.dp))
                Text(raw.appLabel, fontSize = T.Body, fontWeight = FontWeight.SemiBold, color = Ink)
            }
            Text(raw.postedAt.substring(5, 16), fontSize = T.Caption, color = Sub)
        }

        Spacer(Modifier.height(6.dp))
        Text(raw.title, fontSize = T.Body, fontWeight = FontWeight.Medium, color = Ink)
        Text(raw.text, fontSize = T.Body, color = Sub)

        if (raw.note.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text("사유: ${raw.note}", fontSize = T.Caption, color = if (raw.state == Raw.DONE) Good else Sub)
        }

        // 금액이 안 보여 잡담으로 분류된 줄. 실은 결제였을 수 있으므로 여기서도 AI 를 부른다.
        // "켜 둔 앱 알림은 전부 AI 가 볼 수 있어야 한다" 는 말이 이 줄에 걸려 있다.
        if (raw.state == Raw.NOISE || raw.state == Raw.FAILED) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.End, Alignment.CenterVertically) {
                TextButton(onClick = { Store.setRawState(raw.id, Raw.IGNORED, "직접 무시함") }) {
                    Text("무시", fontSize = T.Caption, color = Sub)
                }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = {
                        if (!AiQueue.enqueue(ctx, listOf(raw.id))) {
                            Toast.makeText(ctx, "AI 키가 없습니다. 설정에서 넣어 주세요.", Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Ink)
                ) {
                    Text(if (raw.state == Raw.FAILED) "다시 시도" else "AI로 읽기", fontSize = T.Caption)
                }
            }
        } else if (raw.state == Raw.PENDING || raw.state == Raw.SUGGEST) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.End, Alignment.CenterVertically) {
                // 결제와 상관없는 앱이 계속 올라오면, 그 앱을 여기서 바로 끈다.
                // 설정 탭까지 찾아 들어가게 만들면 결국 아무도 안 끈다.
                TextButton(onClick = {
                    val c = Store.config.value
                    Store.saveConfig(
                        c.copy(
                            blockedPkgs = c.blockedPkgs + raw.pkg,
                            allowedPkgs = c.allowedPkgs - raw.pkg
                        )
                    )
                    val n = Store.ignoreAllFrom(raw.pkg)
                    Toast.makeText(
                        ctx, "${raw.appLabel} 알림을 더 이상 보지 않습니다 (${n}건 정리)",
                        Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Text("이 앱 안 보기", fontSize = T.Caption, color = Warn)
                }
                TextButton(onClick = { Store.setRawState(raw.id, Raw.IGNORED) }) {
                    Text("무시", fontSize = T.Caption, color = Sub)
                }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = {
                        if (!AiQueue.enqueue(ctx, listOf(raw.id))) {
                            Toast.makeText(ctx, "AI 키가 없습니다. 설정에서 넣어 주세요.", Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Ink)
                ) {
                    Text("AI로 기록", fontSize = T.Caption)
                }
            }
        } else if (raw.state == Raw.IGNORED) {
            // 무시한 걸 되돌릴 길이 없으면, 잘못 눌렀을 때 복구할 방법이 사라진다.
            // 무시 취소 → 미처리 → 다시 찾아서 AI, 이렇게 세 걸음이던 것을 한 걸음으로 줄인다.
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.End, Alignment.CenterVertically) {
                TextButton(onClick = {
                    Store.setRawState(raw.id, Raw.PENDING, "무시 취소됨")
                }) {
                    Text("무시 취소", fontSize = T.Caption, color = Accent)
                }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = {
                        if (!AiQueue.enqueue(ctx, listOf(raw.id))) {
                            Toast.makeText(ctx, "AI 키가 없습니다. 설정에서 넣어 주세요.", Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Ink)
                ) {
                    Text("AI로 읽기", fontSize = T.Caption)
                }
            }
        } else if (raw.state == Raw.DONE) {
            // AI 가 잘못 읽었으면 여기서 되돌린다. 만든 거래까지 같이 지운다.
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                TextButton(onClick = {
                    val n = Store.deleteTxnByDedup(raw.dedup)
                    Store.setRawState(raw.id, Raw.PENDING, "기록 취소됨 (거래 ${n}건 삭제)")
                    Toast.makeText(
                        ctx,
                        if (n > 0) "기록을 되돌렸습니다 (${n}건)" else "지울 거래를 찾지 못했습니다",
                        Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Text("기록 취소", fontSize = T.Caption, color = Warn)
                }
            }
        }
    }
}

/** 앱 관리: 어떤 앱의 결제 알림을 읽을지 고른다. */
@Composable
private fun AppSettingsTab() {
    val cfg by Store.config.collectAsState()
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var showAllApps by remember { mutableStateOf(false) }

    val pm = ctx.packageManager
    val allInstalled = remember {
        pm.getInstalledApplications(0).map { appInfo ->
            val label = pm.getApplicationLabel(appInfo).toString()
            val pkg = appInfo.packageName
            val isPay = DEFAULT_PAY_APPS.contains(pkg) ||
                listOf("pay", "페이", "bank", "은행", "카드", "card", "kb", "신한", "우리", "하나", "토스", "toss", "삼성", "카카오")
                    .any { label.lowercase().contains(it) || pkg.lowercase().contains(it) }
            Triple(pkg, label, isPay)
        }.sortedWith(compareByDescending<Triple<String, String, Boolean>> { it.third }.thenBy { it.second })
    }

    // 켜 둔 앱이 항상 맨 위, 그다음이 추천, 나머지는 이름순.
    // 방금 켠 앱이 목록 아래로 사라지지 않아야 무엇을 켰는지 바로 확인된다.
    val displayList = remember(allInstalled, showAllApps, query, cfg.allowedPkgs) {
        allInstalled.filter { (pkg, label, isPay) ->
            // 차단해 둔 앱은 추천이 아니어도 항상 보여야 한다. 목록에서 사라지면 풀 방법이 없다.
            (showAllApps || isPay || cfg.blockedPkgs.contains(pkg)) &&
                (query.isBlank() || label.contains(query, ignoreCase = true) || pkg.contains(query, ignoreCase = true))
        }.sortedWith(
            compareByDescending<Triple<String, String, Boolean>> { cfg.allowedPkgs.contains(it.first) }
                .thenByDescending { it.third }
                .thenBy { it.second }
        )
    }

    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                Field(
                    value = query, onValueChange = { query = it },
                    label = "",
                    placeholder = "앱 검색 (예: 신한, 토스, 카카오)",
                    leading = { DotSym(Sym.SEARCH, 17.dp, Sub) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("설치된 전체 앱 보기", fontSize = T.Body, color = Sub)
                    Switch(
                        checked = showAllApps,
                        onCheckedChange = { showAllApps = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Accent)
                    )
                }
            }
        }

        items(displayList, key = { it.first }) { (pkg, label, isPay) ->
            val isAllowed = cfg.allowedPkgs.contains(pkg)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Card).padding(12.dp),
                Arrangement.SpaceBetween, Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, fontSize = T.Title, fontWeight = FontWeight.SemiBold, color = Ink)
                        if (isPay) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier.clip(RoundedCornerShape(4.dp)).background(Accent.copy(alpha = 0.12f)).padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text("추천", fontSize = T.Caption, color = Accent, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Text(pkg, fontSize = T.Caption, color = Sub)
                    if (cfg.blockedPkgs.contains(pkg)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("무시 중", fontSize = T.Caption, color = Warn, fontWeight = FontWeight.Bold)
                            TextButton(onClick = {
                                Store.saveConfig(cfg.copy(blockedPkgs = cfg.blockedPkgs - pkg))
                                // 안 보기로 하면서 한꺼번에 무시된 것들을 같이 되돌린다.
                                // 앱만 다시 켜 주면 그때 쌓였던 알림은 그대로 묻힌다.
                                val n = Store.restoreAllFrom(pkg)
                                if (n > 0) Toast.makeText(
                                    ctx, "$label 알림 ${n}건을 미처리로 되돌렸습니다", Toast.LENGTH_SHORT
                                ).show()
                            }) { Text("무시 취소", fontSize = T.Caption, color = Accent) }
                        }
                    }
                }

                Switch(
                    checked = isAllowed,
                    onCheckedChange = { checked ->
                        // 켜면 차단도 같이 푼다. 차단 목록에 남아 있으면 켜 놓고도 알림이 안 온다.
                        Store.saveConfig(
                            if (checked) cfg.copy(
                                allowedPkgs = cfg.allowedPkgs + pkg,
                                blockedPkgs = cfg.blockedPkgs - pkg
                            ) else cfg.copy(allowedPkgs = cfg.allowedPkgs - pkg)
                        )
                        if (checked) {
                            val n = Store.restoreAllFrom(pkg)
                            if (n > 0) Toast.makeText(
                                ctx, "$label 알림 ${n}건을 미처리로 되돌렸습니다", Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = Accent)
                )
            }
        }

        item { Spacer(Modifier.height(30.dp)) }
    }
}
