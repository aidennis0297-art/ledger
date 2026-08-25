package com.pushledger

/**
 * 금액을 읽는 방식대로 적는다. 이 앱에서 금액이 글자가 되는 자리는 전부 여기를 지난다.
 *
 *   1000000 → 100만원        123456 → 12만 3456원
 *   3456    → 3456원         123456789 → 1억 2345만 6789원
 *
 * 예전에는 `%,d원` 으로 세 자리마다 쉼표를 찍었다. 1,000,000 은 자릿수를 세어야
 * 백만인 줄 알 수 있는데, 사람은 만 단위로 끊어 읽는다. "백이십삼만..." 하고 읽다가
 * 숫자를 다시 보는 일이 없게, 읽는 단위 그대로 끊어 적는다.
 *
 * 만 아래는 쉼표 없이 붙인다. 3456 에 쉼표를 넣으면 "12만 3,456원" 이 되어
 * 끊는 자리가 두 종류가 되고, 그러면 만 단위 끊기가 눈에 안 들어온다.
 */
fun won(v: Long): String {
    if (v == 0L) return "0원"
    val sign = if (v < 0) "-" else ""
    var rest = kotlin.math.abs(v)
    val sb = StringBuilder(sign)

    val eok = rest / 100_000_000L
    if (eok > 0) { sb.append(eok).append("억"); rest %= 100_000_000L }

    val man = rest / 10_000L
    if (man > 0) {
        if (sb.length > sign.length) sb.append(' ')
        sb.append(man).append("만")
        rest %= 10_000L
    }

    if (rest > 0) {
        if (sb.length > sign.length) sb.append(' ')
        sb.append(rest)
    }
    return sb.append("원").toString()
}
/** 자리가 좁을 때 쓰는 짧은 꼴. 만 아래는 [won] 과 같이 쉼표 없이 붙인다. */
fun wonShort(v: Long): String = when {
    v >= 100_000_000 -> "%.1f억".format(v / 100_000_000.0)
    v >= 10_000 -> "${v / 10_000}만"
    else -> v.toString()
}
