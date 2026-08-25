package com.pushledger

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * AI 로 도는 일 하나의 진행 상황. 이 앱의 AI 기능은 전부 여기로 보고한다.
 *
 * 예전에는 알림 분석과 리포트 생성이 각자 진행 상태를 들고 있었고, 화면은 그 둘을
 * 따로 구독해 같은 모양의 띠를 두 벌 그렸다. 기록 검토가 붙으면 세 벌이 된다.
 * 진행 표시는 기능마다 다시 만들 것이 아니라 한 번 만들어 두고 나눠 쓰는 자리다.
 *
 * 값은 앱이 살아 있는 동안만 남는다. 끝난 결과처럼 앱을 껐다 켜도 남아야 하는 것은
 * [Store.setLastAiRun] 으로 설정에 적는다.
 */
object AiJob {
    /** 지금 무엇이든 도는 중인지. 화면 위 띠가 이 값 하나로 뜨고 진다. */
    val running = MutableStateFlow(false)

    /** 무슨 일인지. "알림 분석", "기록 검토", "이번 달 코칭" 처럼 짧게. */
    val label = MutableStateFlow("")

    /** 지금 그 일의 어느 대목인지. 사용자가 멈춘 것과 도는 것을 구분하는 근거다. */
    val message = MutableStateFlow("")

    val done = MutableStateFlow(0)

    /** 0 이면 진행률을 모른다는 뜻이다. 리포트 생성처럼 몇 걸음인지 셀 수 없는 일이 그렇다. */
    val total = MutableStateFlow(0)

    /**
     * 도는 중인 일의 수. 알림 큐가 도는 동안 리포트를 만들 수 있으므로 둘이 겹친다.
     * 세지 않고 불 하나로 두면 먼저 끝난 쪽이 아직 도는 쪽의 띠까지 꺼 버린다.
     */
    private var active = 0

    @Synchronized
    fun start(label: String, total: Int = 0, msg: String = "") {
        active += 1
        this.label.value = label
        this.total.value = total
        done.value = 0
        message.value = msg
        running.value = true
    }

    /** 한 걸음 나아갔다. [msg] 를 주면 지금 하는 일도 같이 바꾼다. */
    fun step(msg: String? = null) {
        done.value += 1
        if (msg != null) message.value = msg
    }

    fun say(msg: String) { message.value = msg }

    fun finish(msg: String) {
        message.value = msg
        running.value = false
    }
}
