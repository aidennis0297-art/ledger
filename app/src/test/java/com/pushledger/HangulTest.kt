package com.pushledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 조사 떼기. 무턱대고 떼면 멀쩡한 상호가 깎이고, 안 떼면 "한국장학재단에서" 가
 * 그대로 가맹점이 된다. 받침 짝이 그 둘을 가른다.
 */
class HangulTest {

    @Test fun 받침을_센다() {
        assertTrue(Hangul.hasFinal('단'))   // ㄴ
        assertTrue(Hangul.hasFinal('각'))
        assertFalse(Hangul.hasFinal('아'))
        assertFalse(Hangul.hasFinal('사'))
        assertFalse(Hangul.hasFinal('A'))   // 한글이 아니면 받침도 없다
    }

    @Test fun 짝이_맞는_조사만_뗀다() {
        assertEquals("한국장학재단", Hangul.stripParticle("한국장학재단에서"))
        assertEquals("무신사", Hangul.stripParticle("무신사가"))     // 받침 없음 + 가
        assertEquals("이마트", Hangul.stripParticle("이마트는"))     // 받침 없음 + 는
        assertEquals("다이소", Hangul.stripParticle("다이소에"))
        assertEquals("올리브영", Hangul.stripParticle("올리브영은")) // 받침 있음 + 은
        assertEquals("쿠팡", Hangul.stripParticle("쿠팡으로"))       // 받침 있음 + 으로
    }

    @Test fun 짝이_안_맞으면_이름의_일부로_둔다() {
        // "아" 에 받침이 없으니 "이" 는 조사일 수 없다. 이걸 떼면 "코나아" 가 된다.
        assertEquals("코나아이", Hangul.stripParticle("코나아이"))
        // "리" 에 받침이 없으니 "을" 은 조사일 수 없다.
        assertEquals("리을", Hangul.stripParticle("리을"))
        // 두 글자만 남으면 뗀 것이 조사가 아니었을 가능성이 크다.
        assertEquals("가가", Hangul.stripParticle("가가"))
    }

    @Test fun 한글이_아닌_글자_뒤에서는_판정하지_않는다() {
        // 영문·숫자 뒤의 "이/가" 는 받침을 볼 수 없으니 건드리지 않는다.
        assertEquals("GS25가", Hangul.stripParticle("GS25가"))
        // 받침을 가리지 않는 조사는 그래도 뗄 수 없다 — 앞 글자가 한글이 아니다.
        assertEquals("CU에서", Hangul.stripParticle("CU에서"))
    }
}
