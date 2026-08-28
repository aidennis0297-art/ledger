package com.pushledger

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 파일 기반 저장소. 파일 성격에 따라 쪼개는 단위를 다르게 둔다.
 *   ledger/YYYY-MM.json  거래. 영구 보관이라 월별이면 한 파일이 수백 건에 그친다.
 *   inbox/YYYY-MM-DD.json 원본 알림. 양이 많고 대부분 버릴 것이라 날짜째 통으로 지운다.
 *   config.json           예산/고정지출/목표/허용 앱
 * 앱을 켤 때 읽는 건 이번 달 거래 파일 하나뿐이라 몇 년을 써도 시작 속도가 같다.
 *
 * ponytail: 통계는 월 파일을 그때그때 스캔한다. 집계 캐시는 무효화 버그만 늘린다.
 * 한 해치 스캔이 눈에 띄게 느려지면 그때 stats/YYYY-MM.json 을 붙인다.
 */
object Store {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var root: File
    private var appContext: Context? = null
    private val lock = Any()

    /** 화면이 구독하는 이번 달 거래. 알림 서비스가 쓰면 UI 가 따라 갱신된다. */
    val month = MutableStateFlow<List<Txn>>(emptyList())
    val inbox = MutableStateFlow<List<Raw>>(emptyList())
    val config = MutableStateFlow(Config())

    val ts: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    /** 이미 열렸는지. 위젯과 알림 서비스가 각자 init 을 불러도 시드가 두 번 돌지 않게 한다. */
    @Volatile private var opened = false

    /**
     * 저장소를 연다. 앱 화면 말고도 위젯과 알림 서비스가 각자의 진입점에서 부른다.
     * 이 함수를 거르면 [root] 가 초기화되지 않아 첫 접근에서 그대로 죽는다.
     */
    fun ensure(ctx: Context) {
        if (!opened) init(ctx)
    }

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        root = ctx.filesDir
        opened = true
        File(root, "ledger").mkdirs()
        File(root, "inbox").mkdirs()
        config.value = readConfig()
        val ym = YearMonth.now()
        // 예전에는 이번 달이 비어 있으면 예시 거래와 가짜 예산을 자동으로 채웠다.
        // 실제로 쓰기 시작하면 매달 1일마다 가짜 지출이 섞여 통계와 예산이 통째로 틀어진다.
        // 예시가 필요하면 예산 탭의 "테스트 데이터 채우기" 를 직접 누르면 된다.
        checkAutoFixed(ym)
        month.value = readMonth(ym)
        inbox.value = readInboxRecent(config.value.keepInboxDays)
        fixes.value = readFixes()
        sweep()
        StatusNotifier.update(ctx)
    }



    /** 고정지출 결제일이 도래하면 자동으로 해당 월 가계부에 거래를 생성한다. */
    fun checkAutoFixed(ym: YearMonth) = synchronized(lock) {
        val cfg = config.value
        val today = LocalDate.now()
        val monthTxns = readMonth(ym)
        val needed = mutableListOf<Txn>()

        cfg.fixed.forEach { f ->
            val day = f.day.coerceIn(1, ym.lengthOfMonth())
            val targetDate = ym.atDay(day)
            if (ym == YearMonth.now() && today.isBefore(targetDate)) return@forEach

            val fixedDedup = "fixed|${f.name}|${f.amount}|$ym"
            // dedup 만 보면 실제 출금 알림으로 이미 들어온 건을 못 알아본다.
            // 그러면 같은 월세가 자동 생성분과 알림분으로 두 줄이 된다.
            // 취소한 건은 없는 셈 친다. 사용자가 잘못 들어온 월세를 취소했는데
            // "이미 있음" 으로 막아 버리면 그 달 고정지출이 통째로 비게 된다.
            // 금액까지 같아야 "이미 있음" 으로 보면, 관리비처럼 달마다 액수가 달라지는
            // 항목은 실제 출금이 이미 들어와 있는데도 예정 건이 또 만들어진다. 그 둘이
            // 내역에서 나란히 두 줄로 보이던 것이 중복의 정체였다. 이름으로만 센다.
            // by 를 가리지 않는다. 손으로 넣은 월세가 이미 있는데 by=="fixed" 인 것만
            // 세는 바람에 자동 생성분이 또 만들어져, 58만원짜리 월세가 두 줄로 남아 있었다.
            // 그달에 그 이름으로 뭐가 하나라도 있으면 고정지출은 이미 처리된 것이다.
            val already = monthTxns.any {
                !it.canceled && (it.dedup == fixedDedup || Merchant.same(it.merchant, f.name))
            }
            if (!already) {
                val dt = targetDate.atTime(9, 0, 0)
                needed.add(
                    Txn(
                        id = newId(), amount = f.amount,
                        merchant = f.name, category = f.category,
                        at = dt.format(ts), method = "계좌",
                        by = "fixed", dedup = fixedDedup
                    )
                )
            }
        }

        if (needed.isNotEmpty()) {
            writeMonth(ym, (monthTxns + needed).sortedByDescending { it.at })
        }
    }


    // ---- 경로 ----
    private fun monthFile(ym: YearMonth) = File(root, "ledger/$ym.json")
    private fun inboxFile(d: LocalDate) = File(root, "inbox/$d.json")
    private fun configFile() = File(root, "config.json")

    /** 임시 파일에 쓰고 이름을 바꾼다. 쓰는 중에 죽어도 원본이 반쯤 망가지지 않는다. */
    private fun writeAtomic(f: File, body: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(f)) { f.writeText(body); tmp.delete() }
    }

    // ---- 거래 ----

    fun readMonth(ym: YearMonth): List<Txn> = synchronized(lock) {
        val f = monthFile(ym)
        if (!f.exists()) emptyList()
        else runCatching { json.decodeFromString<List<Txn>>(f.readText()) }.getOrDefault(emptyList())
    }

    fun writeMonth(ym: YearMonth, list: List<Txn>) = synchronized(lock) {
        val f = monthFile(ym)
        writeAtomic(f, json.encodeToString(list))
        if (ym == YearMonth.now()) {
            month.value = list
            appContext?.let { StatusNotifier.update(it) }
        }
    }


    /**
     * 거래를 넣는다. 같은 알림이 두 번 들어오는 걸 여기 한 곳에서 막는다.
     * 지출이든 입금이든 전부 이 문을 지나므로, 막을 거면 여기서 막아야 한다.
     *
     * 두 겹으로 본다.
     *  1. dedup 이 같으면 같은 알림이다.
     *  2. 같은 금액이 10초 안에 또 들어오면 같은 결제로 본다.
     *
     * 두 번째 규칙에서 앱과 가맹점은 보지 않는다. 이게 핵심이다 — 한 번 결제하면
     * 카카오페이·카드사·은행이 각자 알림을 띄우는데, 앱마다 가맹점 표기가 다르고
     * (한쪽은 "스타벅스", 다른 쪽은 "스타벅스강남R점", 또 한쪽은 빈칸) 패키지명은
     * 당연히 다르다. 예전에는 앱과 가맹점이 같아야 중복으로 봤기 때문에, 정작 막아야 할
     * 여러 앱 동시 알림은 하나도 못 막고 같은 결제가 두세 줄로 들어왔다.
     *
     * 대가로 10초 안에 같은 금액을 두 번 쓴 진짜 결제는 한 건으로 뭉친다.
     * 편의점에서 3,000원짜리를 두 번 따로 긁는 일은 드물고, 여러 줄로 부풀어
     * 예산이 틀리는 쪽이 사용자에게 훨씬 자주 아프다.
     *
     * 손으로 넣은 건과 고정지출은 사용자가 뜻을 갖고 넣은 것이므로 10초 룰에서 뺀다.
     */
    fun addTxn(t: Txn): Boolean = synchronized(lock) {
        val ym = YearMonth.from(LocalDateTime.parse(t.at, ts))
        val cur = readMonth(ym)
        if (isDuplicate(cur, t)) return false
        writeMonth(ym, (cur + t).sortedByDescending { it.at })
        return true
    }

    /**
     * [cur] 안에 [t] 와 같은 결제가 이미 있는지. 판정만 하고 아무것도 건드리지 않는다.
     * 파일을 안 열기 때문에 이 규칙 하나만 따로 테스트할 수 있다.
     */
    fun isDuplicate(cur: List<Txn>, t: Txn): Boolean {
        if (t.dedup.isNotBlank() && cur.any { it.dedup == t.dedup }) return true
        if (t.by != "rule" && t.by != "ai") return false
        val tAt = LocalDateTime.parse(t.at, ts)
        return cur.any {
            it.amount == t.amount &&
                !it.canceled &&
                (it.by == "rule" || it.by == "ai") &&
                Math.abs(
                    java.time.Duration.between(LocalDateTime.parse(it.at, ts), tAt).seconds
                ) <= DUP_WINDOW_SEC
        }
    }

    /** 같은 결제로 볼 시간 폭. 알림이 지워졌다 다시 뜨는 간격을 덮는다. */
    const val DUP_WINDOW_SEC = 10L

    fun updateTxn(t: Txn) = synchronized(lock) {
        val ym = YearMonth.from(LocalDateTime.parse(t.at, ts))
        val cur = readMonth(ym)
        writeMonth(ym, cur.map { if (it.id == t.id) t else it })
    }

    fun deleteTxn(t: Txn) = synchronized(lock) {
        val ym = YearMonth.from(LocalDateTime.parse(t.at, ts))
        val cur = readMonth(ym)
        writeMonth(ym, cur.filterNot { it.id == t.id })
    }

    /**
     * 결제 취소 알림이 오면 원 거래를 찾아 canceled 를 세운다.
     * 지난달까지 거슬러 본다. 월초에 지난달 결제가 취소되는 일은 흔한데,
     * 같은 달만 뒤지면 그런 건은 조용히 못 찾고 넘어간다.
     */
    fun applyCancel(amount: Long, merchant: String, cancelTime: LocalDateTime): Txn? = synchronized(lock) {
        val key = Merchant.key(merchant)
        for (back in 0L..1L) {
            val ym = YearMonth.from(cancelTime).minusMonths(back)
            val hit = readMonth(ym).filter {
                !it.canceled && it.amount == amount && merchantMatch(key, it.merchant)
            }.minByOrNull {
                val t = LocalDateTime.parse(it.at, ts)
                Math.abs(java.time.Duration.between(t, cancelTime).toMillis())
            }
            if (hit != null) {
                val done = hit.copy(canceled = true, canceledAt = cancelTime.format(ts))
                updateTxn(done)
                return done
            }
        }
        return null
    }

    /**
     * 정산금이 들어왔다. 직전 [hours]시간 안의 지출 중 이 돈을 되받을 만한 건을 찾아 깎는다.
     *
     * 더치페이는 내가 먼저 다 내고 나중에 나눠 받는다. 되받은 돈을 수입으로 세면
     * 그날 지출과 수입이 같이 부풀어 두 숫자가 다 틀리고, 예산은 실제보다 빡빡해진다.
     * 4만원짜리 저녁을 내가 긁고 셋이 1만원씩 보내면 내 지출은 1만원이다.
     *
     * 대상은 **정산금보다 큰 지출 중 가장 최근 것**이다. 작은 결제를 깎아 음수로 만들 수
     * 없고, 여러 건이 걸리면 방금 낸 것일 가능성이 가장 높다.
     *
     * 고친 자국은 [Fix] 로 남긴다. 이미 적어 둔 기록을 자동으로 줄이는 일이라,
     * 무엇이 얼마로 바뀌었는지 보이고 되돌릴 수 있어야 한다.
     */
    fun applySettlement(amount: Long, from: String, at: LocalDateTime, hours: Long = 24): Txn? =
        synchronized(lock) {
            if (amount <= 0L) return null
            val since = at.minusHours(hours)
            val hit = (0L..1L).flatMap { readMonth(YearMonth.from(at).minusMonths(it)) }
                .filter {
                    !it.canceled && it.cat.isExpense && it.by != "fixed" && it.amount > amount &&
                        LocalDateTime.parse(it.at, ts).let { t -> !t.isBefore(since) && !t.isAfter(at) }
                }
                .maxByOrNull { it.at } ?: return null

            val note = "정산 ${amount}원 받음" + if (from.isBlank()) "" else " ($from)"
            val after = hit.copy(
                amount = hit.amount - amount,
                memo = if (hit.memo.isBlank()) note else "${hit.memo} · $note"
            )
            updateTxn(after)
            addFixes(
                listOf(
                    Fix(
                        id = newId(), before = hit, after = after,
                        reason = "정산금이 들어와 원래 결제에서 뺐습니다",
                        applied = true,
                        createdAt = at.format(ts).replace('T', ' ').substring(0, 16)
                    )
                )
            )
            return after
        }

    /**
     * 실제 출금 알림이 고정지출로 잡히면, 미리 넣어 둔 예정 거래를 지우고 실제 건으로 바꾼다.
     * 예정과 실제를 둘 다 남기면 고정지출이 두 배로 집계된다.
     */
    fun replaceAutoFixed(name: String, at: LocalDateTime): Int = synchronized(lock) {
        val ym = YearMonth.from(at)
        // 계획 금액이 아니라 이름으로 지운다. 실제 출금액이 계획과 1원이라도 다르면
        // 금액이 박힌 dedup 으로는 예정 건을 못 찾아서, 예정과 실제가 둘 다 남았다.
        val prefix = "fixed|$name|"
        val cur = readMonth(ym)
        val kept = cur.filterNot { it.dedup.startsWith(prefix) }
        if (kept.size == cur.size) return 0
        writeMonth(ym, kept)
        return cur.size - kept.size
    }

    /**
     * 고정지출을 등록하는 유일한 문.
     *
     * 등록만 하고 끝내면, 이번 달에 이미 들어와 있던 그 가맹점의 결제 한 건이
     * 소비로 남은 채 예정 자리표가 또 만들어진다. 그러면 같은 돈이 소비에서 한 번,
     * 고정지출 계획에서 한 번 빠져 하루 예산이 실제보다 빡빡해지고, 내역에는
     * 똑같이 생긴 두 줄이 나란히 선다.
     *
     * 반복 결제를 찾아 권하기 시작하면서 이건 예외가 아니라 기본 상황이 됐다.
     * 권유는 이미 몇 달치 결제가 쌓인 가맹점에서만 나오기 때문이다.
     */
    fun addFixed(f: Fixed) = synchronized(lock) {
        saveConfig(config.value.copy(fixed = config.value.fixed + f))
        val ym = YearMonth.now()
        val cur = readMonth(ym)
        // 고정지출은 한 달에 한 번이다. 여러 건이 걸리면 가장 최근 것만 바꾼다.
        val hit = cur.filter { !it.canceled && it.by != "fixed" && it.merchant == f.name }
            .maxByOrNull { it.at }
        // 분류도 등록한 고정지출을 따른다. 다음 달에 알림으로 들어올 건이 어차피
        // 그 분류로 기록되므로(NotifListener), 안 맞추면 같은 결제가 달마다 다른 칸에 앉는다.
        if (hit != null) writeMonth(ym, cur.map {
            if (it.id == hit.id)
                it.copy(by = "fixed", category = f.category, subCategory = "고정지출")
            else it
        })
    }

    /**
     * 이번 달에 이 고정지출이 실제로 나갔는지. 예정 자리표는 세지 않는다.
     *
     * 고정지출은 한 달에 한 번이다. 같은 가맹점에서 그달에 또 결제가 일어나면
     * 그건 고정지출이 아니라 그냥 소비다. 이 구분이 없으면 넷플릭스 기프트카드
     * 결제까지 고정지출로 먹혀서 소비 집계에서 통째로 사라진다.
     */
    fun hasRealFixed(name: String, ym: YearMonth): Boolean =
        readMonth(ym).any {
            !it.canceled && it.by == "fixed" && it.merchant == name && !it.isFixedPlan
        }

    /**
     * 어떤 앱의 미처리 알림을 한꺼번에 치운다.
     * 그 앱을 안 보기로 했는데 이미 쌓인 알림이 그대로 남아 있으면,
     * 껐다는 느낌이 들지 않고 목록도 여전히 지저분하다.
     */
    fun ignoreAllFrom(pkg: String, note: String = "앱을 안 보기로 함"): Int {
        val targets = inbox.value.filter {
            it.pkg == pkg &&
                (it.state == Raw.PENDING || it.state == Raw.SUGGEST || it.state == Raw.NOISE)
        }
        targets.forEach { setRawState(it.id, Raw.IGNORED, note) }
        return targets.size
    }

    /**
     * 알림 하나가 만든 거래를 도로 지운다. AI 가 잘못 읽었을 때 되돌릴 길이 없으면
     * 사용자는 내역 탭에서 그 줄을 손으로 찾아 지워야 한다.
     * 지운 건수를 돌려주므로 화면이 결과를 말해 줄 수 있다.
     */
    fun deleteTxnByDedup(dedup: String): Int = synchronized(lock) {
        if (dedup.isBlank()) return 0
        var removed = 0
        val now = YearMonth.now()
        for (back in 0L..1L) {
            val ym = now.minusMonths(back)
            val cur = readMonth(ym)
            val kept = cur.filterNot { it.dedup == dedup }
            if (kept.size != cur.size) {
                removed += cur.size - kept.size
                writeMonth(ym, kept)
            }
        }
        return removed
    }

    /**
     * 거래를 CSV 한 덩어리로 뽑는다. 앱을 지우거나 폰을 바꾸면 filesDir 는 그대로 사라지므로,
     * 밖으로 내보낼 길이 없으면 몇 달치 가계부가 통째로 날아간다.
     * 취소된 건도 표시해서 함께 내보낸다. 나중에 왜 빠졌는지 알아야 하기 때문이다.
     */
    fun exportCsv(months: Int = 24): String = synchronized(lock) {
        val now = YearMonth.now()
        val rows = (0 until months).flatMap { readMonth(now.minusMonths(it.toLong())) }
            .sortedByDescending { it.at }
        val sb = StringBuilder("날짜,시각,가맹점,금액,분류,세부분류,결제수단,취소,출처,메모\n")
        rows.forEach { t ->
            fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
            sb.append(t.at.substring(0, 10)).append(',')
                .append(t.at.substring(11)).append(',')
                .append(q(t.merchant)).append(',')
                .append(t.amount).append(',')
                .append(q(t.cat.label)).append(',')
                .append(q(t.subCategory)).append(',')
                .append(q(t.method)).append(',')
                .append(if (t.canceled) "취소" else "").append(',')
                .append(q(t.by)).append(',')
                .append(q(t.memo)).append('\n')
        }
        return sb.toString()
    }

    /**
     * 받은 알림 원문을 통째로 CSV 로 뽑는다.
     *
     * 거래로 만들어진 것뿐 아니라 **폰에 온 알림을 그대로** 내보낸다. 규칙이 왜 못 읽었는지는
     * 원문을 봐야 알 수 있고, 원문은 이 앱 안에만 있다. 파일에서 직접 읽으므로 화면이
     * 들고 있는 상한([INBOX_MAX])에 잘리지 않는다.
     *
     * 줄바꿈은 공백으로 편다. 알림 본문에는 줄바꿈이 흔한데 그대로 두면 CSV 한 줄이
     * 여러 줄로 쪼개져 표 계산기에서 열이 통째로 밀린다.
     */
    fun exportRawCsv(days: Int = 90): String = synchronized(lock) {
        val today = LocalDate.now()
        val rows = (0 until days.coerceAtLeast(1)).flatMap { d ->
            val f = inboxFile(today.minusDays(d.toLong()))
            if (!f.exists()) emptyList()
            else runCatching { json.decodeFromString<List<Raw>>(f.readText()) }.getOrDefault(emptyList())
        }.sortedByDescending { it.postedAt }

        val sb = StringBuilder("날짜,시각,앱,패키지,제목,내용,상태,사유\n")
        rows.forEach { r ->
            fun q(v: String) =
                "\"" + v.replace('\n', ' ').replace('\r', ' ').replace("\"", "\"\"") + "\""
            sb.append(r.postedAt.substring(0, 10)).append(',')
                .append(r.postedAt.substring(11)).append(',')
                .append(q(r.appLabel)).append(',')
                .append(q(r.pkg)).append(',')
                .append(q(r.title)).append(',')
                .append(q(r.text)).append(',')
                .append(q(stateLabel(r.state))).append(',')
                .append(q(r.note)).append('\n')
        }
        return sb.toString()
    }

    /** 상태 값을 사람이 읽는 말로. CSV 를 열었을 때 pending 이 아니라 미처리로 보이게. */
    private fun stateLabel(s: String) = when (s) {
        Raw.PENDING -> "미처리"
        Raw.DONE -> "기록됨"
        Raw.FAILED -> "실패"
        Raw.IGNORED -> "무시됨"
        Raw.SUGGEST -> "안 켠 앱"
        Raw.NOISE -> "금액 없음"
        else -> s
    }

    /** 알림 원문 CSV 를 파일로 쓴다. */
    fun writeRawCsvFile(days: Int = 90): File = synchronized(lock) {
        val dir = File(root, "export").apply { mkdirs() }
        val f = File(dir, "알림기록_${LocalDate.now()}.csv")
        f.writeText("\uFEFF" + exportRawCsv(days))
        return f
    }

    /** CSV 를 앱 전용 export 폴더에 파일로 쓰고 그 파일을 돌려준다. */
    fun writeCsvFile(months: Int = 24): File = synchronized(lock) {
        val dir = File(root, "export").apply { mkdirs() }
        val f = File(dir, "가계부_${LocalDate.now()}.csv")
        // 엑셀이 한글을 깨뜨리지 않게 BOM 을 앞에 붙인다.
        f.writeText("\uFEFF" + exportCsv(months))
        return f
    }

    /**
     * 리포트를 보관함 맨 앞에 넣는다.
     *
     * 개수 제한을 두지 않는다. 리포트는 글자뿐이라 한 편이 수 KB 이고,
     * 설정 파일 하나에 수백 편이 들어가도 앱이 느려지지 않는다.
     * 오히려 몇 달 뒤에 그때 뭐라고 했는지 되짚는 것이 이 기능의 값어치다.
     */
    fun addReport(r: AiReport) = synchronized(lock) {
        val cfg = config.value
        saveConfig(cfg.copy(latestReport = r, reports = listOf(r) + cfg.reports))
    }

    /** 휴지통으로 보낸다. 실제로 지우지는 않는다. */
    fun trashReport(id: String) = synchronized(lock) {
        val cfg = config.value
        saveConfig(cfg.copy(reports = cfg.reports.map {
            if (it.id == id) it.copy(trashed = true) else it
        }))
    }

    /** 휴지통에서 되돌린다. */
    fun restoreReport(id: String) = synchronized(lock) {
        val cfg = config.value
        saveConfig(cfg.copy(reports = cfg.reports.map {
            if (it.id == id) it.copy(trashed = false) else it
        }))
    }

    /** 휴지통을 비운다. 여기서만 진짜로 사라진다. */
    fun emptyReportTrash(): Int = synchronized(lock) {
        val cfg = config.value
        val kept = cfg.reports.filterNot { it.trashed }
        val gone = cfg.reports.size - kept.size
        if (gone > 0) saveConfig(cfg.copy(reports = kept))
        return gone
    }

    /**
     * 최근 n 개월치 거래를 한 줄로 잇는다. 총괄 리포트는 한 달만 봐서는 흐름이 안 보인다.
     */
    fun recentMonths(n: Int): List<Txn> = synchronized(lock) {
        val now = YearMonth.now()
        (0 until n.coerceAtLeast(1)).flatMap { readMonth(now.minusMonths(it.toLong())) }
            .sortedByDescending { it.at }
    }

    /**
     * 한 해 거래 전부. 월 파일 열두 개를 그때그때 읽는다.
     *
     * 캐시를 두지 않는다. 연간 화면은 들어올 때 한 번 읽고 마는 자리라,
     * 캐시를 두면 얻는 건 없고 거래를 고칠 때마다 무효화를 잊을 자리만 하나 는다.
     */
    fun readYear(year: Int): List<Txn> = (1..12).flatMap { readMonth(YearMonth.of(year, it)) }

    /**
     * 최근 두 달에 쓴 가맹점과 그 분류. AI 프롬프트에 실어 보내 같은 가게가 매번
     * 다른 카테고리로 들어가는 걸 막는다. 사용자가 손으로 고친 분류가 그대로 기준이 된다.
     */
    fun catHints(max: Int = 20): String = synchronized(lock) {
        // 사용자가 손으로 정한 것이 가장 확실한 근거다. 그것부터 싣는다.
        val fixed = config.value.catMemory.entries.take(max / 2).joinToString(", ") { (k, v) ->
            val p = v.split("|")
            "$k=${Cat.of(p.getOrNull(0)).label}/${p.getOrNull(1).orEmpty()}"
        }
        val now = YearMonth.now()
        val txns = (readMonth(now) + readMonth(now.minusMonths(1)))
            .filter { !it.canceled && it.merchant.isNotBlank() }
        if (txns.isEmpty()) return fixed
        val recent = txns.groupBy { it.merchant }
            .entries.sortedByDescending { it.value.size }.take(max)
            .joinToString(", ") { (m, list) ->
                val latest = list.maxByOrNull { it.at }!!
                if (latest.subCategory.isNotBlank()) "$m=${latest.cat.label}/${latest.subCategory}"
                else "$m=${latest.cat.label}"
            }
        return listOf(fixed, recent).filter { it.isNotBlank() }.joinToString(", ")
    }

    private fun merchantMatch(key: String, other: String): Boolean {
        if (key.isBlank()) return true          // 취소 알림에 가맹점이 없으면 금액만으로 본다
        val o = Merchant.key(other)
        return o.contains(key) || key.contains(o)
    }

    // ---- 알림함 ----
    fun readInboxRecent(days: Int): List<Raw> = synchronized(lock) {
        val today = LocalDate.now()
        (0 until days).flatMap { d ->
            val f = inboxFile(today.minusDays(d.toLong()))
            if (!f.exists()) emptyList()
            else runCatching { json.decodeFromString<List<Raw>>(f.readText()) }.getOrDefault(emptyList())
        }.sortedByDescending { it.postedAt }.take(INBOX_MAX)
    }

    fun addRaw(r: Raw) = synchronized(lock) {
        val d = LocalDateTime.parse(r.postedAt, ts).toLocalDate()
        val f = inboxFile(d)
        val cur = if (f.exists())
            runCatching { json.decodeFromString<List<Raw>>(f.readText()) }.getOrDefault(emptyList())
        else emptyList()
        writeAtomic(f, json.encodeToString(cur + r))
        inbox.value = (inbox.value + r).sortedByDescending { it.postedAt }.take(INBOX_MAX)
    }

    /**
     * 같은 알림이 갱신되며 다시 들어오는 것을 막는다.
     *
     * 최근 것만 본다. 이제 폰에 오는 알림을 전부 쌓으므로 목록이 수천 줄이 되는데,
     * 같은 알림이 갱신되며 다시 오는 것은 몇 분 안의 일이라 앞쪽만 봐도 충분하다.
     * 알림 한 건마다 수천 줄을 훑으면 그게 곧 배터리다.
     */
    fun hasRawDedup(dedup: String): Boolean =
        dedup.isNotBlank() && inbox.value.asSequence().take(DEDUP_SCAN).any { it.dedup == dedup }

    private const val DEDUP_SCAN = 400

    /**
     * 화면이 들고 있을 알림 수의 상한.
     *
     * 파일에는 보관 기간(기본 30일)만큼 다 남는다. 여기 상한은 메모리에만 걸린다 —
     * 알림을 전부 모으기 시작하면 하루 수백 건이 쌓이고, 30일치를 통째로 메모리에
     * 올리면 목록을 한 번도 안 열어도 수십 MB 를 물고 있게 된다.
     * 내보내기는 파일에서 직접 읽으므로 여기서 잘린 것도 다 나온다.
     */
    private const val INBOX_MAX = 3000

    fun setRawState(id: String, state: String, note: String = "") = synchronized(lock) {
        val target = inbox.value.firstOrNull { it.id == id } ?: return
        val d = LocalDateTime.parse(target.postedAt, ts).toLocalDate()
        val f = inboxFile(d)
        val cur = runCatching { json.decodeFromString<List<Raw>>(f.readText()) }.getOrDefault(emptyList())
        writeAtomic(f, json.encodeToString(cur.map {
            if (it.id == id) it.copy(state = state, note = note) else it
        }))
        inbox.value = inbox.value.map { if (it.id == id) it.copy(state = state, note = note) else it }
    }

    /**
     * 어떤 상태의 알림을 전부 미처리로 되돌린다. 되돌린 건수를 준다.
     *
     * 알림함의 처리는 전부 되돌릴 수 있어야 한다. 무시를 한 번 잘못 누르거나
     * "이 앱 안 보기" 로 수십 건이 한꺼번에 무시되면, 낱개로 무시 취소를 누르는 것
     * 말고는 길이 없었다. 실제로 그게 비가역이라는 말을 들었다.
     */
    fun restoreAll(from: String, note: String = "되돌림"): Int {
        val targets = inbox.value.filter { it.state == from }
        targets.forEach { setRawState(it.id, Raw.PENDING, note) }
        return targets.size
    }

    /** 한 앱에서 온 알림 중 무시된 것을 전부 되돌린다. 앱을 다시 켤 때 같이 부른다. */
    fun restoreAllFrom(pkg: String): Int {
        val targets = inbox.value.filter {
            it.pkg == pkg && (it.state == Raw.IGNORED || it.state == Raw.NOISE)
        }
        targets.forEach { setRawState(it.id, Raw.PENDING, "앱을 다시 켜서 되돌림") }
        return targets.size
    }

    /** 보관 기간이 지난 알림 파일을 날짜째 지운다. 여기가 앱이 느려지지 않는 이유다. */
    fun sweep() = synchronized(lock) {
        val cut = LocalDate.now().minusDays(config.value.keepInboxDays.toLong())
        File(root, "inbox").listFiles()?.forEach { f ->
            val d = runCatching { LocalDate.parse(f.name.removeSuffix(".json")) }.getOrNull()
            if (d != null && d.isBefore(cut)) f.delete()
        }
    }

    // ---- 설정 ----
    private fun readConfig(): Config {
        val f = configFile()
        return if (!f.exists()) Config()
        else runCatching { json.decodeFromString<Config>(f.readText()) }.getOrDefault(Config())
    }

    fun saveConfig(c: Config) = synchronized(lock) {
        writeAtomic(configFile(), json.encodeToString(c))
        config.value = c
        appContext?.let { StatusNotifier.update(it) }
    }


    /**
     * 이 가맹점은 이 분류라고 기억해 둔다. 사용자가 내역에서 고칠 때 부른다.
     * 지점이 달라도 같은 열쇠가 나오므로 "스타벅스 강남점" 을 고치면 역삼점도 따라온다.
     */
    fun rememberCategory(merchant: String, cat: Cat, sub: String) {
        val k = Merchant.key(merchant)
        if (k.isBlank()) return
        saveConfig(config.value.copy(catMemory = config.value.catMemory + (k to "${cat.name}|$sub")))
    }

    /** 기억해 둔 분류. 없으면 null 이고, 그때만 규칙이 짐작한다. */
    fun recallCategory(merchant: String): Pair<Cat, String>? {
        val v = config.value.catMemory[Merchant.key(merchant)] ?: return null
        val p = v.split("|")
        return Cat.of(p.getOrNull(0)) to p.getOrNull(1).orEmpty()
    }

    /** 사용자가 정한 분류를 잊는다. 잘못 기억시킨 것을 되돌릴 길이 있어야 한다. */
    fun forgetCategory(merchant: String) {
        val k = Merchant.key(merchant)
        saveConfig(config.value.copy(catMemory = config.value.catMemory - k))
    }

    /** 마지막 AI 일괄 분석 결과. 빈 문자열이면 화면에서 그 줄이 사라진다. */
    fun setLastAiRun(msg: String) = saveConfig(config.value.copy(lastAiRun = msg))

    // ---- AI 기록 검토 ----
    //
    // 제안은 설정이 아니라 자기 파일에 둔다. 설정은 앱이 켜질 때마다 통째로 읽는 것이라
    // 수십 건짜리 제안 목록이 얹히면 시작이 그만큼 느려지고, 다 쓰고 나면 통째로 버릴
    // 물건이라 설정과 수명도 다르다.

    private fun fixFile() = File(root, "fixes.json")

    /** 아직 적용하지 않은 것과 적용해서 되돌릴 수 있는 것이 함께 들어 있다. */
    val fixes = MutableStateFlow<List<Fix>>(emptyList())

    private fun readFixes(): List<Fix> {
        val f = fixFile()
        return if (!f.exists()) emptyList()
        else runCatching { json.decodeFromString<List<Fix>>(f.readText()) }.getOrDefault(emptyList())
    }

    private fun writeFixes(list: List<Fix>) = synchronized(lock) {
        writeAtomic(fixFile(), json.encodeToString(list))
        fixes.value = list
    }

    /**
     * 새 제안을 넣는다. 같은 거래에 대한 아직 안 쓴 제안은 새것으로 갈아 끼운다.
     * 검토를 두 번 돌렸을 때 같은 줄에 대한 제안이 두 개 쌓이면 어느 쪽을 눌러야 할지 알 수 없다.
     */
    fun addFixes(list: List<Fix>) = synchronized(lock) {
        if (list.isEmpty()) return
        val incoming = list.map { it.before.id }.toSet()
        val kept = fixes.value.filterNot { !it.applied && it.before.id in incoming }
        writeFixes(list + kept)
    }

    /** 제안대로 고친다. 되돌릴 수 있게 제안 자체는 남긴다. */
    fun applyFix(id: String): Boolean = synchronized(lock) {
        val fx = fixes.value.firstOrNull { it.id == id && !it.applied } ?: return false
        if (fx.drop) deleteTxn(fx.before) else {
            updateTxn(fx.after)
            // 제안을 받아들인 것도 사용자가 그 분류를 정한 것이다. 다음부터 안 물어본다.
            rememberCategory(fx.after.merchant, fx.after.cat, fx.after.subCategory)
        }
        writeFixes(fixes.value.map { if (it.id == id) it.copy(applied = true) else it })
        return true
    }

    /** 적용한 것을 원래대로 돌린다. 지웠던 줄은 다시 넣는다. */
    fun undoFix(id: String): Boolean = synchronized(lock) {
        val fx = fixes.value.firstOrNull { it.id == id && it.applied } ?: return false
        if (fx.drop) restoreTxn(fx.before) else updateTxn(fx.before)
        writeFixes(fixes.value.map { if (it.id == id) it.copy(applied = false) else it })
        return true
    }

    /** 제안을 목록에서 뺀다. 거래는 건드리지 않는다. */
    fun dismissFix(id: String) = synchronized(lock) {
        writeFixes(fixes.value.filterNot { it.id == id })
    }

    fun clearFixes() = synchronized(lock) { writeFixes(emptyList()) }

    /**
     * 되돌리기 전용 삽입. 중복 판정을 건너뛴다 —
     * 방금 지운 그 줄을 그대로 되돌리는 것이라 새 결제인지 따질 게 없다.
     */
    fun restoreTxn(t: Txn) = synchronized(lock) {
        val ym = YearMonth.from(LocalDateTime.parse(t.at, ts))
        val cur = readMonth(ym)
        if (cur.none { it.id == t.id }) writeMonth(ym, (cur + t).sortedByDescending { it.at })
    }

    fun toggleStatsCollapse(key: String) {
        val cur = config.value.collapsedStats
        val next = if (key in cur) cur - key else cur + key
        saveConfig(config.value.copy(collapsedStats = next))
    }

    /** 30개의 다양한 카테고리 샘플 데이터를 생성하여 채운다. */
    fun seedTestData(ym: YearMonth) = synchronized(lock) {
        val days = ym.lengthOfMonth()
        data class Sample(val merchant: String, val amount: Long, val cat: Cat, val sub: String)

                val samples = listOf(
            Sample("맥도날드 강남점", 9400L, Cat.FOOD, "식당/외식"),
            Sample("스타벅스 역삼역점", 6000L, Cat.FOOD, "카페/음료"),
            Sample("배달의민족 (교촌치킨)", 28500L, Cat.FOOD, "배달"),
            Sample("이마트 역삼점", 42000L, Cat.FOOD, "마트/식료품"),
            Sample("투썸플레이스", 12500L, Cat.FOOD, "카페/음료"),
            Sample("김밥천국", 7500L, Cat.FOOD, "식당/외식"),
            Sample("다이소 강남본점", 14000L, Cat.LIVING, "생필품/마트"),
            Sample("올리브영 강남타운", 27400L, Cat.LIVING, "뷰티/미용"),
            Sample("GS25 편의점", 5200L, Cat.LIVING, "생필품/마트"),
            Sample("일원약국", 16200L, Cat.LIVING, "병원/약국"),
            Sample("연세이비인후과의원", 45000L, Cat.LIVING, "병원/약국"),
            Sample("정든반찬가게", 18000L, Cat.LIVING, "반찬/식자재"),
            Sample("카카오T 택시", 14800L, Cat.LEISURE, "교통/차량"),
            Sample("코레일 KTX 예매", 43800L, Cat.LEISURE, "교통/차량"),
            Sample("GS칼텍스 주유소", 70000L, Cat.LEISURE, "교통/차량"),
            Sample("CGV 영화관람", 15000L, Cat.LEISURE, "문화/컨텐츠"),
            Sample("넷플릭스 정기결제", 17000L, Cat.LEISURE, "문화/컨텐츠"),
            Sample("무신사 온라인스토어", 59000L, Cat.LEISURE, "쇼핑/의류"),
            Sample("교보문고 강남점", 23000L, Cat.LEISURE, "문화/컨텐츠"),
            Sample("스팀 (Steam) 게임", 34800L, Cat.LEISURE, "취미/운동"),
            Sample("신한카드 연회비", 15000L, Cat.FINANCE, "이체/수수료"),
            Sample("삼성화재 실손보험", 42000L, Cat.FINANCE, "보험료"),
            Sample("카카오뱅크 대출이자", 85000L, Cat.FINANCE, "대출이자"),
            Sample("토스증권 미국 주식(SPY)", 250000L, Cat.FINANCE, "투자/저축"),
            Sample("청년주택드림 청약통장", 200000L, Cat.FINANCE, "투자/저축"),
            Sample("원룸 월세", 450000L, Cat.HOUSING, "월세"),
            Sample("SKT 통신요금", 55000L, Cat.HOUSING, "통신비"),
            Sample("한국전력 전기요금", 24500L, Cat.HOUSING, "공과금"),
            Sample("당근마켓 중고판매 입금", 50000L, Cat.INCOME, "중고판매"),
            Sample("회사 명절 보너스", 300000L, Cat.INCOME, "용돈/보너스"),
            Sample("친구 결혼식 축의금", 100000L, Cat.ETC, "경조사")
        )

        val txns = samples.mapIndexed { idx, s ->
            val day = ((idx * 2) % (days - 1)) + 1
            val hour = (8 + (idx * 3) % 15).coerceIn(0, 23)
            val min = (idx * 7) % 60
            val atStr = LocalDateTime.of(ym.year, ym.monthValue, day, hour, min, 0).format(ts)
            Txn(
                id = newId(),
                amount = s.amount,
                merchant = s.merchant,
                category = s.cat.name,
                subCategory = s.sub,
                at = atStr,
                method = if (s.sub == "투자/저축") "계좌" else if (idx % 3 == 0) "체크카드" else if (idx % 3 == 1) "신용카드" else "간편결제",
                by = if (s.sub == "투자/저축") "invest" else "manual",
                memo = "테스트 데이터",
                // 표식을 dedup 에 남긴다. 통계에 쓰이지 않는 칸이라 집계를 건드리지 않으면서
                // 나중에 이 건들만 골라 지울 수 있다.
                dedup = SEED_TAG + newId()
            )
        }.toMutableList()

        // 취소된 거래 1건 추가 (취소선 및 통계 제외 검증용)
        val cancelDay = minOf(15, days)
        txns.add(
            Txn(
                id = newId(),
                amount = 12000L,
                merchant = "스타벅스 강남점",
                category = Cat.FOOD.name,
                subCategory = "카페/음료",
                at = LocalDateTime.of(ym.year, ym.monthValue, cancelDay, 14, 20, 0).format(ts),

                method = "카드",
                canceled = true,
                canceledAt = LocalDateTime.of(ym.year, ym.monthValue, cancelDay, 15, 0, 0).format(ts),
                by = "rule",
                memo = "결제 취소 테스트",
                dedup = SEED_TAG + newId()
            )
        )

        // 기존 거래를 덮어쓰면 실제로 쓰던 가계부가 통째로 날아간다. 뒤에 붙인다.
        val kept = readMonth(ym).filterNot { it.dedup.startsWith(SEED_TAG) }
        writeMonth(ym, (kept + txns).sortedByDescending { it.at })
    }

    /** 예시 거래에 붙는 표식. */
    const val SEED_TAG = "seed|"

    /** 예시 거래가 이 달에 들어와 있는지. */
    fun hasTestData(ym: YearMonth = YearMonth.now()): Boolean =
        readMonth(ym).any { it.dedup.startsWith(SEED_TAG) }

    /** 예시 거래만 골라 지운다. 직접 넣은 기록은 건드리지 않는다. */
    fun clearTestData(ym: YearMonth = YearMonth.now()): Int = synchronized(lock) {
        val cur = readMonth(ym)
        val kept = cur.filterNot { it.dedup.startsWith(SEED_TAG) }
        if (kept.size == cur.size) return 0
        writeMonth(ym, kept)
        return cur.size - kept.size
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)
}

