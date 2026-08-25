package com.pushledger.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * 아무 글자나 도트 격자로 찍는다. 한글이 여기 걸린다.
 *
 * 한글 낱자를 손으로 그리지 않는다. 초성 19 · 중성 21 · 종성 28 을 다 그리면 68벌인데,
 * 그려 놓고도 받침이 있고 없고에 따라 초성과 중성의 자리와 크기를 다시 손으로 맞춰야 한다.
 * 옛날 비트맵 한글 폰트가 초성만 여덟 벌씩 갖고 있는 이유가 그것이다.
 *
 * 그래서 다른 길로 간다. 시스템 폰트로 아주 작게 한 번 그린 다음, 그 그림을 격자로
 * 읽어 켜진 칸만 사각형으로 다시 찍는다. 한글이든 영문이든 한자든 같은 방법으로
 * 같은 결이 나오고, 처음 보는 글자를 만날 일이 없다.
 *
 * [ROWS] 가 이 글꼴의 해상도다. 한글은 받침까지 들어가서 7칸으로는 뭉개진다.
 * 9칸이 "읽히는" 과 "알갱이가 보이는" 이 함께 성립하는 자리였다.
 */
object DotFont {

    /**
     * 글자 한 칸의 높이(격자 수). 이 값이 곧 한글 도트의 해상도다.
     *
     * 9칸으로 먼저 그려 보고 11칸으로 올렸다. 9칸에서는 "원" 이 알아볼 수 없는
     * 사각 덩어리가 되고 "스타벅스" 처럼 획이 많은 네 글자가 서로 붙어 버린다.
     * 11칸이면 받침과 모음이 한 칸씩 떨어져 글자가 산다.
     */
    const val ROWS = 11

    /**
     * 이 크기(sp)보다 작은 글자는 도트로 바꾸지 않는다.
     *
     * 11sp 짜리 설명 문구를 9칸 격자에 욱여넣으면 한 칸이 1px 도 안 돼서, 도트가 아니라
     * 뭉갠 얼룩이 된다. 읽을 수 없는 글자를 멋으로 내보내지 않는다.
     * 낮추고 싶으면 이 숫자 하나만 내리면 화면 전체가 따라온다.
     */
    const val MIN_SP = 15f

    /** 켜진 칸 목록. [cols] × [ROWS] 격자를 왼쪽 위부터 훑은 순서다. */
    class Glyphs(val cols: Int, val on: BooleanArray)

    private const val CACHE_MAX = 300

    // 같은 글자를 매 프레임 다시 그리지 않는다. 목록을 스크롤하면 같은 가맹점 이름이
    // 몇 번이고 다시 그려지는데, 그때마다 비트맵을 만들면 스크롤이 걸린다.
    private val cache = object : LinkedHashMap<String, Glyphs>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Glyphs>?): Boolean =
            size > CACHE_MAX
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = android.graphics.Color.WHITE
    }

    /**
     * 글자가 격자를 꽉 채우도록 정한 폰트 크기와 기준선.
     *
     * 처음에는 ascent+descent 를 격자 높이에 맞췄다. 그런데 그 둘에는 줄 간격이
     * 들어 있어서, 실제 글자는 11칸 중 6칸만 차지하고 나머지는 빈 줄이 됐다.
     * 6칸짜리 한글은 "원" 이 통짜 사각형으로 뭉개져 읽히지 않는다.
     * 그래서 기준 글자 하나의 **잉크 높이**(실제로 칠해진 범위)를 격자에 맞춘다.
     *
     * 배율은 글자마다 재지 않고 기준 글자 하나로 한 번만 정한다. 글자마다 재면
     * 받침 있는 글자와 없는 글자의 키가 달라져 한 줄 안에서 들쭉날쭉해진다.
     */
    private val bounds = android.graphics.Rect()
    private var fitSize = 0f
    private var baseline = 0f

    private fun ensureFit() {
        if (fitSize > 0f) return
        paint.textSize = 100f
        paint.getTextBounds(REF, 0, REF.length, bounds)
        fitSize = ROWS * 100f / bounds.height().coerceAtLeast(1)
        paint.textSize = fitSize
        paint.getTextBounds(REF, 0, REF.length, bounds)
        baseline = -bounds.top.toFloat()
    }

    /** 배율을 재는 기준 글자. 받침까지 있어 한글의 위아래 끝을 다 짚는다. */
    private const val REF = "한"

    @Synchronized
    fun of(text: String): Glyphs {
        cache[text]?.let { return it }
        ensureFit()
        paint.textSize = fitSize

        val w = Math.ceil(paint.measureText(text).toDouble()).toInt().coerceIn(1, 400)
        val bmp = Bitmap.createBitmap(w, ROWS, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawText(text, 0f, baseline, paint)

        val px = IntArray(w * ROWS)
        bmp.getPixels(px, 0, w, 0, 0, w, ROWS)
        bmp.recycle()

        // 반쯤 덮인 칸까지 켜면 글자가 두꺼워져 받침이 붙어 버린다. 조금 인색하게 자른다.
        val on = BooleanArray(px.size) { (px[it] ushr 24) >= 110 }
        val g = Glyphs(w, on)
        cache[text] = g
        return g
    }

    /** 이 글자를 [cell] 크기의 격자로 찍었을 때의 폭. 자리를 잡으려면 그리기 전에 알아야 한다. */
    fun width(text: String, cell: Float): Float = of(text).cols * cell
}

/** 좌상단 기준으로 도트 글자를 찍는다. 높이는 항상 [DotFont.ROWS] 칸이다. */
fun DrawScope.dotString(text: String, left: Float, top: Float, cell: Float, color: Color) {
    val g = DotFont.of(text)
    // 알갱이 사이를 조금 띄운다. 꽉 채우면 도트가 아니라 그냥 글자가 된다.
    val d = cell * 0.86f
    for (y in 0 until DotFont.ROWS) {
        val row = y * g.cols
        for (x in 0 until g.cols) {
            if (g.on[row + x]) {
                drawRect(color, Offset(left + x * cell, top + y * cell), Size(d, d))
            }
        }
    }
}
