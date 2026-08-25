package com.pushledger

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 금액 표기. 이 앱에서 금액이 글자가 되는 자리는 전부 [won] 을 지나므로,
 * 여기가 틀리면 화면·상태창·위젯·리포트가 한꺼번에 틀린다.
 */
class MoneyTest {

    @Test fun 만_단위로_끊어_적는다() {
        assertEquals("100만원", won(1_000_000L))
        assertEquals("12만 3456원", won(123_456L))
        assertEquals("3456원", won(3_456L))
        assertEquals("1억 2345만 6789원", won(123_456_789L))
        assertEquals("0원", won(0L))
    }

    @Test fun 딱_떨어지는_자리는_비우지_않는다() {
        // 만 단위가 0 이면 "10만 0원" 이 아니라 "10만원" 이어야 한다.
        assertEquals("10만원", won(100_000L))
        // 억만 있고 만이 없는 경우도 빈 자리를 만들지 않는다.
        assertEquals("1억원", won(100_000_000L))
        assertEquals("1억 5000원", won(100_005_000L))
        assertEquals("1원", won(1L))
    }

    @Test fun 음수는_부호가_앞에_한_번만_붙는다() {
        assertEquals("-12만 3456원", won(-123_456L))
        assertEquals("-5000원", won(-5_000L))
    }

    @Test fun 짧은_꼴은_만_아래를_쉼표없이_적는다() {
        assertEquals("12만", wonShort(123_456L))
        assertEquals("3456", wonShort(3_456L))
        assertEquals("1.2억", wonShort(123_456_789L))
    }
}
