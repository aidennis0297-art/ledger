package com.pushledger

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ParsedResult(
    val isExpense: Boolean,
    val isCancel: Boolean,
    val amount: Long,
    val merchant: String,
    val category: Cat,
    val subCategory: String,
    val method: String,
    val reason: String = ""
)

object Nvidia {

    private const val PREFS = "pushledger_nvidia"
    private const val KEY_API_KEY = "api_key"
    private const val URL = "https://integrate.api.nvidia.com/v1/chat/completions"
    /** 사용자가 지정한 모델. 다른 모델로 바꾸면 계정에서 안 잡혀 404 가 난다. */
    const val MODEL = "deepseek-ai/deepseek-v4-flash-0731"

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // 550B 에 생각 모드까지 켜면 첫 응답까지 수십 초가 걸린다. 30초로는 무조건 끊긴다.
        .readTimeout(240, TimeUnit.SECONDS)
        .build()

    fun apiKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "") ?: ""

    fun saveApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    private val NOTIF_SYSTEM_PROMPT = """
        당신은 금융/결제 알림 텍스트를 정확하게 구조화하는 데이터 파서입니다.
        입력되는 결제, 취소, 입금 알림을 읽고 순수 JSON 만 출력하세요.

        결제 알림일 때:
        {"is_expense":true,"is_cancel":false,"amount":숫자,"merchant":"가맹점","category":"분류","sub_category":"세부분류","method":"결제수단","confidence":0.0~1.0}

        결제 알림이 아닐 때:
        {"is_expense":false,"reason":"짧은 이유"}

        규칙:
        - category 는 반드시 다음 중 하나다: 식비, 생활, 여가, 금융, 고정지출, 수입, 기타
        - sub_category 는 다음 중 가장 알맞은 것을 고른다:
          * 식비: 식당/외식, 카페/음료, 배달, 마트/식료품, 반찬/식자재
          * 생활: 생필품, 병원/약국, 뷰티/미용
          * 여가: 문화/컨텐츠, 교통/차량, 취미/운동, 쇼핑/의류
          * 금융: 투자/저축, 대출이자, 보험료, 이체/수수료
          * 고정지출: 월세, 관리비, 공과금, 통신비, 구독료
          * 수입: 용돈/보너스, 중고판매, 환급금/이자, 기타수입
          * 기타: 경조사, 선물, 기타지출
        - method 는 다음 중 하나다: 카드, 체크카드, 계좌, 간편결제, 할부
        - amount 는 쉼표 없는 정수다. 누적 금액이나 잔액이 아니라 이번에 쓴/받은 금액을 적는다.
        - 결제 취소나 환불 알림이면 is_expense 는 true 로 두고 is_cancel 을 true 로 한다.
        - 용돈, 보너스, 중고판매 등 수입 알림은 category 를 "수입" 으로 지정하고 is_expense 는 true 로 한다.
        - 단순 충전, 적립, 광고, 쿠폰 알림은 is_expense 를 false 로 한다.

        송금 알림은 방향을 반드시 구분한다. 조사 하나로 갈린다.
        - "○○님에게 …보냈어요" : 내 돈이 나갔다.
          is_expense true, category "기타", sub_category "기타지출", merchant "○○님 송금"
        - "○○님이 …보냈어요" 또는 "○○님에게 …받았어요" : 내 돈이 들어왔다.
          is_expense true, category "수입", merchant 는 보낸 사람 이름
        - "○○님이 …받았어요" : 내가 보낸 돈을 상대가 찾아갔다는 확인일 뿐이다.
          보낼 때 이미 기록했으므로 is_expense false, reason "상대 수령 확인"
        - 이 구분을 틀리면 내가 보낸 돈이 내 수입으로 잡힌다. 가장 조심할 것.

        설명이나 인사말을 붙이지 말고 JSON 만 출력한다.
    """.trimIndent()

    private val COACH_SYSTEM_PROMPT = """
[역할 정의]
당신은 소비 데이터를 분석하고 맞춤형 예산 및 개선안을 제시하는 AI 가계부 코치입니다.
사용자의 소비 내역과 프로필(직업, 나이 등)을 분석하여 정확히 3개의 문단으로 구성된 정형화된 보고서를 생성합니다.
카테고리명은 앱 내부 그래픽 컴포넌트 치환을 위해 반드시 대괄호 [ ] 안에 단일 키워드로 작성해야 합니다.

[문단별 출력 규격 - 줄바꿈 및 문단 구조 엄격 준수]
[1문단: 소비 현황]
- 총 지출: (총 금액)원 (총 건수 건)
- 카테고리별 비중: [카테고리명] (금액)원 & [카테고리명] (금액)원
- 소비 패턴: (시간대별 지출 특징 및 주요 지출처 1~2줄 요약)

[2문단: 예산 및 개선점]
- 권장 예산: [일일] (금액)원 & [주간] (금액)원
- 절감 필요: [카테고리명] (절감 방법 및 예상 절약 금액)
- 유지 권장: [카테고리명] (칭찬 및 긍정적 소비 피드백)

[3문단: 실천 및 응원]
- 실천 과제: (오늘 또는 내일 당장 실행할 구체적 행동 1가지)
- 응원 메시지: (따뜻하고 직관적인 응원 한 줄 ^^)

[작성 제약 조건]
1. 특수문자 금지: **(마크다운 볼드) 및 ""(쌍따옴표) 사용을 절대 금지합니다.
2. 허용 기호: 간단한 이모티콘, [], (), ^^, & 기호만 사용합니다.
3. 그래픽 태그 규칙: 카테고리는 반드시 [식비], [생활], [여가], [금융], [고정지출], [카페], [교통], [쇼핑] 등 대괄호로 묶습니다.
4. 분량 제한: 각 세부 항목은 미사여구 없이 1줄 이내로 간결하게 작성합니다.
    """.trimIndent()

    /**
     * 최근 3개월 총괄. 한 달짜리 코칭과 같은 하네스를 쓰되 보는 창만 넓힌다.
     * 형식을 다르게 하면 앱이 결과를 다르게 파싱해야 하고, 사용자도 매번 다시 읽어야 한다.
     */
    private val QUARTER_SYSTEM_PROMPT = """
[역할 정의]
당신은 최근 3개월 소비 흐름을 총괄 진단하는 AI 가계부 코치입니다.
한 달이 아니라 여러 달의 추세를 보고 정확히 3개 문단의 보고서를 생성합니다.
카테고리명은 앱 내부 그래픽 컴포넌트 치환을 위해 반드시 대괄호 [ ] 안에 단일 키워드로 작성해야 합니다.

[문단별 출력 규격 - 줄바꿈 및 문단 구조 엄격 준수]
[1문단: 3개월 흐름]
- 총 지출: (총 금액)원 (총 건수 건)
- 월별 추세: (늘었는지 줄었는지 한 줄)
- 굳어진 습관: [카테고리명] (3개월 내내 반복된 지출 패턴 1줄)

[2문단: 구조 진단]
- 고정비 비중: [고정지출] (금액)원 (전체 대비 느낌 1줄)
- 변동비 문제: [카테고리명] (가장 흔들리는 항목과 이유)
- 개선 여력: [카테고리명] (3개월 기준 절약 가능 금액)

[3문단: 다음 분기]
- 실천 과제: (다음 3개월 동안 지킬 규칙 1가지)
- 응원 메시지: (따뜻하고 직관적인 응원 한 줄 ^^)

[작성 제약 조건]
1. 특수문자 금지: **(마크다운 볼드) 및 ""(쌍따옴표) 사용을 절대 금지합니다.
2. 허용 기호: 간단한 이모티콘, [], (), ^^, & 기호만 사용합니다.
3. 그래픽 태그 규칙: 카테고리는 반드시 [식비], [생활], [여가], [금융], [고정지출] 등 대괄호로 묶습니다.
4. 분량 제한: 각 세부 항목은 미사여구 없이 1줄 이내로 간결하게 작성합니다.
    """.trimIndent()

    /**
     * 다음 달 예산 설계. 셋 중 이걸 고른 이유는 결과를 앱이 그대로 쓸 수 있어서다.
     * 진단으로 끝나지 않고 예산 탭에 넣을 숫자가 나온다.
     */
    private val PLAN_SYSTEM_PROMPT = """
[역할 정의]
당신은 지난 소비 실적을 근거로 다음 달 예산을 설계하는 AI 가계부 코치입니다.
정확히 3개 문단의 보고서를 생성합니다.
카테고리명은 앱 내부 그래픽 컴포넌트 치환을 위해 반드시 대괄호 [ ] 안에 단일 키워드로 작성해야 합니다.

[문단별 출력 규격 - 줄바꿈 및 문단 구조 엄격 준수]
[1문단: 현재 진단]
- 실제 지출: (총 금액)원 (총 건수 건)
- 예산 대비: (남았는지 넘었는지 한 줄)
- 조정 대상: [카테고리명] (배정과 실제가 가장 어긋난 항목)

[2문단: 다음 달 배정안]
- 총 예산: (금액)원
- 항목 배정: [카테고리명] (금액)원 & [카테고리명] (금액)원 & [카테고리명] (금액)원
- 배정 근거: (왜 이렇게 나눴는지 1줄)

[3문단: 실행]
- 실천 과제: (다음 달 첫 주에 할 일 1가지)
- 응원 메시지: (따뜻하고 직관적인 응원 한 줄 ^^)

[작성 제약 조건]
1. 특수문자 금지: **(마크다운 볼드) 및 ""(쌍따옴표) 사용을 절대 금지합니다.
2. 허용 기호: 간단한 이모티콘, [], (), ^^, & 기호만 사용합니다.
3. 그래픽 태그 규칙: 카테고리는 반드시 [식비], [생활], [여가], [금융], [고정지출] 등 대괄호로 묶습니다.
4. 분량 제한: 각 세부 항목은 미사여구 없이 1줄 이내로 간결하게 작성합니다.
5. 금액은 만원 단위로 딱 떨어지게 제안합니다.
    """.trimIndent()

    /**
     * 종류에 따라 프롬프트와 데이터 범위를 바꿔 리포트를 만든다.
     */
    suspend fun generateReport(
        context: Context,
        kind: ReportKind,
        profile: UserProfile,
        txns: List<Txn>,
        cfg: Config
    ): String = withContext(Dispatchers.IO) {
        val key = apiKey(context)
        if (key.isBlank()) return@withContext "API 키가 없습니다. 예산 탭에서 키를 넣거나 기기 분석으로 만들어 보세요."

        val system = when (kind) {
            ReportKind.MONTHLY -> COACH_SYSTEM_PROMPT
            ReportKind.QUARTER -> QUARTER_SYSTEM_PROMPT
            ReportKind.PLAN -> PLAN_SYSTEM_PROMPT
        }

        // ----- AI 에 넘기는 표본 -----
        //
        // 거래를 통째로 보내면 토큰이 몇 배로 늘고, 정작 답은 나아지지 않는다.
        // 조언에 필요한 건 "무엇이 큰가" 와 "어디가 어긋나는가" 이지 모든 줄의 목록이 아니다.
        // 그래서 큰 것만 이름으로 넘기고 나머지는 뭉쳐서 한 줄로 준다.
        val active = Stats.active(txns)
        val totalSpent = Stats.total(txns)

        // 카테고리는 개수가 일곱뿐이라 전부 넘겨도 가볍다.
        val catSummary = Stats.byCat(txns).joinToString(", ") { "[${it.first.label}] ${it.second}원" }

        // 가맹점은 수십~수백 개가 된다. 지출의 큰 몫을 차지하는 곳까지만 이름을 준다.
        // 상위 몇 개로 자르면 달마다 잘리는 지점이 달라지므로, 누적 비중 60% 를 기준으로 삼되
        // 지나치게 길어지지 않게 여덟 곳에서 끊는다.
        val ranked = Stats.topMerchants(txns, 40)
        val cut = (totalSpent * 0.6).toLong()
        val picked = ArrayList<Pair<String, Long>>()
        var acc = 0L
        for ((m, v) in ranked) {
            if (picked.size >= 8 || (acc >= cut && picked.isNotEmpty())) break
            picked.add(m to v)
            acc += v
        }
        val restCount = (active.size - picked.size).coerceAtLeast(0)
        val restSum = (totalSpent - acc).coerceAtLeast(0L)
        val topStores = picked.joinToString(", ") { "${it.first} ${it.second}원" }

        val daily = Stats.dailyBudget(cfg, txns)
        val hours = Stats.byHour(txns)
        val peak = hours.indices.maxByOrNull { hours[it] } ?: 0
        // 시간대 24칸을 다 주는 대신 몰리는 구간 셋만 준다.
        val topHours = hours.indices.sortedByDescending { hours[it] }.take(3)
            .filter { hours[it] > 0 }
            .joinToString(", ") { "${it}시 ${hours[it]}원" }

        val allocated = cfg.catBudget.entries
            .filter { it.value > 0 && Cat.of(it.key) != Cat.HOUSING }
            .joinToString(", ") { "[${Cat.of(it.key).label}] ${it.value}원" }
        val overs = Stats.overBudgetCats(cfg, txns)
            .joinToString(", ") { "[${it.first.label}] ${it.second}원 초과" }

        val userMessage = """
사용자 프로필:
- 직업: ${profile.job.ifBlank { "미입력" }}
- 나이: ${profile.age.ifBlank { "미입력" }}
- 성별: ${profile.gender.ifBlank { "미입력" }}
- 절약 목표/이유: ${profile.goalReason.ifBlank { "현명한 지출 관리" }}

집계 범위: 최근 ${kind.months}개월
- 총 지출: ${totalSpent}원 (${active.size}건)
- 월 총 예산: ${cfg.monthlyBudget}원
- 고정지출 계획: ${Stats.fixedTotal(cfg)}원
- 저축 목표: ${Stats.investGoal(cfg)}원
- 추가 수입: ${Stats.incomeTotal(txns)}원
- 하루 가용 예산: ${daily.dailyLimit}원 (오늘 지출 ${daily.todaySpent}원)
- 카테고리별 지출: ${catSummary.ifBlank { "없음" }}
- 항목별 배정 예산: ${allocated.ifBlank { "미배정" }}
- 예산 초과 항목: ${overs.ifBlank { "없음" }}
- 주요 지출처(지출의 큰 몫 순): ${topStores.ifBlank { "없음" }}
- 그 밖의 소액 지출: ${restCount}건 합계 ${restSum}원
- 지출이 몰리는 시간대: ${topHours.ifBlank { "${peak}시" }}
        """.trimIndent()

        try {
            val responseText = call(key, system, userMessage)
            responseText.replace(Regex("""<think>[\s\S]*?</think>"""), "").trim()
                .ifBlank { responseText }
        } catch (e: Exception) {
            "AI 코칭 생성 실패: ${e.message ?: "알 수 없는 오류"}"
        }
    }

    /**
     * AI 가계부 코치 리포트 생성.
     */
    suspend fun generateCoachReport(
        context: Context,
        profile: UserProfile,
        monthTxns: List<Txn>,
        cfg: Config
    ): String = withContext(Dispatchers.IO) {
        val key = apiKey(context)
        if (key.isBlank()) return@withContext "API 키가 설정되지 않았습니다. 예산 탭에서 NVIDIA API 키를 먼저 입력해 주세요."

        val totalSpent = Stats.total(monthTxns)
        val catSummary = Stats.byCat(monthTxns).joinToString(", ") { "[${it.first.label}] ${it.second}원" }
        val topStores = Stats.topMerchants(monthTxns, 5).joinToString(", ") { "${it.first} (${it.second}원)" }
        val daily = Stats.dailyBudget(cfg, monthTxns)

        val userMessage = """
사용자 프로필:
- 직업: ${profile.job.ifBlank { "미입력" }}
- 나이: ${profile.age.ifBlank { "미입력" }}
- 성별: ${profile.gender.ifBlank { "미입력" }}
- 절약 목표/이유: ${profile.goalReason.ifBlank { "현명한 지출 관리" }}

이번 달 소비 데이터:
- 월 총 지출: ${totalSpent}원 (총 ${monthTxns.count { !it.canceled && it.cat.isExpense }}건)
- 월 총 예산: ${cfg.monthlyBudget}원
- 오늘 하루 가용 예산: ${daily.dailyLimit}원 (오늘 지출: ${daily.todaySpent}원)
- 카테고리별 지출: ${catSummary.ifBlank { "지출 내역 없음" }}
- 주요 지출처 Top 5: ${topStores.ifBlank { "없음" }}
        """.trimIndent()

        try {
            val responseText = call(key, COACH_SYSTEM_PROMPT, userMessage)
            val cleaned = responseText.replace(Regex("""<think>[\s\S]*?</think>"""), "").trim()
            cleaned.ifBlank { responseText }
        } catch (e: Exception) {
            "AI 코칭 생성 실패: ${e.message ?: "알 수 없는 오류"}"
        }
    }

    /**
     * AI Worker 알림 분석.
     */
    fun analyze(
        apiKey: String,
        appLabel: String,
        title: String,
        text: String,
        catHints: String = ""
    ): Result<ParsedResult> {
        if (apiKey.isBlank()) return Result.failure(Exception("API 키 없음"))
        // 사용자가 이미 분류해 둔 가맹점을 같이 넘긴다. 같은 가게가 매번 다른 칸으로 들어가면
        // 통계가 조각나고, 사용자가 손으로 고친 분류도 매번 되돌아간다.
        val userMessage = buildString {
            if (catHints.isNotBlank()) {
                append("사용자가 이미 분류한 가맹점: ")
                append(catHints)
                append("\n같은 가맹점이 보이면 위 분류를 그대로 따른다.\n\n")
            }
            append("앱: $appLabel / 제목: $title / 내용: $text")
        }
        return runCatching {
            // 파싱은 생각 모드를 끄고 짧게 받는다. 이유는 call() 주석에 있다.
            val responseText = call(
                apiKey, NOTIF_SYSTEM_PROMPT, userMessage,
                thinking = false, maxTokens = 400, temperature = 0.0
            )
            val jsonText = extractJson(responseText)
                ?: error("JSON 을 못 찾음: ${responseText.take(60)}")
            val root = json.parseToJsonElement(jsonText).jsonObject

            val isExpense = root["is_expense"]?.jsonPrimitive?.booleanOrNull ?: false
            if (!isExpense) {
                val reason = root["reason"]?.jsonPrimitive?.content ?: "지출 아님"
                return@runCatching ParsedResult(isExpense = false, isCancel = false, amount = 0L, merchant = "", category = Cat.ETC, subCategory = "", method = "", reason = reason)
            }

            val isCancel = root["is_cancel"]?.jsonPrimitive?.booleanOrNull ?: false
            val amount = root["amount"]?.jsonPrimitive?.longOrNull ?: 0L
            val merchant = root["merchant"]?.jsonPrimitive?.content.orEmpty()
            val method = root["method"]?.jsonPrimitive?.content.orEmpty()
            val catStr = root["category"]?.jsonPrimitive?.content.orEmpty()
            val subCatStr = root["sub_category"]?.jsonPrimitive?.content.orEmpty()

            val cat = Cat.of(catStr)
            ParsedResult(
                isExpense = true,
                isCancel = isCancel,
                amount = amount,
                merchant = merchant,
                category = cat,
                subCategory = subCatStr,
                method = method
            )
        }
    }

    /**
     * [thinking] 은 일 종류에 따라 갈린다.
     *
     * 코치 리포트는 판단이 필요하니 생각 모드를 켠다. 반대로 알림 한 줄에서 금액과
     * 가맹점을 뽑는 일은 생각할 게 없는데, 여기에도 생각 모드가 켜져 있었다. 그래서
     * 알림 한 건에 수십 초가 걸렸고 — 스무 건이면 십 분이다 — 응답이 생각 과정만
     * 쓰다가 상한에 걸려 JSON 이 통째로 안 나오는 일도 잦았다. 파싱은 끄고 짧게 받는다.
     */
    private fun call(
        key: String,
        system: String,
        user: String,
        thinking: Boolean = true,
        maxTokens: Int = 16384,
        temperature: Double = 0.3
    ): String {
        val payload = JSONObject().apply {
            put("model", MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            })
            put("temperature", temperature)
            put("top_p", 0.95)
            put("max_tokens", maxTokens)
            put("stream", false)
            put(
                "chat_template_kwargs",
                JSONObject().put("thinking", thinking)
                    .put("reasoning_effort", if (thinking) "high" else "low")
            )
        }

        val req = Request.Builder()
            .url(URL)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val resp = http.newCall(req).execute()
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) error("HTTP ${resp.code}: $body")

        val root = JSONObject(body)
        val msg = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        // reasoning / reasoning_content 는 모델의 생각 과정이라 버린다. 답만 쓴다.
        return msg.optString("content").ifBlank {
            msg.optString("reasoning_content").ifBlank { msg.optString("reasoning") }
        }
    }

    /**
     * 응답에서 JSON 덩어리만 꺼낸다.
     *
     * 모델은 앞에 인사말을 붙이거나 ```json 울타리로 감싸거나 생각 과정을 먼저 쓴다.
     * 셋 다 걷어낸 뒤, 여는 중괄호부터 짝이 맞는 닫는 중괄호까지만 자른다.
     * 예전에는 마지막 중괄호까지 통째로 잘랐다. 그래서 모델이 JSON 뒤에 설명을 덧붙이며
     * 중괄호를 한 번 더 쓰면 두 덩어리가 붙은 채로 넘어와 파싱이 통째로 깨졌다.
     */
    private fun extractJson(text: String): String? {
        val clean = text
            .replace(Regex("""<think>[\s\S]*?</think>"""), "")
            .replace(Regex("""```(?:json)?"""), "")
            .trim()
        val start = clean.indexOf('{')
        if (start < 0) return null
        var depth = 0
        for (i in start until clean.length) {
            when (clean[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return clean.substring(start, i + 1)
            }
        }
        return null
    }
}
