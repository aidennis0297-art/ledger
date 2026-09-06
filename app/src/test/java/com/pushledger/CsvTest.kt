package com.pushledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 내보낸 CSV 를 되읽어 가계부를 되살리는 길.
 *
 * 설정 화면은 "앱을 지우면 같이 사라지니 가끔 내보내 두세요" 라고 말한다. 되읽지
 * 못하면 그 말은 거짓말이 된다. 그래서 이 왕복이 맞는지가 이 파일의 전부다.
 *
 * `Store.csvRow` 와 `Store.parseCsv` 는 같은 열을 마주 보고 있다. 한쪽만 고치면
 * 여기서 깨진다 — 실기기에서 복원해 보고 나서야 알게 되는 것보다 낫다.
 */
class CsvTest {

    private fun txn(
        at: String,
        amount: Long = 12_000,
        merchant: String = "스타벅스",
        cat: Cat = Cat.FOOD,
        sub: String = "카페/음료",
        method: String = "카드",
        canceled: Boolean = false,
        memo: String = "",
        by: String = "rule"
    ) = Txn(
        id = "id-$at", amount = amount, merchant = merchant, category = cat.name,
        subCategory = sub, at = at, method = method, canceled = canceled, memo = memo, by = by
    )

    /** 한 줄을 적고 되읽으면 뜻이 남아 있어야 한다. id 와 dedup 만 새로 붙는다. */
    @Test fun 적은_그대로_되읽는다() {
        val src = txn("2026-08-21T14:32:00", memo = "회의 후")
        val back = Store.parseCsv(Store.CSV_HEADER + "\n" + Store.csvRow(src)).single()

        assertEquals(src.amount, back.amount)
        assertEquals(src.merchant, back.merchant)
        assertEquals(src.at, back.at)
        assertEquals(src.cat, back.cat)
        assertEquals(src.subCategory, back.subCategory)
        assertEquals(src.method, back.method)
        assertEquals(src.memo, back.memo)
        assertEquals(src.by, back.by)
        assertEquals(src.canceled, back.canceled)
    }

    /**
     * 쉼표와 따옴표가 든 가맹점. 그냥 split(",") 로 자르면 이 줄부터 열이 밀려
     * 금액 자리에 글자가 들어오고, 그 줄은 통째로 버려진다.
     */
    @Test fun 쉼표와_따옴표가_든_이름도_살아남는다() {
        listOf(
            "이마트24, 강남점",
            "카페 \"온\"",
            "GS25, 역삼\"1\"호점"
        ).forEach { name ->
            val src = txn("2026-08-21T14:32:00", merchant = name, memo = "메모, 하나")
            val back = Store.parseCsv(Store.CSV_HEADER + "\n" + Store.csvRow(src)).single()
            assertEquals(name, back.merchant)
            assertEquals("메모, 하나", back.memo)
        }
    }

    /** 취소한 건은 취소된 채로 돌아와야 한다. 살아나면 그달 지출이 늘어난다. */
    @Test fun 취소는_취소인_채로_돌아온다() {
        val rows = listOf(
            txn("2026-08-21T14:32:00", canceled = true),
            txn("2026-08-22T10:00:00", canceled = false)
        )
        val csv = Store.CSV_HEADER + "\n" + rows.joinToString("\n") { Store.csvRow(it) }
        assertEquals(listOf(true, false), Store.parseCsv(csv).map { it.canceled })
    }

    /** 고정지출·투자 표식이 살아야 집계가 복원 뒤에도 같은 값을 낸다. */
    @Test fun 출처_표식이_살아야_집계가_같다() {
        val rows = listOf(
            txn("2026-08-01T09:00:00", amount = 500_000, merchant = "월세", cat = Cat.HOUSING, by = "fixed"),
            txn("2026-08-02T09:00:00", amount = 10_000, by = "rule")
        )
        val csv = Store.CSV_HEADER + "\n" + rows.joinToString("\n") { Store.csvRow(it) }
        val back = Store.parseCsv(csv)
        assertEquals(listOf("fixed", "rule"), back.map { it.by })
        // 고정지출 실행건은 소비 집계에서 빠진다. 복원 전후로 같은 값이어야 한다.
        assertEquals(Stats.total(rows), Stats.total(back))
    }

    /** 깨진 줄 하나가 나머지를 끌고 가면 안 된다. 되살릴 수 있는 만큼은 되살린다. */
    @Test fun 깨진_줄은_버리고_나머지를_살린다() {
        val csv = Store.CSV_HEADER + "\n" +
            Store.csvRow(txn("2026-08-21T14:32:00")) + "\n" +
            "이건,csv,가,아니다\n" +
            "2026-13-45,99:99:99,\"가게\",1000,\"식비\",\"\",\"\",\"\",\"rule\",\"\"\n" +
            Store.csvRow(txn("2026-08-22T10:00:00"))
        assertEquals(2, Store.parseCsv(csv).size)
    }

    /** 열 이름으로 자리를 찾는다. 순서가 바뀌어도 금액 자리에 가맹점이 들어오지 않는다. */
    @Test fun 열_순서가_바뀌어도_제_자리를_찾는다() {
        val csv = "금액,날짜,가맹점,시각\n12000,2026-08-21,\"스타벅스\",14:32:00"
        val back = Store.parseCsv(csv).single()
        assertEquals(12_000L, back.amount)
        assertEquals("스타벅스", back.merchant)
        assertEquals("2026-08-21T14:32:00", back.at)
    }

    /** 표 계산기가 시각을 분까지만 줄여 저장하는 일이 있다. 그래도 읽혀야 한다. */
    @Test fun 초가_없는_시각도_읽는다() {
        val csv = "날짜,시각,가맹점,금액\n2026-08-21,14:32,\"가게\",1000"
        assertEquals("2026-08-21T14:32:00", Store.parseCsv(csv).single().at)
    }

    /** 이 앱 것이 아닌 파일은 아예 읽지 않는다. 반쯤 읽어 넣으면 가계부가 더러워진다. */
    @Test fun 남의_파일은_읽지_않는다() {
        listOf(
            "이름,나이\n홍길동,30",
            "날짜,시각\n2026-08-21,14:32:00",
            ""
        ).forEach { bad ->
            assertTrue(runCatching { Store.parseCsv(bad) }.isFailure)
        }
    }

    /**
     * 메모에 줄바꿈이 있어도 거래가 사라지면 안 된다.
     *
     * 되읽기는 줄 단위로 자르므로, 필드 안의 줄바꿈을 그대로 내보내면 한 거래가 두
     * 줄로 쪼개져 양쪽 다 파싱에 실패하고 조용히 버려진다. 되살리려던 바로 그
     * 데이터가 백업을 지나며 없어지는 꼴이라 이 검사가 가장 중요하다.
     */
    @Test fun 줄바꿈이_든_메모도_거래를_잃지_않는다() {
        val src = txn("2026-08-21T14:32:00", memo = "회의 후\n영수증 있음\r\n분할 결제")
        val csv = Store.CSV_HEADER + "\n" + Store.csvRow(src)
        // 내보낸 결과가 한 줄이어야 한다. 두 줄이면 그 자리에서 이미 깨진 것이다.
        assertEquals(2, csv.lines().size)
        val back = Store.parseCsv(csv).single()
        assertEquals(src.amount, back.amount)
        assertEquals("회의 후 영수증 있음  분할 결제", back.memo)
    }

    /** 가맹점 이름에 줄바꿈이 섞여 와도 마찬가지다. */
    @Test fun 줄바꿈이_든_가맹점도_거래를_잃지_않는다() {
        val src = txn("2026-08-21T14:32:00", merchant = "카페\n온")
        val csv = Store.CSV_HEADER + "\n" + Store.csvRow(src)
        assertEquals(2, csv.lines().size)
        assertEquals("카페 온", Store.parseCsv(csv).single().merchant)
    }

    /**
     * 아직 안 나간 고정지출 자리표는 자리표인 채로 돌아와야 한다.
     *
     * 자리표가 '실제로 나간 건' 으로 되살아나면 `hasRealFixed` 가 이미 나갔다고 답하고,
     * 나중에 오는 진짜 출금 알림이 고정지출로 안 묶여 소비로 또 세어진다.
     * 월세가 계획으로 한 번, 소비로 한 번 빠져 하루 한도가 그만큼 잘못 줄어든다.
     */
    @Test fun 고정지출_자리표는_자리표인_채로_돌아온다() {
        val plan = Txn(
            id = "p1", amount = 500_000, merchant = "월세", category = Cat.HOUSING.name,
            at = "2026-08-25T09:00:00", method = "계좌",
            by = "fixed", dedup = "fixed|월세|500000|2026-08"
        )
        val real = plan.copy(id = "r1", at = "2026-08-25T09:10:00", dedup = "kb|500000|2026-08-25")
        assertTrue(plan.isFixedPlan)
        assertFalse(real.isFixedPlan)

        val csv = Store.CSV_HEADER + "\n" +
            Store.csvRow(plan) + "\n" + Store.csvRow(real)
        val back = Store.parseCsv(csv)
        assertEquals(listOf(true, false), back.map { it.isFixedPlan })
        // 자리표 열쇠는 원래 꼴로 돌아와야 checkAutoFixed 가 같은 달에 또 안 만든다.
        assertEquals("fixed|월세|500000|2026-08", back[0].dedup)
    }

    /** 예전에 내보낸 파일에는 '예정' 열이 없다. 그래도 읽혀야 한다. */
    @Test fun 예정_열이_없는_예전_파일도_읽는다() {
        val old = "날짜,시각,가맹점,금액,분류,세부분류,결제수단,취소,출처,메모\n" +
            "2026-08-25,09:00:00,\"월세\",500000,\"고정지출\",\"\",\"계좌\",\"\",\"fixed\",\"\""
        val back = Store.parseCsv(old).single()
        assertEquals(500_000L, back.amount)
        assertFalse(back.isFixedPlan)
    }

    /** 같은 파일을 두 번 넣어도 늘지 않게, 줄 내용에서 만든 dedup 이 같아야 한다. */
    @Test fun 같은_줄은_같은_dedup_을_받는다() {
        val row = Store.csvRow(txn("2026-08-21T14:32:00"))
        val a = Store.parseCsv(Store.CSV_HEADER + "\n" + row).single()
        val b = Store.parseCsv(Store.CSV_HEADER + "\n" + row).single()
        assertEquals(a.dedup, b.dedup)
        assertTrue(a.dedup.isNotBlank())
        // id 는 매번 새로 붙는다. 열쇠는 dedup 이지 id 가 아니다.
        assertTrue(a.id != b.id)
    }
}
