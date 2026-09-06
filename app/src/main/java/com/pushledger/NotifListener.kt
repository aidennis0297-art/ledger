package com.pushledger

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 알림을 받아 거래로 바꾸는 입구. 순서가 곧 정책이다.
 *   1. 안 보기로 한 앱이면 아무 기록도 남기지 않는다.
 *   2. 허용 목록 밖이면 금액이 보일 때만 "이 앱도 켤까요" 제안으로 남긴다.
 *      화이트리스트만 쓰면 목록에 없는 카드사 알림을 통째로 놓치기 때문이다.
 *   3. 허용 목록 안이면 **결과가 무엇이든 알림함에 남긴다.**
 *      잘 읽힌 건 `done`, 금액이 보이는데 못 읽은 건 `pending`, 금액이 없는 건 `noise`.
 *
 * 3번이 예전과 다르다. 전에는 실패한 것만 남겼다. 그래서 결제 알림이 평소와 다른
 * 문구로 와서 통째로 걸러지면 앱 어디에도 흔적이 없었고, 사용자는 왜 안 잡혔는지
 * 알 방법이 없었다. 켜 둔 앱의 알림은 전부 남아야 AI 에게 다시 읽힐 수도 있다.
 */
class NotifListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Store.ensure(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { handle(sbn) }
    }

    private fun handle(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName) return
        // 그룹 묶음 알림은 개별 알림과 내용이 겹쳐서 두 번 세게 만든다.
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val cfg = Store.config.value
        val ex = sbn.notification.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && body.isBlank()) return

        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(sbn.postTime), ZoneId.systemDefault())
        val stamp = at.format(Store.ts)
        val label = appLabel(pkg)
        val dedup = "$pkg|$title|$body|${stamp.substring(0, 16)}"

        // 0. 안 보기로 한 앱은 흔적도 남기지 않는다.
        // 이 줄이 없어서 "이 앱 안 보기" 를 눌러도 그 앱 알림이 계속 제안으로 올라왔다.
        if (pkg in cfg.blockedPkgs) return

        // 같은 알림이 갱신되며 다시 오는 것은 여기서 한 번에 끊는다.
        if (Store.hasRawDedup(dedup)) return

        /**
         * 알림함에 흔적을 남긴다. 켜 둔 앱의 알림은 결과가 무엇이든 전부 여기를 지난다.
         * 못 읽은 건은 자동 모드가 켜져 있으면 그 자리에서 AI 큐에 붙인다.
         */
        fun log(state: String, note: String) {
            val id = Store.newId()
            Store.addRaw(Raw(id, pkg, label, title, body, stamp, state, note, dedup))
            if (state == Raw.PENDING && cfg.autoAi) AiQueue.enqueueAuto(applicationContext, id)
        }

        // 1. 안 켠 앱의 알림도 전부 남긴다.
        //
        // 예전에는 금액이 보이는 것만 남겼다. 그러면 결제 알림이 금액을 특이한 꼴로
        // 적어 오는 앱은 흔적조차 없이 사라져서, 왜 안 잡혔는지 확인할 방법이 없었다.
        // 이제는 차단한 앱 말고는 전부 쌓아 두고 상태로만 갈라 놓는다.
        // 금액이 보이면 "이 앱 켤까요" 제안으로, 아니면 잡담 칸으로 간다.
        if (pkg !in cfg.allowedPkgs) {
            if (Parser.looksLikeMoney(title, body)) {
                log(Raw.SUGGEST, "결제 앱으로 등록하면 자동으로 가계부에 기록됩니다")
            } else {
                log(Raw.NOISE, "안 켠 앱의 알림")
            }
            return
        }

        // 2. 등록된 결제 앱의 알림 파싱
        when (val out = Parser.parse(title, body)) {
            is Parser.Out.Expense -> {
                // 10초 중복(여러 앱이 같은 결제를 동시에 알리는 것)은 Store.addTxn 이 막는다.
                // 여기서 한 번 더 세면 두 곳의 기준이 어긋나 한쪽만 막히는 일이 생긴다.

                // 고정지출(월세 등)과 금액 및 명칭/결제일이 일치하면 fixed 로 기록하여 변동 예산에서 중복 차감 방지
                // 금액이 같고 날짜가 비슷하다는 이유만으로 고정지출로 넘기면, 하필 그 무렵의
                // 같은 금액 결제가 통째로 소비 집계에서 사라진다. 이름이 겹칠 때를 우선하고
                // 날짜는 가맹점 이름을 못 뽑았을 때만 보조로 쓴다.
                val matchedFixed = cfg.fixed.firstOrNull { f ->
                    // 이미 이번 달에 실제로 나간 고정지출이면 두 번째 결제는 그냥 소비다.
                    if (Store.hasRealFixed(f.name, java.time.YearMonth.from(at))) return@firstOrNull false
                    // 이름이 겹치면 금액이 달라도 그 고정지출로 본다. 관리비·공과금은
                    // 달마다 액수가 바뀌는데, 금액까지 같기를 요구하면 그런 달마다
                    // 예정 건과 실제 건이 둘 다 남아 내역이 두 줄이 됐다.
                    val byName = f.name.isNotBlank() && (
                        f.name in out.merchant || out.merchant in f.name ||
                            f.name in title || f.name in body
                        )
                    // 가맹점 이름을 못 뽑았을 때만 날짜로 짚는다. 이쪽은 금액이 같아야 한다.
                    val byDay = out.merchant.isBlank() && f.amount == out.amount &&
                        Math.abs(at.dayOfMonth - f.day) <= 2
                    byName || byDay
                }
                val isFixed = matchedFixed != null

                // 이름은 저장 전에 다듬는다. 여기서 안 하면 통계·반복 결제·취소 대조가
                // 전부 결제사가 붙인 껍데기를 그대로 안고 간다.
                val merchantName =
                    if (isFixed) (matchedFixed?.name ?: out.merchant)
                    else Merchant.clean(out.merchant).ifBlank { label }

                // 사용자가 이 가맹점의 분류를 정해 둔 적이 있으면 그것이 규칙보다 우선한다.
                // 규칙은 이름만 보고 짐작하는 것이라 같은 곳을 매번 같은 식으로 틀린다.
                val remembered = if (isFixed) null else Store.recallCategory(merchantName)
                val cat = when {
                    isFixed -> Cat.of(matchedFixed?.category)
                    remembered != null -> remembered.first
                    else -> Parser.guessCat(out.merchant)
                }
                val subCat = when {
                    isFixed -> "고정지출"
                    remembered != null -> remembered.second
                    else -> Parser.guessSubCat(out.merchant, cat)
                }

                // 예정으로 미리 넣어 둔 같은 고정지출이 있으면 지우고 이 건으로 대체한다.
                if (isFixed && matchedFixed != null) {
                    Store.replaceAutoFixed(matchedFixed.name, at)
                }

                val added = Store.addTxn(
                    Txn(
                        id = Store.newId(), amount = out.amount,
                        merchant = merchantName,
                        category = cat.name, subCategory = subCat,
                        at = stamp, method = out.method,
                        sourcePkg = pkg,
                        by = if (isFixed) "fixed" else if (cat == Cat.FINANCE && subCat == "투자/저축") "invest" else "rule",
                        dedup = dedup
                    )
                )
                // 잘 읽힌 것도 알림함에 남긴다. 성공한 건이 하나도 안 보이면 알림함은
                // 고장난 것만 모인 곳이 되고, 무엇이 들어와서 어떻게 읽혔는지 확인할 데가 없다.
                log(
                    if (added) Raw.DONE else Raw.IGNORED,
                    if (added) "규칙: $merchantName ${out.amount}원"
                    else "10초 안에 같은 금액이 이미 기록돼 건너뜀"
                )
            }

            // 입금은 가계부에 넣지 않는다. 사용자가 명시적으로 요구한 규칙이다.
            //
            // 하루 가용 예산이 `월예산 + 수입 - 고정지출 - 투자` 라서 입금 한 건이 그대로
            // 예산에 더해진다. 실기기 기록의 `1,500,000원 입금 / 정은실 → 내 토스뱅크 통장`
            // 하나가 그 달 남은 나흘 기준 하루 한도를 37만원씩 밀어 올렸고, 사용자는
            // 그 거래를 손으로 지웠다. 용돈·정산금·계좌 이체가 전부 같은 "입금" 문구로
            // 오기 때문에 문구로는 가를 수 없고, 임계값을 두면 그 숫자가 새 오차가 된다.
            //
            // 버리지는 않는다. 알림함 '무시됨' 칸에 사유와 함께 남고, 그 줄에 '직접 입력'
            // 버튼이 있다. 처음에는 그 버튼이 없어서 무시 취소 → 미처리 → 직접 입력
            // 세 걸음을 밟아야 했고, 'AI로 읽기' 는 AI 가 다시 수입이라고 답해 곧장
            // 무시로 돌아오는 죽은 버튼이었다.
            is Parser.Out.Income -> {
                val sender = out.sender.ifBlank { "입금" }
                log(Raw.IGNORED, "수입은 기록하지 않습니다 — $sender ${out.amount}원 · 넣으려면 직접 입력")
            }

            is Parser.Out.Settle -> {
                // 되받은 돈이다. 직전 24시간 지출에서 그만큼 뺀다.
                //
                // 짝을 못 찾으면 예전에는 수입으로 넣었다. 그러다 카카오톡 정산 **요청**
                // 38,000원이 수입으로 들어간 적이 있다. 수입을 안 만들기로 했으므로
                // 이제는 알림함에 미처리로 남겨 사용자가 고르게 한다 — 짝을 못 찾은 건
                // 규칙이 뜻을 모른다는 뜻이고, 모를 때 임의로 정하면 조용히 틀린다.
                val hit = Store.applySettlement(out.amount, out.from, at)
                if (hit != null) {
                    log(Raw.DONE, "규칙: ${hit.merchant} 결제에서 정산 ${out.amount}원 뺌")
                } else {
                    log(Raw.PENDING, "짝지을 지출을 못 찾았습니다 (${out.amount}원)")
                }
            }

            is Parser.Out.Cancel -> {
                // 취소는 수입이 아니다. 원 거래를 찾아 무효화하고, 못 찾으면 사용자에게 고르게 한다.
                val hit = Store.applyCancel(out.amount, out.merchant, at)
                if (hit != null) log(Raw.DONE, "규칙: ${hit.merchant} ${hit.amount}원 결제 취소 처리")
                else log(Raw.PENDING, "취소 알림인데 원 거래를 못 찾았습니다")
            }

            is Parser.Out.None -> {
                // 금액이 보이는데 못 읽은 것만 미처리로 올린다. 나머지는 잡담 칸에 쌓아 두고
                // 필요할 때 찾아볼 수 있게 남긴다 — 여기 없으면 왜 안 잡혔는지 알 길이 없다.
                // ponytail: 켠 앱의 잡담까지 보관 기간(기본 30일) 내내 쌓인다. 카카오톡처럼
                // 결제와 대화가 같은 앱으로 오는 경우 하루 수백 건이 될 수 있다.
                // 눈에 띄게 무거워지면 설정의 보관 기간을 줄이거나, 여기서 잡담만 더 짧게 둔다.
                if (Parser.looksLikeMoney(title, body)) log(Raw.PENDING, out.reason)
                else log(Raw.NOISE, out.reason)
            }
        }
    }


    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
