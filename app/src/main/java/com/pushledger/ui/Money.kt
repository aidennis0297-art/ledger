package com.pushledger.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * 금액 칸에 세 자리마다 쉼표를 넣어 보여 준다.
 *
 * 저장되는 값은 숫자 그대로다. 바뀌는 건 화면에 그려지는 글자뿐이라,
 * 파싱이나 계산 쪽은 이 변환을 몰라도 된다.
 *
 * 커서 위치를 손으로 매핑하는 이유는, 쉼표가 끼어들면 글자 수가 달라져서
 * 그냥 두면 커서가 엉뚱한 자리로 튀기 때문이다. 원본 글자마다 화면에서의
 * 자리를 미리 적어 두고 그 표를 양방향으로 쓴다.
 */
object ThousandsTransformation : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        if (digits.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        val out = StringBuilder()
        // map[i] = 원본 i 번째 글자가 화면에서 시작하는 자리
        val map = IntArray(digits.length + 1)

        for (i in digits.indices) {
            map[i] = out.length
            out.append(digits[i])
            val remaining = digits.length - 1 - i
            if (remaining > 0 && remaining % 3 == 0) out.append(',')
        }
        map[digits.length] = out.length

        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                map[offset.coerceIn(0, digits.length)]

            override fun transformedToOriginal(offset: Int): Int {
                val o = offset.coerceIn(0, out.length)
                // 화면 자리가 o 이하인 원본 글자 중 가장 뒤엣것
                var lo = 0
                for (i in map.indices) {
                    if (map[i] <= o) lo = i else break
                }
                return lo
            }
        }

        return TransformedText(AnnotatedString(out.toString()), mapping)
    }
}
