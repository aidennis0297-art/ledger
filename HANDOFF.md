# PushLedger 인수인계

푸시 알림을 읽어 자동 기록하는 안드로이드 가계부. Kotlin + Jetpack Compose.

- **프로젝트 루트**: `C:\Users\denni\OneDrive\문서\pushledger`
- **원격**: https://github.com/aidennis0297-art/ledger (main)
- **APK 배포 주소**: https://github.com/aidennis0297-art/ledger/raw/main/pushledger.apk
- **설계·화면 구성·알고리즘의 "왜"**: `README.md` — **먼저 읽을 것**

README 에 있는 내용은 여기서 반복하지 않는다. 이 문서는 **지금 상태의 좌표, 검증된
범위, 깨면 안 되는 불변식, 밟으면 시간 나가는 함정, 사용자와 일하는 법**만 적는다.

---

## 1. 현재 상태 (2026-08-28 실측)

소스 32개 파일 9,383줄 + 테스트 5개 파일 723줄. **유닛 테스트 52개 전부 통과.**
`assembleDebug` · `assembleRelease` 성공. APK 13.5MB(픽셀 폰트 포함).

| 영역 | 파일 |
|---|---|
| 도메인 | `Store.kt` 837 · `Coach.kt` 544 · `Nvidia.kt` 530 · `Stats.kt` 410 · `Parser.kt` 393 · `MainActivity.kt` 331 · `Model.kt` 238 · `NotifListener.kt` 221 · `AiWorker.kt` 205 · `StatusNotifier.kt` 180 · `Merchant.kt` 125 · `AiReview.kt` 121 · `DailyWidgetProvider.kt` 103 · `Hangul.kt` 77 · `AiJob.kt` 58 · `Money.kt` 43 |
| 화면 | `ui/Inbox.kt` 649 · `ui/Ledger.kt` 635 · `ui/Charts.kt` 607 · `ui/Budget.kt` 512 · `ui/AiScreen.kt` 397 · `ui/StatsScreen.kt` 386 · `ui/Home.kt` 371 · `ui/Settings.kt` 282 · `ui/StatsYear.kt` 186 |
| 디자인 기반 | `ui/Dots.kt` 420 · `ui/Field.kt` 161 · `ui/EmptyState.kt` 104 · `ui/Burst.kt` 82 · `ui/Text.kt` 73 · `ui/Type.kt` 49 · `ui/Money.kt` 53 |

### 실기기에서 아직 확인되지 않은 것 — 가장 중요한 미검증 영역

사용자는 APK 를 폰에 설치해 쓰고 있지만, 아래는 **한 번도 성공을 확인하지 못했다.**

- **NVIDIA API 실제 호출.** 모델 `deepseek-ai/deepseek-v4-flash-0731`.
  성공 응답을 받아본 적이 단 한 번도 없다. AI 기능 전체(알림 분석·기록 검토·리포트)가
  이 하나에 걸려 있다. **된다고 말하지 말 것.**
- **상태창 알림과 홈 위젯이 실제로 뜨는지.** 코드상 원인은 다 고쳤으나 눈으로 확인 안 됨.
- **위젯 큰 숫자 잘림.** 금액 표기가 `4만 5000원` 꼴로 길어져 18sp·한 줄·말줄임으로
  줄여 뒀지만 실제 위젯에서 안 잘리는지 미확인.
- **한글 픽셀 폰트가 실기기에서 어떻게 보이는지.** JVM 시뮬레이션으로 글자 모양만 확인했다.
- **스크롤 성능.** 화면의 모든 글자가 폰트 렌더링이라 문제없을 것으로 보지만 미측정.

### 실기기 데이터가 증명한, 아직 못 고친 것

사용자가 내보낸 CSV(`가계부_2026-08-28.csv`)에서 나온 것 중 하나가 남았다.

- **`명한돌` 43,333원과 14,000원이 수입으로 잡혔다.** 식당 이름으로 보이는데 수입이 됐다.
  **원문 없이는 못 고친다.** 사용자에게 설정 › 데이터 › `알림 기록 CSV` 를 받아
  그 문구를 보고 규칙을 짜야 한다. 추측으로 규칙을 넣지 말 것.

---

## 2. 빌드

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleDebug
```

- Gradle 8.14.3 / AGP 8.13.0 / Kotlin 2.2.20 / compileSdk 36 / minSdk 26 / JDK 21(JBR)
- **APK 는 빌드가 끝나면 자동으로 `<루트>/가계부.apk` 에 복사된다.**
  `app/build.gradle.kts` 끝의 `assembleDebug.doLast` 가 한다. 사용자 요청이므로 유지할 것.
- 배포용 사본은 `pushledger.apk` 다. `.gitignore` 가 `*.apk` 를 막으므로
  `git add -f pushledger.apk` 로 넣어야 한다. 작업이 끝나면 **커밋 전에 새로 빌드해서 복사**할 것.

### 서명

`keystore.properties`(gitignore 대상)에서 비밀번호를 읽는다. 파일이 없으면 서명 설정을
아예 만들지 않으므로 `assembleRelease` 가 서명 없는 APK 를 낸다. 이 파일과
`release.keystore` 는 **절대 커밋하지 말 것.** 커밋 전 `git status --short | grep -i keystore`
로 확인하는 습관을 들일 것.

### 유닛 테스트는 ASCII 경로에서만 돈다

프로젝트 경로에 한글(`문서`)이 있어서 `testDebugUnitTest` 가 이 자리에서는
`ClassNotFoundException` 으로 실패한다. 클래스는 정상 컴파일되고 APK 빌드도 정상이다.
Gradle 테스트 워커만의 문제다. 이 세션에서 쓴 방법:

```bash
SP=<ascii-temp-dir>
# 최초 1회: gradle 래퍼와 설정 복사
cp -r <루트>/gradle "$SP/" && cp <루트>/gradlew <루트>/gradlew.bat \
      <루트>/settings.gradle.kts <루트>/build.gradle.kts <루트>/gradle.properties "$SP/"
echo "sdk.dir=C:/Android/Sdk" > "$SP/local.properties"
# app/build.gradle.kts 는 서명 설정과 APK 복사 태스크를 뺀 사본을 쓴다
# 매번: 소스만 다시 복사하고 돌린다
rm -rf "$SP/app/src" && cp -r <루트>/app/src "$SP/app/"
cd "$SP" && ./gradlew testDebugUnitTest
```

결과는 `$SP/app/build/test-results/testDebugUnitTest/*.xml` 에 있다.
**콘솔이 한글을 깨뜨리므로** 실패 메시지는 파이썬으로 XML 을 파싱해 읽는 편이 빠르다.

---

## 3. 깨면 안 되는 불변식

코드를 고치기 전에 이 목록을 먼저 볼 것. 전부 실제 버그를 겪고 세운 규칙이다.

1. **`Stats.active()` 가 모든 지출 집계의 유일한 관문이다.**
   취소분·투자분·고정지출 실행건(`by=="fixed"`)을 여기서 걸러낸다. 우회해서 직접
   `filter` 를 쓰면 취소 처리가 조용히 샌다.

2. **`Store.addTxn()` 이 거래 삽입의 유일한 경로다.**
   중복 판정(`isDuplicate`)이 여기 하나뿐이다. **금액과 시각만 본다** — 앱과 가맹점은
   일부러 안 본다. 한 번 결제하면 카카오페이·카드사·은행이 각자 알림을 띄우는데
   표기가 전부 달라서, 앱·가맹점을 조건에 넣으면 정작 막아야 할 것을 하나도 못 막는다.

3. **`Store.addFixed()` 가 고정지출 등록의 유일한 문이다.**
   등록과 동시에 그달의 같은 이름 결제를 실행건으로 바꾼다. 이걸 안 지나면 같은 돈이
   소비에서 한 번, 고정지출 계획에서 한 번 빠진다.

4. **고정지출 예산은 `cfg.fixed` 합계 하나뿐이다.**
   `Stats.BUDGETABLE_CATS` 가 `Cat.HOUSING` 을 뺀다. 항목별 예산에도 배정하면 두 번 깎인다.

5. **결제 취소는 수입이 아니라 원 거래의 무효화다.** `Txn.canceled` 를 세우고 레코드는 남긴다.

6. **`Store.writeMonth()` 가 상태창·위젯 갱신을 트리거한다.** 갱신 호출을 흩뿌리지 않는다.

7. **위젯·알림 서비스는 진입 시 `Store.ensure(context)` 를 부른다.**
   앱을 한 번도 안 연 상태에서 위젯이 갱신되면 `lateinit root` 미초기화로 죽는다.

8. **가맹점 이름은 저장 전에 `Merchant.clean()` 을 지난다.**
   `NotifListener` 와 `AiWorker` 두 곳이 입구다. 여기서 안 다듬으면 통계·반복 결제·
   취소 대조가 전부 결제사가 붙인 껍데기를 안고 간다.

9. **사용자가 정한 분류(`Config.catMemory`)가 규칙보다도 AI 보다도 우선한다.**
   `Store.recallCategory()` 를 먼저 보고 없을 때만 짐작한다. 이 순서를 뒤집으면
   사용자가 고쳐 놔도 다음 결제에서 도로 틀린다.

10. **알림함에서 한 일은 전부 되돌릴 수 있어야 한다.**
    무시·앱 끄기·AI 기록·정산 차감 모두 되돌리는 길이 있다. 새 처리를 넣을 때
    되돌리기를 같이 넣지 않으면 사용자가 그 버튼을 안 누른다. 일괄 처리에는
    **일괄 되돌리기**가 붙어야 한다.

11. **오래 걸리는 일은 진행·결과·이동 자유 세 가지를 갖춘다.**
    진행은 `AiJob` 하나로 보고해 어느 탭에서나 보이고, 결과는 `Config.lastAiRun` 에
    적어 앱을 껐다 켜도 남고, 일은 WorkManager/앱 수명 코루틴에서 돌아 화면을 떠나도 산다.

12. **디자인 토큰 밖의 값을 화면 코드에 쓰지 않는다.**
    - 색 → `ui/Charts.kt` 팔레트와 `CatColor`
    - 글자 크기 → `ui/Type.kt` 의 `T.Display/Amount/Title/Body/Caption` 다섯 단
    - 글꼴 → `ui/Type.kt` 의 `Pixel` (MainActivity 에서 `LocalTextStyle` 에 한 번만 건다)
    - 도트 크기·간격 → `ui/Dots.kt` 의 `DOT` / `DOT_GAP`
    - 모서리 → 배지 4dp · 안쪽 10dp · 카드 16dp
    - 금액 문자열 → `Money.kt` 의 `won()` / `wonShort()`

13. **도트 그래픽은 이 앱의 정체다.** 차트 라이브러리로 갈아타지 않는다.
    띠를 두껍게 만들 때 **알갱이를 키우지 말고 줄 수를 늘린다** — 사용자가 여러 번 지적했다.

---

## 4. 밟으면 시간 나가는 함정

### 4-1. 셸 heredoc 과 파이썬으로 Kotlin 파일을 쓸 때
이 환경의 Bash heredoc 은 `<<'EOF'` 로 인용해도 백슬래시를 한 겹 먹는다.
파이썬 치환도 `\n`, `\"`, `\uFEFF` 가 한 겹 벗겨져 **소스에 보이지 않는 제어문자가 박힌다.**
이 세션에서 실제로 CSV 내보내기 함수가 그렇게 깨져 컴파일이 안 됐다.

- 정규식과 이스케이프가 든 Kotlin 코드는 **Edit/Write 도구로 직접** 쓸 것.
- 파이썬을 쓸 거면 `r'''...'''` 원시 문자열을 쓰고, `\u` 같은 건 `chr(92)+'uFEFF'` 로 만들 것.
- 고치고 나면 `grep -n` 으로 그 줄을 눈으로 확인할 것.

### 4-2. `local.properties` 는 슬래시로
`sdk.dir=C:/Android/Sdk`. 역슬래시는 Properties 문법에서 이스케이프로 먹혀 경로가 깨진다.

### 4-3. Compose `padding` 인자 조합
`padding(horizontal=, top=, bottom=)` 같은 조합은 없다.
`padding(all)` · `padding(horizontal, vertical)` · `padding(start, top, end, bottom)` 뿐이다.

### 4-4. 애니메이션 값을 Composable 본문에서 읽지 말 것
`.value` 를 본문에서 읽으면 **매 프레임 그 함수가 통째로 다시 불린다.**
`State<Float>` 를 그대로 들고 있다가 `Canvas` 람다 안에서 읽으면 다시 그리기만 한다.
`Charts.kt` 의 `grow()` 와 `BudgetBar` 가 그 패턴이다. 목록 안 애니메이션에서는
이 차이가 곧 스크롤 부드러움이다.

### 4-5. 알림 스몰 아이콘은 반드시 단색 벡터
`setSmallIcon(R.mipmap.ic_launcher)` 처럼 적응형 아이콘을 넣으면 Android 8 이상에서
**알림이 통째로 무시되거나 `Bad notification` 으로 죽는다.** 지금은
`R.drawable.ic_stat_budget`(단색 벡터)를 쓴다.

### 4-6. 커스텀 RemoteViews 알림은 실패를 잡을 수 없다
SystemUI 가 그릴 때 실패하므로 앱에서 예외로 감지할 방법이 없다. 그래서 상태창은
표준 알림 두 줄로 갔다. 진단 수단은 설정 › 상태창 알림의 `지금 띄워보기` + `diagnose()` 다.

### 4-7. `res/font/` 에는 폰트 파일만 들어간다
`README.txt` 를 넣었더니 `mergeDebugResources` 가 죽었다. 출처와 라이선스는
저장소 루트 `LICENSE-Galmuri.md` 에 적어 뒀다. **OFL 이 요구하는 고지라 지우면 안 된다.**

### 4-8. `TextStyle()` 을 통째로 새로 만들면 글꼴이 딸려 오지 않는다
`LocalTextStyle` 에 건 `Pixel` 이 상속되지 않아 그 줄만 시스템 폰트로 튄다.
지금 그런 자리가 둘 있고(순위 배지, 입력칸) 거기엔 `fontFamily = Pixel` 을 직접 적었다.
새로 만들 때는 `LocalTextStyle.current.copy(...)` 를 쓰는 편이 안전하다.

### 4-9. 자바 정규식의 `\s` 는 전각 공백을 공백으로 안 본다
카드사 알림에 U+3000 이 흔히 섞여 온다. `Parser.flat()` 이 이걸 편다.
알림 문자열을 새로 다루는 코드를 쓸 때 이 함수를 지나게 할 것.

### 4-10. 예시 데이터는 덮어쓰지 않는다
`seedTestData` 가 예전에 그 달 거래를 통째로 덮어썼다(실사용 데이터 소실 버그).
지금은 뒤에 붙이고 `Store.SEED_TAG` 표식을 남겨 `clearTestData()` 가 그것만 지운다.

---

## 5. 알아 두면 좋은 구조

### 알림 → 거래
`NotifListener.handle()` 하나가 전부다. 순서가 곧 정책이라 순서를 바꾸면 뜻이 바뀐다.
차단 앱 → dedup → 로그 함수 정의 → 허용 목록 밖 → `Parser.parse()` 분기.
**켠 앱이든 아니든 차단하지 않은 앱의 알림은 전부 알림함에 남는다.**

### `Parser.parse()` 의 분기 순서 (바꾸면 뜻이 바뀐다)
취소 → 충전 → 배당 → 정산 → 상대 수령 → 내가 보낸 송금 → 내가 받음 → 지출 아님 → 결제.
예를 들어 "상대 수령" 이 "내가 받음" 보다 앞에 있어야 하는 이유는 둘 다 "받았" 을
포함하기 때문이다. README 의 송금 방향 표를 먼저 읽을 것.

### AI 는 셋
| 하네스 | 무엇 | 파일 |
|---|---|---|
| `AiQueue`/`AiWorker` | 못 읽은 알림을 거래로 | `AiWorker.kt` |
| `ReviewRun`/`ReviewWorker` | 이미 기록된 것을 다시 검토 | `AiReview.kt` |
| `CoachRun` | 리포트 생성 | `Coach.kt` |

셋 다 진행 상황을 `AiJob` 에 보고한다. 새 AI 기능을 붙일 때 진행 표시를 새로 만들지 말 것.

`Nvidia.call()` 은 일 성격에 따라 설정이 갈린다 — **파싱은 생각 모드 끔**(400토큰, temp 0),
리포트는 켬(16384토큰, temp 0.3). 파싱에 생각 모드를 켜면 한 건에 수십 초가 걸린다.

### 저장소
`ledger/YYYY-MM.json`(월별) · `inbox/YYYY-MM-DD.json`(일별) · `config.json` · `fixes.json`.
알림함은 메모리에 최근 3,000건만 들고 있고 파일에는 보관 기간만큼 다 남는다.
**내보내기는 파일에서 직접 읽으므로** 메모리 상한에 안 잘린다.

---

## 6. 남은 작업

### 근거가 있고 값이 확실한 것
- **`명한돌` 오분류** — 사용자에게 `알림 기록 CSV` 를 받아 원문을 보고 고칠 것. (§1)
- **월말 예상 지출** — `현재 지출 ÷ 경과일 × 총일수` 를 홈에 한 줄.
  예산을 넘긴 뒤가 아니라 넘기기 전에 알려 준다. safe-to-spend 계열 앱의 기본 기능.

### 사용자가 미룬 것
- **애니메이션 확대** — 지금은 차트가 자라는 것, 도트 띠의 음영 물결, 카테고리 칩의
  도트 부스러기뿐이다.

### 해 볼 만하지만 급하지 않은 것
- 알림함 잡담 보관 기간을 결제 알림과 따로 두기(지금은 한 칸으로 묶여 있다)
- 이상치 결제 알림(평균+2σ)을 홈에 띄우기 — `LocalCoach` 에 계산은 이미 있다

### 하지 말 것 (검토하고 버린 것)
- **형태소 분석기(Nori/KOMORAN)** — 사전만 수십 MB. 조사 처리는 `Hangul.kt` 의
  받침 규칙으로 충분하다.
- **푸리에/생성모형 주기성 탐지** — 표본 3~6개짜리 월 단위 이벤트에 과하다.
  지금의 간격 25~35일 규칙이 같은 일을 한다.
- **ML 분류기** — 학습 데이터가 없고, `catMemory`(사용자 교정 기억)가 그 자리를 채웠다.
- **오픈뱅킹 API** — "알림만 읽는다" 는 이 앱의 전제를 부순다.

---

## 7. 사용자와 일하는 법

- **한국어로 답한다.**
- 요구가 한 메시지에 여러 개 섞여 들어오고, 작업 중에도 추가된다.
  전부 처리하고 무엇을 했는지 항목별로 답하는 방식이 잘 맞았다.
- **직접 확인하지 않은 것을 됐다고 말하지 않는다.** 사용자가 실기기로 검증하며
  "아직 안 됨" 을 여러 번 지적했다. 확인한 것과 못 한 것을 나눠 말하면 신뢰가 유지된다.
- **지적이 거칠게 온다.** 욕이 섞여도 그 안에 정확한 기술적 지적이 있다.
  변명하거나 사과를 늘어놓지 말고, 원인을 코드에서 짚고 고쳐서 보여 줄 것.
  이 세션에서 "개똥임", "제대로 좀 해" 같은 말이 나온 자리는 전부 실제로 코드가 틀린 자리였다.
- **추측으로 된다고 하지 말 것.** 기기가 없으면 없는 대로, 알고리즘만이라도 JVM 에서
  돌려 눈으로 보여 주는 편이 훨씬 잘 통했다(도트 폰트 ASCII 렌더링이 그 예다).
- 사용자는 도트 그래픽에 강한 취향이 있다. "바 말고 도트", "작은 게 예쁨", "빽빽하게"
  같은 지적이 반복됐다. 알갱이를 키우는 방향은 거의 항상 거절당했다.
- 코드 스타일은 ponytail — 최소 의존성, 최소 추상화. 차트 라이브러리·DI·Room 전부
  일부러 안 썼다. **주석은 "무엇" 이 아니라 "왜" 를 적는다.** 이 저장소의 주석 밀도가
  기준이다. 특히 어떤 규칙을 왜 그렇게 정했는지, 예전에 어떻게 틀렸는지를 남긴다.
- 작업이 끝나면 **빌드해서 APK 를 파일로 건네고, GitHub 에 푸시하고, 다운로드 주소를 준다.**
- 인터넷에서 근거를 찾아오는 것을 좋아한다. 다만 "개소리 같은 아이디어" 는 싫어한다 —
  **지금 실제로 깨져 있는 것**과 **근거가 있는 것**만 가져올 것. 버린 것과 버린 이유를
  같이 말하면 신뢰가 올라간다.

---

## 8. 다음 에이전트가 부를 만한 스킬

| 스킬 | 언제 |
|---|---|
| `ponytail:ponytail` | 코드를 쓰거나 고치기 전에. 이 코드베이스 전체가 그 스타일이라 켜지 않으면 결이 어긋난다. |
| `diagnosing-bugs` | 실기기에서 알림이 안 잡히거나 상태창·위젯이 안 뜰 때. 증상만 보고 고치면 이 프로젝트에서 여러 번 헛다리를 짚었다. |
| `code-review` | `Store`·`Stats` 를 고친 뒤. 알림 서비스와 UI 가 같은 저장소를 동시에 쓰므로 동시성 눈으로 볼 것. |
| `run` | APK 를 설치해 실제로 확인할 때. (지금 이 환경에는 기기도 에뮬레이터도 없다) |

`claude-api` 는 부를 필요 없다 — 이 앱은 Anthropic API 를 쓰지 않는다.
