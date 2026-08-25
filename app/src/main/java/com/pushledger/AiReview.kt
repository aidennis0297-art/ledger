package com.pushledger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * 이미 기록된 거래를 AI 로 다시 훑는 하네스.
 *
 * 알림을 읽는 쪽([AiWorker])은 "못 읽은 것" 을 다룬다. 이쪽은 반대로 **읽혔다고 여긴 것**을
 * 다룬다. 규칙이 만든 기록은 조용히 틀린다 — 가맹점 자리에 카드사 이름이 앉거나,
 * 편의점 결제가 생활비로 가거나, 광고 알림이 지출로 들어온다. 틀린 줄은 아무 신호도
 * 내지 않아서 사용자가 우연히 내역을 훑다 발견하기 전까지 그대로 남는다.
 *
 * 고친 결과를 바로 덮어쓰지 않는다. [Fix] 제안으로 쌓아 두고 사용자가 적용한다.
 * AI 도 틀리는데 그 고침이 소리 없이 적용되면, 틀린 기록을 다른 틀린 기록으로 바꾸는 셈이다.
 */
object ReviewRun {

    private const val WORK = "ai_review"

    /** 한 번에 묶어 보낼 건수. 열다섯이면 응답이 상한에 안 걸리면서 호출 수도 줄어든다. */
    const val BATCH = 15

    /** 이 달의 자동 기록을 검토한다. 시작하지 못하면 이유를 남기고 false. */
    fun start(ctx: Context, ym: YearMonth): Boolean {
        if (AiJob.running.value && AiJob.label.value == LABEL) return false
        if (Nvidia.apiKey(ctx).isBlank()) {
            Store.setLastAiRun("AI 키가 없어 검토를 시작하지 못했습니다. 설정에서 키를 넣어 주세요.")
            return false
        }
        if (targets(ym).isEmpty()) {
            Store.setLastAiRun("검토할 자동 기록이 없습니다.")
            return false
        }
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReviewWorker>()
                .setInputData(Data.Builder().putString("ym", ym.toString()).build())
                .build()
        )
        return true
    }

    const val LABEL = "기록 검토"

    /**
     * 검토 대상: 취소되지 않은 자동 기록.
     *
     * 손으로 넣은 건과 고정지출은 뺀다. 사용자가 직접 정한 것을 AI 가 다시 고치라고
     * 권하면, 고쳐 놓을 때마다 같은 제안이 또 올라온다.
     */
    fun targets(ym: YearMonth): List<Txn> =
        Store.readMonth(ym).filter { !it.canceled && (it.by == "rule" || it.by == "ai") }
}

class ReviewWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Store.init(applicationContext)
        val ym = runCatching { YearMonth.parse(inputData.getString("ym")) }
            .getOrDefault(YearMonth.now())
        val all = ReviewRun.targets(ym)
        val batches = all.chunked(ReviewRun.BATCH)

        AiJob.start(ReviewRun.LABEL, batches.size, "${all.size}건을 훑는 중")

        val key = Nvidia.apiKey(applicationContext)
        val hints = Store.catHints()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        var found = 0
        var failed = 0

        batches.forEachIndexed { bi, batch ->
            AiJob.say("${bi * ReviewRun.BATCH + 1}~${bi * ReviewRun.BATCH + batch.size}번째 기록을 보는 중")
            var r = Nvidia.review(key, batch, hints)
            if (r.isFailure) r = Nvidia.review(key, batch, hints)

            r.onSuccess { list ->
                val fixes = list.mapNotNull { s ->
                    val before = batch.getOrNull(s.i - 1) ?: return@mapNotNull null
                    val after = if (s.drop) before else before.copy(
                        merchant = s.merchant.ifBlank { before.merchant },
                        category = (if (s.category.isBlank()) before.cat else Cat.of(s.category)).name,
                        subCategory = s.subCategory.ifBlank { before.subCategory }
                    )
                    // 바뀐 게 없으면 제안이 아니다. 목록에 넣으면 눌러도 아무 일이 안 일어난다.
                    if (!s.drop && after == before) return@mapNotNull null
                    Fix(
                        id = Store.newId(), before = before, after = after,
                        reason = s.reason, drop = s.drop, createdAt = stamp
                    )
                }
                Store.addFixes(fixes)
                found += fixes.size
            }.onFailure { failed += 1 }

            AiJob.step()
        }

        AiJob.finish("검토 완료")
        Store.setLastAiRun(
            when {
                failed > 0 && found > 0 -> "기록 검토: 고칠 곳 ${found}건을 찾았습니다 (묶음 ${failed}개는 실패)"
                failed > 0 -> "기록 검토에 실패했습니다. 잠시 뒤 다시 시도해 주세요."
                found > 0 -> "기록 검토: 고칠 곳 ${found}건을 찾았습니다. 내역 탭에서 확인하세요."
                else -> "기록 검토: ${all.size}건을 봤고 고칠 곳이 없었습니다."
            }
        )
        Result.success()
    }
}
