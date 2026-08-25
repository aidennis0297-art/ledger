package com.pushledger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * 화면이 구독하는 분석 진행 상황.
 *
 * 여기 있는 값은 앱 전체가 같이 본다. 홈이든 통계든 어느 탭에 있어도 위쪽 띠에
 * 진행 상황이 뜨고, 알림함을 닫아도 큐는 그대로 돈다. 진행을 화면 안에 두면
 * 그 화면을 떠나는 순간 사용자는 지금 도는 중인지 끝난 건지 알 방법이 없어진다.
 *
 * 끝난 결과는 여기 두지 않고 [Config.lastAiRun] 에 적는다. 이 객체는 앱이 내려가면
 * 같이 사라지는데, 결과는 앱을 껐다 켜도 남아 있어야 하기 때문이다.
 */
object AiQueue {

    /** 이 큐가 화면 위 띠에 뜰 때 쓰는 이름. 진행 상황은 [AiJob] 이 들고 있다. */
    const val LABEL = "알림 분석"

    /**
     * 큐를 건다. 시작하지 못하면 이유를 [Config.lastAiRun] 에 적고 false 를 돌려준다.
     * 키가 없는 채로 스무 건을 넣으면 스무 건이 전부 실패로 물드는데,
     * 그러고 나서 이유를 알려 주면 이미 늦다.
     */
    fun enqueue(ctx: Context, ids: List<String>): Boolean {
        if (ids.isEmpty()) return false
        if (Nvidia.apiKey(ctx).isBlank()) {
            Store.setLastAiRun("AI 키가 없어 시작하지 못했습니다. 설정에서 NVIDIA API 키를 넣어 주세요.")
            return false
        }
        AiJob.start(LABEL, ids.size, "분석을 시작했습니다")
        Store.setLastAiRun("")
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request(ids)
        )
        return true
    }

    /**
     * 자동 모드에서 알림 한 건을 큐 뒤에 붙인다.
     *
     * 알림이 들어오는 자리(NotifListener)에서 부르므로 한 건씩 온다. 같은 이름의
     * 작업 뒤에 이어 붙여서 한 번에 하나씩만 돌게 한다 — 알림 열 개가 몰아쳐 들어올 때
     * 열 개를 동시에 부르면 API 쪽에서 막히고, 진행 표시도 뒤엉킨다.
     */
    fun enqueueAuto(ctx: Context, id: String) {
        if (Nvidia.apiKey(ctx).isBlank()) return
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request(listOf(id), auto = true)
        )
    }

    private fun request(ids: List<String>, auto: Boolean = false) =
        OneTimeWorkRequestBuilder<AiWorker>()
            .setInputData(
                Data.Builder()
                    .putStringArray("ids", ids.toTypedArray())
                    .putBoolean("auto", auto)
                    .build()
            )
            .build()

    private const val WORK = "ai_queue"
}

/**
 * 규칙이 못 읽은 알림을 AI 에 하나씩 넘긴다. 앱을 나가도 큐는 계속 돈다.
 *
 * 한 건씩 부르고 한 건씩 상태를 남긴다. 여러 건을 한 번에 묶어 보내면 호출 수는
 * 줄지만, 하나가 어긋나면 묶음 전체가 같이 죽고 어디서 틀렸는지도 알 수 없다.
 */
class AiWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // 앱이 내려간 뒤 큐가 되살아날 수 있다. 저장소가 비어 있는 채로 돌지 않게 먼저 연다.
        Store.init(applicationContext)
        val ids = inputData.getStringArray("ids").orEmpty()
        val auto = inputData.getBoolean("auto", false)

        // 프로세스가 죽었다 살아나면 진행 상태가 0/0 인 채로 시작한다.
        // 여기서 다시 세워 두지 않으면 일은 도는데 화면에는 아무것도 안 보인다.
        AiJob.start(AiQueue.LABEL, ids.size, "분석을 이어서 하는 중")

        val key = Nvidia.apiKey(applicationContext)
        // 한 번만 만들어 큐 전체가 같은 기준으로 분류되게 한다.
        val hints = Store.catHints()
        var ok = 0
        var fail = 0

        for (id in ids) {
            val raw = Store.inbox.value.firstOrNull { it.id == id }
            if (raw == null) { AiJob.step(); continue }

            AiJob.say("${raw.appLabel} 알림을 읽는 중")
            // 한 번은 다시 걸어 본다. 이동 중 끊긴 한 건 때문에 실패로 남기기에는 아깝다.
            var r = Nvidia.analyze(key, raw.appLabel, raw.title, raw.text, hints)
            if (r.isFailure) r = Nvidia.analyze(key, raw.appLabel, raw.title, raw.text, hints)

            r.onSuccess { res ->
                when {
                    !res.isExpense -> {
                        Store.setRawState(id, Raw.IGNORED, "AI: 지출 아님 (${res.reason})")
                        ok++
                    }
                    res.isCancel -> {
                        // 취소는 새 거래가 아니라 원 거래의 무효화다.
                        val at = LocalDateTime.parse(raw.postedAt, Store.ts)
                        val hit = Store.applyCancel(res.amount, res.merchant, at)
                        if (hit != null) {
                            Store.setRawState(id, Raw.DONE, "AI: ${res.merchant} 결제 취소 처리")
                            ok++
                        } else {
                            Store.setRawState(id, Raw.FAILED, "AI: 취소건인데 원 거래를 못 찾음")
                            fail++
                        }
                    }
                    res.amount <= 0L -> {
                        // 금액이 0 인 채로 넣으면 내역에 0원짜리 유령 줄이 남는다.
                        Store.setRawState(id, Raw.FAILED, "AI: 금액을 못 읽음")
                        fail++
                    }
                    else -> {
                        val added = Store.addTxn(
                            Txn(
                                id = Store.newId(), amount = res.amount,
                                merchant = res.merchant.ifBlank { raw.appLabel },
                                category = res.category.name,
                                subCategory = res.subCategory,
                                at = raw.postedAt,
                                method = res.method, sourcePkg = raw.pkg,
                                by = if (res.category == Cat.FINANCE && res.subCategory == "투자/저축") "invest" else "ai",
                                dedup = raw.dedup
                            )
                        )
                        // 안 들어간 이유는 하나뿐이다 — 같은 결제가 이미 기록돼 있다.
                        // 그걸 실패로 적으면 사용자는 뭔가 잘못된 줄 알고 다시 누른다.
                        if (added) {
                            Store.setRawState(id, Raw.DONE, "AI: ${res.merchant} ${res.amount}원")
                        } else {
                            Store.setRawState(id, Raw.IGNORED, "AI: 이미 기록된 결제 (${res.amount}원)")
                        }
                        ok++
                    }
                }
            }.onFailure {
                Store.setRawState(id, Raw.FAILED, "AI 실패: ${it.message?.take(80)}")
                fail++
            }
            AiJob.step()
        }

        val summary = "AI 분석 완료 · ${ok}건 처리, ${fail}건 실패"
        AiJob.finish(summary)

        // 자동 모드는 알림 한 건마다 이 자리를 지난다. 매번 요약을 띄우고 시스템 알림까지
        // 울리면 결제할 때마다 알림이 두 번 오는 셈이 된다. 잘 됐으면 조용히 지나간다.
        if (auto) {
            if (fail > 0) Store.setLastAiRun("자동 분석에서 ${fail}건을 못 읽었습니다. 알림함 실패 칸에서 다시 시도할 수 있어요")
            return@withContext Result.success()
        }

        // 진행 띠는 끝나면 사라진다. 결과는 여기 남아 사용자가 볼 때까지 기다린다.
        Store.setLastAiRun(
            if (fail > 0) "$summary · 실패한 건은 알림함의 실패 칸에서 다시 시도할 수 있어요"
            else summary
        )
        notify(ok, fail)
        Result.success()
    }

    private fun notify(ok: Int, fail: Int) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "AI 분석 결과", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.notify(
            1001,
            NotificationCompat.Builder(applicationContext, CH)
                .setSmallIcon(R.drawable.ic_stat_budget)
                .setContentTitle("알림 분석 완료")
                .setContentText("${ok}건 기록, ${fail}건 실패")
                .setAutoCancel(true)
                .build()
        )
    }

    private companion object { const val CH = "ai_result" }
}
