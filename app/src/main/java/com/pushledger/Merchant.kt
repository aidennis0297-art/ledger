package com.pushledger

/**
 * 가맹점 이름을 다듬는다.
 *
 * 알림에 찍히는 이름은 결제사가 붙인 것들로 덮여 있다. 앞에는 `[체크]`, `(주)`, `토스페이_`
 * 같은 것이 붙고, 뒤에는 `8월분`, `3/12회` 같은 청구 꼬리표가 붙는다. 게다가 같은 가게가
 * 결제 경로에 따라 법인명(`에스씨케이컴퍼니`)으로 오기도 한다.
 *
 * 그대로 두면 두 곳이 망가진다. 통계의 가맹점 순위가 같은 가게를 여러 줄로 쪼개고,
 * 반복 결제 감지는 이름이 글자까지 같아야 묶이므로 달마다 꼬리표가 바뀌는 청구서를
 * 통째로 놓친다.
 *
 * 두 가지를 내놓는다.
 *  - [clean] 은 화면과 기록에 남길 이름이다. 지점명은 남긴다 — 어느 지점에서 썼는지는
 *    사용자에게 뜻이 있는 정보다.
 *  - [key] 는 비교용 열쇠다. 지점명·공백·기호를 다 털어 내서, 같은 가게면 같은 값이 된다.
 */
object Merchant {

    /**
     * 이름 앞에 붙는 껍데기. 여러 겹으로 붙는 일이 흔해서(`[체크](주)`) 반복해서 벗긴다.
     * 대괄호 안은 열 글자까지만 본다 — 그보다 길면 가맹점 이름 자체일 수 있다.
     */
    private val PREFIX = Regex(
        """^\s*(\[[^\]]{1,10}\]|\((주|유)\)|㈜|주식회사|유한회사|""" +
            """[가-힣A-Za-z]{2,8}페이[_\s]|KG이니시스[_\s]|나이스페이[_\s]|스마트로[_\s]|""" +
            """다날[_\s]|KCP[_\s]|올더게이트[_\s])+"""
    )

    /** 이름 뒤에 붙는 껍데기. */
    private val SUFFIX = Regex("""\s*(\(주\)|㈜|주식회사|_법인|\(법인\))\s*$""")

    /**
     * 정기 청구서 꼬리표.
     *
     * 통신비·관리비·보험료는 달마다 이름 뒤가 바뀐다. 이걸 안 떼면 8월 관리비와
     * 9월 관리비가 서로 다른 가맹점이 되어 반복 결제로 잡히지 않는다.
     */
    private val BILLING = Regex(
        """\s*(\d{4}[.\-/]\s?\d{1,2}(월)?|\d{1,2}월\s?분|\d{1,2}월\s?청구|""" +
            """\d{1,3}\s?회차|\d{1,3}\s?/\s?\d{1,3}\s?회?|\d{2}년\s?\d{1,2}월)\s*$"""
    )

    /** 지점 표기. 비교할 때만 떼고 화면에는 남긴다. */
    private val BRANCH = Regex("""\s*\d*호?점$""")

    /**
     * 도메인 부스러기. "i.kiwoom." 이 그대로 가맹점으로 남아 있었다.
     * 앞뒤의 점과 한 글자짜리 조각을 털어 낸다.
     */
    private val DOTTY = Regex("""^[a-zA-Z]\.|^\.+|\.+$""")

    /** 비교 열쇠에서 털어 낼 기호. */
    private val JUNK = Regex("""[\s()\[\]*·.,\-_/'"]""")

    /**
     * 법인명·PG 표기를 사람이 부르는 이름으로 바꾼다.
     *
     * 포함 여부로 본다. 앞뒤에 무엇이 붙어 있든 이 조각이 들어 있으면 그 가게다.
     * 긴 것을 앞에 둬야 한다 — `쿠팡이츠` 를 `쿠팡` 보다 먼저 봐야 배달이 쇼핑으로 안 간다.
     */
    private val ALIAS: List<Pair<String, String>> = listOf(
        "우아한형제들" to "배달의민족",
        "쿠팡이츠서비스" to "쿠팡이츠",
        "쿠팡이츠" to "쿠팡이츠",
        "쿠팡페이" to "쿠팡",
        "에스씨케이컴퍼니" to "스타벅스",
        "스타벅스코리아" to "스타벅스",
        "씨제이올리브영" to "올리브영",
        "cj올리브영" to "올리브영",
        "지에스리테일" to "GS25",
        "지에스25" to "GS25",
        "비지에프리테일" to "CU",
        "코리아세븐" to "세븐일레븐",
        "카카오모빌리티" to "카카오T",
        "네이버파이낸셜" to "네이버페이",
        "요기요" to "요기요",
        "우버코리아" to "우버",
        // 실제 기록에 이렇게 남아 있던 것들이다.
        "비바리퍼블리카" to "토스",
        "kiwoom" to "키움증권",
        "키움증권" to "키움증권",
        "한국장학재단" to "한국장학재단",
        "aliexpress" to "알리익스프레스"
    )

    /** 화면과 기록에 남길 이름. */
    fun clean(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s

        s = PREFIX.replace(s, "")
        s = SUFFIX.replace(s, "")
        var d: String
        do { d = s; s = DOTTY.replace(s, "") } while (s != d)
        // 꼬리표는 겹쳐 붙는다. "SKT 통신요금 2026.08 3회차" 같은 것을 한 겹씩 벗긴다.
        var before: String
        do { before = s; s = BILLING.replace(s, "") } while (s != before)

        val lower = s.lowercase()
        ALIAS.firstOrNull { lower.contains(it.first.lowercase()) }?.let { return it.second }

        return s.replace(Regex("""\s+"""), " ").trim().ifEmpty { raw.trim() }
    }

    /** 같은 가게인지 견주는 열쇠. 지점이 달라도 같은 값이 나온다. */
    fun key(raw: String): String =
        BRANCH.replace(clean(raw), "").let { JUNK.replace(it, "") }.lowercase()

    /**
     * 두 이름이 같은 가게를 가리키는지. 한쪽이 다른 쪽을 품고 있어도 같은 것으로 본다.
     *
     * 다만 짧은 열쇠로 품기 판정을 하면 엉뚱한 것을 삼킨다. 고정지출 이름이 "KT" 면
     * "KTX 예매" 가 통신비로 잡혀 소비 집계에서 통째로 사라진다. 세 글자부터만 품기를 본다.
     */
    fun same(a: String, b: String): Boolean {
        val x = key(a)
        val y = key(b)
        if (x.isBlank() || y.isBlank()) return false
        if (x == y) return true
        if (minOf(x.length, y.length) < 3) return false
        return x.contains(y) || y.contains(x)
    }
}
