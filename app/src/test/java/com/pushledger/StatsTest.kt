package com.pushledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/**
 * 주 구간과 고정지출 판정만 본다. 이 둘이 틀어지면 화면에서는 티가 안 나고
 * 숫자만 조용히 어긋난다 — 주 하나가 통째로 빠지거나, 같은 돈이 두 번 세어진다.
 */
class StatsTest {

    private fun txn(at: String, amount: Long = 1000, by: String = "rule", dedup: String = "") =
        Txn(id = at + by, amount = amount, merchant = "가게", at = at, by = by, dedup = dedup)

    @Test fun 주_구간은_달의_모든_날을_한_번씩만_덮는다() {
        listOf("2026-02", "2026-08", "2026-11", "2024-02").forEach { m ->
            val ym = YearMonth.parse(m)
            val spans = Stats.weeksOf(ym)
            val covered = spans.sumOf { it.days }
            assertEquals(m, ym.lengthOfMonth(), covered)
            // 구간은 이어져야 한다. 사이가 뜨면 그 날들은 어느 주에서도 안 보인다.
            spans.zipWithNext { a, b -> assertEquals(m, a.end.plusDays(1), b.start) }
            assertEquals(m, ym.atDay(1), spans.first().start)
            assertEquals(m, ym.atEndOfMonth(), spans.last().end)
        }
    }

    @Test fun 주를_고르면_그_주_거래만_남는다() {
        val ym = YearMonth.of(2026, 8)
        val spans = Stats.weeksOf(ym)
        val all = (1..ym.lengthOfMonth()).map { txn("2026-08-%02dT12:00:00".format(it)) }
        // 어느 주에도 안 잡히는 날이 있으면 합계가 모자란다.
        assertEquals(all.size, spans.sumOf { Stats.inSpan(all, it).size })
        assertEquals(all.size, Stats.inSpan(all, null).size)
    }

    @Test fun 이번_달_남은_돈은_저축_제외_설정을_따른다() {
        val txns = listOf(
            txn("2026-08-05T12:00:00", 300_000L),
            Txn(id = "i", amount = 200_000L, merchant = "적금", category = Cat.FINANCE.name,
                subCategory = "투자/저축", at = "2026-08-06T12:00:00")
        )
        val base = Config(monthlyBudget = 2_000_000L, catBudget = mapOf("INVEST_GOAL" to 500_000L))
        // 투자 건은 소비가 아니므로 어느 쪽이든 지출 30만만 빠진다.
        assertEquals(1_700_000L, Stats.monthRemain(base, txns))
        // 저축을 빼고 보면 목표 50만이 미리 잠긴다.
        assertEquals(1_200_000L, Stats.monthRemain(base.copy(budgetExcludesSaving = true), txns))
    }

    @Test fun 월별_합계는_거래가_없는_달도_한_칸씩_남긴다() {
        val m = Stats.byMonth(
            listOf(
                txn("2026-01-05T10:00:00", 10_000L),
                txn("2026-01-20T10:00:00", 5_000L),
                txn("2026-12-01T10:00:00", 7_000L)
            )
        )
        // 칸이 밀리면 12월 지출이 11월 자리에 그려진다. 화면에서는 티가 안 난다.
        assertEquals(12, m.size)
        assertEquals(15_000L, m[0])
        assertEquals(0L, m[5])
        assertEquals(7_000L, m[11])
    }

    @Test fun 매달_같은_금액이_나가면_고정지출로_권한다() {
        fun sub(month: Int, amount: Long = 17_000L, day: Int = 5) = Txn(
            id = "n$month$amount$day", amount = amount, merchant = "넷플릭스",
            at = "2026-%02d-%02dT09:00:00".format(month, day)
        )
        val three = listOf(sub(6), sub(7), sub(8))
        val cfg = Config()

        val hit = Stats.recurring(three, cfg).single()
        assertEquals("넷플릭스", hit.merchant)
        assertEquals(17_000L, hit.amount)
        assertEquals(5, hit.day)
        assertEquals(3, hit.months)

        // 두 달치는 우연일 수 있다. 세 달은 돼야 매달이라고 말할 수 있다.
        assertTrue(Stats.recurring(three.take(2), cfg).isEmpty())
        // 금액이 들쭉날쭉하면 구독이 아니라 그냥 자주 가는 곳이다.
        assertTrue(Stats.recurring(listOf(sub(6), sub(7, 30_000L), sub(8)), cfg).isEmpty())
        // 한 달에 두 번 간 곳도 구독이 아니다.
        assertTrue(Stats.recurring(three + sub(8, day = 20), cfg).isEmpty())
        // 이미 등록했거나 안 보기로 한 곳을 또 권하면 카드는 끌 수 없는 잔소리가 된다.
        assertTrue(
            Stats.recurring(three, cfg.copy(fixed = listOf(Fixed("1", "넷플릭스", 17_000L, 5)))).isEmpty()
        )
        assertTrue(Stats.recurring(three, cfg.copy(ignoredRecurring = setOf("넷플릭스"))).isEmpty())
    }

    @Test fun 청구서_꼬리표가_달라도_같은_반복_결제로_묶는다() {
        // 통신비·관리비는 달마다 이름 뒤가 바뀐다. 글자 그대로 견주면 매달 다른 가게가 되어
        // 정작 매달 나가는 청구서를 통째로 놓친다.
        fun bill(month: Int) = Txn(
            id = "b$month", amount = 55_000L,
            merchant = "(주)SKT 통신요금 ${month}월분",
            at = "2026-%02d-25T09:00:00".format(month)
        )
        val hit = Stats.recurring(listOf(bill(6), bill(7), bill(8)), Config()).single()
        assertEquals(55_000L, hit.amount)
        assertEquals(3, hit.months)
        // 화면에 보일 이름에는 껍데기와 꼬리표가 없어야 한다.
        assertFalse(hit.merchant, hit.merchant.contains("(주)"))
        assertFalse(hit.merchant, hit.merchant.contains("월분"))

        // 이미 등록했거나 안 보기로 한 것도 다듬은 이름으로 견준다.
        val known = Config(fixed = listOf(Fixed("1", "SKT 통신요금", 55_000L, 25)))
        assertTrue(Stats.recurring(listOf(bill(6), bill(7), bill(8)), known).isEmpty())
    }

    @Test fun 결제일이_들쭉날쭉하면_구독이_아니다() {
        // 석 달 연속 비슷한 값을 쓴 단골 식당은 구독이 아니다. 개수와 금액만 보면 걸린다.
        fun visit(month: Int, day: Int) = Txn(
            id = "v$month", amount = 17_000L, merchant = "동네국밥",
            at = "2026-%02d-%02dT12:00:00".format(month, day)
        )
        // 3일, 14일, 27일 — 날짜가 흩어져 있다.
        assertTrue(Stats.recurring(listOf(visit(6, 3), visit(7, 14), visit(8, 27)), Config()).isEmpty())
        // 25일, 25일, 27일 — 주말에 밀린 정도다. 구독으로 본다.
        assertEquals(
            1, Stats.recurring(listOf(visit(6, 25), visit(7, 25), visit(8, 27)), Config()).size
        )
    }

    @Test fun 예정_고정지출과_실제_고정지출을_가른다() {
        val plan = txn("2026-08-01T09:00:00", by = "fixed", dedup = "fixed|월세|500000|2026-08")
        val real = txn("2026-08-01T09:10:00", by = "fixed", dedup = "kb|500000|2026-08-01")
        assertTrue(plan.isFixedPlan)
        assertFalse(real.isFixedPlan)
        // 둘 다 소비 집계에서는 빠진다. 고정지출은 변동 예산에서 이미 뺀 돈이다.
        assertEquals(0L, Stats.total(listOf(plan, real)))
    }
}
