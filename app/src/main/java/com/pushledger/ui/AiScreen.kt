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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * AI 분석 화면.
 *
 * 예전에는 알림함 안의 첫 번째 탭이었다. 그러니 리포트를 보려면 알림함을
 * 먼저 열고 탭을 한 번 더 넘겨야 했고, 정작 알림함은 뭔가 잘못됐을 때만
 * 들여다보는 자리였다. 둘을 바꿔 AI 분석을 아래 탭으로 올리고,
 * 알림함은 홈 상단의 서랍 버튼으로 내려보냈다.
 */
@Composable
fun AiScreen() {
    val cfg by Store.config.collectAsState()
    val ctx = LocalContext.current

    val running by CoachRun.running.collectAsState()
    val runKind by CoachRun.kind.collectAsState()
    val runMsg by CoachRun.message.collectAsState()

    var kind by remember { mutableStateOf(ReportKind.MONTHLY) }
    var profileOpen by remember { mutableStateOf(false) }
    var reportOpen by remember { mutableStateOf(true) }
    var archiveOpen by remember { mutableStateOf(false) }
    var trashOpen by remember { mutableStateOf(false) }
    // 보고 있는 리포트. 아무것도 고르지 않았으면 가장 최근 것을 본다.
    var viewing by remember { mutableStateOf<String?>(null) }

    var job by remember(cfg.profile.job) { mutableStateOf(cfg.profile.job) }
    var age by remember(cfg.profile.age) { mutableStateOf(cfg.profile.age) }
    var gender by remember(cfg.profile.gender) { mutableStateOf(cfg.profile.gender) }
    var reason by remember(cfg.profile.goalReason) { mutableStateOf(cfg.profile.goalReason) }
    var profileSaved by remember { mutableStateOf(false) }

    val live = cfg.reports.filterNot { it.trashed }
    // 새로 만들면 보관함에서 골라 둔 것이 아니라 방금 나온 것을 보여 준다.
    // 이 줄이 없으면 예전 리포트를 열어 둔 채로 새 걸 만들었을 때 화면이 그대로다.
    LaunchedEffect(cfg.reports.size) { viewing = null }
    val trashed = cfg.reports.filter { it.trashed }
    val shown = live.firstOrNull { it.id == viewing } ?: live.firstOrNull()
    val reportText = shown?.content ?: cfg.latestReport?.content.orEmpty()
    val reportTime = shown?.createdAt ?: cfg.latestReport?.createdAt.orEmpty()

    LazyColumn(Modifier.fillMaxWidth()) {

        // 프로필: 한 번 넣고 나면 계속 볼 이유가 없어서 접어 둔다.
        item {
            Panel(
                title = "나의 프로필",
                icon = Sym.PERSON,
                collapsible = true,
                collapsed = !profileOpen,
                onToggleCollapse = { profileOpen = !profileOpen }
            ) {
                Text("입력할수록 조언이 내 상황에 맞아집니다", fontSize = T.Caption, color = Sub)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Field(
                        value = job, onValueChange = { job = it; profileSaved = false },
                        label = "직업", placeholder = "직장인",
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Field(
                        value = age, onValueChange = { age = it; profileSaved = false },
                        label = "나이", placeholder = "29",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.7f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Field(
                        value = gender, onValueChange = { gender = it; profileSaved = false },
                        label = "성별", placeholder = "선택",
                        modifier = Modifier.weight(0.7f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Field(
                    value = reason, onValueChange = { reason = it; profileSaved = false },
                    label = "절약 목표",
                    placeholder = "예: 1억 모으기",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.End) {
                    Button(
                        onClick = {
                            Store.saveConfig(
                                cfg.copy(profile = UserProfile(job.trim(), age.trim(), gender.trim(), reason.trim()))
                            )
                            profileSaved = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (profileSaved) Good else Ink)
                    ) {
                        Text(if (profileSaved) "저장됨" else "프로필 저장", fontSize = T.Body)
                    }
                }
            }
        }

        // 리포트 종류 + 생성
        item {
            Panel("리포트 만들기", Sym.SPARK) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    ReportKind.entries.forEach { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = { kind = k },
                            label = { Text(k.label, fontSize = T.Caption) }
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))

                if (running) {
                    // 다른 탭에 갔다 와도 이 상태가 그대로 보인다. 생성은 화면 밖에서 돈다.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = Accent, modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                runKind.label + " 만드는 중", fontSize = T.Body,
                                fontWeight = FontWeight.Bold, color = Ink
                            )
                            Text(runMsg + " · 다른 탭을 써도 계속됩니다", fontSize = T.Caption, color = Sub)
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                if (!CoachRun.start(ctx, kind, useAi = true)) {
                                    Toast.makeText(ctx, CoachRun.message.value, Toast.LENGTH_LONG).show()
                                }
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) { Text("AI 로 생성", fontSize = T.Body, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { CoachRun.start(ctx, kind, useAi = false) },
                            modifier = Modifier.height(44.dp)
                        ) { Text("기기 분석", fontSize = T.Body) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "기기 분석은 키 없이 앱 안의 집계만으로 같은 형식을 만듭니다",
                        fontSize = T.Caption, color = Sub
                    )
                }
            }
        }

        // 보고서: 길어서 접을 수 있어야 아래 내용에 닿는다.
        if (reportText.isNotBlank()) {
            item {
                Panel(
                    title = "분석 보고서",
                    icon = Sym.DOC,
                    collapsible = true,
                    collapsed = !reportOpen,
                    onToggleCollapse = { reportOpen = !reportOpen }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val label = ReportKind.entries
                            .firstOrNull { it.name == shown?.kind }?.label ?: "리포트"
                        Box(
                            Modifier.clip(RoundedCornerShape(4.dp))
                                .background(Accent.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(label, fontSize = T.Caption, color = Accent, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (shown?.byAi == false) "기기 분석" else "AI",
                            fontSize = T.Caption, color = Sub
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(reportTime, fontSize = T.Caption, color = Sub)
                    Spacer(Modifier.height(8.dp))
                    ReportContentView(reportText)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), Arrangement.End) {
                        TextButton(onClick = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("가계부 리포트", reportText))
                        }) { Text("복사", fontSize = T.Body, color = Accent) }
                        TextButton(onClick = {
                            ctx.startActivity(
                                Intent.createChooser(
                                    Intent().apply {
                                        action = Intent.ACTION_SEND
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, reportText)
                                    }, "리포트 보내기"
                                )
                            )
                        }) { Text("내보내기", fontSize = T.Body, color = Accent) }
                    }
                }
            }
        }

        // 보관함: 만들어 둔 리포트를 골라 본다.
        if (live.size > 1) {
            item {
                Panel(
                    title = "보관함 ${live.size}편",
                    icon = Sym.DOC,
                    collapsible = true,
                    collapsed = !archiveOpen,
                    onToggleCollapse = { archiveOpen = !archiveOpen }
                ) {
                    live.forEach { r ->
                        val on = r.id == (shown?.id ?: "")
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { viewing = r.id }
                                .padding(vertical = 8.dp),
                            Arrangement.SpaceBetween, Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    r.createdAt.ifBlank { "이름 없는 리포트" },
                                    fontSize = T.Body,
                                    color = if (on) Accent else Ink,
                                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1
                                )
                                Text(
                                    r.content.lineSequence().firstOrNull { it.isNotBlank() }
                                        ?.removePrefix("- ")?.take(40).orEmpty(),
                                    fontSize = T.Caption, color = Sub, maxLines = 1
                                )
                            }
                            TextButton(onClick = {
                                Store.trashReport(r.id)
                                if (viewing == r.id) viewing = null
                            }) {
                                Text("삭제", fontSize = T.Caption, color = Warn)
                            }
                        }
                    }
                }
            }
        }

        // 휴지통: 지운 리포트는 여기 머문다. 같은 리포트를 다시 만들 수는 없기 때문이다.
        if (trashed.isNotEmpty()) {
            item {
                Panel(
                    title = "휴지통 ${trashed.size}편",
                    icon = Sym.DOC,
                    collapsible = true,
                    collapsed = !trashOpen,
                    onToggleCollapse = { trashOpen = !trashOpen }
                ) {
                    Text(
                        "지운 리포트는 여기 남습니다. 그때의 소비 데이터는 이미 지나가서 같은 걸 다시 만들 수 없어요.",
                        fontSize = T.Caption, color = Sub
                    )
                    Spacer(Modifier.height(8.dp))
                    trashed.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            Arrangement.SpaceBetween, Alignment.CenterVertically
                        ) {
                            Text(
                                r.createdAt.ifBlank { "이름 없는 리포트" },
                                fontSize = T.Body, color = Sub,
                                modifier = Modifier.weight(1f), maxLines = 1
                            )
                            TextButton(onClick = { Store.restoreReport(r.id) }) {
                                Text("복구", fontSize = T.Caption, color = Accent)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), Arrangement.End) {
                        TextButton(onClick = {
                            val n = Store.emptyReportTrash()
                            Toast.makeText(ctx, "${n}편을 완전히 지웠습니다", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("휴지통 비우기", fontSize = T.Caption, color = Warn)
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ReportContentView(text: String) {
    val lines = text.split("\n")
    Column(Modifier.fillMaxWidth()) {
        lines.forEach { line ->
            if (line.startsWith("[") && line.contains("문단")) {
                Spacer(Modifier.height(8.dp))
                Text(
                    line,
                    fontSize = T.Title,
                    fontWeight = FontWeight.Bold,
                    color = Accent,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else if (line.isBlank()) {
                Spacer(Modifier.height(4.dp))
            } else {
                Text(
                    line,
                    fontSize = T.Body,
                    color = Ink,
                    lineHeight = T.Amount,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

