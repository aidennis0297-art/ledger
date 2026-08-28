package com.pushledger.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pushledger.Nvidia
import com.pushledger.StatusNotifier
import com.pushledger.Store
import java.time.YearMonth

/**
 * 설정.
 *
 * 예전에는 이 네 장이 예산 탭 맨 아래에 접힌 채로 매달려 있었다. 예산을 고치러
 * 들어간 사람은 그것들을 매번 지나쳐야 했고, 정작 설정을 바꾸려는 사람은 그게
 * 예산 탭에 있다는 걸 몰랐다. 홈 상단의 서랍으로 빼냈다.
 */
@Composable
fun SettingsScreen() {
    val cfg by Store.config.collectAsState()
    val ctx = LocalContext.current
    val ym = YearMonth.now()
    val month by Store.month.collectAsState()

    var keyInput by remember { mutableStateOf(Nvidia.apiKey(ctx)) }
    var keySaved by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxWidth()) {

        // AI 자동 분석. 키 입력 칸 바로 위가 아니라 맨 위에 둔다 — 이 스위치 하나로
        // 알림함을 들여다볼 일 자체가 없어지기 때문이다.
        item {
            Panel("AI 자동 분석", Sym.SPARK) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("못 읽은 알림을 자동으로 AI 에 넘기기", fontSize = T.Body, color = Ink)
                        Text(
                            "규칙이 못 읽은 결제 알림이 들어오면 그 자리에서 AI 가 읽습니다. " +
                                "앱을 켜 두지 않아도 되고, 실패한 건만 알림함에 남습니다.",
                            fontSize = T.Caption, color = Sub
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = cfg.autoAi,
                        onCheckedChange = { on ->
                            if (on && Nvidia.apiKey(ctx).isBlank()) {
                                Toast.makeText(ctx, "먼저 아래에 NVIDIA API 키를 넣어 주세요", Toast.LENGTH_LONG).show()
                            } else {
                                Store.saveConfig(cfg.copy(autoAi = on))
                            }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Accent)
                    )
                }
                if (cfg.autoAi) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "알림 한 건마다 API 를 부릅니다. 쓴 만큼 키에 요금이 붙는 점만 알아 두세요.",
                        fontSize = T.Caption, color = Warn
                    )
                }
            }
        }

        // 상태창 알림 제어 + 왜 안 뜨는지 진단
        item {
            var diag by remember { mutableStateOf("") }
            Panel(
                "상태창 알림",
                Sym.BELL
            ) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("상태창에 오늘 예산 띄우기", fontSize = T.Body, color = Ink)
                        Text("적정·초과와 오늘 쓴 돈만 간결하게 띄웁니다", fontSize = T.Caption, color = Sub)
                    }
                    Switch(
                        checked = cfg.showStatusNotif,
                        onCheckedChange = {
                            Store.saveConfig(cfg.copy(showStatusNotif = it))
                            StatusNotifier.update(ctx)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Accent)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            StatusNotifier.update(ctx)
                            diag = StatusNotifier.diagnose(ctx)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("지금 띄워보기", fontSize = T.Body) }
                    TextButton(onClick = {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        )
                    }) { Text("시스템 알림 설정", fontSize = T.Body, color = Accent) }
                }
                if (diag.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(diag, fontSize = T.Caption, color = if (diag.contains("정상")) Good else Warn)
                }
            }
        }

        // 홈 위젯에 무엇을 보일지
        item {
            Panel(
                "홈 위젯",
                Sym.GRID
            ) {
                Text(
                    "켠 것만 보입니다. 위젯을 작게 줄이면 자리에 안 들어가는 줄부터 접히고, " +
                        "1x1 까지 줄이면 오늘 남은 돈 하나만 남습니다.",
                    fontSize = T.Caption, color = Sub
                )
                Spacer(Modifier.height(10.dp))
                WidgetToggle("도트 띠", "오늘 한도를 얼마나 썼는지", cfg.widgetDots) {
                    Store.saveConfig(cfg.copy(widgetDots = it))
                }
                WidgetToggle("오늘 지출·한도", "숫자 두 개를 아래 줄에", cfg.widgetToday) {
                    Store.saveConfig(cfg.copy(widgetToday = it))
                }
                WidgetToggle("이달 남은 돈", "배지 아래 작은 글씨로", cfg.widgetMonth) {
                    Store.saveConfig(cfg.copy(widgetMonth = it))
                }
                WidgetToggle("월말 예상 지출", "이대로 쓰면 말일에 얼마가 될지", cfg.widgetForecast) {
                    Store.saveConfig(cfg.copy(widgetForecast = it))
                }
            }
        }

        // 데이터 내보내기 / 알림 보관 기간
        item {
            Panel(
                "데이터",
                Sym.DOC
            ) {
                Text(
                    "가계부는 이 폰 안에만 있습니다. 앱을 지우면 같이 사라지니 가끔 내보내 두세요.",
                    fontSize = T.Caption, color = Sub
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            runCatching {
                                val f = Store.writeCsvFile()
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    ctx, ctx.packageName + ".fileprovider", f
                                )
                                ctx.startActivity(
                                    android.content.Intent.createChooser(
                                        android.content.Intent().apply {
                                            action = android.content.Intent.ACTION_SEND
                                            type = "text/csv"
                                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }, "가계부 내보내기"
                                    )
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Ink)
                    ) { Text("가계부 CSV", fontSize = T.Body) }

                    // 알림 원문은 이 앱 안에만 있다. 규칙이 왜 못 읽었는지는 원문을 봐야
                    // 알 수 있으므로, 거래로 만들어진 것뿐 아니라 받은 알림을 통째로 뽑는다.
                    Button(
                        onClick = {
                            runCatching {
                                val f = Store.writeRawCsvFile()
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    ctx, ctx.packageName + ".fileprovider", f
                                )
                                ctx.startActivity(
                                    android.content.Intent.createChooser(
                                        android.content.Intent().apply {
                                            action = android.content.Intent.ACTION_SEND
                                            type = "text/csv"
                                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }, "알림 기록 내보내기"
                                    )
                                )
                            }.onFailure {
                                Toast.makeText(ctx, "내보내기 실패: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("알림 기록 CSV", fontSize = T.Body) }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "알림 기록은 켠 앱뿐 아니라 폰에 온 알림을 전부 담습니다. " +
                        "왜 안 잡혔는지 확인하거나 규칙을 고칠 근거로 씁니다.",
                    fontSize = T.Caption, color = Sub
                )
                Spacer(Modifier.height(12.dp))
                Text("알림 보관 기간", fontSize = T.Body, color = Ink)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(6.dp)) {
                    listOf(7, 14, 30, 60).forEach { d ->
                        FilterChip(
                            selected = cfg.keepInboxDays == d,
                            onClick = {
                                Store.saveConfig(cfg.copy(keepInboxDays = d))
                                Store.sweep()
                            },
                            label = { Text("${d}일", fontSize = T.Caption) }
                        )
                    }
                }
                Text(
                    "지난 알림은 날짜째 지워집니다. 길게 두면 앱이 무거워집니다.",
                    fontSize = T.Caption, color = Sub,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        // 5. AI 설정 & 테스트 데이터
        item {
            Panel(
                "AI 가계부 코치 설정",
                Sym.KEY
            ) {
                Text("키를 넣으면 AI 분석과 코칭을 쓸 수 있습니다", fontSize = T.Caption, color = Sub)
                Spacer(Modifier.height(8.dp))
                Field(
                    value = keyInput,
                    onValueChange = { keyInput = it; keySaved = false },
                    label = "API 키",
                    placeholder = "nvapi-...",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            Nvidia.saveApiKey(ctx, keyInput.trim())
                            keySaved = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Ink)
                    ) {
                        Text(if (keySaved) "저장됨" else "저장", fontSize = T.Body)
                    }

                }
            }
        }

        // 예시 데이터: 켜고 끄는 스위치 하나로. 켜면 넣고 끄면 그것만 골라 지운다.
        item {
            var seeded by remember(month) { mutableStateOf(Store.hasTestData(ym)) }
            Panel(
                "예시 데이터",
                Sym.PENCIL
            ) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("이번 달에 예시 거래 채우기", fontSize = T.Body, color = Ink)
                        Text(
                            "화면을 둘러볼 때만 쓰세요. 끄면 예시만 지워지고 직접 넣은 기록은 남습니다.",
                            fontSize = T.Caption, color = Sub
                        )
                    }
                    Switch(
                        checked = seeded,
                        onCheckedChange = { want ->
                            if (want) Store.seedTestData(ym) else Store.clearTestData(ym)
                            seeded = want
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Accent)
                    )
                }
            }
        }

        item { Spacer(Modifier.height(30.dp)) }
    }
}

/**
 * 설정의 켬/끔 한 줄.
 *
 * 위젯 항목이 넷이라 같은 열두 줄을 네 번 적는 대신 하나로 뒀다.
 * 항목이 하나뿐이었다면 이 함수는 없는 편이 낫다.
 */
@Composable
private fun WidgetToggle(
    title: String,
    hint: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = T.Body, color = Ink)
            Text(hint, fontSize = T.Caption, color = Sub)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Accent)
        )
    }
}
