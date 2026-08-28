package com.pushledger

/**
 * 은행·카드사 알림 문장에서 금액과 가맹점을 뽑아낸다.
 * 앱마다 형식이 제각각이라 정규식 하나에 기대지 않고,
 * 금액 / 취소 / 가맹점 / 결제수단을 각각 독립된 규칙으로 찾아 조합한다.
 */
object Parser {

    sealed interface Out {
        data class Expense(val amount: Long, val merchant: String, val method: String) : Out
        /** 취소 알림. 수입이 아니라 원 거래를 무효화하라는 신호다. */
        data class Cancel(val amount: Long, val merchant: String) : Out
        /** 추가 수입/용돈/입금 알림. */
        data class Income(val amount: Long, val sender: String, val method: String) : Out

        /**
         * 더치페이 정산금이 들어왔다는 알림.
         *
         * 수입이 아니다. 내가 먼저 다 내고 나눠 받는 돈이라, 수입으로 세면 그날
         * 지출과 수입이 같이 부풀어 두 숫자가 다 틀린다. 원래 결제를 그만큼 깎는 게 맞다.
         */
        data class Settle(val amount: Long, val from: String) : Out
        data class None(val reason: String) : Out
    }

    private val MONEY = Regex("""([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)\s*원""")
    private val CANCEL = Regex("""취소|환불""")
    private val SPEND = Regex("""결제|승인|사용|출금|자동이체|일시불|할부""")

    /**
     * 송금은 방향이 전부다.
     *
     * 카카오페이·토스는 보낸 쪽과 받은 쪽 모두에게 알림을 띄우는데 문구가 거의 같아서
     * 조사 하나로 갈린다.
     *   "○○님에게 5,000원을 보냈어요"  → 내 돈이 나갔다. 지출.
     *   "○○님이 5,000원을 보냈어요"    → 내 돈이 들어왔다. 수입.
     *   "○○님이 5,000원을 받았어요"    → 내가 보낸 돈을 상대가 찾아갔다는 확인.
     *                                    보낼 때 이미 지출로 셌으므로 여기서는 아무것도 하지 않는다.
     *
     * 예전에는 "받았어요" 만 보고 수입으로 넣었다. 그래서 세 번째 알림이 올 때마다
     * 보낸 돈이 그대로 수입으로 잡혀 이번 달 수입이 부풀었다.
     *
     * 조사와 동사를 짝지으면 네 경우가 깔끔하게 갈린다.
     *   님이   + 보냈 → 내가 받음      님이   + 받았 → 상대가 받음(내 지출)
     *   님에게 + 보냈 → 내가 보냄      님에게 + 받았 → 내가 받음
     */
    private val OTHER_RECEIVED = Regex(
        // "○○님이 보낸 10,000원을 받았습니다" 에 걸리면 안 된다. 여기서 받은 건 나다 —
        // "님이" 는 "보낸" 의 주어일 뿐이고, "받았"의 주어는 생략된 나다.
        // 그래서 "님이" 뒤에 "보낸/보냈" 이 오면 이 규칙에서 뺀다.
        """님이\s*(?![^.]{0,16}(보낸|보냈|송금한))[^.]{0,14}(받았|수령)""" +
            """|님께서\s*[^.]{0,14}받았|받기\s*완료|수령\s*완료"""
    )
    private val SENT_BY_ME = Regex("""님(에게|께)\s*[^.]{0,14}(보냈|송금)|송금\s*완료|이체\s*완료|보내기\s*완료""")
    private val I_RECEIVED = Regex(
        """입금|송금받|받았습니다|용돈|상여금|급여|환급|캐시백""" +
            """|님이\s*[^.]{0,14}보냈|님(에게|께)\s*[^.]{0,14}받았"""
    )

    /**
     * "○○님" 에서 이름만. 송금 상대는 가맹점이 아니라 사람이라 따로 뽑는다.
     *
     * 별표를 허용한다. 은행과 간편결제는 이름을 가려서 "김*수님" 으로 보내는 일이 훨씬 많은데,
     * 한글만 받으면 그런 알림에서 상대를 통째로 못 뽑는다. 카카오의 카드 SMS 파서도
     * 사람 이름 규칙을 `[\p{Hangul}\*]{2,4}님` 으로 잡는다.
     */
    private val PERSON = Regex("""([가-힣*]{2,4})\s*님""")

    /**
     * 정산금이 들어왔다는 알림.
     *
     * 토스·카카오페이의 정산하기(더치페이)는 내가 먼저 다 내고 나중에 나눠 받는 구조다.
     * 그 돈은 새로 생긴 수입이 아니라 이미 기록된 지출의 일부를 되받은 것이다.
     * "○○님이 보낸 10,000원을 받았습니다" 처럼 평범한 송금 문구로도 오기 때문에,
     * 문구만으로 가르지 않고 [Store.applySettlement] 이 직전 24시간 지출과 대조한다.
     */
    private val SETTLE = Regex("""정산\s*(요청|하기)?\s*(완료|받기|됨)|정산금|더치페이|N빵|1/N|회비\s*정산""")

    /**
     * 배당금·분배금 입금.
     *
     * 증권사 알림은 "결제" 같은 낱말이 없어서 지출 규칙에도 안 걸리고, "입금" 만 보고
     * 수입으로 넘기면 용돈과 한 칸에 섞인다. 배당은 따로 세어야 얼마를 받고 있는지 보인다.
     */
    private val DIVIDEND = Regex("""배당금|현금배당|배당\s*입금|분배금|이자\s*지급""")

    /** 지출이 아닌 알림. 광고와 적립을 지출로 넣으면 통계가 통째로 틀어진다. */
    private val NOT_SPEND = Regex("""적립|이벤트|쿠폰|당첨|광고|혜택|잔액조회|미납""")

    /**
     * 충전은 지출이 아니다.
     *
     * 네이버페이에 4만원을 충전하면 그 돈은 아직 안 쓴 내 돈이고, 나중에 실제로 쓸 때
     * 결제 알림이 또 온다. 충전을 지출로 세면 같은 돈이 두 번 나간 것으로 잡힌다.
     * 실제로 "네이버페이충전 40,000원" 이 여가/문화 지출로 기록돼 있었다.
     *
     * 그렇다고 조용히 버리지는 않는다. 알림함 미처리로 남겨서, 충전을 지출로 보고 싶은
     * 사람은 직접 입력으로 넣을 수 있게 둔다.
     */
    private val TOPUP = Regex("""충전""")

    /** 이 단어 뒤에 붙는 금액은 결제액이 아니다. */
    private val NOISE_BEFORE = Regex("""(누적|잔액|총|합계|적립|할인|한도|남은|사용가능|최대)\s*$""")

    private val DATEISH = Regex("""^[0-9]{1,4}[./:\-][0-9]{1,2}([./:\-][0-9]{1,4})?$""")

    /** 이렇게 끝나는 토큰은 카드사나 결제수단, 사람 이름이지 가맹점이 아니다. */
    private val DROP_SUFFIX = Regex("""(카드|페이|은행|뱅크|님|증권)$""")
    private val DROP_TOKEN = Regex(
        """^(승인|취소|환불|결제|사용|일시불|할부|체크|신용|카드|은행|계좌|알림|발신|""" +
            """[Ww]eb|WEB|님|원|건|누적|잔액|합계|출금|입금|페이|kakaopay|toss|했어요|했습니다)$"""
    )

    /**
     * 눈에는 공백인데 정규식의 `\s` 가 공백으로 안 보는 글자들.
     *
     * 자바 정규식의 `\s` 는 ASCII 공백만 센다. 그런데 카드사 알림에는 전각 공백(U+3000)이
     * 흔히 섞여 들어오고, 그러면 "스타벅스　강남점" 이 통째로 한 덩어리가 되어 토큰이
     * 쪼개지지 않는다. 카카오의 카드 SMS 파서도 이 글자를 따로 공백으로 바꾼다.
     */
    private val WIDE_SPACE = Regex("[\u3000\u00A0\u200B\uFEFF]")

    /** 줄바꿈과 눈에 안 보이는 공백을 평범한 공백으로 편다. */
    private fun flat(s: String) = WIDE_SPACE.replace(s.replace('\n', ' '), " ")

    fun parse(title: String, text: String): Out {
        val body = flat(title + " " + text).trim()
        if (body.isBlank()) return Out.None("본문 없음")

        val cancel = CANCEL.containsMatchIn(body)
        val amount = pickAmount(body) ?: return Out.None("금액 못 찾음")

        if (cancel) {
            val merchant = pickMerchant(text).ifBlank { pickMerchant(title) }
            return Out.Cancel(amount, merchant)
        }

        // 충전은 지갑 안에서 돈이 옮겨간 것뿐이다. 쓸 때 결제 알림이 또 온다.
        if (TOPUP.containsMatchIn(body)) return Out.None("충전은 지출이 아님 (쓸 때 따로 잡힘)")

        // 배당금은 수입이되 용돈과 다른 칸이다.
        if (DIVIDEND.containsMatchIn(body) && !SPEND.containsMatchIn(body)) {
            return Out.Income(amount, pickMerchant(text).ifBlank { pickMerchant(title) }, "계좌")
        }

        // 정산 문구가 붙어 있으면 방향을 따질 것도 없다. 정산은 언제나 내가 받는 쪽이다.
        if (SETTLE.containsMatchIn(body) && !SPEND.containsMatchIn(body)) {
            val who = PERSON.find(body)?.groupValues?.get(1).orEmpty()
            return Out.Settle(amount, who.ifBlank { pickMerchant(text) })
        }

        // 송금은 방향을 가른다. 이 세 줄의 순서가 곧 정책이다.
        // 상대가 받았다는 확인이 앞이다 — "받았" 이 들어 있어 수입 규칙에도 걸리기 때문이다.
        if (OTHER_RECEIVED.containsMatchIn(body) && !SPEND.containsMatchIn(body))
            return Out.None("상대가 받은 송금 확인 (보낼 때 이미 기록됨)")

        if (SENT_BY_ME.containsMatchIn(body) && !CANCEL.containsMatchIn(body)) {
            val to = PERSON.find(body)?.groupValues?.get(1).orEmpty()
            return Out.Expense(amount, if (to.isBlank()) "송금" else "${to}님 송금", pickMethod(body))
        }

        // 수입 / 용돈 / 입금 알림
        if (I_RECEIVED.containsMatchIn(body) && !SPEND.containsMatchIn(body)) {
            val person = PERSON.find(body)?.groupValues?.get(1).orEmpty()
            val sender = person.ifBlank { pickMerchant(text).ifBlank { pickMerchant(title) } }
            return Out.Income(amount, sender, pickMethod(body))
        }

        if (NOT_SPEND.containsMatchIn(body) && !SPEND.containsMatchIn(body))
            return Out.None("지출 알림 아님")

        if (!SPEND.containsMatchIn(body)) return Out.None("결제 문구 없음")

        // 제목은 대개 카드사나 앱 이름이라 가맹점 후보로 두면 그쪽이 뽑힌다. 본문을 먼저 본다.
        val merchant = pickMerchant(text).ifBlank { pickMerchant(title) }
        return Out.Expense(amount, merchant, pickMethod(body))
    }

    /** 본문에 금액이 하나라도 보이는지. 허용 목록 밖 앱을 알림함에 띄울지 판단할 때 쓴다. */
    fun looksLikeMoney(title: String, text: String): Boolean =
        pickAmount("$title $text") != null

    /**
     * 못 읽은 알림에서 그래도 건질 수 있는 만큼만 건진다.
     *
     * 규칙이 거래로 못 만든 알림이라도 금액과 가게 이름은 대개 보인다. 사용자가 손으로
     * 넣을 때 그 둘을 다시 타이핑하게 만들 이유가 없다 — 빈 칸에서 시작하면 대개 안 넣는다.
     */
    fun salvage(title: String, text: String): Pair<Long, String> {
        val body = "$title $text".replace('\n', ' ')
        val amount = pickAmount(body) ?: 0L
        val merchant = pickMerchant(text).ifBlank { pickMerchant(title) }
        return amount to merchant
    }

    /** 누적·잔액 뒤에 붙은 금액을 걸러내고 남은 첫 금액을 결제액으로 본다. */
    private fun pickAmount(body: String): Long? {
        for (m in MONEY.findAll(body)) {
            val before = body.substring(0, m.range.first)
            if (NOISE_BEFORE.containsMatchIn(before)) continue
            val num = m.groupValues[1].replace(",", "").toLongOrNull()
            if (num != null && num > 0) return num
        }
        return null
    }

    /**
     * 문장을 띄어쓰기로 쪼갠 뒤 가맹점이 아닌 토큰(금액, 카드사, 시간, 승인 등)을 지우고
     * 남은 것 중에서 가맹점을 고른다.
     *
     * 예전에는 남은 토큰 중 가장 긴 것 하나만 집었다. 그런데 카드 알림의 가맹점은
     * "홍콩반점0410 서산예천점" 처럼 브랜드와 지점이 떨어져 오는 일이 흔하고,
     * 지점 쪽이 더 길면 브랜드가 통째로 날아간다. 실제로 내역에 "서산예천점",
     * "예천점", "서산효행길점" 만 남아 어느 가게인지 알 수 없는 줄들이 있었다.
     *
     * 그래서 지점으로 끝나는 토큰은 혼자 두지 않고 바로 앞 토큰과 붙인다.
     */
    private fun pickMerchant(src: String): String {
        val cleaned = MONEY.replace(flat(src), " ")
            // "국민(1234)", "신한(9012)" 은 카드지 가맹점이 아니다. 괄호만 지우면
            // "국민" 이 남아 가맹점 후보가 된다. 통째로 지운다.
            .replace(Regex("""[가-힣A-Za-z]+\s*\([\d*]{3,4}\)"""), " ")
            .replace(Regex("""\[[^\]]*\]|\([^)]*\)"""), " ")
            .replace(Regex("""(?<![a-zA-Z가-힣0-9])[0-9]{1,3}(?:,[0-9]{3})+(?![a-zA-Z가-힣0-9])"""), " ")
            .replace(Regex("""[:\-/,~]"""), " ")

        val tokens = cleaned.split(Regex("""\s+"""))
            .map { it.trim() }
            // 토큰 끝의 조사를 뗀다. 받침 짝이 맞을 때만 떼므로 "코나아이" 는 안 깎인다.
            .map { Hangul.stripParticle(it) }
            // 눌어붙은 결제 낱말도 여기서 뗀다. 거르기 전에 떼야 한다 —
            // "이마트사용" 은 "사용" 이 들어 있다는 이유로 토큰째 버려지고 있었다.
            .map { trim(it) }
            .filter { it.length >= 2 }
            .filterNot { DROP_TOKEN.matches(it) }
            .filterNot { DROP_SUFFIX.containsMatchIn(it) }
            .filterNot { DATEISH.matches(it) }
            .filterNot { it.all { c -> c.isDigit() } }
            .filterNot { CANCEL.containsMatchIn(it) || SPEND.containsMatchIn(it) }

        if (tokens.isEmpty()) return ""

        val best = tokens.maxByOrNull { it.length } ?: return ""
        val i = tokens.indexOf(best)
        // 지점은 대개 브랜드 뒤에 온다("홍콩반점0410 서산예천점"). 뒤를 먼저 보고,
        // 고른 것 자체가 지점이면 앞을 붙여 어느 브랜드의 지점인지 살린다.
        tokens.getOrNull(i + 1)?.let { if (BRANCHY.containsMatchIn(it)) return "$best $it" }
        if (BRANCHY.containsMatchIn(best) && i > 0) return tokens[i - 1] + " " + best
        return best
    }

    /** 지점을 가리키는 꼬리. 이걸로 끝나는 토큰은 그것만으로 가게를 알 수 없다. */
    private val BRANCHY = Regex("""(점|지점|매장|본점)$""")

    /**
     * 가맹점 이름에 눌어붙은 결제 낱말. 띄어쓰기 없이 붙어 오면 토큰 걸러내기로는 안 빠진다.
     * 카카오의 카드 SMS 파서도 가맹점 이름 끝의 "사용", "일시불", "취소" 를 잘라 낸다.
     */
    private val GLUED_TAIL = Regex("""(사용|일시불|승인|결제|완료|취소)$""")

    /** 뽑은 이름의 꼬리를 다듬는다. 두 글자 넘게 남을 때만 자른다. */
    private fun trim(name: String): String {
        var s = name.trim()
        while (true) {
            val m = GLUED_TAIL.find(s) ?: break
            if (s.length - m.value.length < 2) break
            s = s.dropLast(m.value.length).trim()
        }
        return s
    }

    private fun pickMethod(body: String) = when {
        body.contains("체크") -> "체크카드"
        body.contains("할부") -> "할부"
        body.contains("계좌") || body.contains("이체") -> "계좌"
        body.contains("카드") -> "카드"
        else -> "간편결제"
    }

    /** 가맹점 이름 및 결제 내용으로 카테고리를 정밀 판별 */
    fun guessCat(merchant: String): Cat {
        val m = merchant.lowercase()
        fun has(vararg k: String) = k.any { m.contains(it) }
        return when {
            // 배당은 증권사 이름과 같이 오는 일이 많아서 금융보다 먼저 본다.
            // 그러지 않으면 "키움증권 배당금" 이 "증권" 에 걸려 금융/투자로 간다.
            has("배당", "분배금") -> Cat.INCOME

            // 1. 식비 (외식, 카페, 배달, 식료품)
            has(
                "스타벅스", "카페", "커피", "coffee", "cafe", "이디야", "투썸", "메가커피", "컴포즈", "빽다방", "폴바셋", "할리스", "디저트", "베이커리", "파리바게", "뚜레쥬르", "설빙", "공차",
                "식당", "분식", "김밥", "치킨", "피자", "버거", "맥도날드", "버거킹", "롯데리아", "맘스터치", "kfc", "서브웨이", "국밥", "찌개", "갈비", "삼겹", "고기", "마라", "초밥", "돈까스",
                "배달", "배민", "배달의민족", "요기요", "쿠팡이츠", "땡겨요", "식료품", "정육", "청과", "수산",
                "마트", "이마트", "홈플러스", "롯데마트", "마켓컬리", "오아시스", "노브랜드",
                "편의점", "gs25", "cu", "세븐일레븐", "이마트24", "미니스톱",
                "반찬", "반찬가게", "식자재", "김치", "채소"
            ) -> Cat.FOOD

            // 2. 고정지출 (월세, 관리비, 공과금, 통신비)
            has(
                "월세", "임대료", "관리비", "선수관리비", "아파트관리비", "빌라관리비",
                "전기요금", "한국전력", "한전", "도시가스", "가스비", "삼천리", "수도요금", "상하수도", "난방비", "공과금", "지로",
                "skt", "sk텔레콤", "kt", "lgu+", "lg유플러스", "알뜰폰", "세븐모바일", "kt엠모바일", "유모바일", "인터넷", "통신비", "와이파이"
            ) -> Cat.HOUSING

            // 3. 생활 (생필품, 의료/약국, 미용) — 마트와 반찬은 식비로 넘겼다
            has(
                "다이소", "크린토피아", "세탁", "빨래방", "잡화", "철물", "생필품", "올리브영",
                "병원", "의원", "약국", "치과", "안과", "이비인후과", "피부과", "정형외과", "내과", "외과", "한의원", "소아과", "동물병원", "보건소", "클리닉", "재활", "메디컬", "건강검진",
                "미용실", "헤어", "바버", "네일", "속눈썹", "왁싱", "피부관리", "마사지", "에스테틱"
            ) -> Cat.LIVING

            // 4. 여가 (문화/컨텐츠, 교통/차량, 취미/운동, 쇼핑/의류)
            has(
                "cgv", "메가박스", "롯데시네마", "영화", "극장", "공연", "뮤지컬", "전시", "티켓", "인터파크", "멜론", "지니", "벅스", "스포티파이",
                "넷플릭스", "netflix", "왓챠", "디즈니", "유튜브", "youtube", "티빙", "웨이브", "쿠팡플레이", "교보문고", "영풍문고", "예스24", "알라딘", "밀리의서재", "리디북스", "웹툰",
                "카카오t", "택시", "타다", "우버", "버스", "지하철", "티머니", "코레일", "ktx", "srt", "기차", "주유", "s-oil", "gs칼텍스", "sk에너지", "오일뱅크", "하이패스", "통행료", "쏘카", "그린카", "렌터카", "주차", "파킹", "항공", "대한항공", "아시아나", "제주항공", "진에어", "에어서울",
                "헬스", "피트니스", "gym", "필라테스", "요가", "수영", "골프", "테니스", "클라이밍", "볼링", "pc방", "노래방", "오락실", "스팀", "steam", "닌텐도", "플레이스테이션", "게임", "캠핑", "낚시",
                "무신사", "지그재그", "에이블리", "29cm", "w컨셉", "유니클로", "자라", "zara", "스파오", "탑텐", "나이키", "아디다스", "abc마트", "백화점", "아울렛", "면세점", "쿠팡", "11번가", "g마켓", "옥션", "네이버페이"
            ) -> Cat.LEISURE

            // 5. 금융 (투자/저축, 대출이자, 보험료, 수수료)
            has(
                "주식", "증권", "투자", "etf", "키움", "토스증권", "미래에셋", "한국투자", "nh투자", "kb증권", "신한투자", "삼성증권", "업비트", "빗썸", "가상자산", "코인",
                "적금", "예금", "청약", "isa", "irp", "펀드", "cma",
                "대출", "이자", "원리금", "신용대출", "담보대출", "전세대출",
                "보험", "생명", "화재", "해상", "손해", "다이렉트", "실비", "암보험", "자동차보험",
                "수수료", "카드연회비", "연회비", "이체수수료",
                // 학자금 대출 이자가 "기타지출" 로 쌓여 있었다. 갚는 돈은 금융이다.
                "장학재단", "학자금", "주택도시기금", "국민행복기금"
            ) -> Cat.FINANCE

            // 6. 수입 (용돈, 보너스, 배당금, 중고판매, 환급금/이자)
            has("입금", "송금받음", "용돈", "상여", "보너스", "급여", "월급", "수당", "당근", "중고나라", "번개장터", "중고", "캐시백", "환급", "배당") -> Cat.INCOME

            else -> Cat.ETC
        }
    }

    /** 카테고리 내에서 세부 서브카테고리를 정밀하게 짐작한다. */
    fun guessSubCat(merchant: String, cat: Cat): String {
        val m = merchant.lowercase()
        fun has(vararg k: String) = k.any { m.contains(it) }
        return when (cat) {
            Cat.FOOD -> when {
                has("카페", "커피", "coffee", "cafe", "스타벅스", "투썸", "메가", "컴포즈", "이디야", "빽다방", "할리스", "폴바셋", "디저트", "베이커리", "빵", "설빙", "공차") -> "카페/음료"
                has("배달", "요기요", "쿠팡이츠", "배민", "배달의민족", "땡겨요") -> "배달"
                has("반찬", "김치", "채소", "장보기", "식자재") -> "반찬/식자재"
                has("마트", "식료품", "정육", "청과", "수산", "마켓컬리", "오아시스", "에브리데이", "편의점", "gs25", "cu ", "세븐일레븐", "이마트24") -> "마트/식료품"
                else -> "식당/외식"
            }
            Cat.HOUSING -> when {
                has("월세", "임대료", "보증금") -> "월세"
                has("관리비", "선수관리비", "아파트관리비", "빌라관리비") -> "관리비"
                has("전기", "한전", "가스", "수도", "공과금", "지로", "난방") -> "공과금"
                has("skt", "kt", "u+", "유플러스", "통신", "알뜰폰", "인터넷", "와이파이") -> "통신비"
                else -> "월세"
            }
            Cat.LIVING -> when {
                has("병원", "약국", "의원", "치과", "의료", "안과", "이비인후과", "피부과", "정형외과", "내과", "한의원", "클리닉", "건강검진") -> "병원/약국"
                has("미용실", "헤어", "바버", "네일", "속눈썹", "왁싱", "피부관리", "마사지", "에스테틱") -> "뷰티/미용"
                else -> "생필품"
            }
            Cat.LEISURE -> when {
                has("택시", "카카오t", "타다", "우버", "버스", "지하철", "코레일", "ktx", "srt", "기차", "주유", "s-oil", "gs칼텍스", "sk에너지", "오일뱅크", "하이패스", "항공", "대한항공", "아시아나", "주차") -> "교통/차량"
                has("헬스", "피트니스", "gym", "필라테스", "요가", "수영", "골프", "테니스", "클라이밍", "볼링", "pc방", "노래방", "스팀", "게임", "캠핑", "낚시") -> "취미/운동"
                has("무신사", "지그재그", "에이블리", "29cm", "w컨셉", "옷", "의류", "신발", "나이키", "아디다스", "유니클로", "자라", "백화점", "아울렛", "쇼핑", "쿠팡", "11번가") -> "쇼핑/의류"
                else -> "문화/컨텐츠"
            }
            Cat.FINANCE -> when {
                has("장학재단", "학자금", "주택도시기금", "국민행복기금") -> "대출이자"
                has("주식", "증권", "투자", "etf", "키움", "토스증권", "코인", "업비트", "빗썸", "가상자산", "적금", "예금", "청약", "펀드", "isa") -> "투자/저축"
                has("보험", "생명", "화재", "해상", "손해", "실비", "다이렉트") -> "보험료"
                has("수수료", "연회비", "이체수수료") -> "이체/수수료"
                else -> "대출이자"
            }
            Cat.INCOME -> when {
                // 배당은 환급·이자와 다른 칸이다. 얼마를 받고 있는지 따로 세야 보인다.
                has("배당", "분배금") -> "배당금"
                has("중고", "당근", "번개") -> "중고판매"
                has("이자", "환급", "캐시백") -> "환급금/이자"
                has("용돈", "보너스", "상여", "급여", "월급", "알바") -> "용돈/보너스"
                else -> "기타수입"
            }
            Cat.ETC -> when {
                has("축의금", "조의금", "부의금", "화환", "청첩장", "경조사") -> "경조사"
                has("선물", "기프티콘") -> "선물"
                else -> "기타지출"
            }
        }
    }
}
