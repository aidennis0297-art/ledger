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

        // 1. 등록되지 않은 앱이면 금액처럼 보일 때만 "등록 제안"으로 알림함에 남긴다
        if (pkg !in cfg.allowedPkgs) {
            if (Parser.looksLikeMoney(title, body)) {
                log(Raw.SUGGEST, "결제 앱으로 등록하면 자동으로 가계부에 기록됩니다")
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
                val cat = if (isFixed) Cat.of(matchedFixed?.category) else Parser.guessCat(out.merchant)
                val subCat = if (isFixed) "고정지출" else Parser.guessSubCat(out.merchant, cat)

                // 이름은 저장 전에 다듬는다. 여기서 안 하면 통계·반복 결제·취소 대조가
                // 전부 결제사가 붙인 껍데기를 그대로 안고 간다.
                val merchantName =
                    if (isFixed) (matchedFixed?.name ?: out.merchant)
                    else Merchant.clean(out.merchant).ifBlank { label }

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

            is Parser.Out.Income -> {
                val sender = out.sender.ifBlank { "추가 수입" }
                val added = Store.addTxn(
                    Txn(
                        id = Store.newId(), amount = out.amount,
                        merchant = Merchant.clean(sender),
                        category = Cat.INCOME.name, subCategory = "용돈/보너스",
                        at = stamp, method = out.method,
                        sourcePkg = pkg,
                        by = "rule",
                        dedup = dedup
                    )
                )
                log(
                    if (added) Raw.DONE else Raw.IGNORED,
                    if (added) "규칙: $sender 입금 ${out.amount}원"
                    else "10초 안에 같은 금액이 이미 기록돼 건너뜀"
                )
            }

            is Parser.Out.Settle -> {
                // 되받은 돈이다. 직전 24시간 지출에서 그만큼 뺀다.
                // 짝을 못 찾으면 정산이 아니라 그냥 받은 돈이었을 수 있으니 수입으로 넣는다.
                val hit = Store.applySettlement(out.amount, out.from, at)
                if (hit != null) {
                    log(Raw.DONE, "규칙: ${hit.merchant} 결제에서 정산 ${out.amount}원 뺌")
                } else {
                    val added = Store.addTxn(
                        Txn(
                            id = Store.newId(), amount = out.amount,
                            merchant = Merchant.clean(out.from).ifBlank { "정산금" },
                            category = Cat.INCOME.name, subCategory = "기타수입",
                            at = stamp, sourcePkg = pkg, by = "rule", dedup = dedup
                        )
                    )
                    log(
                        if (added) Raw.DONE else Raw.IGNORED,
                        if (added) "규칙: 짝지을 지출을 못 찾아 수입으로 넣음 (${out.amount}원)"
                        else "10초 안에 같은 금액이 이미 기록돼 건너뜀"
                    )
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
