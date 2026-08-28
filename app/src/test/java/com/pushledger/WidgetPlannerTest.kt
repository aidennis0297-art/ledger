package com.pushledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 위젯 칸 크기에 따른 접힘. 기기가 없어 눈으로 못 보는 자리라 여기서 대신 본다.
 *
 * 위젯이 안 뜨거나 글자가 잘리는 것이 이 앱에서 실제로 겪은 실패다. 그래서 검사의
 * 방향은 한쪽이다 — **작을수록 덜 보여야 한다.** 큰 칸에서 뭐가 더 나오는지보다,
 * 작은 칸에서 아무것도 넘치지 않는지가 중요하다.
 */
class WidgetPlannerTest {

    private val on = Config(monthlyBudget = 500_000)

    /** 1x1. 오늘 남은 돈 하나만 남고, 그 숫자도 짧은 꼴로 줄어야 한다. */
    @Test fun 한칸짜리는_숫자_하나만_남긴다() {
        val p = WidgetPlanner.plan(40, 40, on)
        assertEquals(0, p.dotRows)
        assertEquals(0, p.dots)
        assertFalse(p.showBadge)
        assertFalse(p.showToday)
        assertFalse(p.showMonth)
        assertFalse(p.showForecast)
        assertTrue(p.shortAmount)
        assertEquals(6, p.padDp)
    }

    /**
     * 가로로만 긴 4x1. 폭이 넉넉해도 높이가 없으면 새로 쌓는 줄은 못 넣는다.
     * 배지와 이달 남은 돈은 큰 숫자 옆에 쌓이므로 이 높이에서도 살아남는다.
     */
    @Test fun 낮으면_아래로_쌓는_줄부터_접는다() {
        val p = WidgetPlanner.plan(250, 70, on)
        assertEquals(0, p.dotRows)
        assertFalse(p.showToday)
        assertTrue(p.showBadge)
        assertTrue(p.showMonth)
        assertFalse(p.showForecast)

        // 두 칸 높이가 되면 아래 줄이 돌아온다.
        assertTrue(WidgetPlanner.plan(250, 90, on).showToday)
    }

    /** 기본 크기(4x2). 지금까지 쓰던 모습 그대로 — 띠 한 줄에 열여섯 알. */
    @Test fun 기본_크기는_한_줄을_가득_채운다() {
        val p = WidgetPlanner.plan(240, 110, on)
        assertEquals(1, p.dotRows)
        assertEquals(StatusNotifier.WIDGET_DOTS_PER_ROW, p.dots)
        assertTrue(p.showBadge)
        assertTrue(p.showToday)
        assertTrue(p.showMonth)
        assertFalse(p.shortAmount)
    }

    /** 높으면 알갱이를 키우지 않고 줄을 늘린다. 불변식 13. */
    @Test fun 높으면_알갱이가_아니라_줄이_늘어난다() {
        val small = WidgetPlanner.plan(240, 110, on)
        val tall = WidgetPlanner.plan(240, 200, on)
        assertEquals(2, tall.dotRows)
        assertEquals(small.dots, tall.dots)
    }

    /** 좁으면 한 줄에 들어갈 만큼만 깔고, 여덟 알 아래로 떨어지면 아예 접는다. */
    @Test fun 좁으면_알갱이_수가_줄고_바닥에서_접힌다() {
        val narrow = WidgetPlanner.plan(150, 110, on)
        assertTrue(narrow.dots in WidgetPlanner.MIN_DOTS until StatusNotifier.WIDGET_DOTS_PER_ROW)

        // 폭 90dp 는 여백을 빼면 일곱 알밖에 안 되고, 그 아래는 눈금 구실을 못 해 접는다.
        val tooNarrow = WidgetPlanner.plan(90, 200, on)
        assertEquals(0, tooNarrow.dots)
        assertEquals(0, tooNarrow.dotRows)
    }

    /** 사용자가 끈 것은 칸이 아무리 넓어도 되살아나지 않는다. */
    @Test fun 끈_것은_큰_칸에서도_안_보인다() {
        val p = WidgetPlanner.plan(320, 250, on.copy(widgetDots = false, widgetMonth = false, widgetToday = false))
        assertEquals(0, p.dots)
        assertEquals(0, p.dotRows)
        assertFalse(p.showMonth)
        assertFalse(p.showToday)
    }

    /** 월말 예상은 켠 데다 자리까지 넉넉해야 나온다. 기본값은 꺼짐이다. */
    @Test fun 월말_예상은_켜고_넓어야_나온다() {
        assertFalse(WidgetPlanner.plan(320, 250, on).showForecast)
        val want = on.copy(widgetForecast = true)
        assertTrue(WidgetPlanner.plan(320, 250, want).showForecast)
        assertFalse(WidgetPlanner.plan(320, 110, want).showForecast)
        assertFalse(WidgetPlanner.plan(150, 250, want).showForecast)
    }

    /** 글자 크기는 폭을 따라 커지되 순서가 뒤집히면 안 된다. */
    @Test fun 넓어질수록_숫자가_커진다() {
        val sizes = listOf(40, 110, 180, 250, 320).map { WidgetPlanner.plan(it, 200, on).amountSp }
        assertEquals(sizes.sorted(), sizes)
        assertTrue(sizes.first() < sizes.last())
    }
}
