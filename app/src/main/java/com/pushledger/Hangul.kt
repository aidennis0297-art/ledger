package com.pushledger

/**
 * 한국어 조사를 떼는 규칙.
 *
 * "한국장학재단에서" 가 그대로 가맹점 이름이 되어 있었다. 그렇다고 끝에 붙은 조사를
 * 무턱대고 지우면 멀쩡한 이름이 깎인다 — "코나아이" 에서 "이" 를 떼면 "코나아" 가 되고,
 * "무신사" 에서 "사" 를... 는 조사가 아니니 그렇다 치더라도, "이/가/은/는/을/를" 로
 * 끝나는 상호는 얼마든지 있다.
 *
 * 사전 없이 이걸 가르는 방법이 한국어 문법 안에 있다. **조사는 앞 글자의 받침 유무로
 * 짝이 갈린다.**
 *
 *   받침 있음 → 은, 이, 을, 과, 으로, 아, 이랑
 *   받침 없음 → 는, 가, 를, 와, 로,   야, 랑
 *
 * 그래서 "코나아이" 의 "이" 를 조사로 보려면 앞 글자 "아" 에 받침이 있어야 하는데 없다.
 * 짝이 안 맞으니 그 "이" 는 조사가 아니라 이름의 일부다. 반대로 "무신사가" 의 "가" 는
 * 앞 글자 "사" 에 받침이 없으니 짝이 맞고, 떼어도 된다.
 *
 * 한글 음절은 (코드 - 0xAC00) 을 28로 나눈 나머지가 종성 번호라, 그 값이 0이면 받침이 없다.
 * 형태소 분석기를 붙이면 더 정확하겠지만 사전만 수십 MB다. 가맹점 이름 한 토막을
 * 다듬는 데 그만한 것을 들일 이유가 없다.
 */
object Hangul {

    private const val BASE = 0xAC00
    private const val LAST = 0xD7A3

    /** 한글 음절인지. 자모(ㄱ, ㅏ)와 한자·영문은 아니다. */
    fun isSyllable(c: Char): Boolean = c.code in BASE..LAST

    /** 받침이 있는지. 종성 번호가 0이면 없다. */
    fun hasFinal(c: Char): Boolean = isSyllable(c) && (c.code - BASE) % 28 != 0

    /**
     * 조사 목록. [needsFinal] 이 true 면 받침 있는 글자 뒤에, false 면 없는 글자 뒤에 붙는다.
     * null 이면 받침을 가리지 않는다.
     *
     * 긴 것을 앞에 둔다. "에서" 를 "에" 보다 먼저 봐야 "에서" 가 통째로 떨어진다.
     */
    private val PARTICLES: List<Pair<String, Boolean?>> = listOf(
        "에서는" to null, "에서도" to null, "에게서" to null,
        "으로는" to true, "로는" to false,
        "에서" to null, "에게" to null, "께서" to null, "부터" to null, "까지" to null,
        "으로" to true, "로" to false,
        "이랑" to true, "랑" to false,
        "과" to true, "와" to false,
        "은" to true, "는" to false,
        "이" to true, "가" to false,
        "을" to true, "를" to false,
        "도" to null, "만" to null, "의" to null, "에" to null
    )

    /** 조사를 떼고 남는 이름의 최소 길이. 이보다 짧아지면 뗀 것이 조사가 아니었다는 뜻이다. */
    private const val MIN_STEM = 2

    /**
     * 토큰 끝의 조사를 뗀다. 뗄 수 없으면 원래 것을 그대로 돌려준다.
     *
     * 판정은 세 가지를 다 넘겨야 한다.
     *  1. 조사를 떼고도 [MIN_STEM] 글자 넘게 남을 것
     *  2. 남는 부분의 마지막 글자가 한글일 것 — 영문·숫자 뒤의 "이/가" 는 판정할 근거가 없다
     *  3. 그 글자의 받침 유무가 조사의 짝과 맞을 것
     */
    fun stripParticle(token: String): String {
        for ((p, needsFinal) in PARTICLES) {
            if (!token.endsWith(p) || token.length - p.length < MIN_STEM) continue
            val stem = token.dropLast(p.length)
            val tail = stem.last()
            if (!isSyllable(tail)) continue
            // 받침을 가리지 않는 조사는 짝을 볼 것도 없다.
            if (needsFinal == null || hasFinal(tail) == needsFinal) return stem
        }
        return token
    }
}
