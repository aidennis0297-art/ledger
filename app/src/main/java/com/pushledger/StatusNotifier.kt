package com.pushledger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import java.time.YearMonth

/**
 * 상태창 상주 알림.
 *
 * 도트는 유니코드 문자가 아니라 진짜 View 로 그린다. 문자로 찍으면 폰트가 크기와
 * 간격을 제멋대로 정하고, 기기에 따라 이모지 폰트로 잡혀 색까지 바뀐다.
 * 여기서 쓰는 색은 셋뿐이다 — 파랑(쓴 만큼), 연회색(남은 칸), 빨강(예산을 넘긴 상태).
 */
object StatusNotifier {

    // 채널을 한 번 만들면 설정이 굳는다. 아이콘 문제로 죽던 시절의 채널을 그대로 쓰면
    // 사용자가 껐던 상태가 남아 있을 수 있어 새 채널로 옮긴다.
    private const val CHANNEL_ID = "budget_status_v2"
    private const val NOTIF_ID = 9001

    private const val ON = 0xFF2F6BFF.toInt()      // 쓴 칸
    private const val OFF = 0xFFE0E0E6.toInt()     // 아직 남은 칸
    private const val OVER = 0xFFE5484D.toInt()    // 한도를 넘긴 상태

    /** 위젯 쪽 도트. 알림과 같은 칠하기 규칙을 쓰도록 id 만 따로 둔다. */
    val WIDGET_DOTS = intArrayOf(
        R.id.wd0, R.id.wd1, R.id.wd2, R.id.wd3, R.id.wd4, R.id.wd5, R.id.wd6, R.id.wd7,
        R.id.wd8, R.id.wd9, R.id.wd10, R.id.wd11, R.id.wd12, R.id.wd13, R.id.wd14, R.id.wd15
    )

    fun update(context: Context) {
        val cfg = Store.config.value
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (!cfg.showStatusNotif) {
            nm.cancel(NOTIF_ID)
            DailyWidgetProvider.updateAll(context)
            return
        }

        val monthTxns = Store.readMonth(YearMonth.now())
        val daily = Stats.dailyBudget(cfg, monthTxns)
        val remain = Stats.monthRemain(cfg, monthTxns)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "하루 가용 예산", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "오늘 남은 예산을 상태창에 띄웁니다"
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                }
            )
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val noBudget = cfg.monthlyBudget == 0L

        // 상태창은 스치듯 보는 자리다. 상태 한 낱말과 숫자 한 줄이면 충분하고,
        // 그 이상은 알림 줄에서 잘리거나 읽는 걸 방해한다.
        val plainTitle = when {
            noBudget -> "예산 미설정"
            daily.isSuccess -> "적정"
            else -> "초과"
        }
        val plainBody = if (noBudget) "예산 탭에서 월 예산을 정해 주세요"
        else "오늘 ${won(daily.todaySpent)} / ${won(daily.dailyLimit)} · 월 " +
            (if (remain >= 0) monthShort(remain) else "-" + monthShort(-remain))

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            // 적응형 아이콘(mipmap)을 여기 쓰면 Android 8 이상에서 알림이 통째로 무시된다.
            .setSmallIcon(R.drawable.ic_stat_budget)
            .setContentTitle(plainTitle)
            .setContentText(plainBody)
            .setColor(ON)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // 알림 하나 못 띄웠다고 저장이나 파싱까지 같이 죽으면 안 된다.
        runCatching { nm.notify(NOTIF_ID, builder.build()) }

        // 상주 알림은 조용해서 넘긴 걸 놓치기 쉽다. 넘어선 그 순간에 하루 한 번만 따로 알린다.
        if (!noBudget && !daily.isSuccess) warnOverspend(context, nm, daily)

        DailyWidgetProvider.updateAll(context)
    }

    /** 만원 단위로 줄인 금액. 상태창과 위젯은 자리가 좁아 원 단위까지 적을 곳이 없다. */
    fun monthShort(v: Long): String = if (v >= 10_000L) wonShort(v) else won(v)

    /**
     * 도트 칠하기. 한도를 넘으면 열두 칸을 전부 빨갛게 채운다.
     * 절반만 빨간 것보다 통째로 빨간 편이 상태창을 스칠 때 훨씬 빨리 읽힌다.
     */
    fun paintDots(views: RemoteViews, ids: IntArray, spent: Long, limit: Long, noBudget: Boolean) {
        val n = ids.size
        val filled = when {
            noBudget || limit <= 0L -> 0
            else -> ((spent.toDouble() / limit) * n).toInt().coerceIn(0, n)
        }
        val over = !noBudget && limit > 0L && spent > limit
        ids.forEachIndexed { i, id ->
            val color = when {
                over -> OVER
                i < filled -> ON
                else -> OFF
            }
            views.setInt(id, "setBackgroundColor", color)
        }
    }

    /**
     * 상태창에 왜 안 뜨는지 사람이 읽을 수 있게 알려 준다.
     * 알림은 실패해도 예외를 주지 않아서, 이렇게 물어보지 않으면 원인을 알 길이 없다.
     */
    fun diagnose(context: Context): String {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val appOn = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!appOn) return "이 앱의 알림이 시스템에서 꺼져 있습니다"
        if (!Store.config.value.showStatusNotif) return "앱 설정에서 상태창 알림이 꺼져 있습니다"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = nm.getNotificationChannel(CHANNEL_ID)
            if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) {
                return "알림 채널이 차단되어 있습니다 (시스템 알림 설정에서 해제)"
            }
        }
        val shown = runCatching {
            nm.activeNotifications.any { it.id == NOTIF_ID }
        }.getOrDefault(false)
        return if (shown) "정상 표시 중입니다" else "떠 있지 않습니다. 아래 버튼으로 다시 띄워 보세요"
    }

    private const val OVER_CH = "budget_over"
    private const val OVER_ID = 9002
    /** 같은 날 여러 번 울리지 않게 마지막으로 알린 날짜를 기억한다. */
    private var warnedOn: String = ""

    private fun warnOverspend(context: Context, nm: NotificationManager, daily: DailyStatus) {
        val today = java.time.LocalDate.now().toString()
        if (warnedOn == today) return
        warnedOn = today

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(OVER_CH, "예산 초과 알림", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "오늘 예산을 넘어선 순간 한 번 알립니다" }
            )
        }
        runCatching {
            nm.notify(
                OVER_ID,
                NotificationCompat.Builder(context, OVER_CH)
                    .setSmallIcon(R.drawable.ic_stat_budget)
                    .setContentTitle("오늘 예산을 넘었어요")
                    .setContentText(
                        "${won(-daily.remaining)} 초과 · 오늘 한도 ${won(daily.dailyLimit)}"
                    )
                    .setAutoCancel(true)
                    .setColor(OVER)
                    .build()
            )
        }
    }
}
