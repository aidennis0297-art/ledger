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
        // 11초 뒤에 가맹점까지 다르면 다른 결제로 본다.
        assertFalse(
            Store.isDuplicate(listOf(first), txn("2026-08-21T14:32:11", "com.shinhan.card", "이디야"))
        )
        // 가맹점이 같으면 11초를 넘겨도 같은 결제다. 예전에는 여기서 갈랐는데,
        // 삼성페이와 토스가 같은 결제를 58~85초 벌려 알리는 것이 실기기 기록에서
        // 확인돼 넓은 창을 뒀다. 자세한 이유는 Store.DUP_WIDE_SEC 에 적었다.
        assertTrue(
            Store.isDuplicate(listOf(first), txn("2026-08-21T14:32:11", "com.shinhan.card", "스타벅스강남R점"))
        )
        // 손으로 넣은 건은 사용자가 뜻을 갖고 넣은 것이라 막지 않는다.
        assertFalse(
            Store.isDuplicate(listOf(first), txn("2026-08-21T14:32:04", "manual", "스타벅스", by = "manual"))
        )
    }

    /**
     * 더치페이 정산금은 수입이 아니다. 내가 먼저 다 내고 나눠 받는 돈이라,
     * 수입으로 세면 그날 지출과 수입이 같이 부풀어 두 숫자가 다 틀린다.
     */
    @Test fun 정산금은_수입이_아니라_되받은_돈으로_읽는다() {
        val r = Parser.parse("토스", "정산 요청 완료 · 김철수님이 보낸 10,000원을 받았습니다")
        assertTrue(r.toString(), r is Parser.Out.Settle)
        r as Parser.Out.Settle
        assertEquals(10_000L, r.amount)
        assertEquals("김철수", r.from)

        // 정산 문구가 없는 평범한 입금은 그대로 수입이다.
        val plain = Parser.parse("토스", "김철수님이 10,000원을 보냈어요")
        assertTrue(plain.toString(), plain is Parser.Out.Income)
    }

    @Test fun 못_읽은_알림에서도_금액과_가게는_건진다() {
        // 직접 입력 칸을 빈 채로 열면 사용자는 대개 안 넣는다.
        val (amount, merchant) = Parser.salvage("우리은행", "행복카드 스타벅스강남 9,500원 정상처리")
        assertEquals(9_500L, amount)
        assertTrue(merchant, merchant.contains("스타벅스"))
    }

    /** 실제 내역 CSV 에서 잘못 잡혀 있던 것들. 같은 실수가 다시 들어오지 않게 막는다. */
    @Test fun 실제_기록에서_틀렸던_것들() {
        // 배당금은 용돈이 아니라 배당금 칸이다.
        val div = Parser.parse("키움증권", "배당금 374원이 입금되었습니다")
        assertTrue(div.toString(), div is Parser.Out.Income)
        assertEquals(374L, (div as Parser.Out.Income).amount)
        assertEquals(Cat.INCOME, Parser.guessCat("키움증권 배당금"))
        assertEquals("배당금", Parser.guessSubCat("키움증권 배당금", Cat.INCOME))

        // 충전은 지출이 아니다. 쓸 때 결제 알림이 또 온다.
        assertTrue(Parser.parse("네이버페이", "네이버페이충전 40,000원 결제완료") is Parser.Out.None)

        // 학자금 대출 이자가 기타지출로 쌓여 있었다.
        assertEquals(Cat.FINANCE, Parser.guessCat("한국장학재단"))
        assertEquals("대출이자", Parser.guessSubCat("한국장학재단", Cat.FINANCE))

        // 조사가 붙은 채로 가맹점이 되어 있었다.
        val r = Parser.parse("신한카드", "승인 2,887원 한국장학재단에서 출금")
        assertTrue(r.toString(), r is Parser.Out.Expense)
        assertEquals("한국장학재단", (r as Parser.Out.Expense).merchant)
    }

    @Test fun 지점만_남기지_않고_브랜드를_함께_잡는다() {
        // "서산예천점" 만 남아 어느 가게인지 알 수 없는 줄들이 있었다.
        val r = Parser.parse("신한카드", "승인 54,800원 홍콩반점0410 서산예천점")
        assertTrue(r.toString(), r is Parser.Out.Expense)
        val m = (r as Parser.Out.Expense).merchant
        assertTrue(m, m.contains("홍콩반점"))
        assertTrue(m, m.contains("서산예천점"))
    }

    /**
     * 카카오의 카드 SMS 파서(kakao/credit-card-sms-parser)가 다루는데 여기 없던 것들.
     * 전부 실제 카드사 알림에 흔한 꼴이다.
     */
    @Test fun 카드사_알림의_흔한_꼴들을_읽는다() {
        // 전각 공백(U+3000). 자바 정규식의 \s 는 이걸 공백으로 안 봐서 토큰이 안 쪼개졌다.
        val wide = expense("신한카드", "승인 12,000원　스타벅스　강남점")
        assertEquals(12_000L, wide.amount)
        assertTrue(wide.merchant, wide.merchant.contains("스타벅스"))

        // "국민(1234)" 는 카드지 가맹점이 아니다. 괄호만 지우면 "국민" 이 남는다.
        val card = expense("KB국민카드", "국민(1234) 승인 8,000원 투썸플레이스")
        assertTrue(card.merchant, card.merchant.contains("투썸"))
        assertFalse(card.merchant, card.merchant.contains("국민"))

        // 가맹점 이름에 결제 낱말이 눌어붙어 오는 일이 있다.
        val glued = expense("우리카드", "승인 5,000원 이마트사용")
        assertEquals("이마트", glued.merchant)
    }

    @Test fun 가려진_이름도_사람으로_읽는다() {
        // 은행과 간편결제는 이름을 가려서 보내는 일이 훨씬 많다.
        val r = Parser.parse("토스", "김*수님에게 20,000원을 보냈어요")
        assertTrue(r.toString(), r is Parser.Out.Expense)
        assertTrue((r as Parser.Out.Expense).merchant, r.merchant.contains("김*수"))
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

    /**
     * 삼성 월렛(삼성페이)은 금액을 `₩7,100` 으로 보낸다. '원' 글자가 없다.
     *
     * 아래 문자열은 지어낸 것이 아니라 **사용자 실기기의 알림 기록 CSV 에서 그대로
     * 옮긴 것**이다. 이 꼴을 못 읽어서 편의점·PC방·마트 결제 18건 약 24만원이 통째로
     * 가계부에 안 들어가 있었다. 삼성페이는 허용 앱 목록에 처음부터 있었는데도
     * 기록된 건이 한 건도 없었다.
     */
    @Test fun 원화기호로_온_금액을_읽는다() {
        // (제목, 본문, 기대 금액, 기대 가맹점)
        listOf(
            listOf("₩7,100 결제 완료", "씨유 휘경행복점", 7_100L, "씨유 휘경행복점"),
            listOf("₩2,300 결제 완료", "씨유 휘경행복점", 2_300L, "씨유 휘경행복점"),
            listOf("₩1,000 결제 완료", "아크(ARK)PC방", 1_000L, "아크PC방"),
            listOf("₩4,900 결제 완료", "서흥마트", 4_900L, "서흥마트"),
            listOf("₩7,700 결제 완료", "맘스터치휘경점", 7_700L, "맘스터치휘경점")
        ).forEach { row ->
            val title = row[0] as String
            val text = row[1] as String
            val out = Parser.parse(title, text)
            assertTrue("$title / $text -> $out", out is Parser.Out.Expense)
            out as Parser.Out.Expense
            assertEquals(title, row[2] as Long, out.amount)
            // 가맹점까지 못 박는다. `안 비어 있다` 로만 두면 `결제` 가 뽑혀도 통과한다 —
            // 이 세션에서 실제로 문장·코드·날짜가 가게 이름 행세를 하던 버그류다.
            assertEquals(text, row[3] as String, out.merchant)
        }
    }

    /**
     * 정산해 달라는 **요청**은 내가 낼 돈이지 받을 돈이 아니고, 아직 움직이지도 않았다.
     *
     * 아래 문구는 사용자 실기기 알림 기록에서 그대로 옮긴 것이다. `정산금액` 이
     * 정산 규칙(`정산금`)에 걸려 **수입 38,000원**으로 가계부에 들어가 있었고,
     * 가맹점 자리에는 `확인해주세요` 가 앉아 있었다.
     */
    @Test fun 정산_요청은_기록하지_않는다() {
        val out = Parser.parse(
            "석원",
            "정산 내용을 확인해주세요.   - 정산금액 : 38,000원 - 요청인원 : 4명 " +
                "- 정산기한 : 2026. 09. 08.(화) 12:00까지  송금해주세요. " +
                "* 정산기한이 지나기 전에 정산을 완료해주세요."
        )
        assertTrue("$out", out is Parser.Out.None)

        // 카톡방에서 돈 보내 달라는 말도 마찬가지다. 실제로 보내면 송금 알림이 따로 온다.
        assertTrue(Parser.parse("송민준", "꽃닭 9000원씩 보내주세요~") is Parser.Out.None)

        // 정산금이 실제로 들어온 알림은 여전히 정산으로 잡혀야 한다.
        assertTrue(
            Parser.parse("카카오페이", "정산금 30,000원을 받았습니다") !is Parser.Out.None
        )
    }

    /**
     * 포인트가 없어진다는 안내는 지출이 아니다.
     *
     * 실기기 기록의 G마켓 스마일캐시 소멸 안내가 지출 2,200원으로 들어가 있었다.
     * 본문에 "8월 28일까지 **사용** 가능" 이 있어 지출 규칙에 먼저 걸렸고,
     * 광고 규칙(NOT_SPEND)은 지출 규칙이 없을 때만 보므로 못 막았다.
     */
    @Test fun 소멸_안내는_지출이_아니다() {
        val out = Parser.parse(
            "G마켓",
            "[G마켓] 고객님, 보유하신 스마일캐시 2,200원이 소멸 예정입니다.  " +
                "▶ 2026년 8월 28일(금)까지 사용 가능  이 메시지는 G마켓 회원 대상 " +
                "전자금융거래약관 동의에 따라 지급된 캐시 소멸 안내이며, 오늘 기준 잔액 보유 " +
                "고객에게 발송되는 메시지입니다."
        )
        assertTrue("$out", out is Parser.Out.None)
    }

    /**
     * 편의점은 한글 표기로 온다. 규칙에 없어서 일곱 건이 기타/기타지출로 들어가 있었다.
     * 항목별 예산을 쓰는 사람에게는 이게 곧 예산이 틀리는 것이다.
     */
    @Test fun 한글로_적힌_편의점도_식비로_본다() {
        assertEquals(Cat.FOOD, Parser.guessCat("씨유 휘경행복점"))
        assertEquals("마트/식료품", Parser.guessSubCat("씨유 휘경행복점", Cat.FOOD))
        assertEquals(Cat.FOOD, Parser.guessCat("매머드익스프레스 시립대후문점"))
        assertEquals("카페/음료", Parser.guessSubCat("매머드익스프레스 시립대후문점", Cat.FOOD))
        // 알뜰폰은 여전히 고정지출이어야 한다. '세븐' 을 식비에 넣으면 여기가 깨진다.
        assertEquals(Cat.HOUSING, Parser.guessCat("세븐모바일"))
    }

    /**
     * 술집도 밥값이다. 실기기 기록에서 생생호프 54,800원과 호맥 55,500원이
     * 기타/기타지출로 빠져 있었다. 금액이 커서 식비 통계가 통째로 어긋난다.
     */
    @Test fun 술집도_식비로_본다() {
        listOf("생생호프 서산예천점", "○○포차", "이자카야 소라", "생맥주창고").forEach {
            assertEquals(it, Cat.FOOD, Parser.guessCat(it))
            assertEquals(it, "식당/외식", Parser.guessSubCat(it, Cat.FOOD))
        }
        // `호맥` 같은 개별 상호까지 규칙에 넣지는 않는다. 업종 낱말이 아니라 가게 이름이고,
        // 그런 건 사용자가 한 번 고치면 catMemory 가 기억한다(불변식 9). 규칙을 상호로
        // 채우기 시작하면 끝이 없고, 남의 가게 이름을 잘못 삼키는 쪽이 더 위험하다.
        assertEquals(Cat.ETC, Parser.guessCat("호맥 예천점"))
    }

    /**
     * 쿠팡은 옷보다 생필품이 훨씬 잦다.
     * 사용자 가계부에서도 아홉 건 중 일곱 건을 손수 생활/생필품으로 고쳐 놨다.
     */
    @Test fun 쿠팡은_생필품으로_본다() {
        assertEquals(Cat.LIVING, Parser.guessCat("쿠팡"))
        // 쿠팡이츠는 여전히 배달이다. `쿠팡` 을 생활에 넣으며 같이 끌려가면 안 된다.
        assertEquals(Cat.FOOD, Parser.guessCat("쿠팡이츠"))
        assertEquals("배달", Parser.guessSubCat("쿠팡이츠", Cat.FOOD))
    }

    /**
     * 입금은 가계부에 넣지 않기로 했다. 파서는 여전히 수입으로 읽어야 한다 —
     * 기록할지 말지는 `NotifListener` 가 정하고, 알림함에 사유를 남겨야 하기 때문이다.
     */
    @Test fun 입금은_수입으로_읽되_기록은_호출자가_정한다() {
        val out = Parser.parse("토스", "1,500,000원 입금 정은실 → 내 토스뱅크 통장")
        assertTrue("$out", out is Parser.Out.Income)
        assertEquals(1_500_000L, (out as Parser.Out.Income).amount)
    }

    /**
     * 가맹점 자리에 문장이 앉으면 안 된다.
     *
     * 실기기 기록에서 한끼통살 주문 알림의 가맹점이 `완료되었습니다` 로 들어가 있었다.
     * 본문에 쓸 만한 이름이 없으면 이런 토큰이 가장 길어서 뽑힌다. 문장 끝맺음으로
     * 끝나는 토큰을 걸러 내면 본문이 비고, 그때 제목의 가게 이름으로 넘어간다.
     */
    @Test fun 가맹점_자리에_문장이_앉지_않는다() {
        val out = Parser.parse(
            "한끼통살",
            "전성호님, 주문건 결제 완료되었습니다.  ▶결제 완료 일자 : 2026-08-29 " +
                "▶결제금액 : 59,800원 ▶주문번호 : 20260829-0001395"
        )
        assertTrue("$out", out is Parser.Out.Expense)
        out as Parser.Out.Expense
        assertEquals(59_800L, out.amount)
        assertFalse(out.merchant, out.merchant.endsWith("습니다"))
        assertTrue(out.merchant, out.merchant.contains("한끼통살"))

        // 알림 본문이 중간에서 잘려 마침표만 남는 일이 있다. 두 글자라 길이 검사를
        // 통과해 `..` 가 가맹점이 됐었다.
        val cut = Parser.parse("한끼통살", "주문건 결제 완료되었습니다.  ▶결제금액 : 59,800원 ..")
        assertTrue("$cut", (cut as Parser.Out.Expense).merchant.contains("한끼통살"))

        // 본문이 문단처럼 길면 제목을 먼저 본다. 이 알림은 본문에 인사말·접수번호·
        // 점검제품·엔지니어가 줄줄이 있고 가맹점은 제목에만 있어서, 본문에서 고르면
        // `설문조사` 가 뽑혔다.
        val svc = Parser.parse(
            "삼성전자서비스",
            "안녕하세요 삼성전자서비스입니다.  고객님의 서비스 내역과 함께 보다 나은 " +
                "서비스 제공을 위한 설문조사 참여를 안내해 드립니다.  [서비스 내역] " +
                "■ 접수번호 : 202608261416WEB010 ■ 점검제품 : 시스템 에어컨(AC090RN4PBH1) " +
                "■ 엔지니어 : 김봉규 ■ 완료일자 : 2026년 08월 28일 ■ 결제금액 : 166000원"
        )
        assertTrue("$svc", (svc as Parser.Out.Expense).merchant.contains("삼성전자서비스"))

        // 짧은 카드 알림은 그대로 본문에서 뽑는다. 제목에는 금액밖에 없다.
        val card2 = Parser.parse("7,100원 결제", "토스뱅크 체크카드 | 씨유 휘경행복점 잔액 1,157,953원")
        assertEquals("씨유 휘경행복점", (card2 as Parser.Out.Expense).merchant)

        // 한글 없는 영문 상호는 그대로 살아야 한다. 코드 거르기에 걸리면 안 된다.
        val ali = Parser.parse("알림", "ALIEXPRESS.COM 7,485원 결제")
        assertTrue("$ali", (ali as Parser.Out.Expense).merchant.contains("ALIEXPRESS"))
    }

    /**
     * 이름 한가운데 있는 괄호가 가맹점을 쪼개면 안 된다.
     *
     * 실기기 기록의 `아크(ARK)PC방` 이 `아크` + `PC방` 으로 갈려 `PC방` 만 남았다.
     * 뒤에서 가장 긴 토큰을 고르는 규칙 때문에 앞쪽이 통째로 떨어져 나간다.
     */
    @Test fun 이름_속_괄호가_가맹점을_쪼개지_않는다() {
        val out = Parser.parse("토스", "1,000원 결제 토스뱅크 체크카드 | 아크(ARK)PC방")
        assertTrue("$out", out is Parser.Out.Expense)
        assertEquals("아크PC방", (out as Parser.Out.Expense).merchant)

        // 카드사 괄호는 여전히 통째로 사라져야 한다. 붙인다고 `국민1234` 가 남으면 안 된다.
        val card = Parser.parse("알림", "국민(1234) 스타벅스 9,500원 승인")
        assertEquals("스타벅스", (card as Parser.Out.Expense).merchant)

        // 대괄호 꼬리표도 그대로 사라진다.
        val web = Parser.parse("알림", "[Web발신] 이마트 12,000원 결제")
        assertEquals("이마트", (web as Parser.Out.Expense).merchant)
    }

    /** 기호가 붙어도 누적·적립 뒤에 오는 숫자는 결제액이 아니다. */
    @Test fun 원화기호도_적립_뒤의_숫자는_안_집는다() {
        // CU멤버십 알림. 점수는 금액이 아니므로 애초에 안 걸려야 한다.
        assertTrue(Parser.parse("CU멤버십", "휘경행복점에서 142점 적립되었습니다.") is Parser.Out.None)
        // 적립 뒤에 붙은 금액은 건너뛰고 그다음 결제액을 집는다.
        val out = Parser.parse("결제 완료", "적립 ₩500 사용 ₩12,000 스타벅스")
        assertEquals(12_000L, (out as Parser.Out.Expense).amount)
    }

    /**
     * `(광고)` 가 붙은 문자는 금액이 있어도 돈이 움직인 게 아니다.
     *
     * 실기기 알림 6,955건 중 28건이 광고였다. [Parser.NOT_SPEND] 에 "광고" 가 있는데도
     * 두 건이 새어 들어왔다 — 그쪽은 결제 낱말이 없을 때만 보는데, 광고 문구에
     * "사용 중인 번호 그대로" 나 "특가" 가 섞여 결제 규칙이 먼저 걸리기 때문이다.
     */
    @Test fun 광고_문자는_지출도_취소도_아니다() {
        // 알뜰폰 요금제 광고. `월 10원부터` 가 10원짜리 지출로 들어가 있었다.
        val ad = Parser.parse(
            "1670-9098",
            "[Web발신] (광고)안녕하세요. 고고모바일입니다. 사용 중인 번호 그대로! " +
                "[4개월 특가! 월 10원부터] 200분/10GB 10원"
        )
        assertTrue(ad.toString(), ad is Parser.Out.None)

        // 쇼핑몰 광고. 수신거부 안내의 "알림받기취소" 때문에 17,800원 결제 취소로 들어갔다.
        val sale = Parser.parse(
            "애경본사직영몰",
            "(광고)⏰단 4시간! 17,800원 반값 특가 (수신거부:톡톡더보기>혜택알림받기관리>알림받기취소)"
        )
        assertTrue(sale.toString(), sale is Parser.Out.None)
    }

    /**
     * 취소는 무엇이 취소됐는지 말할 때만 취소다.
     *
     * 실기기 기록에서 취소로 잡힌 12건 중 7건이 취소가 아니었다. 그중 셋은 송금
     * 알림이라 73,100원이 지출로도 안 들어가고 사라졌다.
     */
    @Test fun 안내_문구_속_취소는_결제_취소가_아니다() {
        // 주문 확인서 맨 밑의 반품 안내. 결제는 실제로 일어났다.
        val order = Parser.parse(
            "한끼통살",
            "전성호님, 주문건 결제 완료되었습니다. ▶결제금액 : 59,800원 " +
                "※ 식품 특성상 취소 및 반품이 어려우니 배송지 정보를 다시 확인해 주세요."
        )
        assertEquals(59_800L, (order as Parser.Out.Expense).amount)

        // 송금했다는 알림. "취소할 수 있어요" 는 상대가 받기 전까지 무를 수 있다는 안내다.
        val sent = Parser.parse(
            "카카오페이",
            "김다은님에게 13,700원을 보냈어요. 송금 받기 전까지 보낸 분은 " +
                "내역 상세화면에서 취소할 수 있어요."
        )
        assertEquals(13_700L, (sent as Parser.Out.Expense).amount)

        // 진짜 취소는 그대로 취소다. 세 꼴 다 실기기 기록에 있는 문구다.
        assertTrue(Parser.parse("토스", "12,500원 결제 취소 토스뱅크 체크카드 | 티머니") is Parser.Out.Cancel)
        assertTrue(
            Parser.parse("1588-3819", "[Web발신] [네이버페이]결제취소안내 올리브 19,300원") is Parser.Out.Cancel
        )
        assertTrue(Parser.parse("KB국민카드", "승인취소 12,000원 이마트") is Parser.Out.Cancel)
    }

    /**
     * 링크와 눈에 안 보이는 방향 문자는 가맹점 이름이 아니다.
     *
     * 네이버페이 문자 다섯 건의 가맹점이 전부 `naver.me` 였다. 도메인이 가장 긴 토큰이라
     * 뽑힌 것인데, 서로 다른 가게가 한 이름으로 뭉치면 [Store.recallCategory] 의
     * 분류 기억까지 같이 오염된다.
     *
     * 문자 제목은 `⁨1588-3819⁩` 처럼 U+2068/U+2069 로 감싸여 온다. 눈에는 안 보이지만
     * 이름에 남으면 같은 가게가 매번 다른 이름이 되어 중복 판정도 기억도 어긋난다.
     */
    @Test fun 링크와_보이지_않는_문자는_가맹점이_아니다() {
        val out = Parser.parse(
            "⁨1588-3819⁩",
            "[Web발신] [네이버페이]결제완료안내 올리브… '[8/31 하루…' 19,300원 http://naver.me/PayO"
        )
        val e = out as Parser.Out.Expense
        assertEquals(19_300L, e.amount)
        assertEquals("올리브", e.merchant)

        // 주소 꼴 상호는 남는다. 스킴이 없으면 링크가 아니라 가게 이름이다.
        val ali = Parser.parse("카드", "ALIEXPRESS.COM 26,282원 결제")
        assertEquals("ALIEXPRESS.COM", (ali as Parser.Out.Expense).merchant)
    }

    /** 후불교통 정산 문자는 가맹점 자리에 `교통대금` 이 그대로 온다. */
    @Test fun 교통대금은_교통비로_본다() {
        val out = Parser.parse(
            "KB국민카드",
            "[Web발신] [KB국민체크]전*호님 교통대금 8,800원 09/03 체크결제계좌(537)에서 출금예정"
        )
        val e = out as Parser.Out.Expense
        assertEquals(8_800L, e.amount)
        assertEquals(Cat.LEISURE, Parser.guessCat(e.merchant))
        assertEquals("교통/차량", Parser.guessSubCat(e.merchant, Cat.LEISURE))
    }
}
