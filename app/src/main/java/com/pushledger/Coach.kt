package com.pushledger

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** 코치 리포트 종류. 프롬프트와 데이터 범위가 종류마다 다르다. */
enum class ReportKind(val label: String, val months: Int) {
    MONTHLY("이번 달 코칭", 1),
    QUARTER("3개월 총괄", 3),
    PLAN("다음 달 예산 설계", 2)
}

/**
 * 코치 리포트를 앱 전체가 공유하는 자리에서 돌린다.
 * 화면 안에서 코루틴을 띄우면 탭을 옮기는 순간 화면이 사라지면서 생성도 같이 죽는다.
 * 여기서 돌리면 다른 탭을 보고 와도 계속 돌고, 돌아왔을 때 진행 중인지도 알 수 있다.
 */
object CoachRun {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val running = MutableStateFlow(false)
    val kind = MutableStateFlow(ReportKind.MONTHLY)
    val message = MutableStateFlow("")

    /** 시작하지 못하면 이유를 [message] 에 남기고 false. 키 없이 부르면 실패 문구가 리포트로 저장됐다. */
    fun start(ctx: Context, k: ReportKind, useAi: Boolean): Boolean {
        if (running.value) return false
        if (useAi && Nvidia.apiKey(ctx).isBlank()) {
            message.value = "AI 키가 없습니다. 설정에서 넣거나 기기 분석으로 만들어 보세요."
            return false
        }
        running.value = true
        kind.value = k
        message.value = if (useAi) "AI 가 소비 내역을 읽는 중" else "소비 내역을 계산하는 중"
        // 진행 표시는 화면마다 만들지 않는다. 어느 탭에 있든 위쪽 띠에 이게 뜬다.
        AiJob.start(k.label, 0, message.value)

        scope.launch {
            val cfg = Store.config.value
            val txns = Store.recentMonths(k.months)
            val text = try {
                if (useAi) Nvidia.generateReport(ctx, k, cfg.profile, txns, cfg)
                else LocalCoach.build(
                    k, cfg.profile, txns, cfg,
                    prev = Store.readMonth(YearMonth.now().minusMonths(1))
                )
            } catch (e: Exception) {
                "리포트 생성 실패: ${e.message ?: "알 수 없는 오류"}"
            }
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            Store.addReport(
                AiReport(
                    content = text,
                    createdAt = "$stamp · ${k.label}${if (useAi) "" else " · 기기 분석"}",
                    id = Store.newId(),
                    kind = k.name,
                    byAi = useAi
                )
            )
            message.value = "완료"
            running.value = false
            AiJob.finish("완료")
        }
        return true
    }
}

/**
 * AI 없이 만드는 리포트.
 *
 * 코치 리포트의 알맹이는 사실 집계와 정렬이다. AI 는 그 위에 문장을 씌울 뿐이라,
 * 문장 틀만 준비해 두면 키 없이도 같은 모양의 보고서가 나온다.
 *
 * 처음에는 AI 쪽 3문단 규격을 흉내 내느라 열 줄 남짓으로 끝났다. 그런데 기기에서
 * 할 수 있는 계산은 거기서 멈출 이유가 없다 — 시간대·요일·연속 무지출일·소액 누수·
 * 반복 결제 후보·이상치 결제·집중도·월말 예상치는 전부 이미 가진 데이터로 나온다.
 * 오히려 이쪽이 AI 보다 정확하다. 숫자를 지어내지 않기 때문이다.
 * 그래서 기기 분석은 AI 와 같은 형식을 쓰되 문단을 여섯으로 늘려 더 깊이 판다.
 */
object LocalCoach {

    /** [prev] 는 지난달 거래. 없으면 지난달 비교 줄만 빠진다. */
    fun build(
        k: ReportKind,
        profile: UserProfile,
        txns: List<Txn>,
        cfg: Config,
        prev: List<Txn> = emptyList()
    ): String {
        val active = Stats.active(txns)
        if (active.isEmpty()) return "아직 분석할 지출이 없어요. 며칠 써 보고 다시 눌러 주세요."
        return when (k) {
            ReportKind.QUARTER -> quarter(profile, txns, cfg)
            ReportKind.PLAN -> plan(profile, txns, cfg)
            ReportKind.MONTHLY -> monthly(profile, txns, cfg, prev)
        }
    }

    // ---------------------------------------------------------------- 집계 도구

    private fun at(t: Txn): LocalDateTime = LocalDateTime.parse(t.at, Store.ts)


    /** 만원 단위로 뭉갠 금액. 리포트에서 1원 단위는 읽는 데 방해만 된다. */
    private fun round(v: Long): Long = v / 1_000 * 1_000

    private fun share(part: Long, whole: Long): Int =
        if (whole <= 0L) 0 else (part * 100 / whole).toInt()

    /** 날짜별 지출. 무지출일과 최다 지출일이 여기서 나온다. */
    private fun byDate(list: List<Txn>): Map<LocalDate, Long> =
        Stats.active(list).groupBy { at(it).toLocalDate() }
            .mapValues { (_, v) -> v.sumOf { it.amount } }

    /**
     * 가장 길게 이어진 무지출 구간.
     *
     * 총액만 보면 아낀 날이 안 보인다. 하루도 안 쓴 날이 이어졌다는 것은
     * 그 사람이 실제로 할 수 있다는 증거라, 줄이라는 말보다 근거가 된다.
     */
    private fun noSpendStreak(spent: Map<LocalDate, Long>, from: LocalDate, to: LocalDate): Int {
        var best = 0
        var run = 0
        var d = from
        while (!d.isAfter(to)) {
            if ((spent[d] ?: 0L) == 0L) { run++; if (run > best) best = run } else run = 0
            d = d.plusDays(1)
        }
        return best
    }

    /** 1만원 미만 결제. 한 건은 작지만 모이면 카테고리 하나만큼 나간다. */
    private fun smallLeak(list: List<Txn>): Pair<Int, Long> {
        val small = Stats.active(list).filter { it.amount in 1..9_999 }
        return small.size to small.sumOf { it.amount }
    }

    /** 밤 10시부터 새벽 5시까지. 이 시간대 비중이 높으면 계획보다 기분으로 쓴 달이다. */
    private fun lateNight(list: List<Txn>): Pair<Int, Long> {
        val late = Stats.active(list).filter { val h = at(it).hour; h >= 22 || h < 5 }
        return late.size to late.sumOf { it.amount }
    }

    /** 주말(토·일) 대 평일. */
    private fun weekendSplit(list: List<Txn>): Pair<Long, Long> {
        val (we, wd) = Stats.active(list).partition { at(it).dayOfWeek.value >= 6 }
        return we.sumOf { it.amount } to wd.sumOf { it.amount }
    }

    /**
     * 반복 결제 후보. 같은 가맹점에서 세 번 이상, 그리고 금액이 거의 같은 것.
     *
     * 구독은 끊기 전까지 매달 자동으로 나가서 예산에서 가장 늦게 발견된다.
     * 고정지출로 등록해 두면 변동 예산에서 빠지므로, 찾아서 알려 주는 값이 있다.
     */
    private fun subscriptions(list: List<Txn>): List<Triple<String, Int, Long>> =
        Stats.active(list).filter { it.merchant.isNotBlank() }
            .groupBy { it.merchant }
            .filter { (_, v) ->
                if (v.size < 3) false
                else {
                    val avg = v.sumOf { it.amount } / v.size
                    // 편차가 평균의 15% 안이면 같은 금액이 반복된 것으로 본다.
                    avg > 0 && v.all { kotlin.math.abs(it.amount - avg) * 100 / avg <= 15 }
                }
            }
            .map { (m, v) -> Triple(m, v.size, v.sumOf { it.amount } / v.size) }
            .sortedByDescending { it.second }
            .take(3)

    /**
     * 튀는 결제. 평균에서 표준편차 두 배를 넘긴 건.
     * 총액이 늘어난 이유가 습관인지 사건 하나인지 여기서 갈린다.
     */
    private fun outliers(list: List<Txn>): List<Txn> {
        val a = Stats.active(list)
        if (a.size < 5) return emptyList()
        val mean = a.sumOf { it.amount }.toDouble() / a.size
        val sd = kotlin.math.sqrt(a.sumOf { (it.amount - mean) * (it.amount - mean) } / a.size)
        if (sd <= 0.0) return emptyList()
        return a.filter { it.amount > mean + 2 * sd }.sortedByDescending { it.amount }.take(3)
    }

    /** 상위 세 곳이 전체에서 차지하는 비중. 높을수록 줄일 자리가 뚜렷하다. */
    private fun concentration(list: List<Txn>): Int {
        val total = Stats.total(list)
        val top3 = Stats.topMerchants(list, 3).sumOf { it.second }
        return share(top3, total)
    }

    /** 도트 막대 한 줄. 비중을 글자로만 적으면 항목끼리 견주기 어렵다. */
    private fun bar(p: Int): String {
        val n = (p.coerceIn(0, 100) + 5) / 10
        return "■".repeat(n) + "·".repeat(10 - n)
    }

    // ---------------------------------------------------------------- 이번 달

    private fun monthly(
        profile: UserProfile,
        txns: List<Txn>,
        cfg: Config,
        prev: List<Txn>
    ): String {
        val active = Stats.active(txns)
        val today = LocalDate.now()
        val ym = YearMonth.from(today)

        val total = Stats.total(txns)
        val count = active.size
        val cats = Stats.byCat(txns)
        val tops = Stats.topMerchants(txns, 3)
        val hours = Stats.byHour(txns)
        val peakHour = hours.indices.maxByOrNull { hours[it] } ?: 0
        val daily = Stats.dailyBudget(cfg, txns)
        val overs = Stats.overBudgetCats(cfg, txns)
        val weekdayNames = listOf("월", "화", "수", "목", "금", "토", "일")
        val weekdays = Stats.byWeekday(txns)

        val spent = byDate(txns)
        val elapsed = today.dayOfMonth
        val left = (ym.lengthOfMonth() - elapsed).coerceAtLeast(0)
        val perDay = if (elapsed > 0) total / elapsed else 0L
        val projected = perDay * ym.lengthOfMonth()

        val busiest = spent.maxByOrNull { it.value }
        val quiet = spent.count { it.value == 0L }
        val streak = noSpendStreak(spent, ym.atDay(1), today)

        val (smallN, smallSum) = smallLeak(txns)
        val (lateN, lateSum) = lateNight(txns)
        val (weekend, weekday) = weekendSplit(txns)
        val subs = subscriptions(txns)
        val odd = outliers(txns)
        val conc = concentration(txns)

        // 지난달과 견준다. 같은 금액도 늘어난 것인지 줄어든 것인지에 따라 뜻이 다르다.
        // 지난달 거래는 불러 오는 쪽이 넘겨 준다. 여기서 Store 를 직접 읽으면
        // 집계 함수 하나가 파일 시스템을 부르게 되어 혼자 돌려볼 수 없어진다.
        val prevTotal = Stats.total(prev)
        val prevCats = Stats.byCat(prev).toMap()
        val moved = cats.map { (c, v) -> Triple(c, v, v - (prevCats[c] ?: 0L)) }
            .filter { kotlin.math.abs(it.third) >= 10_000L }
            .sortedByDescending { kotlin.math.abs(it.third) }
            .take(3)

        val cutTarget = overs.firstOrNull()?.first
            ?: cats.firstOrNull { it.first != Cat.HOUSING && it.first != Cat.INCOME }?.first
            ?: Cat.ETC
        val cutAmount = round((cats.firstOrNull { it.first == cutTarget }?.second ?: 0L) / 10)
        val keepTarget = Stats.catProgress(cfg, txns)
            .filter { it.second < it.third }
            .minByOrNull { it.second.toDouble() / it.third }?.first
            ?: cats.lastOrNull()?.first ?: Cat.FOOD

        return buildString {
            appendLine("[1문단: 소비 현황]")
            appendLine("- 총 지출: ${won(total)} (${count}건) · 하루 평균 ${won(round(perDay))}")
            if (prevTotal > 0) {
                val d = total - prevTotal
                appendLine(
                    "- 지난달 대비: ${won(kotlin.math.abs(d))} " +
                        (if (d >= 0) "더 씀" else "덜 씀") + " (${share(kotlin.math.abs(d), prevTotal)}%)"
                )
            }
            busiest?.let {
                appendLine("- 가장 많이 쓴 날: ${it.key.monthValue}월 ${it.key.dayOfMonth}일 ${won(it.value)}")
            }
            appendLine("- 안 쓴 날: ${quiet}일" + if (streak >= 2) " · 최장 ${streak}일 연속" else "")
            appendLine()

            appendLine("[2문단: 어디에 썼나]")
            cats.take(4).forEach { (c, v) ->
                appendLine("- ${c.label} ${bar(share(v, total))} ${share(v, total)}% · ${won(v)}")
            }
            if (tops.isNotEmpty()) {
                appendLine("- 자주 간 곳: " + tops.joinToString(" · ") { "${it.first} ${won(it.second)}" })
            }
            appendLine("- 상위 세 곳 집중도: ${conc}%" +
                if (conc >= 50) " · 줄일 자리가 뚜렷합니다" else " · 여러 곳에 고르게 흩어져 있어요")
            if (moved.isNotEmpty()) {
                appendLine(
                    "- 지난달과 달라진 항목: " + moved.joinToString(" · ") {
                        "${it.first.label} ${if (it.third >= 0) "+" else "-"}${won(kotlin.math.abs(it.third))}"
                    }
                )
            }
            appendLine()

            appendLine("[3문단: 언제 썼나]")
            appendLine("- 피크 시간대: ${peakHour}시 무렵")
            if (lateN > 0) {
                appendLine(
                    "- 심야(22~5시) 지출: ${lateN}건 ${won(lateSum)} · 전체의 ${share(lateSum, total)}%" +
                        if (share(lateSum, total) >= 20) " · 기분으로 쓴 달입니다" else ""
                )
            }
            appendLine(
                "- 주말 ${won(weekend)} / 평일 ${won(weekday)} · 주말 비중 ${share(weekend, total)}%"
            )
            weekdays.indices.maxByOrNull { weekdays[it] }?.let { i ->
                appendLine("- 가장 헤픈 요일: ${weekdayNames[i]}요일 ${won(weekdays[i])}")
            }
            appendLine()

            appendLine("[4문단: 새는 곳]")
            if (smallN > 0) {
                appendLine(
                    "- 1만원 미만 결제: ${smallN}건 ${won(smallSum)} · 전체의 ${share(smallSum, total)}%"
                )
            }
            if (subs.isNotEmpty()) {
                appendLine(
                    "- 반복 결제 후보: " + subs.joinToString(" · ") { "${it.first} ${it.second}회 · 회당 ${won(it.third)}" }
                )
                appendLine("- 위 항목은 고정지출로 등록해 두면 변동 예산에서 빠집니다")
            }
            if (odd.isNotEmpty()) {
                appendLine(
                    "- 튀는 결제: " + odd.joinToString(" · ") { "${it.merchant.ifBlank { it.cat.label }} ${won(it.amount)}" }
                )
            }
            if (overs.isNotEmpty()) {
                appendLine(
                    "- 예산 초과 항목: " + overs.joinToString(" · ") { "${it.first.label} +${won(it.second)}" }
                )
            }
            if (smallN == 0 && subs.isEmpty() && odd.isEmpty() && overs.isEmpty()) {
                appendLine("- 눈에 띄게 새는 곳은 없어요")
            }
            appendLine()

            appendLine("[5문단: 이대로 가면]")
            appendLine("- 남은 날: ${left}일 · 이 속도면 월말 ${won(round(projected))}")
            if (cfg.monthlyBudget > 0) {
                val gap = cfg.monthlyBudget - projected
                appendLine(
                    "- 예산 ${won(cfg.monthlyBudget)} 대비: " +
                        if (gap >= 0) "${won(round(gap))} 남길 속도" else "${won(round(-gap))} 넘길 속도"
                )
                if (perDay > 0) {
                    val burn = cfg.monthlyBudget / perDay
                    if (burn <= ym.lengthOfMonth()) {
                        appendLine("- 예산 소진 예상일: ${ym.monthValue}월 ${burn.coerceAtMost(31L)}일 무렵")
                    }
                }
            }
            appendLine("- 권장 예산: 하루 ${won(daily.dailyLimit)} · 주간 ${won(daily.dailyLimit * 7)}")
            appendLine()

            appendLine("[6문단: 실천 및 응원]")
            appendLine("- 절감 대상: ${cutTarget.label} ${won(cutAmount)}만 줄여도 흐름이 눈에 띄게 편해져요")
            appendLine("- 유지 권장: ${keepTarget.label} 지금 속도면 예산 안에서 잘 지키고 있어요")
            appendLine("- 실천 과제 1: 내일 ${cutTarget.label} 지출을 한 번만 건너뛰기")
            appendLine(
                "- 실천 과제 2: " +
                    if (subs.isNotEmpty()) "${subs.first().first} 구독을 이번 주에 다시 볼지 정하기"
                    else if (smallN >= 10) "1만원 미만 결제를 하루 한 건으로 묶기"
                    else "가장 헤픈 요일 하루를 무지출로 잡기"
            )
            appendLine("- 실천 과제 3: 남은 ${left}일 동안 하루 ${won(daily.dailyLimit)} 안에서 쓰기")
            append("- 응원 메시지: ${cheer(profile, daily.isSuccess)}")
        }
    }

    // ---------------------------------------------------------------- 3개월

    /**
     * 3개월 총괄. 월별로 갈라 흐름을 본다.
     * 한 달짜리 리포트와 달리 여기서는 "이번 달이 유별났나" 가 아니라
     * "이게 굳어진 모양인가" 를 본다. 그래서 편차와 반복이 주인공이다.
     */
    private fun quarter(profile: UserProfile, txns: List<Txn>, cfg: Config): String {
        val active = Stats.active(txns)
        val byMonth = active.groupBy { it.at.substring(0, 7) }
            .mapValues { (_, v) -> v.sumOf { it.amount } }
            .toSortedMap()
        val months = byMonth.keys.toList()
        val totals = byMonth.values.toList()
        val total = totals.sum()
        val avg = if (totals.isNotEmpty()) total / totals.size else 0L
        val swingRange = if (totals.size >= 2) (totals.max() - totals.min()) else 0L

        val trend = when {
            totals.size < 2 -> "아직 견줄 달이 없어요"
            totals.last() > totals.first() -> "처음 달보다 ${won(totals.last() - totals.first())} 늘었어요"
            totals.last() < totals.first() -> "처음 달보다 ${won(totals.first() - totals.last())} 줄었어요"
            else -> "거의 그대로예요"
        }

        val cats = Stats.byCat(txns)
        val habit = cats.firstOrNull()?.first ?: Cat.ETC
        val fixedShare = Stats.fixedRecordedTotal(txns)
        val swing = cats.getOrNull(1)?.first ?: habit
        val room = round((cats.firstOrNull()?.second ?: 0L) / 10)

        // 달마다 흔들리는 항목 찾기: 월별 최대와 최소의 차이가 큰 쪽.
        val catSwing = cats.take(5).map { (c, _) ->
            val per = months.map { m ->
                active.filter { it.cat == c && it.at.startsWith(m) }.sumOf { it.amount }
            }
            c to (if (per.size >= 2) per.max() - per.min() else 0L)
        }.sortedByDescending { it.second }

        val subs = subscriptions(txns)
        val (smallN, smallSum) = smallLeak(txns)
        val (weekend, weekday) = weekendSplit(txns)
        val conc = concentration(txns)

        return buildString {
            appendLine("[1문단: 3개월 흐름]")
            appendLine("- 총 지출: ${won(total)} (${active.size}건) · 월평균 ${won(round(avg))}")
            appendLine("- 월별 추세: ${months.size}개월 집계 · $trend")
            months.forEachIndexed { i, m ->
                val v = totals[i]
                appendLine("- ${m.substring(5)}월 ${bar(share(v, totals.max()))} ${won(v)}")
            }
            if (swingRange > 0) appendLine("- 가장 많은 달과 적은 달의 차이: ${won(swingRange)}")
            appendLine()

            appendLine("[2문단: 구조 진단]")
            appendLine("- 굳어진 습관: ${habit.label} 석 달 내내 가장 큰 자리를 차지했어요")
            cats.take(4).forEach { (c, v) ->
                appendLine("- ${c.label} ${bar(share(v, total))} ${share(v, total)}% · ${won(v)}")
            }
            appendLine("- 고정비 비중: ${won(fixedShare)} · 전체의 ${share(fixedShare, total)}%")
            catSwing.firstOrNull()?.let {
                appendLine("- 달마다 가장 출렁이는 항목: ${it.first.label} 최대 ${won(it.second)} 차이")
            }
            appendLine("- 상위 세 곳 집중도: ${conc}%")
            appendLine("- 주말 비중: ${share(weekend, total)}% (주말 ${won(weekend)} / 평일 ${won(weekday)})")
            appendLine()

            appendLine("[3문단: 다음 분기]")
            appendLine("- 개선 여력: ${swing.label} · ${habit.label} 기준 석 달에 ${won(room * 3)}쯤 줄일 수 있어요")
            if (subs.isNotEmpty()) {
                appendLine(
                    "- 석 달 내내 반복된 결제: " + subs.joinToString(" · ") { "${it.first} ${it.second}회" }
                )
            }
            if (smallN > 0) {
                appendLine("- 소액 누수: ${smallN}건 ${won(smallSum)} · 석 달치라 습관으로 봐야 합니다")
            }
            appendLine("- 실천 과제 1: 다음 석 달은 ${habit.label} 지출을 주 1회 건너뛰기")
            appendLine("- 실천 과제 2: ${swing.label} 예산을 월평균 ${won(round(avg / 10))} 낮춰 잡아 보기")
            append("- 응원 메시지: ${cheer(profile, true)}")
        }
    }

    // ---------------------------------------------------------------- 다음 달

    /**
     * 다음 달 예산 설계. 실적을 근거로 항목별 금액을 만원 단위로 제안한다.
     * 이 값은 그대로 예산 탭에 옮겨 적을 수 있어야 쓸모가 있다.
     */
    private fun plan(profile: UserProfile, txns: List<Txn>, cfg: Config): String {
        val active = Stats.active(txns)
        val total = Stats.total(txns)
        val months = active.map { it.at.substring(0, 7) }.distinct().size.coerceAtLeast(1)
        val monthly = total / months

        val cats = Stats.byCat(txns).filter { it.first != Cat.HOUSING && it.first != Cat.INCOME }
        val overs = Stats.overBudgetCats(cfg, txns)
        val budget = cfg.monthlyBudget
        val gap = budget - monthly
        val fixedSum = Stats.fixedTotal(cfg)
        val investGoal = Stats.investGoal(cfg)
        val subs = subscriptions(txns)
        val (smallN, smallSum) = smallLeak(txns)

        // 실적을 만원 단위로 올려 다음 달 배정으로 삼는다. 초과한 항목은 그대로 두지 않고
        // 한 칸 줄여 잡는다 — 같은 금액을 다시 주면 또 넘긴다.
        fun suggest(c: Cat, spent: Long): Long {
            val base = ((spent / months + 9_999) / 10_000) * 10_000
            val over = overs.any { it.first == c }
            return if (over) (base - 10_000).coerceAtLeast(10_000) else base
        }

        val picks = cats.take(5).map { (c, v) -> c to suggest(c, v) }
        val planned = picks.sumOf { it.second } + fixedSum + investGoal
        val cutTarget = overs.firstOrNull()?.first ?: cats.firstOrNull()?.first ?: Cat.ETC

        return buildString {
            appendLine("[1문단: 현재 진단]")
            appendLine("- 실제 지출: ${won(total)} (${active.size}건) · ${months}개월 기준 월 ${won(round(monthly))}")
            appendLine(
                "- 예산 대비: " + when {
                    budget <= 0L -> "아직 월 예산을 정하지 않았어요"
                    gap >= 0L -> "${won(round(gap))} 남았어요"
                    else -> "${won(round(-gap))} 넘었어요"
                }
            )
            appendLine("- 조정 대상: ${cutTarget.label} 배정과 실제가 가장 어긋난 항목이에요")
            if (overs.isNotEmpty()) {
                appendLine("- 초과 항목: " + overs.joinToString(" · ") { "${it.first.label} +${won(it.second)}" })
            }
            appendLine()

            appendLine("[2문단: 다음 달 배정안]")
            appendLine("- 고정지출: ${won(fixedSum)} (등록한 항목 합계)")
            if (investGoal > 0) appendLine("- 투자·저축 목표: ${won(investGoal)}")
            appendLine("- 항목 배정 (실적 기준)")
            picks.forEach { (c, v) ->
                val actual = (cats.firstOrNull { it.first == c }?.second ?: 0L) / months
                appendLine("  · ${c.label}: ${won(v)} (실적 월 ${won(round(actual))})")
            }
            appendLine("- 합계: ${won(planned)}" + if (budget > 0) " · 현재 예산 ${won(budget)}" else "")
            appendLine("- 배정 근거: 실적을 만원 단위로 올리고 넘긴 항목은 한 칸 줄였어요")
            appendLine()

            appendLine("[3문단: 실행]")
            appendLine("- 실천 과제 1: 다음 달 첫 주에 ${cutTarget.label} 예산을 먼저 정해 두기")
            if (subs.isNotEmpty()) {
                appendLine(
                    "- 실천 과제 2: " + subs.joinToString(" · ") { it.first } + " 를 고정지출로 등록해 변동 예산에서 빼기"
                )
            } else if (smallN >= 10) {
                appendLine("- 실천 과제 2: 1만원 미만 결제 ${smallN}건 ${won(smallSum)} 을 절반으로 줄이기")
            } else {
                appendLine("- 실천 과제 2: 주 1회 무지출일 정해 두기")
            }
            appendLine("- 실천 과제 3: 매주 일요일에 항목별 예산 소진율 한 번 보기")
            append("- 응원 메시지: ${cheer(profile, gap >= 0L)}")
        }
    }

    private fun cheer(profile: UserProfile, todayOk: Boolean): String {
        val goal = profile.goalReason.ifBlank { "" }
        return when {
            goal.isNotBlank() && todayOk -> "$goal, 오늘도 한 걸음 가까워졌어요"
            goal.isNotBlank() -> "$goal 을 향해 가는 길이에요. 내일 다시 잡으면 돼요"
            todayOk -> "오늘 예산 안에서 잘 지켰어요. 이 페이스 좋아요"
            else -> "조금 넘쳤지만 흐름은 만들어지고 있어요"
        }
    }
}
