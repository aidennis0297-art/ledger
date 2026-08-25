package com.pushledger

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.YearMonth

/**
 * 홈 화면 위젯 프로바이더.
 */
class DailyWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // 위젯은 앱을 한 번도 열지 않은 상태에서도 홈 화면에 놓일 수 있다.
        // 저장소를 열지 않고 읽으면 그 자리에서 죽고, 런처에는 빈 칸만 남는다.
        Store.ensure(context)
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    companion object {
        fun updateAll(context: Context) {
            Store.ensure(context)
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, DailyWidgetProvider::class.java))
            for (id in ids) {
                updateWidget(context, manager, id)
            }
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            runCatching { updateWidgetInner(context, appWidgetManager, appWidgetId) }
        }

        private fun updateWidgetInner(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val cfg = Store.config.value
            val ym = YearMonth.now()
            val monthTxns = Store.readMonth(ym)
            val daily = Stats.dailyBudget(cfg, monthTxns)
            val remain = Stats.monthRemain(cfg, monthTxns)

            val views = RemoteViews(context.packageName, R.layout.widget_daily_budget)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            // 상태창 알림과 같은 말을 같은 순서로 한다. 두 곳이 다른 숫자를 앞세우면 헷갈린다.
            val noBudget = cfg.monthlyBudget == 0L
            if (noBudget) {
                views.setTextViewText(R.id.widget_daily_amount, "예산 미설정")
                views.setTextViewText(R.id.widget_status_badge, "설정")
                views.setTextViewText(R.id.widget_spent_text, "예산 탭에서")
                views.setTextViewText(R.id.widget_remaining_text, "월 예산 입력")
                views.setTextViewText(R.id.widget_month_text, "")
            } else {
                val over = !daily.isSuccess
                views.setTextViewText(
                    R.id.widget_daily_amount,
                    won(if (over) -daily.remaining else daily.remaining)
                )
                views.setTextColor(
                    R.id.widget_daily_amount,
                    if (over) 0xFFE5484D.toInt() else 0xFF16161A.toInt()
                )
                views.setTextViewText(R.id.widget_status_badge, if (over) "초과" else "남음")
                views.setTextColor(
                    R.id.widget_status_badge,
                    if (over) 0xFFE5484D.toInt() else 0xFF2F6BFF.toInt()
                )
                views.setTextViewText(R.id.widget_spent_text, "지출 " + won(daily.todaySpent))
                views.setTextViewText(R.id.widget_remaining_text, "한도 " + won(daily.dailyLimit))
                // 큰 숫자는 오늘 몫, 이 줄은 달 전체. 위젯은 한 칸짜리라 배지 아래
                // 남는 자리에 끼워 넣는다. 줄을 하나 더 쌓으면 아래가 잘린다.
                views.setTextViewText(
                    R.id.widget_month_text,
                    if (remain >= 0) "월 %s".format(StatusNotifier.monthShort(remain))
                    else "월 -%s".format(StatusNotifier.monthShort(-remain))
                )
                views.setTextColor(
                    R.id.widget_month_text,
                    if (remain < 0) 0xFFE5484D.toInt() else 0xFF8A8A94.toInt()
                )
            }
            StatusNotifier.paintDots(
                views, StatusNotifier.WIDGET_DOTS,
                daily.todaySpent, daily.dailyLimit, noBudget
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
