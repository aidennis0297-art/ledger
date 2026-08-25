package com.pushledger

import kotlinx.serialization.Serializable

/**
 * 지출 및 자산 카테고리:
 * - 식비 (카페 포함, 외식, 배달, 마트/식료품, 반찬/식자재)
 * - 생활 (생필품, 의료/약국, 미용)
 * - 여가 (문화/컨텐츠, 교통/차량, 취미/운동, 쇼핑)
 * - 금융 (투자/저축, 대출이자, 보험료, 수수료)
 * - 고정지출 (월세, 관리비, 공과금, 통신비, 구독료)
 * - 수입 (용돈, 보너스, 중고판매, 환급금/이자)
 * - 기타 (경조사, 선물, 기타지출)
 */
enum class Cat(val label: String, val isExpense: Boolean = true, val subs: List<String>) {
    FOOD("식비", true, listOf("식당/외식", "카페/음료", "배달", "마트/식료품", "반찬/식자재")),
    LIVING("생활", true, listOf("생필품", "병원/약국", "뷰티/미용")),
    LEISURE("여가", true, listOf("문화/컨텐츠", "교통/차량", "취미/운동", "쇼핑/의류")),
    FINANCE("금융", true, listOf("투자/저축", "대출이자", "보험료", "이체/수수료")),
    HOUSING("고정지출", true, listOf("월세", "관리비", "공과금", "통신비", "구독료")),
    INCOME("수입", false, listOf("용돈/보너스", "중고판매", "환급금/이자", "기타수입")),
    ETC("기타", true, listOf("경조사", "선물", "기타지출"));

    companion object {
        fun of(raw: String?): Cat =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) || it.label == raw } ?: when (raw?.trim()) {
                "CAFE", "식비", "카페", "마트", "반찬", "식료품", "생필품/마트", "반찬/식자재" -> FOOD
                "LIVING", "생활", "생필품", "의료", "병원", "CULTURE", "의료·문화" -> LIVING
                "LEISURE", "여가", "문화", "교통", "쇼핑", "TRANSPORT", "SHOPPING" -> LEISURE
                "FINANCE", "금융", "이자", "투자", "INVEST" -> FINANCE
                "HOUSING", "주거", "고정지출", "월세", "공과금", "통신", "구독" -> HOUSING
                "INCOME", "수입", "용돈", "보너스", "급여" -> INCOME
                else -> ETC
            }
        val labels get() = entries.joinToString(", ") { it.label }
    }
}


/**
 * 거래 한 건.
 * [canceled]: 결제 취소 여부 (집계 제외)
 * [subCategory]: 세부 분류
 */
@Serializable
data class Txn(
    val id: String,
    val amount: Long,
    val merchant: String,
    val category: String = Cat.ETC.name,
    val subCategory: String = "",
    val at: String,                       // ISO-8601 local, "2026-08-21T14:32:00"
    val method: String = "",
    val sourcePkg: String = "",
    val canceled: Boolean = false,
    val canceledAt: String? = null,
    val memo: String = "",
    val by: String = "rule",              // rule | ai | manual | fixed | invest
    val dedup: String = ""
) {
    val cat get() = Cat.of(category)

    /**
     * 아직 안 나간 고정지출. 결제일이 오면 미리 넣어 두는 자리표다.
     *
     * 실제 출금 알림이 오면 이 줄은 지워지고 진짜 건으로 바뀐다. 그전까지는
     * 화면에서 실제 결제와 구분되어야 한다 — 똑같이 생긴 두 줄이 나란히 있으면
     * 같은 돈이 두 번 빠진 것처럼 보인다.
     */
    val isFixedPlan: Boolean get() = by == "fixed" && dedup.startsWith("fixed|")
    val isInvestment: Boolean get() = (cat == Cat.FINANCE && (subCategory == "투자/저축" || subCategory.startsWith("투자") || subCategory.startsWith("주식") || subCategory.startsWith("적금") || subCategory.startsWith("코인"))) || by == "invest"
}



/** 파싱 전 원본 알림. 인식 실패분만 오래 남고 나머지는 보관 기간이 지나면 지운다. */
@Serializable
data class Raw(
    val id: String,
    val pkg: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: String,
    val state: String = PENDING,
    val note: String = "",                // 실패 사유 / AI 응답 요약
    val dedup: String = ""                // 같은 알림이 갱신되며 여러 번 오는 것을 막는 열쇠
) {
    companion object {
        const val PENDING = "pending"     // 규칙이 못 읽음. 사용자 처리 대기
        const val DONE = "done"           // 거래로 확정됨
        const val IGNORED = "ignored"     // 사용자가 무시
        const val FAILED = "failed"       // AI 도 못 읽음
        const val SUGGEST = "suggest"     // 허용 목록 밖인데 금액이 보임

        /**
         * 켜 둔 앱에서 왔지만 금액이 안 보이는 알림.
         *
         * 예전에는 이런 알림을 그냥 버렸다. 그래서 카카오페이 결제 알림이 평소와 다른
         * 문구로 오면 앱에는 아무 흔적도 남지 않았고, 사용자는 왜 안 잡혔는지 알 길이
         * 없었다. 이제는 남긴다. 다만 미처리로 두면 목록이 잡담으로 덮이므로 상태를 나눠
         * 두고, 여기 있는 것도 낱개로는 AI 에 넘길 수 있게 했다.
         */
        const val NOISE = "noise"
    }
}

@Serializable
data class Fixed(
    val id: String,
    val name: String,
    val amount: Long,
    val day: Int,                         // 매월 결제일 1..31
    val category: String = Cat.LIVING.name,
    val autoRecord: Boolean = true
)

/** 여유비 목표. 달성률은 예산에서 고정지출과 이번 달 지출을 뺀 값으로 본다. */
@Serializable
data class Goal(val name: String = "여유비", val target: Long = 0, val until: String = "")

/**
 * 처음 켜둘 결제 앱. 패키지명은 기기/버전마다 달라서 이건 씨앗일 뿐이고,
 * 실제 조정은 알림함의 앱 관리 화면에서 사용자가 직접 한다.
 */
val DEFAULT_PAY_APPS: Set<String> = setOf(
    "com.kakao.talk",                     // 카카오페이 결제 알림이 톡으로 온다
    "com.kakaopay.app",
    "viva.republica.toss",
    "com.samsung.android.spay",           // 삼성페이 / 삼성월렛
    "com.samsung.android.spaylite",
    "com.nhnent.payapp",                  // 페이코
    "com.nhn.android.search",             // 네이버앱(네이버페이)
    "com.naver.nozzle"
)

@Serializable
data class UserProfile(
    val job: String = "",
    val age: String = "",
    val gender: String = "",
    val goalReason: String = ""
)

/**
 * 만들어 둔 리포트 한 편.
 *
 * [trashed] 는 지웠다는 표시일 뿐 실제로 지우지 않는다. 리포트는 한 번 날리면
 * 같은 걸 다시 만들 수 없다 — 그 시점의 소비 데이터가 이미 지나갔기 때문이다.
 */
@Serializable
data class AiReport(
    val content: String = "",
    val createdAt: String = "",
    val id: String = "",
    val kind: String = "MONTHLY",
    val byAi: Boolean = true,
    val trashed: Boolean = false
)

@Serializable
data class Config(
    val monthlyBudget: Long = 0,
    val catBudget: Map<String, Long> = emptyMap(),
    val fixed: List<Fixed> = emptyList(),
    val goal: Goal? = null,
    val allowedPkgs: Set<String> = DEFAULT_PAY_APPS,
    val blockedPkgs: Set<String> = emptySet(),
    /**
     * 접어 둔 통계 카드. 처음에는 일별과 항목별만 펴 둔다.
     * 일곱 장이 한꺼번에 펼쳐져 있으면 스크롤만 길고 무엇을 봐야 할지 알기 어렵다.
     */
    val collapsedStats: Set<String> = setOf("weekly", "weekday", "hourly", "heat", "merchants"),
    val showStatusNotif: Boolean = true,
    /** 상태창 알림을 도트 그림으로 그릴지. 기기가 커스텀 알림을 안 그려 주면 꺼서 쓴다. */
    val dotNotif: Boolean = false,
    /** 홈의 '남음' 에서 저축 목표를 빼고 볼지. 켜면 실제로 쓸 수 있는 돈만 남는다. */
    val budgetExcludesSaving: Boolean = false,
    val keepInboxDays: Int = 30,
    /**
     * 반복 결제로 잡혔지만 고정지출로 올리지 않기로 한 가맹점.
     * 이게 없으면 매번 같은 곳을 다시 권하게 되고, 권유 카드는 끌 수 없는 잔소리가 된다.
     */
    val ignoredRecurring: Set<String> = emptySet(),
    /**
     * 마지막 AI 일괄 분석 결과 한 줄.
     *
     * 예전에는 결과를 진행 바 안의 글자로만 보여 줬다. 그런데 진행 바는 큐가 끝나는
     * 순간 사라지므로, 정작 "몇 건 됐고 몇 건 실패" 는 눈에 닿기 전에 없어졌다.
     * 설정에 적어 두면 앱을 껐다 켜도 남는다.
     */
    val lastAiRun: String = "",
    /**
     * 규칙이 못 읽은 알림을 자동으로 AI 에 넘길지.
     *
     * 꺼 두면 알림함에 쌓아 두고 사용자가 버튼을 눌러야 돈다. 켜 두면 결제 알림이
     * 들어온 그 자리에서 바로 넘어가므로 알림함을 들여다볼 일 자체가 없어진다.
     * 기본값은 꺼짐이다 — 켜는 순간 알림 한 건마다 API 를 부르기 때문에,
     * 그 비용은 사용자가 알고 켜야 한다.
     */
    val autoAi: Boolean = false,
    val profile: UserProfile = UserProfile(),
    val latestReport: AiReport? = null,
    /** 만들어 둔 리포트 보관함. 최신이 앞에 온다. */
    val reports: List<AiReport> = emptyList()
)


