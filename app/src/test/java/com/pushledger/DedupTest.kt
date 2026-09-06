package com.pushledger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 같은 결제가 두 줄로 들어오는 것을 막는 규칙.
 *
 * 아래 시간 간격은 지어낸 것이 아니라 **사용자 실기기 알림 기록에서 잰 값**이다.
 * 삼성페이와 토스가 같은 결제를 각자 알리는데, 18건 중 15건은 2초 안에 붙어 왔고
 * 셋은 58초·59초·85초 벌어졌다. 10초 창만 보던 때는 그 셋이 두 줄이 된다.
 */
class DedupTest {

    private fun txn(at: String, amount: Long, merchant: String, by: String = "rule") =
        Txn(id = at + merchant, amount = amount, merchant = merchant, at = at, by = by)

    /** 두 앱이 가맹점을 다르게 적어도 10초 안이면 같은 결제로 본다. 불변식 2 그대로. */
    @Test fun 십초_안이면_가맹점이_달라도_뭉친다() {
        val cur = listOf(txn("2026-09-06T12:50:28", 7_100, "씨유 휘경행복점"))
        val other = txn("2026-09-06T12:50:33", 7_100, "CU휘경행복")
        assertTrue(Store.isDuplicate(cur, other))
    }

    /**
     * 10초를 넘겨도 가맹점이 같으면 같은 결제다.
     * 실측 간격 58초·59초·85초가 전부 여기 걸려야 한다.
     */
    @Test fun 십초를_넘겨도_가맹점이_같으면_뭉친다() {
        val cur = listOf(txn("2026-09-05T01:58:02", 5_000, "씨유 휘경행복점"))
        listOf(58L, 59L, 85L, 179L).forEach { sec ->
            val later = txn(
                java.time.LocalDateTime.parse("2026-09-05T01:58:02", Store.ts)
                    .plusSeconds(sec).format(Store.ts),
                5_000, "씨유 휘경행복점"
            )
            assertTrue("$sec 초", Store.isDuplicate(cur, later))
        }
    }

    /**
     * 같은 금액을 잇달아 쓴 진짜 결제는 뭉치면 안 된다.
     * 이 사용자 기록에 PC방 1,000원이 여러 번 있다 — 연장 결제가 붙어 올 수 있다.
     * 가맹점이 같으면 3분까지는 한 건으로 보지만, 그 밖은 따로 센다.
     */
    @Test fun 넓은_창_밖의_같은_금액은_따로_센다() {
        val cur = listOf(txn("2026-09-03T15:12:22", 1_000, "PC방"))
        val later = txn("2026-09-03T15:16:22", 1_000, "PC방")   // 240초
        assertFalse(Store.isDuplicate(cur, later))
    }

    /** 넓은 창 안이라도 가맹점이 다르면 다른 결제다. 편의점 1,000원과 PC방 1,000원. */
    @Test fun 넓은_창_안이라도_가맹점이_다르면_안_뭉친다() {
        val cur = listOf(txn("2026-09-03T15:12:22", 1_000, "씨유 휘경행복점"))
        val later = txn("2026-09-03T15:13:22", 1_000, "PC방")   // 60초
        assertFalse(Store.isDuplicate(cur, later))
    }

    /** 금액이 다르면 아무리 붙어 있어도 다른 결제다. */
    @Test fun 금액이_다르면_안_뭉친다() {
        val cur = listOf(txn("2026-09-06T12:50:28", 7_100, "씨유 휘경행복점"))
        assertFalse(Store.isDuplicate(cur, txn("2026-09-06T12:50:29", 7_200, "씨유 휘경행복점")))
    }

    /** 손으로 넣은 건과 고정지출은 사용자가 뜻을 갖고 넣은 것이라 시간 규칙에서 뺀다. */
    @Test fun 손으로_넣은_건은_시간_규칙을_안_탄다() {
        val cur = listOf(txn("2026-09-06T12:50:28", 7_100, "씨유 휘경행복점"))
        assertFalse(
            Store.isDuplicate(cur, txn("2026-09-06T12:50:29", 7_100, "씨유 휘경행복점", by = "manual"))
        )
    }

    /** 취소된 건은 없는 셈 친다. 취소한 결제와 같은 금액을 다시 써도 들어와야 한다. */
    @Test fun 취소된_건은_중복_판정에서_뺀다() {
        val cur = listOf(
            txn("2026-09-06T12:50:28", 7_100, "씨유 휘경행복점").copy(canceled = true)
        )
        assertTrue(Store.isDuplicate(cur, txn("2026-09-06T12:50:29", 7_100, "씨유 휘경행복점")).not())
    }

    /**
     * 고정지출 자리표와 손으로 넣은 같은 건이 나란히 남으면 안 된다.
     *
     * 사용자 실기기 가계부에 월세 580,000원이 두 줄로 있었다 — 자동 자리표 한 줄과
     * 손으로 넣은 한 줄. 자리표는 소비 집계에서 빠지지만 손으로 넣은 줄은 안 빠져서,
     * 같은 월세가 고정지출 계획으로 한 번 소비로 한 번 빠졌다.
     *
     * `Store.addTxn` 이 파일을 쓰므로 여기서는 판정에 쓰는 값만 확인한다.
     */
    @Test fun 자리표와_실제건은_구분된다() {
        val plan = Txn(
            id = "p", amount = 580_000, merchant = "월세", at = "2026-08-19T09:00:00",
            by = "fixed", dedup = "fixed|월세|580000|2026-08"
        )
        val byHand = Txn(
            id = "m", amount = 580_000, merchant = "월세", at = "2026-08-19T00:27:00",
            by = "manual"
        )
        assertTrue(plan.isFixedPlan)
        assertFalse(byHand.isFixedPlan)
        // 자리표는 소비에서 빠지고, 손으로 넣은 줄은 그대로 소비로 잡힌다.
        // 둘이 같이 남으면 이 값이 58만원이 되어 같은 돈이 두 번 세어진다.
        assertTrue(Stats.total(listOf(plan)) == 0L)
        assertTrue(Stats.total(listOf(plan, byHand)) == 580_000L)
        // 이름이 같으므로 addTxn 이 자리표를 알아본다.
        assertTrue(Merchant.same(plan.merchant, byHand.merchant))
    }
}
