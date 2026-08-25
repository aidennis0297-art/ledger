package com.pushledger

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 통계 계산.
 * - 지출 집계([active]): 취소 거래, 투자([Txn.isInvestment]), 고정지출 실행건([by]=="fixed") 제외.
 * - 투자/저축 집계([investTotal]): 금융 내 투자/저축 및 by=="invest" 거래로 별도 집계.
 * - 수입 집계([incomeTotal]): [Cat.INCOME] 거래로 별도 집계하여 가용 예산 풀에 가산.
 */
object Stats {

    /** 취소되지 않은 순수 소비/지출 거래 목록 (고정지출 실행건, 투자, 수입 제외) */
    fun active(list: List<Txn>): List<Txn> =
        list.filter { !it.canceled && it.cat.isExpense && !it.isInvestment && it.by != "fixed" }

    /** 취소되지 않은 투자/저축 거래 목록 */
    fun activeInvest(list: List<Txn>): List<Txn> =
        list.filter { !it.canceled && it.isInvestment }

    /** 이번 달 총 추가 수입 (용돈, 상여금, 부수입 등) */
    fun incomeTotal(list: List<Txn>): Long =
        list.filter { !it.canceled && it.cat == Cat.INCOME }.sumOf { it.amount }

    /** 이번 달 고정지출 실제 결제/출금 건 합계 */
    fun fixedRecordedTotal(list: List<Txn>): Long =
        list.filter { !it.canceled && it.by == "fixed" }.sumOf { it.amount }

    private fun at(t: Txn): LocalDateTime = LocalDateTime.parse(t.at, Store.ts)

    /** 이번 달 총 소비 지출 (투자, 수입 및 고정지출 실행건 제외) */
    fun total(list: List<Txn>): Long = active(list).sumOf { it.amount }

    /** 이번 달 총 투자·저축액 */
    fun investTotal(list: List<Txn>): Long = activeInvest(list).sumOf { it.amount }

    /** 1일부터 말일까지. 지출 없는 날도 0 으로 채워 그래프 결측을 방지. */
    fun byDay(list: List<Txn>, ym: YearMonth): List<Long> {
        val out = LongArray(ym.lengthOfMonth())
        active(list).forEach { t ->
            val d = at(t).dayOfMonth
            if (d in 1..out.size) out[d - 1] += t.amount
        }
        return out.toList()
    }

    /** 0시부터 23시까지 시간대별 지출 금액. */
    fun byHour(list: List<Txn>): List<Long> {
        val out = LongArray(24)
        active(list).forEach { out[at(it).hour] += it.amount }
        return out.toList()
    }

    /** 0시부터 23시까지 시간대별 결제 건수(빈도). */
    fun byHourCount(list: List<Txn>): List<Long> {
        val out = LongArray(24)
        active(list).forEach { out[at(it).hour] += 1L }
        return out.toList()
    }

    /** 월요일이 0. 요일별 씀씀이. */
    fun byWeekday(list: List<Txn>): List<Long> {
        val out = LongArray(7)
        active(list).forEach { out[at(it).dayOfWeek.value - 1] += it.amount }
        return out.toList()
    }

    /** [요일][시간] 격자. 시간대 패턴 히트맵용. */
    fun heat(list: List<Txn>): List<List<Long>> {
        val out = Array(7) { LongArray(24) }
        active(list).forEach {
            val d = at(it)
            out[d.dayOfWeek.value - 1][d.hour] += it.amount
        }
        return out.map { it.toList() }
    }

    /**
     * 달을 월~일 주 단위로 자른다. 첫 주와 마지막 주는 잘린 채로 남는다.
     *
     * 예전에는 1일부터 7일씩 기계적으로 끊었다. 그러면 매달 "1주" 의 시작 요일이
     * 달라져서, 요일이 축인 그래프(요일별, 요일×시간)에서 주를 골라 볼 방법이 없었다.
     * 여기서 나온 구간은 그 달의 모든 날을 빠짐없이, 겹치지 않게 덮는다.
     */
    fun weeksOf(ym: YearMonth): List<WeekSpan> {
        val first = ym.atDay(1)
        val last = ym.atEndOfMonth()
        val out = mutableListOf<WeekSpan>()
        var cur = first.minusDays((first.dayOfWeek.value - 1).toLong())
        var i = 0
        while (!cur.isAfter(last)) {
            val s = if (cur.isBefore(first)) first else cur
            val e = cur.plusDays(6).let { if (it.isAfter(last)) last else it }
            out.add(WeekSpan(i, s, e))
            cur = cur.plusDays(7)
            i++
        }
        return out
    }

    /** 한 주 구간만 남긴다. 날짜 문자열이 정렬 가능한 꼴이라 앞 10글자 비교로 끝난다. */
    fun inSpan(list: List<Txn>, span: WeekSpan?): List<Txn> {
        if (span == null) return list
        val from = span.start.toString()
        val to = span.end.toString()
        return list.filter { it.at.substring(0, 10) in from..to }
    }

    /** 월 안에서 1주차부터. 구간은 [weeksOf] 와 같아서 주 선택 칩과 눈금이 어긋나지 않는다. */
    fun byWeek(list: List<Txn>, ym: YearMonth): List<Long> {
        val spans = weeksOf(ym)
        val out = LongArray(spans.size)
        active(list).forEach { t ->
            val d = at(t).toLocalDate()
            val i = spans.indexOfFirst { !d.isBefore(it.start) && !d.isAfter(it.end) }
            if (i >= 0) out[i] += t.amount
        }
        return out.toList()
    }

    /**
     * 1월부터 12월까지 월별 소비 합계. 거래가 없는 달도 0 으로 남는다.
     *
     * 넘겨받은 목록이 그 해 것이라고 믿는다. 달을 [Store.readYear] 가 이미 갈라 읽어
     * 왔으므로 여기서 연도를 또 거르면 같은 일을 두 번 하는 셈이다.
     */
    fun byMonth(list: List<Txn>): List<Long> {
        val out = LongArray(12)
        active(list).forEach { out[at(it).monthValue - 1] += it.amount }
        return out.toList()
    }

    /**
     * 매달 같은 곳에서 비슷한 금액이 빠져나가는 결제.
     *
     * 고정지출은 지금까지 전부 손으로 등록해야 했다. 구독료처럼 소리 없이 매달
     * 빠지는 돈은 등록 전까지 변동 지출로 잡히고, 그만큼 하루 예산이 실제보다
     * 넉넉하게 계산된다.
     *
     * 판정은 세 가지만 본다. 서로 다른 달에 [minMonths] 번 이상 나올 것, 금액이
     * 중앙값에서 20% 안에 있을 것, 그 달에 한 번씩만 나올 것. 날짜가 며칠 밀리는
     * 것은 보지 않는다 — 결제일이 주말이면 밀리는데, 그걸 걸면 진짜 구독이 걸러진다.
     *
     * ponytail: 가맹점 이름이 글자까지 같아야 묶인다. 달마다 "8월분" 같은 꼬리가
     * 붙는 청구서는 못 잡는다. 놓치는 게 눈에 띄면 그때 Store.normMerchant 를 꺼내 쓴다.
     */
    fun recurring(list: List<Txn>, cfg: Config, minMonths: Int = 3): List<Recurring> {
        val known = cfg.fixed.map { it.name.trim() }.toSet()
        return active(list)
            .filter { it.merchant.isNotBlank() }
            .groupBy { it.merchant.trim() }
            .mapNotNull inner@{ (m, txns) ->
                if (m in known || m in cfg.ignoredRecurring) return@inner null
                // 한 달에 여러 번 간 곳은 구독이 아니라 단골이다.
                val perMonth = txns.groupBy { it.at.substring(0, 7) }
                if (perMonth.size < minMonths || perMonth.any { it.value.size > 1 }) return@inner null

                val picks = perMonth.values.map { it.first() }
                val amounts = picks.map { it.amount }.sorted()
                val mid = amounts[amounts.size / 2]
                // 중앙값에서 20% 넘게 벌어진 달이 하나라도 있으면 고정 금액이 아니다.
                if (amounts.any { Math.abs(it - mid) * 5 > mid }) return@inner null

                val days = picks.map { at(it).dayOfMonth }.sorted()
                val latest = picks.maxByOrNull { it.at }!!
                Recurring(m, mid, days[days.size / 2], perMonth.size, latest.cat, latest.subCategory)
            }
            .sortedByDescending { it.amount }
    }

    fun byCat(list: List<Txn>): List<Pair<Cat, Long>> =
        active(list).groupBy { it.cat }
            .map { (c, v) -> c to v.sumOf { t -> t.amount } }
            .sortedByDescending { it.second }

    /**
     * 방문 횟수순. 금액순과 답이 다르다. 큰 한 방보다 자주 새는 곳이 습관을 만든다.
     * 값에는 횟수가 들어간다.
     */
    fun topMerchantsByCount(list: List<Txn>, n: Int = 10): List<Pair<String, Long>> =
        active(list).filter { it.merchant.isNotBlank() }
            .groupBy { it.merchant }
            .map { (m, v) -> m to v.size.toLong() }
            .sortedByDescending { it.second }
            .take(n)

    /** 가맹점별 결제 금액 합계 순. */
    fun topMerchants(list: List<Txn>, n: Int = 10): List<Pair<String, Long>> =
        active(list).filter { it.merchant.isNotBlank() }
            .groupBy { it.merchant }
            .map { (m, v) -> m to v.sumOf { t -> t.amount } }
            .sortedByDescending { it.second }
            .take(n)

    /** 예산 탭에서 정한 이번 달 투자·저축 목표. 키가 흩어지지 않게 여기서만 읽는다. */
    fun investGoal(cfg: Config): Long =
        cfg.catBudget["INVEST_GOAL"] ?: cfg.catBudget[Cat.FINANCE.name + "_INVEST"] ?: 0L

    /**
     * 하루에 쓸 수 있는 변동 예산.
     *
     * 예전에는 그냥 월 예산을 일수로 나눴다. 그러면 월세와 저축까지 매일 조금씩
     * 쓸 수 있는 돈인 것처럼 선이 그어져, 실제로 지킬 수 있는 선보다 훨씬 높게 잡혔다.
     * 고정지출과 저축 몫은 애초에 빼 두고, 들어온 돈은 더한 뒤에 나눈다.
     *
     * 홈의 오늘 가용 예산([dailyBudget])과 기준이 같아서 두 화면의 숫자가 어긋나지 않는다.
     * 다만 이쪽은 그 달 전체를 고르게 나눈 값이라 지난 달 그래프에도 그대로 쓸 수 있다.
     */
    fun dailyVariableBudget(cfg: Config, month: List<Txn>, ym: YearMonth): Long {
        if (cfg.monthlyBudget <= 0L) return 0L
        val pool = cfg.monthlyBudget + incomeTotal(month) - fixedTotal(cfg) - investGoal(cfg)
        return (pool.coerceAtLeast(0L) / ym.lengthOfMonth()) / 10L * 10L
    }

    /**
     * 시간대별 지출을 하루 평균으로 환산한다.
     *
     * 시간대 그래프의 원래 값은 그 달에 쌓인 누적이라, 하루 예산선과 나란히 두면
     * 단위가 달라 아무 뜻도 없는 비교가 된다. 경과한 날수로 나눠 두면
     * "이 시간대에 하루 평균 얼마를 쓰는가" 가 되어 하루 예산과 견줄 수 있다.
     * 어느 한 시간대가 선을 넘으면 그 시간대만으로 하루치를 다 쓴다는 뜻이다.
     */
    fun byHourDaily(list: List<Txn>, ym: YearMonth, today: java.time.LocalDate): List<Long> =
        byHourDaily(list, if (ym == YearMonth.from(today)) today.dayOfMonth else ym.lengthOfMonth())

    /** 주를 골라 보는 화면에서는 그 주에 실제로 지난 날수로 나눠야 한다. */
    fun byHourDaily(list: List<Txn>, days: Int): List<Long> {
        val d = days.coerceAtLeast(1)
        return byHour(list).map { it / d }
    }

    /** 한 주 안에서 지금까지 지난 날수. 아직 오지 않은 날로 나누면 평균이 헐거워진다. */
    fun elapsedDays(span: WeekSpan, today: LocalDate): Int {
        val end = if (span.end.isAfter(today)) today else span.end
        return ((end.toEpochDay() - span.start.toEpochDay()).toInt() + 1).coerceAtLeast(1)
    }

    /**
     * 이번 달 남은 돈.
     *
     * 홈의 큰 숫자, 상태창 알림, 위젯이 모두 이 값을 쓴다. 세 곳이 각자 계산하면
     * 저축 제외 스위치를 켰을 때 화면마다 다른 숫자를 말하게 된다.
     */
    fun monthRemain(cfg: Config, month: List<Txn>): Long =
        cfg.monthlyBudget - total(month) -
            (if (cfg.budgetExcludesSaving) investGoal(cfg) else 0L)

    /** 이번 달 고정지출 합계. */
    fun fixedTotal(cfg: Config): Long = cfg.fixed.sumOf { it.amount }

    /**
     * 항목별로 배정된 총 예산 합계.
     *
     * 고정지출[Cat.HOUSING]은 여기서 뺀다. 고정지출 예산은 [fixedTotal] 로 이미 한 번
     * 총예산에서 빠지는데, 카테고리에도 배정하면 같은 돈이 두 번 깎인다.
     * 고정지출의 예산은 등록한 고정지출 항목의 합, 그 하나뿐이다.
     */
    fun allocatedCatBudgetTotal(cfg: Config): Long =
        BUDGETABLE_CATS.sumOf { c -> cfg.catBudget[c.name] ?: 0L }

    /** 사용자가 직접 예산을 매길 수 있는 항목. 고정지출과 수입은 여기 없다. */
    val BUDGETABLE_CATS: List<Cat> =
        Cat.entries.filter { it.isExpense && it != Cat.ETC && it != Cat.HOUSING }

    /**
     * 고정지출 진행 상황: 계획한 금액과 실제로 빠져나간 금액.
     * 계획만 보여 주면 이번 달에 정말 나갔는지 알 수 없고,
     * 실제만 보여 주면 앞으로 얼마가 더 나갈지 알 수 없다.
     */
    fun fixedProgress(cfg: Config, month: List<Txn>): Pair<Long, Long> =
        fixedTotal(cfg) to fixedRecordedTotal(month)

    /**
     * 미배정 자유 예산 = 총 예산 - 고정지출 - 항목별 배정 예산 합계.
     */
    fun unallocatedBudget(cfg: Config): Long =
        cfg.monthlyBudget - fixedTotal(cfg) - allocatedCatBudgetTotal(cfg)

    /**
     * 여유비 = 총 예산 + 추가 수입 - 고정지출 - 이번 달 소비 - 투자.
     */
    fun spare(cfg: Config, month: List<Txn>): Long {
        val spent = total(month)
        val invested = investTotal(month)
        val income = incomeTotal(month)
        return cfg.monthlyBudget + income - fixedTotal(cfg) - spent - invested
    }

    /** 카테고리별 예산 소진율. */
    fun catProgress(cfg: Config, month: List<Txn>): List<Triple<Cat, Long, Long>> {
        val spent = byCat(month).toMap()
        return cfg.catBudget.mapNotNull { (k, budget) ->
            if (budget <= 0 || Cat.of(k) == Cat.HOUSING) null
            else {
                val cat = Cat.of(k)
                val catSpent = spent[cat] ?: 0L
                Triple(cat, catSpent, budget)
            }
        }.sortedByDescending { it.second.toDouble() / it.third }
    }

    /** 예산을 초과한 항목 및 초과 금액 목록. */
    fun overBudgetCats(cfg: Config, month: List<Txn>): List<Pair<Cat, Long>> {
        val spent = byCat(month).toMap()
        return cfg.catBudget.mapNotNull { (k, budget) ->
            // 고정지출은 실행건이 소비 집계에서 빠지므로 항상 0 으로 잡힌다. 초과 판정 대상이 아니다.
            if (budget <= 0 || Cat.of(k) == Cat.HOUSING) null
            else {
                val cat = Cat.of(k)
                val catSpent = spent[cat] ?: 0L
                if (catSpent > budget) cat to (catSpent - budget) else null
            }
        }.sortedByDescending { it.second }
    }

    /**
     * 하루 가용 예산 (N빵).
     */
    fun dailyBudget(cfg: Config, month: List<Txn>, today: java.time.LocalDate = java.time.LocalDate.now()): DailyStatus {
        val daysInMonth = today.lengthOfMonth()
        val remainingDays = (daysInMonth - today.dayOfMonth + 1).coerceAtLeast(1)

        val activeTxns = active(month)
        val invested = investTotal(month)
        val income = incomeTotal(month)
        val fixedPlan = fixedTotal(cfg)

        val todayPrefix = today.toString()
        val todaySpent = activeTxns.filter { it.at.startsWith(todayPrefix) }.sumOf { it.amount }
        val pastSpent = activeTxns.filter { !it.at.startsWith(todayPrefix) && it.at < todayPrefix }.sumOf { it.amount }

        val totalAvailableForMonth = (cfg.monthlyBudget + income - fixedPlan - invested).coerceAtLeast(0L)
        val remainingBudgetForMonth = (totalAvailableForMonth - pastSpent).coerceAtLeast(0L)

        // 10원 단위 아래는 버린다. 상태창에 66,666원처럼 뜨면 읽는 데 방해만 된다.
        val dailyLimit =
            if (remainingDays > 0) (remainingBudgetForMonth / remainingDays) / 10L * 10L else 0L
        val remaining = dailyLimit - todaySpent
        val isSuccess = remaining >= 0
        val monthSpare = spare(cfg, month)
        val overBudgets = overBudgetCats(cfg, month)

        return DailyStatus(
            dailyLimit = dailyLimit,
            todaySpent = todaySpent,
            remaining = remaining,
            isSuccess = isSuccess,
            remainingDays = remainingDays,
            monthSpare = monthSpare,
            overBudgetCats = overBudgets,
            incomeTotal = income
        )
    }
}

/**
 * 고정지출로 올릴 만한 반복 결제 하나.
 * [months] 는 몇 달치가 근거인지다 — 사용자가 권유를 믿을지 정하는 근거라 같이 들고 다닌다.
 */
data class Recurring(
    val merchant: String,
    val amount: Long,
    val day: Int,
    val months: Int,
    val cat: Cat,
    val sub: String
)

/** 달을 덮는 월~일 한 구간. 달 경계에서 잘린 주는 그만큼만 갖는다. */
data class WeekSpan(val index: Int, val start: LocalDate, val end: LocalDate) {
    val label: String get() = "${index + 1}주"
    val range: String get() = "${start.monthValue}/${start.dayOfMonth}~${end.monthValue}/${end.dayOfMonth}"
    val days: Int get() = (end.toEpochDay() - start.toEpochDay()).toInt() + 1
}

data class DailyStatus(
    val dailyLimit: Long,
    val todaySpent: Long,
    val remaining: Long,
    val isSuccess: Boolean,
    val remainingDays: Int,
    val monthSpare: Long = 0L,
    val overBudgetCats: List<Pair<Cat, Long>> = emptyList(),
    val incomeTotal: Long = 0L
)
