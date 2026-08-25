package com.pushledger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import com.pushledger.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pushledger.ui.Accent
import com.pushledger.ui.AiScreen
import com.pushledger.ui.Card
import com.pushledger.ui.BudgetScreen
import com.pushledger.ui.DotSym
import com.pushledger.ui.Faint
import com.pushledger.ui.HomeScreen
import com.pushledger.ui.InboxScreen
import com.pushledger.ui.Ink
import com.pushledger.ui.LedgerScreen
import com.pushledger.ui.SettingsScreen
import com.pushledger.ui.StatsScreen
import com.pushledger.ui.Sub
import com.pushledger.ui.Sym
import com.pushledger.ui.T

/** 화이트 베이스. 도트 그래픽은 골격이 선 다음에 이 위에 얹는다. */
private val Scheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color.White,
    surface = Color.White,
    onBackground = Ink,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF6F6F8),
    outline = Faint
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)

        setContent {
            MaterialTheme(colorScheme = Scheme) {
                Surface(Modifier.fillMaxSize(), color = Color.White) { Shell() }
            }
        }
    }
}

private data class Tab(val label: String, val icon: Sym)

/**
 * 하단 다섯 칸.
 *
 * 알림함이 여기 있던 자리에는 AI 분석이 들어왔다. 알림함은 뭔가 잘못 읽혔을 때만
 * 들여다보는 자리라 매일 보는 다섯 칸 중 하나를 차지할 이유가 없었고, 반대로
 * 리포트는 알림함 안쪽 탭에 숨어 있어서 두 번 들어가야 닿았다. 둘을 맞바꿨다.
 */
private val TABS = listOf(
    Tab("홈", Sym.DASH),
    Tab("내역", Sym.LIST),
    Tab("AI 분석", Sym.SPARK),
    Tab("통계", Sym.CHART),
    Tab("예산", Sym.WALLET)
)

/** 홈 상단 버튼으로만 열리는 서랍. 탭이 아니라서 아래 칸을 쓰지 않는다. */
private enum class Drawer { INBOX, SETTINGS }

@Composable
private fun Shell() {
    var tab by remember { mutableIntStateOf(0) }
    var drawer by remember { mutableStateOf<Drawer?>(null) }
    val focus = LocalFocusManager.current

    androidx.activity.compose.BackHandler(enabled = drawer != null) { drawer = null }

    Scaffold(
        containerColor = Color.White,
        bottomBar = {
            if (drawer != null) return@Scaffold
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Accent,
                            selectedTextColor = Accent,
                            unselectedIconColor = Sub,
                            unselectedTextColor = Sub,
                            indicatorColor = Color(0xFFEDF2FF)
                        ),
                        icon = { DotSym(t.icon, 20.dp, if (tab == i) Accent else Sub) },
                        label = { Text(t.label, fontSize = T.Caption) }
                    )
                }
            }
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize()
                // 빈 곳을 누르면 입력이 끝난다. 안 그러면 금액 칸을 건드린 뒤로
                // 키보드와 커서가 계속 따라다니고, 뒤로 가기 말고는 끄는 길이 없다.
                // 자식이 먼저 처리한 터치는 여기까지 내려오지 않는다.
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focus.clearFocus() })
                }
        ) {
            PermissionBanner()
            // 알림함 안에도 같은 진행 표시가 있다. 서랍을 열었을 때 두 줄이 겹치지 않게 비운다.
            if (drawer != Drawer.INBOX) AiStrip { drawer = Drawer.INBOX }
            when (drawer) {
                Drawer.INBOX -> DrawerPage("알림함", { drawer = null }) { InboxScreen() }
                Drawer.SETTINGS -> DrawerPage("설정", { drawer = null }) { SettingsScreen() }
                null -> when (tab) {
                    0 -> HomeScreen(
                        goInbox = { drawer = Drawer.INBOX },
                        goStats = { tab = 3 },
                        goSettings = { drawer = Drawer.SETTINGS }
                    )
                    1 -> LedgerScreen()
                    2 -> AiScreen()
                    3 -> StatsScreen()
                    else -> BudgetScreen()
                }
            }
        }
    }
}

/**
 * AI 분석 진행과 결과를 알리는 띠. 어느 탭에 있어도 여기 뜬다.
 *
 * 예전에는 진행 표시가 알림함 안에만 있었다. 큐를 걸고 그 화면을 벗어나면 지금
 * 도는 중인지 끝난 건지 알 길이 없었고, 끝나는 순간 진행 바가 사라지면서
 * "몇 건 처리, 몇 건 실패" 도 같이 사라졌다. 진행은 어디서나 보이고, 결과는
 * 사용자가 지울 때까지 남는다. 그동안 탭 이동은 아무 제약이 없다.
 */
@Composable
private fun AiStrip(onOpen: () -> Unit) {
    val running by AiJob.running.collectAsState()
    val label by AiJob.label.collectAsState()
    val msg by AiJob.message.collectAsState()
    val done by AiJob.done.collectAsState()
    val total by AiJob.total.collectAsState()
    val cfg by Store.config.collectAsState()

    if (running) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp)).background(Card)
                .clickable { onOpen() }.padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DotSym(Sym.SPARK, 16.dp, Accent)
                Box(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        label + (if (total > 0) " $done / $total" else "") + " · " + msg,
                        fontSize = T.Body, color = Ink, maxLines = 1
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { if (total > 0) done.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = Accent,
                trackColor = Faint
            )
        }
        return
    }

    if (cfg.lastAiRun.isBlank()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp)).background(Card)
            .clickable { onOpen() }.padding(start = 10.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DotSym(Sym.SPARK, 16.dp, Sub)
        Box(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(cfg.lastAiRun, fontSize = T.Body, color = Ink)
        }
        TextButton(onClick = { Store.setLastAiRun("") }) {
            Text("닫기", fontSize = T.Caption, color = Sub)
        }
    }
}

/** 서랍 한 장. 제목 줄에 돌아가는 길이 있어야 탭이 없는 화면에서 갇히지 않는다. */
@Composable
private fun DrawerPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable { onBack() }.padding(10.dp)
            ) { DotSym(Sym.LEFT, 18.dp, Sub) }
            Text(title, fontSize = T.Title, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Ink)
        }
        content()
    }
}

/**
 * 알림 접근 권한이 없으면 앱이 아무것도 못 한다. 조용히 비어 있는 화면을 보여 주는 대신
 * 맨 위에 띄워서 설정으로 바로 보낸다.
 */
@Composable
private fun PermissionBanner() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(listenerOn(ctx)) }

    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        granted = listenerOn(ctx)
        StatusNotifier.update(ctx)
        onPauseOrDispose { }
    }


    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // 알림 접근 권한(다른 앱 알림 읽기)과 알림 표시 권한(내 알림 띄우기)은 다른 권한이다.
    // 표시 권한이 없으면 상태창 알림이 조용히 안 뜨는데, 그동안은 알 길이 없었다.
    val canPost = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    if (granted && !canPost) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp)
                .clip(RoundedCornerShape(12.dp)).background(Card).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DotSym(Sym.SHIELD, 18.dp, Color(0xFFB45309))
            Box(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("알림 표시가 꺼져 있어 상태창에 예산이 안 보입니다.", fontSize = T.Body, color = Ink)
            }
            TextButton(onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                )
            }) { Text("켜기") }
        }
        return
    }

    if (granted) return
    Row(
        Modifier.fillMaxWidth().padding(12.dp)
            .clip(RoundedCornerShape(12.dp)).background(Card).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DotSym(Sym.SHIELD, 18.dp, Color(0xFFB45309))
        Box(Modifier.weight(1f).padding(start = 10.dp)) {
            Text("알림 접근 권한이 꺼져 있어요. 켜야 결제 알림을 읽습니다.", fontSize = T.Body, color = Ink)
        }
        TextButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }) { Text("켜기") }
    }
}

private fun listenerOn(ctx: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

