package com.pushledger

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.YearMonth

/**
 * 위젯 칸 크기를 보고 무엇을 펼칠지 정한 결과.
 *
 * [dots] 는 한 줄에 놓을 알갱이 수, [dotRows] 는 줄 수다. 둘을 나눠 둔 이유는
 * 불변식 13 이다 — 띠를 두껍게 할 때 알갱이를 키우지 않고 줄을 늘린다.
 */
data class WidgetPlan(
    val dots: Int,
    val dotRows: Int,
    val showBadge: Boolean,
    val showMonth: Boolean,
    val showToday: Boolean,
    val showForecast: Boolean,
    val shortAmount: Boolean,
    val amountSp: Float,
    val padDp: Int
)

/**
 * 1x1 부터 큰 칸까지 한 레이아웃으로 받는다.
 *
 * 크기마다 레이아웃 파일을 따로 두는 길도 있지만, 그러면 같은 줄을 네 벌 고쳐야 하고
 * 한 벌을 빠뜨리면 그 크기에서만 조용히 틀린다. 줄은 한 벌만 두고 접었다 편다.
 *
 * 경계는 넉넉하게 잡았다. 런처가 주는 dp 는 기기마다 들쭉날쭉하고, 애매할 때는
 * **덜 보이는 쪽으로 기운다** — 잘린 글자보다 없는 줄이 읽기 쉽다.
 * 위젯 큰 숫자가 잘리는 것이 이 앱에서 실제로 걱정하던 자리다.
 */
object WidgetPlanner {
    /** 알갱이 8dp + 사이 2dp. 레이아웃의 `wd*` 크기와 같이 움직여야 한다. */
    const val DOT_STRIDE = 10

    /** 이 아래로 떨어지면 띠가 눈금 구실을 못 한다. 한 알이 한도의 십몇 %씩 뛴다. */
    const val MIN_DOTS = 8

    fun plan(wDp: Int, hDp: Int, cfg: Config): WidgetPlan {
        // 1x1 에서 여백을 14dp 씩 먹으면 글자 놓을 자리가 안 남는다.
        val pad = if (wDp < 110 || hDp < 70) 6 else 14
        val amountSp = when {
            wDp < 110 -> 14f
            wDp < 180 -> 16f
            wDp < 250 -> 18f
            else -> 20f
        }
        val perRow =
            if (!cfg.widgetDots) 0
            else ((wDp - pad * 2) / DOT_STRIDE).coerceAtMost(StatusNotifier.WIDGET_DOTS_PER_ROW)
        val dots = if (perRow >= MIN_DOTS && hDp >= 100) perRow else 0
        return WidgetPlan(
            dots = dots,
            dotRows = if (dots == 0) 0 else if (hDp >= 180) 2 else 1,
            showBadge = wDp >= 110,
            showMonth = cfg.widgetMonth && wDp >= 110 && hDp >= 70,
            // 배지와 '이달 남은 돈' 은 큰 숫자 옆에 세로로 쌓이므로 한 칸 높이에도 들어간다.
            // 아래 줄은 그 밑에 새로 쌓이는 줄이라 한 칸(약 70dp)에서는 자리가 안 난다.
            showToday = cfg.widgetToday && wDp >= 110 && hDp >= 90,
            showForecast = cfg.widgetForecast && wDp >= 180 && hDp >= 140,
            shortAmount = wDp < 130,
            amountSp = amountSp,
            padDp = pad
        )
    }
}

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

    /**
     * 사용자가 위젯을 늘리거나 줄이면 런처가 이걸 부른다.
     * 여기서 다시 그리지 않으면 칸만 변하고 내용은 예전 크기 기준으로 남는다.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        Store.ensure(context)
        updateWidget(context, appWidgetManager, appWidgetId)
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

            // 세로는 MIN 이 아니라 MAX 를 본다. 런처가 주는 OPTION_APPWIDGET_MIN_HEIGHT 는
            // '가로 화면일 때의 높이' 라, 세로로 쓰는 폰에서 그대로 믿으면 실제보다 한참
            // 낮게 잡혀 도트 띠가 통째로 사라진다. 폭은 반대로 MIN 이 세로 화면 기준이다.
            val opt = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val wDp = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 240
            // 런처가 아직 크기를 안 알려 준 첫 그림에서는 선언한 기본 칸(4x2)으로 친다.
            // 여기서 90 을 쓰면 첫 그림만 도트 없이 떴다가 곧 도트가 생겨 깜빡여 보인다.
            val hDp = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: 110
            val plan = WidgetPlanner.plan(wDp, hDp, cfg)

            val views = RemoteViews(context.packageName, R.layout.widget_daily_budget)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            val padPx = (plan.padDp * context.resources.displayMetrics.density).toInt()
            views.setViewPadding(R.id.widget_root, padPx, padPx, padPx, padPx)
            views.setTextViewTextSize(
                R.id.widget_daily_amount, TypedValue.COMPLEX_UNIT_SP, plan.amountSp
            )

            // 상태창 알림과 같은 말을 같은 순서로 한다. 두 곳이 다른 숫자를 앞세우면 헷갈린다.
            val noBudget = cfg.monthlyBudget == 0L
            if (noBudget) {
                views.setTextViewText(
                    R.id.widget_daily_amount, if (plan.shortAmount) "미설정" else "예산 미설정"
                )
                views.setTextColor(R.id.widget_daily_amount, INK)
                views.setTextViewText(R.id.widget_status_badge, "설정")
                views.setTextColor(R.id.widget_status_badge, ACCENT)
                views.setTextViewText(R.id.widget_spent_text, "예산 탭에서")
                views.setTextViewText(R.id.widget_remaining_text, "월 예산 입력")
                views.setTextViewText(R.id.widget_month_text, "")
            } else {
                val over = !daily.isSuccess
                val left = if (over) -daily.remaining else daily.remaining
                // 1x1 에서는 "4만 5000원" 이 통째로 안 들어간다. 좁으면 만 단위로 줄인다.
                views.setTextViewText(
                    R.id.widget_daily_amount,
                    if (plan.shortAmount) StatusNotifier.monthShort(left) else won(left)
                )
                views.setTextColor(R.id.widget_daily_amount, if (over) OVER else INK)
                views.setTextViewText(R.id.widget_status_badge, if (over) "초과" else "남음")
                views.setTextColor(R.id.widget_status_badge, if (over) OVER else ACCENT)
                views.setTextViewText(R.id.widget_spent_text, "지출 " + won(daily.todaySpent))
                views.setTextViewText(R.id.widget_remaining_text, "한도 " + won(daily.dailyLimit))
                // 큰 숫자는 오늘 몫, 이 줄은 달 전체. 배지 아래 남는 자리에 끼워 넣는다.
                views.setTextViewText(
                    R.id.widget_month_text,
                    if (remain >= 0) "월 %s".format(StatusNotifier.monthShort(remain))
                    else "월 -%s".format(StatusNotifier.monthShort(-remain))
                )
                views.setTextColor(R.id.widget_month_text, if (remain < 0) OVER else SUB)

                // 예산을 넘긴 뒤가 아니라 넘기기 전에 알려 준다. 오늘까지의 속도를 말일까지 잇는다.
                val pace = Stats.total(monthTxns) / LocalDate.now().dayOfMonth * ym.lengthOfMonth()
                views.setTextViewText(
                    R.id.widget_forecast_text, "이대로면 월 " + StatusNotifier.monthShort(pace)
                )
                views.setTextColor(R.id.widget_forecast_text, if (pace > cfg.monthlyBudget) OVER else SUB)
            }

            views.setViewVisibility(R.id.widget_status_badge, vis(plan.showBadge))
            views.setViewVisibility(R.id.widget_month_text, vis(plan.showMonth))
            views.setViewVisibility(R.id.widget_today_row, vis(plan.showToday))
            views.setViewVisibility(R.id.widget_forecast_text, vis(plan.showForecast && !noBudget))
            views.setViewVisibility(R.id.widget_dots_wrap, vis(plan.dotRows > 0))
            views.setViewVisibility(R.id.widget_dots_row2, vis(plan.dotRows >= 2))

            // 안 쓰는 알갱이는 접는다. 남겨 두면 색 없는 칸이 띠 끝에 꼬리처럼 붙는다.
            val ids = StatusNotifier.WIDGET_DOTS
            val perRow = StatusNotifier.WIDGET_DOTS_PER_ROW
            val shown = ArrayList<Int>(plan.dots * plan.dotRows)
            for (r in 0 until ids.size / perRow) {
                for (i in 0 until perRow) {
                    val on = r < plan.dotRows && i < plan.dots
                    views.setViewVisibility(ids[r * perRow + i], vis(on))
                    if (on) shown.add(ids[r * perRow + i])
                }
            }
            StatusNotifier.paintDots(
                views, shown.toIntArray(), daily.todaySpent, daily.dailyLimit, noBudget
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun vis(on: Boolean) = if (on) View.VISIBLE else View.GONE

        private val INK = 0xFF16161A.toInt()
        private val SUB = 0xFF8A8A94.toInt()
        private val ACCENT = 0xFF2F6BFF.toInt()
        private val OVER = 0xFFE5484D.toInt()
    }
}
