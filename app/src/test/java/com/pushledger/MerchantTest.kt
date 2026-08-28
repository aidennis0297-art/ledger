package com.pushledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 가맹점 이름 정규화. 여기가 헐거우면 통계 순위가 같은 가게를 여러 줄로 쪼개고,
 * 반복 결제 감지가 달마다 꼬리표가 바뀌는 청구서를 통째로 놓친다.
 */
class MerchantTest {

    @Test fun 결제사가_붙인_껍데기를_벗긴다() {
        assertEquals("스타벅스 강남점", Merchant.clean("[체크]스타벅스 강남점"))
        assertEquals("이마트", Merchant.clean("(주)이마트"))
        assertEquals("이마트", Merchant.clean("㈜이마트"))
        assertEquals("올리브영", Merchant.clean("주식회사 씨제이올리브영"))
        assertEquals("파리바게뜨", Merchant.clean("파리바게뜨_법인"))
        // 여러 겹으로 붙는 일이 흔하다.
        assertEquals("김밥천국", Merchant.clean("[신용](주)김밥천국"))
    }

    @Test fun 간편결제_접두를_벗긴다() {
        assertEquals("스타벅스", Merchant.clean("토스페이_스타벅스"))
        assertEquals("교촌치킨", Merchant.clean("카카오페이 교촌치킨"))
        assertEquals("무신사", Merchant.clean("KG이니시스_무신사"))
    }

    @Test fun 청구서_꼬리표를_떼서_매달_같은_이름이_되게_한다() {
        // 이게 안 되면 8월 관리비와 9월 관리비가 서로 다른 가게가 된다.
        assertEquals(Merchant.key("아파트관리비 8월분"), Merchant.key("아파트관리비 9월분"))
        assertEquals(Merchant.key("SKT 통신요금 2026.08"), Merchant.key("SKT 통신요금 2026.09"))
        assertEquals(Merchant.key("삼성화재 3회차"), Merchant.key("삼성화재 4회차"))
        assertEquals(Merchant.key("현대카드 1/12회"), Merchant.key("현대카드 2/12회"))
    }

    @Test fun 법인명과_별칭을_사람이_부르는_이름으로_바꾼다() {
        assertEquals("배달의민족", Merchant.clean("(주)우아한형제들"))
        assertEquals("스타벅스", Merchant.clean("에스씨케이컴퍼니"))
        assertEquals("GS25", Merchant.clean("지에스리테일 역삼점"))
        // 긴 것을 먼저 봐야 배달이 쇼핑으로 안 간다.
        assertEquals("쿠팡이츠", Merchant.clean("쿠팡이츠서비스"))
    }

    @Test fun 지점이_달라도_같은_가게로_본다() {
        assertEquals(Merchant.key("스타벅스 강남점"), Merchant.key("스타벅스강남2호점"))
        assertTrue(Merchant.same("스타벅스 역삼점", "[체크]스타벅스역삼2호점"))
        assertFalse(Merchant.same("스타벅스", "투썸플레이스"))
        // 빈 이름은 아무것과도 같지 않다. 같다고 하면 취소 대조가 아무 거래나 집는다.
        assertFalse(Merchant.same("", "스타벅스"))
    }

    @Test fun 짧은_이름으로_품기_판정을_하지_않는다() {
        // 고정지출 이름이 "KT" 면 "KTX 예매" 가 통신비로 잡혀 소비 집계에서 사라진다.
        assertFalse(Merchant.same("KT", "KTX 예매"))
        // 글자가 똑같으면 짧아도 같은 것이다.
        assertTrue(Merchant.same("KT", "kt"))
        // 세 글자부터는 품기를 본다.
        assertTrue(Merchant.same("스타벅스", "스타벅스 강남점"))
    }

    @Test fun 다_벗기고_나면_아무것도_안_남는_경우엔_원래_이름을_지킨다() {
        // 이름이 통째로 사라지면 내역에 빈 줄이 남는다.
        assertEquals("(주)", Merchant.clean("(주)"))
    }
}
