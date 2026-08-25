package com.pushledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {

    private fun expense(title: String, body: String): Parser.Out.Expense {
        val r = Parser.parse(title, body)
        assertTrue(r is Parser.Out.Expense)
        return r as Parser.Out.Expense
    }

    @Test fun 신한카드_기본_승인문자_파싱() {
        val r = expense(
            "신한카드",
            "[Web발신] 신한카드 승인 12,000원 08/21 14:32 스타벅스강남점 누적 350,000원"
        )
        assertEquals(12_000L, r.amount)
        assertTrue(r.merchant, r.merchant.contains("스타벅스"))
    }

    @Test fun 금액_뒤의_잔액은_건너뛴다() {
        val r = expense("KB국민카드", "승인 3,500원 잔액 1,250,000원 GS25")
        assertEquals(3_500L, r.amount)
        assertTrue(r.merchant, r.merchant.contains("GS25"))
    }

    @Test fun 카드사_이름은_가맹점으로_뽑지_않는다() {
        val r = expense("카카오페이", "8,900원 결제완료 맥도날드 강남점")
        assertEquals(8_900L, r.amount)
        assertTrue(r.merchant, r.merchant.contains("맥도날드"))
    }

    @Test fun 사람_이름은_가맹점으로_뽑지_않는다() {
        val r = expense("삼성카드 알림", "홍길동님 45,000원 일시불 올리브영 강남")
        assertEquals(45_000L, r.amount)
        assertTrue(r.merchant, r.merchant.contains("올리브영"))
    }

    @Test fun 체크카드를_구분한다() {
        val r = expense("토스", "토스뱅크 체크카드 결제 15,900원 식당")
        assertEquals(15_900L, r.amount)
        assertEquals("체크카드", r.method)
        assertTrue(r.merchant, r.merchant.contains("식당"))
    }

    @Test fun 취소는_수입이_아니라_취소로_읽는다() {
        val r = Parser.parse("신한카드", "[Web발신] 신한카드 승인취소 12,000원 스타벅스")
        assertTrue(r is Parser.Out.Cancel)
        r as Parser.Out.Cancel
        assertEquals(12_000L, r.amount)
        assertTrue(r.merchant, r.merchant.contains("스타벅스"))
    }

    @Test fun 입금_알림은_수입으로_분류된다() {
        val res = Parser.parse("토스", "홍길동님이 50,000원을 입금했어요")
        assertTrue(res is Parser.Out.Income)
        val inc = res as Parser.Out.Income
        assertEquals(50_000L, inc.amount)
    }

    /**
     * 송금 알림의 방향. 이 네 줄이 어긋나면 보낸 돈이 내 수입으로 잡힌다.
     * 실제로 그렇게 잡혀서 이번 달 수입이 부풀었다.
     */
    @Test fun 송금은_조사로_방향을_가른다() {
        // 내가 보냄 → 지출
        val sent = Parser.parse("카카오페이", "김철수님에게 20,000원을 보냈어요")
        assertTrue(sent.toString(), sent is Parser.Out.Expense)
        sent as Parser.Out.Expense
        assertEquals(20_000L, sent.amount)
        assertTrue(sent.merchant, sent.merchant.contains("김철수"))

        // 상대가 받았다는 확인 → 아무것도 아니다. 보낼 때 이미 셌다.
        val taken = Parser.parse("카카오페이", "김철수님이 20,000원을 받았어요")
        assertTrue(taken.toString(), taken is Parser.Out.None)

        // 내가 받음 → 수입. 조사가 "이/가" 면 상대가 보낸 것이다.
        val got = Parser.parse("카카오페이", "김철수님이 20,000원을 보냈어요")
        assertTrue(got.toString(), got is Parser.Out.Income)
        assertEquals("김철수", (got as Parser.Out.Income).sender)

        // 토스는 받는 쪽에 "님에게 받았어요" 로 온다. 여기도 수입이다.
        val got2 = Parser.parse("토스", "김철수님에게 20,000원을 받았어요")
        assertTrue(got2.toString(), got2 is Parser.Out.Income)
    }

    /**
     * 한 번 결제하면 카카오페이·카드사·은행이 각자 알림을 띄운다.
     * 앱도 가맹점 표기도 다르므로, 금액과 시각만으로 같은 결제를 알아봐야 한다.
     */
    @Test fun 십초_안에_들어온_같은_금액은_한_건으로_본다() {
        fun txn(at: String, pkg: String, merchant: String, by: String = "rule") = Txn(
            id = at + pkg, amount = 12_000L, merchant = merchant,
            at = at, sourcePkg = pkg, by = by, dedup = "$pkg|$at"
        )
        val first = txn("2026-08-21T14:32:00", "com.kakao.talk", "스타벅스")

        // 다른 앱, 다른 가맹점 표기, 4초 뒤 — 같은 결제다.
        assertTrue(
            Store.isDuplicate(listOf(first), txn("2026-08-21T14:32:04", "com.shinhan.card", "스타벅스강남R점"))
        )
        // 11초 뒤는 다른 결제로 본다.
        assertFalse(
            Store.isDuplicate(listOf(first), txn("2026-08-21T14:32:11", "com.shinhan.card", "스타벅스강남R점"))
        )
        // 손으로 넣은 건은 사용자가 뜻을 갖고 넣은 것이라 막지 않는다.
        assertFalse(
            Store.isDuplicate(listOf(first), txn("2026-08-21T14:32:04", "manual", "스타벅스", by = "manual"))
        )
    }

    @Test fun 적립_알림은_거른다() {
        assertTrue(Parser.parse("스타벅스", "별 2개가 적립되었어요") is Parser.Out.None)
    }

    @Test fun 광고_알림은_거른다() {
        assertTrue(Parser.parse("쿠팡", "오늘의 특가! 최대 70,000원 할인 쿠폰 받기") is Parser.Out.None)
    }

    @Test fun 금액_없는_알림은_거른다() {
        assertTrue(Parser.parse("카카오톡", "친구의 메시지가 도착했습니다") is Parser.Out.None)
    }

    @Test fun 가맹점_카테고리를_정밀하게_추론한다() {
        // 1. 식비 (카페 포함)
        assertEquals(Cat.FOOD, Parser.guessCat("스타벅스 역삼역점"))
        assertEquals("카페/음료", Parser.guessSubCat("스타벅스 역삼역점", Cat.FOOD))
        assertEquals("배달", Parser.guessSubCat("배달의민족 교촌치킨", Cat.FOOD))

        // 2. 생활 (생필품, 의료, 미용)
        assertEquals(Cat.FOOD, Parser.guessCat("GS25 편의점"))   // 편의점·마트는 식비로 모았다
        assertEquals("마트/식료품", Parser.guessSubCat("GS25 편의점", Cat.FOOD))
        assertEquals(Cat.LIVING, Parser.guessCat("연세이비인후과"))
        assertEquals("병원/약국", Parser.guessSubCat("연세이비인후과", Cat.LIVING))
        assertEquals("반찬/식자재", Parser.guessSubCat("정든반찬가게", Cat.FOOD))

        // 3. 여가 (문화, 교통, 쇼핑, 취미)
        assertEquals(Cat.LEISURE, Parser.guessCat("카카오T 택시"))
        assertEquals("교통/차량", Parser.guessSubCat("카카오T 택시", Cat.LEISURE))
        assertEquals(Cat.LEISURE, Parser.guessCat("CGV 영화관람"))
        assertEquals("문화/컨텐츠", Parser.guessSubCat("CGV 영화관람", Cat.LEISURE))
        assertEquals(Cat.LEISURE, Parser.guessCat("무신사 온라인스토어"))
        assertEquals("쇼핑/의류", Parser.guessSubCat("무신사 온라인스토어", Cat.LEISURE))

        // 4. 금융 (투자, 대출이자, 보험료, 수수료)
        assertEquals(Cat.FINANCE, Parser.guessCat("카카오뱅크 대출이자"))
        assertEquals("대출이자", Parser.guessSubCat("카카오뱅크 대출이자", Cat.FINANCE))
        assertEquals(Cat.FINANCE, Parser.guessCat("토스증권 주식 매수"))
        assertEquals("투자/저축", Parser.guessSubCat("토스증권 주식 매수", Cat.FINANCE))

        // 5. 주거 (월세, 관리비, 공과금, 통신비)
        assertEquals(Cat.HOUSING, Parser.guessCat("원룸 월세"))
        assertEquals("월세", Parser.guessSubCat("원룸 월세", Cat.HOUSING))
        assertEquals(Cat.HOUSING, Parser.guessCat("아파트 관리비"))
        assertEquals("관리비", Parser.guessSubCat("아파트 관리비", Cat.HOUSING))
        assertEquals(Cat.HOUSING, Parser.guessCat("SKT 통신요금"))
        assertEquals("통신비", Parser.guessSubCat("SKT 통신요금", Cat.HOUSING))

        // 6. 기타
        assertEquals(Cat.ETC, Parser.guessCat("친구 결혼식 축의금"))
        assertEquals("경조사", Parser.guessSubCat("친구 결혼식 축의금", Cat.ETC))
    }

    @Test fun 하루_가용_예산_N빵_계산_및_성공_판정() {
        val cfg = Config(
            monthlyBudget = 1_500_000L,
            fixed = listOf(Fixed("f1", "월세", 500_000L, 25, Cat.HOUSING.name))
        )
        // 8월 10일 기준 (8월 총 31일, 남은 일수 = 31 - 10 + 1 = 22일)
        val today = java.time.LocalDate.of(2026, 8, 10)
        // 과거 지출 200,000원, 오늘 지출 30,000원, 투자 100,000원
        val txns = listOf(
            Txn("1", 200_000L, "식료품", Cat.FOOD.name, at = "2026-08-05T12:00:00"),
            Txn("2", 100_000L, "주식매수", Cat.FINANCE.name, subCategory = "투자/저축", by = "invest", at = "2026-08-07T10:00:00"),
            Txn("3", 30_000L, "식당", Cat.FOOD.name, at = "2026-08-10T13:00:00")
        )

        val daily = Stats.dailyBudget(cfg, txns, today)
        // 전체 변동 예산 = 1,500,000 - 500,000 (고정) - 100,000 (투자) = 900,000원
        // 남은 변동 예산 = 900,000 - 200,000 (과거 지출) = 700,000원
        // 오늘 가용 예산 = 700,000 / 22 = 31,818 -> 10원 단위 절삭으로 31,810원
        assertEquals(22, daily.remainingDays)
        assertEquals(31810L, daily.dailyLimit)
        assertEquals(30000L, daily.todaySpent)
        assertEquals(1810L, daily.remaining)
        assertTrue(daily.isSuccess) // 30,000 <= 31,818 이므로 오늘 성공!
    }

    @Test fun 시간대별_금액_및_건수_집계_검증() {
        val txns = listOf(
            Txn("1", 10_000L, "카페", Cat.FOOD.name, at = "2026-08-10T14:15:00"),
            Txn("2", 15_000L, "식당", Cat.FOOD.name, at = "2026-08-10T14:45:00"),
            Txn("3", 50_000L, "쇼핑", Cat.LEISURE.name, at = "2026-08-10T19:00:00")
        )
        val hours = Stats.byHour(txns)
        val counts = Stats.byHourCount(txns)
        assertEquals(25_000L, hours[14])
        assertEquals(2L, counts[14])
        assertEquals(50_000L, hours[19])
        assertEquals(1L, counts[19])
        assertEquals(0L, counts[0])
    }

    @Test fun 추가_수입_및_용돈_파싱_검증() {
        val out = Parser.parse("신한은행", "08/21 15:30 100,000원 입금 홍길동 잔액 1,500,000원")
        assertTrue(out is Parser.Out.Income)
        val inc = out as Parser.Out.Income
        assertEquals(100_000L, inc.amount)
        assertEquals("홍길동", inc.sender)
    }

    @Test fun 고정지출_실행건_및_추가수입_예산반영_검증() {
        val cfg = Config(
            monthlyBudget = 1_000_000L,
            fixed = listOf(Fixed("f1", "월세", 500_000L, 25, Cat.HOUSING.name)),
            catBudget = mapOf(Cat.FOOD.name to 300_000L)
        )
        val txns = listOf(
            Txn("1", 500_000L, "월세", Cat.HOUSING.name, by = "fixed", at = "2026-08-01T00:00:00"),
            Txn("2", 100_000L, "용돈", Cat.INCOME.name, at = "2026-08-05T10:00:00"),
            Txn("3", 350_000L, "식당", Cat.FOOD.name, at = "2026-08-10T12:00:00")
        )
        // 소비 총액 = 350,000원 (월세는 by="fixed"이므로 변동 소비에서 제외)
        assertEquals(350_000L, Stats.total(txns))
        // 수입 총액 = 100,000원
        assertEquals(100_000L, Stats.incomeTotal(txns))
        // 여유비 = 1,000,000 (예산) + 100,000 (수입) - 500,000 (고정) - 350,000 (소비) = 250,000원
        assertEquals(250_000L, Stats.spare(cfg, txns))

        // 예산 초과 항목 검출: 식비 (350,000원 > 300,000원) -> 50,000원 초과
        val over = Stats.overBudgetCats(cfg, txns)
        assertEquals(1, over.size)
        assertEquals(Cat.FOOD, over[0].first)
        assertEquals(50_000L, over[0].second)

        // 미배정 예산 검증: 1,000,000 - 500,000 (고정) - 300,000 (식비 배정) = 200,000원
        assertEquals(200_000L, Stats.unallocatedBudget(cfg))
    }

    @Test fun 고정지출은_항목배정에서_빠져_두_번_깎이지_않는다() {
        // 고정지출 항목 50만원을 등록하고, 실수로 고정지출 카테고리에도 40만원을 배정한 상태.
        val cfg = Config(
            monthlyBudget = 2_000_000L,
            fixed = listOf(Fixed("f1", "월세", 500_000L, 25, Cat.HOUSING.name)),
            catBudget = mapOf(
                Cat.FOOD.name to 600_000L,
                Cat.HOUSING.name to 400_000L   // 이 값은 무시되어야 한다
            )
        )
        // 항목 배정 합계에 고정지출(40만)이 섞이면 안 된다.
        assertEquals(600_000L, Stats.allocatedCatBudgetTotal(cfg))
        // 미배정 = 200만 - 50만(고정) - 60만(식비) = 90만
        assertEquals(900_000L, Stats.unallocatedBudget(cfg))
        // 고정지출은 초과 판정 대상이 아니다 (실행건이 소비 집계에서 빠지므로 항상 0으로 잡힌다)
        val txns = listOf(
            Txn("1", 500_000L, "월세", Cat.HOUSING.name, by = "fixed", at = "2026-08-25T09:00:00")
        )
        assertTrue(Stats.overBudgetCats(cfg, txns).none { it.first == Cat.HOUSING })
    }

    @Test fun 고정지출_계획과_실제를_따로_센다() {
        val cfg = Config(
            monthlyBudget = 1_000_000L,
            fixed = listOf(
                Fixed("f1", "월세", 500_000L, 25, Cat.HOUSING.name),
                Fixed("f2", "통신비", 50_000L, 10, Cat.HOUSING.name)
            )
        )
        // 통신비만 실제로 빠져나간 상태
        val txns = listOf(
            Txn("1", 50_000L, "통신비", Cat.HOUSING.name, by = "fixed", at = "2026-08-10T09:00:00")
        )
        val (plan, done) = Stats.fixedProgress(cfg, txns)
        assertEquals(550_000L, plan)
        assertEquals(50_000L, done)
        // 실제 출금건은 변동 소비에 섞이지 않는다
        assertEquals(0L, Stats.total(txns))
    }

    @Test fun 기기분석은_종류마다_다른_보고서를_만든다() {
        val cfg = Config(
            monthlyBudget = 2_000_000L,
            fixed = listOf(Fixed("f1", "월세", 500_000L, 25, Cat.HOUSING.name)),
            catBudget = mapOf(Cat.FOOD.name to 300_000L)
        )
        val txns = listOf(
            Txn("1", 350_000L, "식당", Cat.FOOD.name, at = "2026-08-10T12:00:00"),
            Txn("2", 120_000L, "무신사", Cat.LEISURE.name, at = "2026-07-11T19:00:00"),
            Txn("3", 60_000L, "올리브영", Cat.LIVING.name, at = "2026-06-12T15:00:00")
        )
        val profile = UserProfile("직장인", "29", "", "1억 모으기")

        val monthly = LocalCoach.build(ReportKind.MONTHLY, profile, txns, cfg)
        val quarter = LocalCoach.build(ReportKind.QUARTER, profile, txns, cfg)
        val plan = LocalCoach.build(ReportKind.PLAN, profile, txns, cfg)

        // 각 종류가 자기 문단 제목을 갖는다. 셋이 같은 글이면 고른 의미가 없다.
        assertTrue(monthly, monthly.contains("권장 예산"))
        assertTrue(quarter, quarter.contains("월별 추세"))
        assertTrue(quarter, quarter.contains("굳어진 습관"))
        assertTrue(plan, plan.contains("항목 배정"))
        assertTrue(plan, plan.contains("배정 근거"))

        // 그리고 서로 달라야 한다.
        assertTrue(monthly != quarter)
        assertTrue(quarter != plan)
        assertTrue(monthly != plan)
    }

    @Test fun 예산설계는_초과항목을_한칸_줄여_제안한다() {
        val cfg = Config(
            monthlyBudget = 1_000_000L,
            catBudget = mapOf(Cat.FOOD.name to 100_000L)   // 식비 예산 10만
        )
        val txns = listOf(
            Txn("1", 155_000L, "식당", Cat.FOOD.name, at = "2026-08-10T12:00:00")
        )
        val plan = LocalCoach.build(ReportKind.PLAN, UserProfile(), txns, cfg)
        // 15.5만을 쓴 초과 항목이므로 16만으로 올린 뒤 한 칸 줄여 15만을 제안한다.
        // 금액 표기가 만 단위로 끊어 읽는 꼴로 바뀌었다 (150,000원 → 15만원).
        assertTrue(plan, plan.contains("15만원"))
    }

    @Test fun 하루_변동예산은_고정지출과_저축을_뺀다() {
        val cfg = Config(
            monthlyBudget = 3_100_000L,
            fixed = listOf(Fixed("f1", "월세", 600_000L, 25, Cat.HOUSING.name)),
            catBudget = mapOf("INVEST_GOAL" to 500_000L)
        )
        val ym = java.time.YearMonth.of(2026, 8)   // 31일
        // (310만 - 60만 고정 - 50만 저축) / 31 = 64,516 -> 10원 절삭 64,510
        assertEquals(64_510L, Stats.dailyVariableBudget(cfg, emptyList(), ym))
    }
}
