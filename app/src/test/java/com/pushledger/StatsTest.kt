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

    /**
     * 남은 돈은 고정지출을 미리 뺀다. 하루 한도와 기준이 같아야 한다.
     *
     * `monthlyBudget` 에는 월세 몫이 들어 있고 `total()` 에는 안 들어 있다. 그냥 빼면
     * 아직 안 나간 월세만큼 남은 돈이 부풀어, 같은 화면의 두 숫자가 다른 말을 한다.
     */
    @Test fun 남은_돈은_고정지출을_미리_뺀다() {
        val cfg = Config(
            monthlyBudget = 2_000_000L,
            fixed = listOf(Fixed(id = "f1", name = "월세", amount = 580_000L, day = 25))
        )
        val txns = listOf(txn("2026-08-05T12:00:00", 300_000L))
        assertEquals(1_120_000L, Stats.monthRemain(cfg, txns))

        // 월세가 실제로 나가도 같은 값이어야 한다. 나간 줄은 by="fixed" 라
        // total() 에 안 들어오므로 두 번 빠지지 않는다.
        val paid = txns + Txn(
            id = "r", amount = 580_000L, merchant = "월세", category = Cat.HOUSING.name,
            subCategory = "고정지출", at = "2026-08-25T12:00:00", by = "fixed"
        )
        assertEquals(1_120_000L, Stats.monthRemain(cfg, paid))
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

    @Test fun 말일_구독도_잡는다() {
        fun bill(month: Int, day: Int) = Txn(
            id = "e$month$day", amount = 9_900L, merchant = "구독서비스",
            at = "2026-%02d-%02dT09:00:00".format(month, day)
        )
        // 6/30 · 7/31 · 8/31. 날짜 숫자로는 들쭉날쭉해도 실제 간격은 31일과 31일이다.
        assertEquals(
            1, Stats.recurring(listOf(bill(6, 30), bill(7, 31), bill(8, 31)), Config()).size
        )
        // 2월이 낀 경우도 간격은 28일과 31일이라 한 달 언저리다.
        assertEquals(
            1, Stats.recurring(listOf(bill(1, 31), bill(2, 28), bill(3, 31)), Config()).size
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

    /**
     * 월말 예상은 월 예산과 같은 잣대로 재야 한다.
     *
     * 변동 소비만 늘려 놓고 월 예산 전체와 견주면 고정지출 몫만큼 늘 여유 있어 보인다.
     * 위젯의 '이대로면 월' 줄이 처음에 그렇게 틀려서, 넘길 판인데도 회색으로 떴다.
     */
    @Test fun 월말_예상은_고정지출을_계획_금액_그대로_더한다() {
        val cfg = Config(
            monthlyBudget = 1_000_000,
            fixed = listOf(Fixed(id = "f1", name = "월세", amount = 500_000, day = 1))
        )
        val d10 = java.time.LocalDate.of(2026, 8, 10)   // 31일 달의 열흘째

        // 열흘 동안 변동 소비 10만. 이 속도면 말일에 31만이고, 월세 50만을 더해 81만이다.
        val spent = (1..10).map { txn("2026-08-%02dT12:00:00".format(it), amount = 10_000) }
        assertEquals(310_000L + 500_000L, Stats.monthPace(cfg, spent, d10))

        // 고정지출은 늘리지 않는다. 월세가 이미 나갔어도(by="fixed") 예상은 그대로다.
        val withReal = spent + txn("2026-08-01T09:00:00", amount = 500_000, by = "fixed")
        assertEquals(310_000L + 500_000L, Stats.monthPace(cfg, withReal, d10))

        // 고정지출을 빼고 재면 81만이 31만으로 보여, 100만 예산을 넘길 판인데도 여유로 읽힌다.
        assertTrue(Stats.monthPace(cfg, spent + spent, d10) > cfg.monthlyBudget)
    }

    /**
     * '이 속도면 넘김' 은 이미 넘긴 것과 겹치지 않아야 한다.
     * 같은 항목이 '초과' 와 '넘길 것 같음' 두 줄로 동시에 뜨면 어느 쪽을 봐야 할지 모른다.
     */
    @Test fun 이_속도면_넘길_항목은_이미_넘긴_것과_겹치지_않는다() {
        val cfg = Config(
            monthlyBudget = 1_000_000,
            catBudget = mapOf(Cat.FOOD.name to 300_000, Cat.LEISURE.name to 200_000)
        )
        val d10 = java.time.LocalDate.of(2026, 8, 10)   // 31일 달의 열흘째

        fun buy(cat: Cat, amount: Long, day: Int) = Txn(
            id = "$cat$day", amount = amount, merchant = "가게",
            category = cat.name, at = "2026-08-%02dT12:00:00".format(day)
        )

        // 식비 15만(예산 30만 안이지만 이 속도면 46만) · 여가 25만(이미 20만을 넘김)
        val month = listOf(buy(Cat.FOOD, 150_000, 5), buy(Cat.LEISURE, 250_000, 6))

        assertEquals(listOf(Cat.LEISURE), Stats.overBudgetCats(cfg, month).map { it.first })
        assertEquals(listOf(Cat.FOOD), Stats.catPace(cfg, month, d10).map { it.first })

        // 두 목록이 겹치는 항목이 있으면 안 된다.
        val over = Stats.overBudgetCats(cfg, month).map { it.first }.toSet()
        assertTrue(Stats.catPace(cfg, month, d10).none { it.first in over })

        // 달 초 사흘은 표본이 며칠뿐이라 예상이 크게 튄다. 말을 꺼내지 않는다.
        assertTrue(Stats.catPace(cfg, month, java.time.LocalDate.of(2026, 8, 2)).isEmpty())

        // 이 속도로 가도 예산 안이면 아무것도 안 뜬다.
        val calm = listOf(buy(Cat.FOOD, 50_000, 5))
        assertTrue(Stats.catPace(cfg, calm, d10).isEmpty())
    }

    /**
     * `Stats.total()` 의 짝은 `cfg.monthlyBudget` 이 아니라 `variableBudget` 이다.
     *
     * 이 앱에서 숫자가 어긋난 자리는 거의 다 그 짝을 잘못 맞춘 것이었다 — 위젯의
     * '이대로면 월', 리포트의 월말 예상과 소진 예상일, 홈·예산 탭의 예산 띠,
     * 리포트 프롬프트까지 넷. `total` 은 고정지출을 빼고 세는데 `monthlyBudget` 에는
     * 그 몫이 들어 있어서, 나란히 놓으면 늘 고정지출만큼 여유 있어 보인다.
     */
    @Test fun 변동_예산은_고정지출과_저축을_뺀_값이다() {
        val cfg = Config(
            monthlyBudget = 1_000_000,
            fixed = listOf(Fixed(id = "f1", name = "월세", amount = 500_000, day = 1)),
            catBudget = mapOf("INVEST_GOAL" to 100_000)
        )
        assertEquals(400_000L, Stats.variableBudget(cfg))

        // 고정지출과 저축이 예산을 넘겨도 음수로 내려가지 않는다.
        val tight = cfg.copy(monthlyBudget = 300_000)
        assertEquals(0L, Stats.variableBudget(tight))

        // 실제로 나간 돈이 아니라 계획을 뺀다. 이달 소비가 얼마든 이 값은 안 변한다.
        val spent = listOf(
            Txn(id = "a", amount = 200_000, merchant = "가게", at = "2026-08-10T12:00:00")
        )
        assertEquals(400_000L, Stats.variableBudget(cfg))
        assertEquals(200_000L, Stats.total(spent))
        // 견줄 짝은 이 둘이다. total 을 monthlyBudget 과 견주면 80만이 남은 것처럼 보인다.
        assertEquals(200_000L, Stats.variableBudget(cfg) - Stats.total(spent))
    }

    /**
     * 튀는 결제. 표본이 적을 때 아무거나 튄다고 하면 그 표시를 아무도 안 믿게 된다.
     */
    @Test fun 튀는_결제는_표본이_모였을_때만_고른다() {
        fun t(n: Int, amount: Long) = txn("2026-08-%02dT12:00:00".format(n), amount = amount)

        // 네 건짜리 달. 하나가 아무리 커도 평균과 편차에 뜻이 없어 고르지 않는다.
        assertTrue(Stats.outliers(listOf(t(1, 1000), t(2, 1000), t(3, 1000), t(4, 900_000))).isEmpty())

        // 만원짜리 여덟 건에 30만원 하나. 이건 습관이 아니라 사건이다.
        val many = (1..8).map { t(it, 10_000) } + t(9, 300_000)
        assertEquals(listOf(300_000L), Stats.outliers(many).map { it.amount })

        // 전부 같은 금액이면 편차가 0 이라 튀는 건이 없다. 0 으로 나누지도 않는다.
        assertTrue(Stats.outliers((1..8).map { t(it, 10_000) }).isEmpty())

        // 고정지출 실행건은 소비가 아니므로 애초에 후보가 아니다.
        val withFixed = (1..8).map { t(it, 10_000) } +
            txn("2026-08-09T09:00:00", amount = 900_000, by = "fixed")
        assertTrue(Stats.outliers(withFixed).isEmpty())
    }
}
