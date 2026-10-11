# JSON 성능 튜닝 기록

silk-json의 쓰기·읽기를 fastjson2 수준으로 끌어올리는 작업의 기록과 작전이다: 무엇이 통했고, 무엇이 통하지 않았고, 무엇이 남았고, 남은 것을 어떤 순서로 실험하는가.
근거는 결정 75·76(`notes/decisions.md`), 부록 `benchmark.adoc`, 커밋 `49e3f5a`·`68f5478`·`a64dd2d`·`474dc47`이다.
"다음 작전" 절부터는 2026-10-10에 "다음 후보"를 검증을 거친 실험 순서로 바꾼 것이다.
2026-10-11에 작전 0단계 일부와 2-1~2-5를 실행했고, 결과는 "성공한 작업"의 6절과 "작전 실행 결과" 절에 있다.
4단계(부록 문서)는 같은 날 `-prof gc` 전체 실행으로 했고, 3단계(빌린 버퍼 본문)는 같은 날 결정 78로 구현했다.
같은 날 오후에 0단계의 나머지, 1단계, 2-6, 3단계의 측정, 남은 격차의 변형을 실행했고, 결과는 "성공한 작업"의 7절과 "작전 실행에서 기각"에 있다.

## 제약

- `sun.misc.Unsafe`는 쓰지 않는다.
  리플렉션도, 새 의존성도 없다(`AGENTS.md`).
- 대상은 JDK 21이다.
- 8바이트 접근은 `LittleEndian`(`MethodHandles.byteArrayViewVarHandle`)으로 한다.
  네이티브 이미지에서 설정 없이 돈다.
- 문자열 루프에서 `String.charAt`을 쓰지 않는다.
  `charAt`의 프로파일은 프로세스 전체에 하나라서, Latin-1 밖의 문자열이 한 번 지나가면 그것을 부르는 모든 루프가 느리게 컴파일된다.
- 출력은 유효한 JSON이어야 한다.
  검사를 건너뛰는 API는 들이지 않는다.
- JSON 응답 본문의 복사를 없애기 위해 core의 `WebResponse.Bytes`와 sealed `Body`를 바꾸는 것은 허용된다(작전 3단계).

## 지금 수치 (2026-10-11, 작업 트리)

2026-10-11에 커밋 `cd57b46`의 라이브러리(기준)와 2-1~2-5를 적용한 작업 트리를 같은 세션에서 연달아 쟀다.
JMH 1.37, 포크 2, 워밍업·측정 각 5회×1초, CPU 0~3(P코어), `-Xms1g -Xmx1g`, AC 전원, `performance` 프로파일, Temurin 25.
할당(`-prof gc`)은 이번에 재지 않았다.

| 케이스 | 기준 | 지금 | fastjson2 | 지금 / fastjson2 |
|---|---|---|---|---|
| `readList` | 5.96 µs | 5.45 µs | 6.05 µs | 0.90 |
| `readKoreanList` (신설) | 10.96 µs | 5.64 µs | 7.87 µs | 0.72 |
| `readReversedList` (신설) | 9.47 µs | 8.03 µs | 6.16 µs | 1.30 |
| `readLongStringList` (신설, 200자 설명) | 7.11 µs | 6.55 µs | 5.21 µs | 1.26 |
| `readPlaces` | 5.41 µs | 4.91 µs | 5.18 µs | 0.95 |
| `writeList` | 4.67 µs | 4.65 µs | 4.52 µs | 1.03 |
| `writePlainList` | 4.40 µs | 4.15 µs | 3.53 µs | 1.18 |
| `writeKoreanList` | 4.39 µs | 4.00 µs | 3.51 µs | 1.14 |
| `writeLongStringList` (신설, 27 KB) | 15.03 µs | 8.60 µs | 6.17 µs | 1.39 |
| `writePlaces` | 9.37 µs | 9.21 µs | 9.37 µs | 0.98 |
| `writeMessage` | 24 ns | 25 ns | 29 ns | 0.86 |

읽기는 역순 키와 긴 문자열을 빼고 모두 fastjson2와 같거나 앞선다.
쓰기는 긴 문자열에서 격차가 2.4배에서 1.39배로 줄었고, 이스케이프 없는 짧은 문자열의 격차(1.14~1.18배)가 남았다.
`writeList`가 거의 그대로인 까닭은 2-5의 비용이다(6절).

### 2026-10-11 전체 실행 (부록 수치)

커밋 `9c88809`에서 `-prof gc`를 붙여 30케이스 전부를 한 번에 돌린 수치이고, 부록 `benchmark.adoc`의 표가 이것이다.
조건은 위와 같고, 실행은 10분 16초였다.
긴 실행이라 위의 연달아 잰 수치보다 양쪽 모두 1~4% 느리게 나왔다(`writePlainList` 4.15 → 4.34 µs, fastjson2 3.53 → 3.66 µs).

| 케이스 | 생성 코덱 | fastjson2 | 비율 | 할당 (코덱 / fastjson2) |
|---|---|---|---|---|
| `writeList` | 4.76 µs | 4.59 µs | 1.04 | 10,696 / 10,680 B |
| `writePlainList` | 4.34 µs | 3.66 µs | 1.19 | 10,296 / 10,280 B |
| `writeKoreanList` | 4.07 µs | 3.62 µs | 1.13 | 9,792 / 9,776 B |
| `writeLongStringList` (27,498 B) | 9.01 µs | 6.36 µs | 1.42 | 28,352 / 27,688 B |
| `readList` | 5.34 µs | 6.07 µs | 0.88 | 17,616 / 24,824 B |
| `readKoreanList` | 5.70 µs | 7.89 µs | 0.72 | 23,064 / 34,128 B |
| `readReversedList` | 8.16 µs | 6.13 µs | 1.33 | 17,616 / 24,824 B |
| `readLongStringList` | 6.41 µs | 5.21 µs | 1.23 | 34,336 / 34,416 B |
| `readPlaces` | 4.97 µs | 5.25 µs | 0.95 | 11,144 / 11,224 B |
| `writePlaces` | 9.34 µs | 9.64 µs | 0.97 | 8,464 / 17,632 B |
| `writeMessage` | 25.3 ns | 27.3 ns | 0.93 | 232 / 216 B |

- `writeLongStringList`의 할당 664 B 차이는 문서마다 한 번 만드는 `US_ASCII` 인코더와 `CharBuffer`·`ByteBuffer` 뷰다.
- `writeMessage`의 할당이 224 B에서 232 B가 됐다.
  인코더와 두 뷰를 담는 필드 셋이 `JsonOutput` 객체를 8바이트 키운 것으로 본다(미확인).
- 트리·손 라이터·Jackson은 2026-10-10과 같거나 조금 빠르다: 트리 쓰기 8.5 → 8.1 µs, 손 라이터 6.2 → 5.9 µs, 손 리더 14.4 → 14.3 µs.

### 2026-10-11 오후 전체 실행 (부록 수치)

커밋 `5f067c8` 위에 7절의 변경을 얹은 작업 트리에서 `-prof gc`로 46케이스(큰 id 두 매개변수 포함 48행)를 돌린 수치이고, 부록의 표가 이것이다.
조건은 위와 같고, 실행은 16분 27초였다.

| 케이스 | 생성 코덱 | fastjson2 | 비율 | 할당 (코덱 / fastjson2) |
|---|---|---|---|---|
| `writeList` | 4.80 µs | 4.59 µs | 1.05 | 10,696 / 10,680 B |
| `writeList_stream` (신설) | 3.82 µs | 3.55 µs | 1.07 | 184 / 184 B |
| `writePlainList` | 4.34 µs | 3.75 µs | 1.16 | 10,296 / 10,280 B |
| `writeKoreanList` | 4.07 µs | 3.65 µs | 1.12 | 9,792 / 9,776 B |
| `writeLongStringList` | 6.84 µs | 6.31 µs | 1.08 | 28,352 / 27,688 B |
| `writeOrders` (신설) | 2.40 µs | 2.69 µs | 0.89 | 6,192 / 6,176 B |
| `readList` | 5.43 µs | 6.19 µs | 0.88 | 17,616 / 24,824 B |
| `readKoreanList` | 5.67 µs | 7.90 µs | 0.72 | 23,064 / 34,128 B |
| `readReversedList` | 7.34 µs | 6.16 µs | 1.19 | 17,616 / 24,824 B |
| `readPythonList` (신설) | 6.61 µs | 6.94 µs | 0.95 | 17,616 / 24,824 B |
| `readPrettyList` (신설) | 8.74 µs | 7.93 µs | 1.10 | 17,616 / 24,824 B |
| `readLongStringList` | 6.55 µs | 5.22 µs | 1.25 | 34,336 / 34,416 B |
| `readBigIdList` 13자리 (신설) | 5.89 µs | 6.97 µs | 0.84 | 17,616 / 24,824 B |
| `readBigIdList` 18자리 (신설) | 5.92 µs | 7.28 µs | 0.81 | 17,616 / 24,824 B |
| `readShortStringList` (신설) | 2.84 µs | 3.83 µs | 0.74 | 5,536 / 14,816 B |
| `readOrders` (신설) | 2.97 µs | 3.12 µs | 0.95 | 9,536 / 9,616 B |
| `readPlaces` | 4.95 µs | 5.37 µs | 0.92 | 11,144 / 11,224 B |
| `writePlaces` | 9.51 µs | 9.73 µs | 0.98 | 8,464 / 17,632 B |
| `writeMessage` | 26 ns | 28 ns | 0.93 | 232 / 216 B |

- fastjson2 `creator=reflect`(네이티브 이미지가 타는 모드)는 `readList` 23.98 µs·111,224 B, `writeList` 5.72 µs다.
  `-verbose:class`로 기본 모드는 `OWG_1_5_Item`(쓰기)·`ORG_1_5_Item`(읽기)을, reflect 모드는 생성 클래스 없이 `FieldWriter*`·`FieldReader*`를 올리는 것을 확인했다.
- Temurin 21.0.8에서 같은 설치본: `writePlainList` 4.38 대 3.81 µs(1.15), `writeKoreanList` 4.24 대 3.77 µs(1.13), `writeLongStringList` 6.96 대 6.29 µs(1.11), `readList` 5.53 대 6.29 µs(0.88)다.
  비율은 Temurin 25와 같다.
- HTTP `json-list`의 요청당 할당(Temurin 25, wrk 4스레드 64연결, 워밍업 20초 뒤 30초, GC 로그 합산, 두 번 잼): Jetty 14,470·14,403 B → 3,765·3,765 B, Tomcat 13,253·13,199 B → 2,556·2,559 B다.
  이전은 커밋 `fb143f7`(빌린 버퍼 전), 이후는 이 작업 트리다.
  처리량은 Jetty 151k → 159k req/s(두 번 다), Tomcat은 126k → 133k와 138k → 126k로 엇갈려 판단하지 않는다.

### 2026-10-10 수치 (커밋 `474dc47`)

JMH 1.37, 포크 2, 워밍업·측정 각 5회×1초, CPU 0~3(P코어), `-Xms1g -Xmx1g`, AC 전원, Temurin 25.

| 연산 | 생성 코덱 | fastjson2 | Jackson 3.1 |
|---|---|---|---|
| 레코드 100개 쓰기 (10,490 B) | 4.8 µs | 4.6 µs | 11.8 µs |
| 이스케이프 없는 레코드 100개 쓰기 (10,090 B) | 4.6 µs | 3.6 µs | |
| 한글 레코드 100개 쓰기 (9,590 B) | 4.6 µs | 3.6 µs | |
| 레코드 100개 읽기 | 6.1 µs | 6.2 µs | 23.9 µs |
| 27바이트 객체 쓰기 | 24 ns | 27 ns | 136 ns |
| 장소 100개 쓰기 (8,263 B) | 9.3 µs | 9.5 µs | 19.7 µs |
| 장소 100개 읽기 | 5.4 µs | 5.2 µs | 25.7 µs |

목록 세 가지를 이전 빌드와 연달아 잰 수치는 다음과 같다(긴 실행의 편차를 뺀 값).

| 케이스 | 생성 코덱 | fastjson2 | 비율 |
|---|---|---|---|
| `writeList` (레코드마다 이스케이프 둘) | 4.71 µs | 4.46 µs | 1.06 |
| `writePlainList` | 4.39 µs | 3.56 µs | 1.23 |
| `writeKoreanList` | 4.42 µs | 3.54 µs | 1.25 |

## 성공한 작업

### 1. 트리를 UTF-8 바이트로 바로 쓰기 (결정 75)

- 트리를 `StringBuilder`에 문자 단위로 채워 `String`을 만들고 서블릿이 다시 인코딩하던 경로를 없앴다.
  트리를 한 번, UTF-8로 바이트 배열에 쓰고 그 배열이 응답 바디가 된다.
- 10 KB 바디가 114 KB 할당과 24 µs(Jackson 440 B·9 µs)이던 것을 줄였다.
  HTTP `json-list`는 Spring MVC 대비 0.6배에서 Jetty 1.05배, Tomcat 1.02배가 됐다.
- `JsonObject`는 `LinkedHashMap` 대신 배열 둘(16개 넘으면 인덱스)이다.
  `put(key, "x")`는 `String`을 그대로 담아, 필드 다섯 개 레코드의 할당이 열두 개 안팎에서 세 개로 줄었다.
- 문자열은 `getChars`로 한 번 복사해 그 배열에서 검사한다(`charAt` 금지의 시작).
- 한 문서에서 되풀이되는 키는 한 번 인코딩하고 그 뒤로는 복사한다.
- 플랫폼 스레드는 64 KB까지의 버퍼를 `ThreadLocal`의 `byte[]`로 다시 쓴다.
  가상 스레드는 버퍼를 남기지 않는다.

### 2. 바이트 엔진과 생성 코덱 (결정 76, 커밋 `49e3f5a`)

- 모든 문서가 `JsonOutput`(바이트 생성기)과 `JsonInput`(바이트 풀 파서)을 지나고, 매핑은 그 위의 람다이거나 `silk-json-processor`가 컴파일 때 만든 코덱이다.
- 트리를 거치던 레코드 100개 목록이 쓰기 12.6 µs·29.8 KB, 읽기 25.5 µs·82 KB에서 쓰기 5.2 µs·11 KB, 읽기 6.0 µs·18 KB가 됐다.
  27바이트 객체는 25 ns(Jackson 133 ns)다.
- `JsonKey.of(name)`가 쉼표가 있는 형태와 없는 형태의 바이트를 들고 있어, 생성 코덱은 레코드마다 키를 이스케이프하지 않는다.
  키는 두 워드를 저장 두 번으로 쓴다.
- 생성 코덱의 읽기는 선언 순서의 다음 키를 `nextKeyIs`로 `,"key":` 통째로 제자리 비교한다.
  순서 밖의 키만 `keyIndex` 헬퍼로 간다.

### 3. 8바이트씩 다루기 (`LittleEndian`, 결정 76)

- 문자열 읽기는 8바이트 SWAR로 따옴표·역슬래시·제어 문자·0x80 이상을 찾는다(`LittleEndian.special`).
- 7자리 이하 정수는 SWAR로 자릿수를 세고 곱셈 세 번으로 변환한다.
- 이 방식으로 목록 읽기가 13.0 µs에서 6.0 µs(fastjson2 동급), 쓰기가 7.0 µs에서 5.2 µs가 됐다.
- 이스케이프가 있는 문자열 읽기는 `byte[]`에 구간을 복사한 뒤 폐기 예정 `new String(bytes, 0, off, len)`(Latin-1)으로 만든다.
  인라인돼서 더 빨랐다.
- 키 스캔은 바이트 루프로 둔다.
  짧은 키에서는 SWAR보다 빨랐다.
- 컨테이너 상태는 배열 대신 필드에 둔다.
  C2가 레지스터에 둔다.
- 문자열 키 캐시는 4칸 선형 탐사다.
  `"name"`과 `"quantity"`가 같은 칸에 떨어져 레코드마다 서로를 밀어내던 문제를 고쳤다.

### 4. 소수의 읽기와 쓰기 (커밋 `a64dd2d`)

- `readDouble`: 유효 숫자가 2^53 이하이고 10의 지수가 -22~22이면 곱셈이나 나눗셈 한 번(Clinger), 19자리까지의 나머지는 Eisel-Lemire의 128비트 곱, 그 밖과 반올림이 정해지지 않는 경우만 `Double.parseDouble`로 간다.
  장소 100개 읽기가 12.6 µs·43.9 KB에서 5.3 µs·11.1 KB가 됐다(fastjson2 5.2 µs).
- `readFloat`는 자릿수에서 한 번만 반올림한다.
  `(float) readDouble()`은 두 번 반올림해 다른 float에 떨어질 수 있다.
- double 쓰기: `Double.toString`(JDK 19+)과 같은 알고리즘인 Schubfach로 가장 짧은 십진수를 찾고, 자릿수를 8개씩 워드로 버퍼에 늘어놓는다.
  장소 100개 쓰기가 16.5 µs·35.6 KB에서 9.3 µs·8.5 KB가 됐다(fastjson2 9.4 µs).
- `value(float)`는 `Float.toString`의 텍스트를 쓴다.
  넓힌 double로 쓰면 `0.1f`가 `0.10000000149011612`가 된다.

### 5. 문자열 쓰기의 이스케이프와 검사 (커밋 `474dc47`)

- 따옴표와 역슬래시를 평범한 ASCII를 쓰는 루프 안에서 2바이트로 쓰고, 쓰기 위치 `at`을 한 칸 미룬다.
  그 전에는 첫 따옴표에서 나머지 전부가 `rest()`로 갔다.
  공간 검사는 이스케이프 분기에서만 하고, 모자라면 남은 문자가 전부 이스케이프되는 최악을 잡는다.
  이스케이프 없는 문자열의 버퍼 크기 동작은 그대로다.
- 문자 검사는 `PLAIN[c]` 표 대신 비교 연산(`c >= 0x80 || c < 0x20 || c == '"' || c == '\\'`)이다.
  문자마다의 로드가 빠진다.
- `ensure`는 공간 검사만 남기고, 늘리는 일은 `makeRoom`으로 뺐다.
  111바이트라 인라인되지 않던 호출(6~7%)이 사라졌다.
- 이전 빌드와 연달아 잰 결과: `writeList` 5.28 → 4.71 µs(-11%), `writePlainList` 4.82 → 4.39 µs(-9%), `writeKoreanList` 4.55 → 4.42 µs(-3%).
- 정확성은 `JsonOutputTest`의 세 테스트가 지킨다: 참조 구현과의 무작위 비교 2만 개(값·키·스트림), 0~65자 길이 경계에 특수 문자를 모든 자리에, 버퍼 끝과 스트림 송신 지점에 걸치는 문자열.

### 6. 작전 2-1~2-5 (2026-10-11)

모두 같은 세션에서 이전 설치본과 새 설치본을 연달아 재서 판정했다.

- 2-1 한글 읽기: 0x80 이상의 바이트가 있는 문자열을 `utf8()`(검증 후 `new String(..., UTF_8)`)로 보내지 않고 `decodeChars`가 검증하면서 한 번에 `chars`로 디코드한다.
  `plainRun`이 확인한 ASCII 접두어는 검사 없이 `(char)`로 넓힌다(접두어 확장).
  3바이트 시퀀스는 `codePoint()`를 거치지 않는 빠른 경로로 읽는다.
  `readKoreanList`가 10.96 µs에서 5.64 µs(-49%)로 fastjson2(7.87 µs)를 앞선다.
  ASCII 접두어가 긴 문자열(70자 영어 뒤 한글)도 10.07 µs에서 9.15 µs로 빨라졌다.
  `utf8()`은 지웠다.
- 2-2 읽기 번들: 생성 코덱의 `read`가 선언 순서의 `nextKeyIs` 직선 체인을 먼저 돌고, 체인이 끊기면 지금의 switch 루프로 떨어진다(읽을 수 있는 속성 32개 이하).
  `keyIndex`는 새 공개 메서드 `JsonInput.keyHead()`(다음 멤버 자리의 4바이트)로 switch해 그 머리글을 가진 키만 `nextKeyIs`로 시도하고, 아무것도 맞지 않으면 예전처럼 `nextKey()`와 `keyIs`로 간다(64개 이하).
  머리글은 첫 멤버 형태(`"` + 3바이트)와 다음 멤버 형태(`,"` + 2바이트) 둘이고, 같은 머리글의 키는 한 case에서 선언 순서로 시도한다.
  이스케이프가 필요한 이름과 4바이트가 안 되는 형태는 머리글이 없다.
  `readList` 5.96 → 5.45 µs, `readReversedList` 9.47 → 8.03 µs, `readPlaces` 5.41 → 4.91 µs다.
  `nextKeyIs` 저장 축소는 넣지 않았다.
- 2-3 쓰기 번들: `put(JsonKey, String|long|double|float|boolean)`이 상태 검사 한 번, 키, 값으로 쓰고, 생성 코덱이 `out.put(K_x, expr)`를 내보낸다(원시 타입, 박싱 타입, `String`).
  `name(JsonKey)`와 `put`이 같은 `memberKey(key, after)`를 부르고, 첫 멤버와 다음 멤버 형태를 분기 없이 골라 호출 자리가 하나다.
  상태는 `ensure` 뒤에 바꾸고, double·float은 키를 쓰기 전에 `Json.finite`로 확인한다.
  `number(long)`은 0 이상 1000 미만을 `SMALL_NUMBERS` 표에서 `putInt` 하나로 쓴다.
  같은 세션의 기준과 2-3까지 적용한 설치본을 견주면 `writePlainList` 4.46 → 4.28 µs, `writeList` 4.73 → 4.50 µs다.
- 2-4 한글 쓰기: `rest()`의 3바이트 분기를 `LittleEndian.putInt` 하나로 쓴다.
  네 번째 바이트 때문에 `rest()`는 `(length - from) * 3 + 1`, `unicodeEscape`는 `7 + charactersLeft * 3`을 잡는다.
  `writeKoreanList`가 4.11 → 3.97 µs다.
  `rest()` 분할을 더한 변형은 3.95 µs로 더 얻는 것이 없어 기각했다(기각 목록).
- 2-5 긴 문자열: 64자 이상의 문자열은 `longString`이 `getChars` 뒤 `US_ASCII` 인코더(`encodeASCII` intrinsic)로 버퍼에 바로 좁히고, 쓴 바이트를 `LittleEndian.special`로 8바이트씩 검사한다.
  따옴표·역슬래시·제어 문자를 만나면 그 자리에서 이스케이프하고 다시 좁히고, ASCII 밖의 문자를 만나면 나머지를 `rest()`로 넘긴다.
  인코더와 `CharBuffer`·`ByteBuffer` 뷰는 출력마다 첫 긴 문자열에서 한 번 만든다.
  `writeLongStringList`가 15.03 → 8.60 µs(-43%)다.
  임시 벤치마크(같은 길이의 문자열 100개)로 잰 손익 분기는 약 40자다: 40자 2.44 → 2.38, 48자 2.66 → 2.66, 64자 3.29 → 3.03, 128자 5.97 → 4.44, 200자 8.35 → 5.64 µs.
  기준값은 여유를 두어 64자다.
  인코더는 작전이 견준 ISO_8859_1·UTF_8이 아니라 US_ASCII다: ASCII 밖의 첫 문자에서 정확히 멈추므로 Latin-1 바이트를 다시 검사할 필요가 없다.
  비용은 `writeList`의 +2~2.5%(4.49 → 4.56~4.60 µs, 세 번 연달아 잼)다.
  `string()`이 278에서 291바이트코드가 됐지만 인라인 결정은 `PrintInlining`으로 전후가 같았고, 원인은 확인하지 못했다.
- 측정판: `JsonBench`에 `readKoreanList`, `readReversedList`, `readLongStringList`, `writeLongStringList`를 두 라이브러리 모두 더했다.
  새 문서는 그 케이스만 쓰는 `@State`(`Document`)에서 만들고, 두 파서가 원래 레코드로 읽는지 먼저 확인한다.

### 7. 0단계 나머지, 1단계, 2-6, 남은 격차 (2026-10-11 오후)

모두 같은 세션에서 이전 설치본과 새 설치본을 두 번씩 번갈아 재서 판정했다.

- 측정판: `readPythonList`, `readPrettyList`, `readBigIdList`(`@Param` 13·18), `readShortStringList`, `writeOrders`·`readOrders`(enum), `writeList_stream`·`writeList_streamFastjson2`, fastjson2의 `creator=reflect` 짝을 더했다.
  새 문서는 모두 케이스 전용 `@State`(`Document`, `BigIds`)에서 만든다.
- 3단계 측정: `writeList_stream`은 3.68 µs·184 B로 `writeList_generated`(4.74 µs·10,696 B)보다 -1.06 µs·-10.5 KB다(기대 -0.6~-0.75 µs).
  fastjson2도 `JSON.writeTo`로 4.65 → 3.54 µs라 이 행의 비율은 1.04다.
  HTTP 할당은 위 표대로 요청당 -10.7 KB다.
- 1단계(격차 귀속, `writePlainList`, 기준 4.33 µs, fastjson2 3.69 µs): N=1(폐기 예정 `getBytes` 복사, 검사 없음) 2.71 µs, N=2(복사 + `special` SWAR 검사) 4.00 µs, N=3(`getBytes(UTF_8)` + SWAR + `arraycopy`) 4.37 µs.
  N=1이 fastjson2보다 1.0 µs 빠르므로 격차 전체가 문자열 표현의 몫이다.
  복사 뒤 검사가 1.29 µs(문자열당 약 6.5 ns)로 비싸다.
  N=2는 UTF-16 문자열을 자르므로 출시할 수 없고, N=3은 지금과 같아 기각했다.
- 역순 키(C): 체인이 있는 코덱의 switch 루프에서 다음 선언 키의 `nextKeyIs` 예측을 뺐다(`CHAIN_MAX` 초과 타입은 그대로 예측).
  `readReversedList` 8.16 → 7.28 µs(-11%), Python -5%, 들여쓰기 -8%, `readList`는 그대로다.
- 공백 문서(W, P): `nextKeyIs`가 실패한 자리의 바이트나 그다음 바이트가 공백이면 `spacedKeyIs`로 공백(쉼표 앞뒤)을 건너뛰고 첫 멤버 형태로 다시 비교한다.
  W로 들여쓰기 10.83 → 8.60 µs(-21%), Python 9.43 → 8.34 µs(-12%)이고, `, "` 모양을 `skipWhitespace` 없이 먼저 보는 P로 Python 8.37 → 6.59 µs(-21%)다.
  키와 콜론 사이의 공백(`"id" : 1`, Jackson pretty printer)과 입력 끝 16바이트 안은 예전처럼 `nextKey()`로 간다.
- 2-6 큰 정수(B2): 첫 워드가 8자리면 둘째 워드로 이어 읽고, 둘째도 8자리면 16자리를 시드로 기존 루프를 잇는다.
  18자리 6.50 → 5.89 µs(-9%), 13자리 6.14 → 5.87 µs(-4%)다.
  B1(둘째 워드만, 16자리 이상은 처음부터)은 18자리가 +3.6%였고, B3(첫 워드만 시드)은 18자리 6.12 µs였다.
  퍼즈 테스트(1~19자리, 부호, 선행 0, `.0`·`.5`·`e0`·`E1` 종결자, 2만 개)를 더했다.
- 2-6 0~1자 문자열(sc): `readString`의 평문 경로에서 길이 0이면 `""`, 1이면 `ASCII_STRINGS[b]`다.
  `readShortStringList` 3.41 → 2.80 µs(-18%), 13,936 → 5,536 B, 다른 읽기는 그대로다.
- 2-6 enum: `JsonKey.names(Enum[])`, `JsonOutput.put(JsonKey, JsonKey)`(키처럼 두 워드 저장, 콜론 뺀 길이), `JsonInput.readOneOf(JsonKey[])`(두 워드를 읽어 상수마다 `valueMask` 비교, 안 맞거나 입력 끝이면 `readString()` 뒤 이름 비교, 없으면 `JsonException`)을 새 공개 API로 더했다.
  생성 코덱은 enum 속성(직접이거나 `Optional` 안)마다 `C_x = E.values()`와 `V_x = JsonKey.names(C_x)`를 두고 `out.put(K_x, V_x[e.ordinal()])`, `C_x[in.readOneOf(V_x)]`로 쓰고 읽는다.
  `writeOrders` 2.92 → 2.40 µs(-18%), `readOrders` 4.61 → 3.03 µs(-34%)로 둘 다 fastjson2(2.70·3.12 µs)를 앞선다.
  어댑터가 붙은 속성과 컬렉션 원소는 예전처럼 `name()`·`valueOf`다.
  잘못된 상수 이름은 `valueOf`의 `IllegalArgumentException` 대신 `JsonException`(`Not a name this value takes: X`)이 된다.
- 긴 문자열(ls, LS2): `special` SWAR 대신 `String.indexOf('"')`·`indexOf('\\')`로 이스케이프 위치를 찾고 좁힌 바이트는 제어 문자만 SWAR(`(w - 0x20…) & 0x80…`)로 본다.
  `writeLongStringList` 8.9 → 7.1 µs(-20%)다.
  LS2는 이스케이프 뒤에 다시 좁히지 않고 이미 좁힌 뒷부분을 `arraycopy`로 1(또는 5)바이트 민다: 7.18 → 6.90 µs, 이스케이프 둘 있는 64자 100개 5.54 → 4.66 µs다.
  `ensure`가 스트림으로 보내거나 버퍼를 키우면 좁힌 뒷부분이 사라지므로, 버퍼나 위치가 바뀌면 다시 좁힌다(처음 구현에서 무작위 스트림 테스트가 잡았다).
- 기준 길이 T(임시 `LengthBench`, 같은 길이 문자열 100개, 이스케이프 있는 문자열로 먼저 훈련): 평문은 Temurin 25·21·GraalVM CE 25 JIT 모두 16자 근처부터 긴 경로가 이긴다(32자: 2.81 → 2.06, 2.40 → 2.13, 2.40 → 2.11 µs).
  이스케이프 둘인 문자열은 LS2를 얹고도 Temurin 25에서 32~48자, Graal에서 약 64자, Temurin 21(LS2 전)에서 64~96자부터 이긴다.
  `JsonBench`에서 T=32는 T=64와 같고, T=24·16은 26~28자 이스케이프 문자열 때문에 `writeList`가 4.8 → 6.5~7.1 µs다.
  그래서 64를 유지한다.
  작전의 "GraalVM JIT에서는 64자에서 뒤집히므로 128이 안전"은 맞지 않았다: 평문 64자에서 Graal도 긴 경로가 2배 빠르다.
- `writeList` +2~2.5% 회귀: 긴 문자열 분기를 뺀 빌드(p0)와 같은 세션에서 4.68 대 4.72 µs로 잡음 범위다.
  지금은 재현되지 않으므로 닫는다.

## 실패하거나 기각한 작업

### 설계

- `Class`에서 코덱으로 가는 레지스트리를 core에 두기: `json(Object)`에서 리플렉션만 빼고 이름으로 찾기는 남긴다.
- jackson-core 위에 코드 생성(avaje-jsonb 방식): 그 생성기는 같은 목록을 13.7 µs에 썼다.
- 생성 코덱 읽기에 `JsonKeys` 표 방식: 의존 로드가 많아 기각하고 `nextKeyIs` 상태 기계를 택했다.
- 검사 없이 쓰는 원시 출력 API: 목록 쓰기가 5% 빨라질 뿐이고, 코덱이 JSON이 아닌 문서를 쓸 수 있게 된다.

### 숫자

- double의 자릿수를 두 자리 표(digit pairs)로 쓰기: 장소 쓰기 10.0 µs, 워드 방식 9.3 µs.
- 숫자 자릿수를 8개씩 세기: 장소 읽기 5.7~6.1 µs, 바이트 루프 5.3 µs.
  두~여섯 자리 숫자는 워드가 본전을 찾기 전에 끝난다.

### 문자열 쓰기

- `String.getBytes(UTF_8)`: 문자열마다 배열을 할당하고 더 느렸다.
- 폐기 예정 `String.getBytes(srcBegin, srcEnd, dst, dstBegin)`로 버퍼에 바로 복사: 문자열당 약 3.3 ns로 빠르지만, Latin-1 밖의 문자를 잘라 낸 바이트와 원래 바이트를 구별할 수 없다.
  `hashCode` 비교는 충돌이 있어 정확하지 않고, `equals`로 확인하려면 문자열을 하나 더 만들어야 한다.
  공개 API로 String의 coder를 싸고 정확하게 알 길은 찾지 못했다.
- 4자·8자 펼친 루프: `getChars` + 단순 루프보다 느렸다.
- 분기 없는 패스로 문자열 전체를 좁혀 쓰면서 이스케이프 필요 여부만 산술로 누적하고, 필요하면 처음부터 다시 쓰기: `writeList` 9.24 µs, 이스케이프 없는 목록도 4.97 → 5.67 µs로 느려졌다(길이 문턱 12자를 둬도 8.00 µs).
  Temurin 25의 C2는 `char`→`byte`로 좁혀 쓰는 루프도, OR 누적도 벡터화하지 않는다.
  `-XX:-UseSuperWord`로 꺼도 수치가 같았다.
  분기 없는 루프는 일만 더 한다.
- 좁혀 쓰면서 `wide |= c`만 누적한 뒤, 버퍼의 바이트를 `LittleEndian.special`로 8바이트씩 검사: `writeList` 7.35 µs, 이스케이프 없는 목록 5.27 µs(기준 5.28·4.81).
- 이스케이프를 처리하는 바깥 루프 안에 평범한 문자 루프를 중첩하기(별도 메서드 `escaped`): `writeList` 5.55~5.64 µs로 오히려 느렸다.
  `rest()`로 넘기는 것과 같은 속도였고, 한 루프에서 오프셋만 미는 모양이 빨랐다.
- `rest()`의 `PLAIN[c]`를 비교 연산으로: 차이 없음.
- `c < 0x20 || c >= 0x80`을 `(char) (c - 0x20) >= 0x60` 하나로 합치기: `writeList` 4.73 → 5.18 µs로 느려졌다.

### 길이별 루프 모양 (임시 `LoopBench`, 문자열 16개 합계, ns)

| 루프 | 6자 | 25자 | 100자 |
|---|---|---|---|
| `getChars`만 | 33 | 31 | 44 |
| 폐기 예정 `getBytes`만 | | 53 | 52 |
| 좁혀 쓰기만(검사 없음) | 68 | 145 | 340 |
| 좁혀 쓰기 + `wide` 누적 | 79 | 164 | 404 |
| 조기 탈출, 비교 연산 | 100 | 212 | 530 |
| 조기 탈출, `PLAIN` 표 | 102 | 232 | 682 |
| 분기 없음, 128칸 표 | 105 | 219 | 1017 |
| 분기 없음, 산술 플래그 | 130 | 340 | 1174 |
| 좁혀 쓰기 + `wide` + SWAR 검사 | 148 | 291 | 727 |

검사하는 모양 가운데 조기 탈출 비교 루프가 가장 빨랐고, 검사 없는 바닥과의 차이도 25자에서 30% 정도다.

### 작전 검증에서 기각·보류

작전을 세우며 검증에서 기각하거나 보류한 후보다.

- `readLong` 분할: `PrintInlining`이 지목한 호출 경계는 실제였고 분할하면 인라인되지만, 두 프로브에서 readList가 0~+6% 느려졌다.
  직선 체인 안에서만 중립이다.
- `JsonKey` 레코드화와 키 워드 상수 오버로드: 효과 0~1%다.
  레코드는 18개 컴포넌트와 배열 접근자를 공개하고, 상수 오버로드는 검사 없는 API다.
  `-XX:+TrustFinalNonStaticFields`로 상한을 재는 데 몇 분이면 된다.
- `kinds` 스택의 지연 할당: 문서당 32바이트와 1~2 ns다.
  readMessage 벤치마크가 없어 22 ns 기준선도 미측정이다.
- `nextKeyIs` 저장 축소 단독: 0±1%다.
  2-2의 보조로만 남는다.
- FFM `MemorySegment.ofArray(buf).setString`: `String.value`를 확장 없이 복사하는 유일한 공개 경로지만 JDK 22 이상이다.
  JDK 21에서는 프리뷰라 릴리스 대상 21로는 쓸 수 없다.
  25자 -6%, 100자 -37%, 1000자 -51%다.
  multi-release jar의 `META-INF/versions/22`로 가능하므로 "JDK 22 기준으로 보류"로 적고, JDK 25에서 2-5와 나란히 한 행만 잰다.
- 3바이트 run 루프: 벤치마크 문자열 +26~+28%, 산문 +36~+47%다.
  음절 1~3개마다 끊기는 실제 텍스트에서 루프 진입 비용이 비교 절약보다 크다.
- 8글자를 `putLong` 셋으로 패킹: 4.9배 느리다.
  `char[]`를 워드로 읽는 공개 API가 없다.
- `plainRun` 4워드 언롤: 110 KB 문서에서 +5%다.
  데이터 의존 탈출이 있는 루프는 C2가 파이프라인하지 못한다.
- 문서 전체 ASCII 사전 검사: 10 KB에 0.5 µs를 쓰고 0.06 µs를 아낀다.
- 손실 복사의 사후 검증(coder 누출이나 `equals`): 25자에서 16~25 ns로 지금 루프보다 비싸다.
  `codePoints().spliterator().hasCharacteristics(SIZED)`는 문서화되지 않은 누출이라 쓰지 않는다.
- 짧은 문자열의 고정 비용 다듬기: 문자열당 0.3 ns 이하다.

### 작전 실행에서 기각 (2026-10-11)

- 2-1 접두어 길이 가드(ASCII 접두어 16바이트 미만만 `decode`, 나머지는 `utf8()`): 접두어 확장보다 모든 모양에서 같거나 느렸다(tail 8.09 대 6.85 µs, 긴 접두어 9.91 대 9.15 µs).
- 2-4 `rest()` 분할(1·2·3바이트만 다루는 뜨거운 루프와 `restSlow`): `putInt` 위에 얹어 `writeKoreanList` 3.97 → 3.95 µs로 잡음 범위였다.
- `string()`의 짧은 문자열 본문을 `shortString`으로 떼고 길이 분기만 남기기(`writeList` 회귀 변형): 4.77 대 4.82 µs, 다른 케이스도 ±1%라 기각했다.
- enum을 `out.put(K, e.name())`으로 융합(E1): 2.95 대 2.95 µs로 차이 없음.
- 1단계 N=3(`getBytes(UTF_8)` + SWAR + `arraycopy`): 4.37 대 4.33 µs.
- 2-6 큰 정수 B1(둘째 워드만)과 B3(첫 워드만 시드): 위 7절.
- 긴 문자열 기준 길이 16·24·32: 위 7절.

## fastjson2가 속도와 바꾼 것

fastjson2의 속도는 JDK 내부에 기대는 것과 입력을 너그럽게 받는 것에서 나오고, 그 값은 이식성·장래성·엄격성으로 치른다.
silk-json이 따라가지 않는 것은 이 목록이고, 격차의 남은 몫은 이 목록의 가격이다.
아래 동작은 2026-10-11에 fastjson2 2.0.57을 Temurin 25에서 기본 설정으로 돌려 확인했다(`JSON.parse`, `JSON.parseObject(doc, Item.class)`, `JSON.toJSONString`).
같은 입력을 silk-json은 모두 거부하거나 그대로 쓴다.

### JDK 내부에 기대는 것

- `sun.misc.Unsafe` 없이는 돌지 않는다.
  `--sun-misc-unsafe-memory-access=deny`에서 `JDKUtils.<clinit>`이 실패하고, 이후 모든 호출이 `Could not initialize class`다.
  `-Dfastjson2.creator=reflect`로도 같고, Unsafe 없는 모드는 없다.
- JDK 24 이상에서는 첫 호출에서 `sun.misc.Unsafe::arrayBaseOffset will be removed in a future release` 경고를 표준 오류에 찍는다.
  JEP 471·498이 정한 순서는 경고, 거부 기본값, 제거이고, 지금 기본값은 경고다.
  거부가 기본값이 되는 JDK에서는 실행 옵션을 하나 더 달아야 하고, 제거되는 JDK에서는 돌지 않는다.
- `Unsafe`로 훔친 `IMPL_LOOKUP`으로 `String(byte[], coder)` 생성자, `indexOfChar`, `countPositives` 같은 JDK 내부 메서드를 묶는다.
  이 경로는 JDK의 호환성 약속 밖이라, JDK가 내부 메서드의 이름이나 서명을 바꾸면 깨질 수 있다.
- VarHandle로 옮기는 2.1.x 브랜치는 2026년 2월 이후 멈춰 있다.
- 바인딩은 리플렉션과 실행 중 ASM 클래스 생성이다.
  타입마다 첫 호출에서 클래스를 만들어 올리므로, 그 비용은 첫 요청과 메타스페이스가 낸다.
- GraalVM 네이티브 이미지에서는 `imagecode` 속성을 보고 `String.value`·`coder` fast path와 ASM을 끄고 리플렉션 경로로 가며, 사용자 타입마다 reachability 메타데이터가 필요하다(소스에서 읽음, 미측정).
  JVM에서 잰 속도는 네이티브 이미지의 속도가 아니다.

### 입력을 너그럽게 받는 것

`JSON.parse`는 RFC 8259 밖의 문서를 기본값으로 받는다.

| 입력 | fastjson2 |
|---|---|
| `{'a':1}`, `{a:1}` | `{"a":1}` |
| `[1,2,]`, `{"a":1,}` | 끝 쉼표를 버린다 |
| `[01]`, `[+1]` | `[1]` |
| 따옴표 안의 날 탭 문자 | 받는다 |
| `[1 /* c */]` | `[1,null]`: 주석 자리에 `null` 원소가 생긴다 |
| 과잉 길이 UTF-8 `C0 80`, UTF-8로 쓴 서로게이트 `ED A0 80` | U+FFFD로 바꿔 받는다 |

마지막 행은 "한글 읽기: 검증 생략"의 결과다.
검증을 생략한 만큼 빠르지만, 잘못된 바이트가 오류가 아니라 다른 문자열이 된다.

### 레코드로 읽을 때 값을 바꾸는 것

`JSON.parseObject(doc, type)`는 맞지 않는 값을 오류 대신 다른 값으로 바꾼다.

| 문서 | 필드 | fastjson2 |
|---|---|---|
| `{"id":1.7}` | `long id` | 1: 소수부를 버린다 |
| `{"id":"12"}` | `long id` | 12: 문자열을 숫자로 읽는다 |
| `{"name":3}` | `String name` | `"3"` |
| `{"count":true}` | `int count` | 1 |
| `{"id":null}` | `long id` | 0 |
| 키 `name`이 없음 | `String name` | `null`, 오류 없음 |
| 키 `count`가 없음 | `int count` | 0, 오류 없음 |

silk-json의 생성 코덱은 위의 다섯 값을 `JsonException`으로 거부하고, 필수 속성(`@Nullable`도 `Optional`도 아닌 속성)이 빠진 문서는 `Missing key in JSON object`로 거부한다.
요청 본문을 읽는 서버에서는 이 차이가 검증 코드의 양이 된다.

### 쓸 때 값을 바꾸는 것

- `Double.NaN`과 무한대를 `null`로 쓴다.
  silk-json은 JSON에 문법이 없는 값이라며 `JsonException`을 던진다.
- 짝 없는 서로게이트(`"a\uD800b"`)를 `?`로 쓴다.
  읽어 들인 값이 쓴 값과 달라진다.
  silk-json은 `\ud800`으로 이스케이프해 같은 값으로 돌아오게 한다.

### 보안 이력

fastjson 1.x는 `@type`으로 클래스를 고르는 autoType 역직렬화에서 원격 코드 실행 취약점이 거듭 나왔다(2022년의 CVE-2022-25845가 그 하나다).
fastjson2는 autoType을 기본으로 끄지만 기능은 남아 있어, 켜는 설정 하나가 공격 면이 된다.
silk-json에는 문서가 타입을 고르는 경로가 없다.

## 남은 격차

2026-10-11 오후 기준으로 fastjson2가 앞서는 곳은 다섯이다: 긴 문자열 읽기 1.25배, 역순 키 읽기 1.19배, 이스케이프 없는 짧은 문자열 쓰기 1.12~1.16배, 들여쓴 문서 읽기 1.10배, 긴 문자열 쓰기 1.08배.
(오전에는 넷이었다: 긴 문자열 쓰기 1.39배, 역순 키 읽기 1.30배, 긴 문자열 읽기 1.26배, 짧은 문자열 쓰기 1.14~1.18배.)
아래는 2026-10-10에 짧은 문자열 쓰기 격차(당시 1.23~1.25배)를 분해한 기록이다.

- fastjson2는 `Unsafe`로 `String`의 Latin-1 바이트를 직접 읽고, 8바이트씩 SWAR로 이스케이프 여부를 본 뒤 바이트 배열을 통째로 복사한다.
  문자열당 약 5.5 ns다.
- 우리는 `getChars`로 넓혔다가 문자당 약 1사이클로 다시 좁힌다.
  25자 문자열 하나에 약 13 ns이고, 그중 `getChars`가 약 2 ns, 검사·축소 루프가 약 11 ns다(길이별 루프 모양 표의 25자 열을 16으로 나눈 값).

### 결론

- fastjson2의 우위 가운데 공개 API로 따라갈 수 없는 것은 `String.value`와 `coder`를 `Unsafe`로 직접 읽는 것 하나다.
  그 영향은 25자 이하 평문 문자열 쓰기에 집중되고, 한글 쓰기의 `getChars` 복사 0.75 µs도 같은 벽이다.
- 8바이트 비정렬 접근은 `byteArrayViewVarHandle`로 이미 동급이다.
- ASM 생성 코드 자체는 컴파일 타임 코덱과 같은 직선 코드다.
  ASM이 더 사주는 것 가운데 남은 하나는 키 첫 4바이트 lookupswitch로 하는 순서 무관 키 분기이고, 이것이 silk-json의 실제 약점이다.
- 한글 읽기, 순서가 어긋난 키, 긴 문자열의 격차는 Unsafe와 무관한 루프와 코덱 모양의 문제라서 공개 API로 닫을 수 있다.
  한글 읽기는 fastjson2를 넘어선다.
- fastjson2에는 Unsafe 없는 모드가 없다.
  `--sun-misc-unsafe-memory-access=deny`에서 `JDKUtils.<clinit>`가 실패하고 `-Dfastjson2.creator=reflect`로도 같다.

### fastjson2 속도의 분해

| 요소 | fastjson2 | silk-json | 판정 |
|---|---|---|---|
| `String.value`·`coder` 직접 읽기 | Latin-1 바이트를 SWAR 검사 후 memcpy, 문자열당 약 5.5 ns | `getChars` 2.0 ns + 검사·축소 루프 11 ns | 공개 API의 바닥이 13.8~16.5 ns로 지금과 같거나 비싸다. 25자 이하에서는 닫을 수 없다 |
| 비정렬 8/4/2바이트 로드·스토어 | `UNSAFE.getLong`·`putLong` | `LittleEndian`의 VarHandle, 같은 intrinsic에 `checkIndex` 하나 | 동급 |
| 훔친 `IMPL_LOOKUP`으로 묶은 핸들 | byte[]의 `indexOfChar`, `countPositives`, `String(byte[], coder)` | 공개 경로 없음 | 1000자 문자열 읽기 1.5배 차이의 원인이다. 공개 API로는 못 닫는다 |
| ASM: 키 워드 `ldc` 상수 | 즉시값 | `JsonKey` 필드 로드, L1 적중 | 효과 1% 이하로 측정, 기각 |
| ASM: `getRawInt` lookupswitch | 순서 무관 키 분기 | 선언 순서 `nextKeyIs`, 어긋나면 `scanKey` + 선형 `keyIs` | 역순 문서 +74%, 2-2의 후보 |
| ASM: 레코드 생성자 직접 호출 | public 레코드는 `invokespecial` | 생성 코덱도 직접 호출 | 동급 |
| 숫자 표, 버퍼 캐시, 마지막 `Arrays.copyOf` | 있음 | 있음 | 동급 |
| 한글 쓰기 | `UNSAFE.getChar`로 문자 하나씩, SWAR 없음 | `getChars` 후 문자 하나씩 | 루프는 동급이고 차이는 복사의 고정 비용이다 |
| 한글 읽기 | 선두 바이트로 건너뛰고 JDK 생성자가 디코드, 검증 생략 | 직접 검증 후 JDK 생성자가 다시 디코드, 배열 3개 할당 | 한 번만 디코드하면 앞선다 |

평문 쓰기 격차 0.83 µs 가운데 문자열 표현의 몫은 아직 둘로 갈린다.
리더는 전부라고 추정했고, 설계자는 25자 손실 복사 바닥과의 차이로 최대 0.4 µs라고 측정했다.
작전 1단계가 이것을 숫자로 결정한다.

### 프로파일 (async-profiler itimer, `ensure` 분리 전)

`writeList_generated` 4.7 µs: `JsonOutput.string` 36.2%, `jlong_disjoint_arraycopy`(`toBytes`의 결과 복사, fastjson2도 같음) 16.1%, `StringLatin1.getChars` 10.3%, `number` 6.2%, `ensure` 6.2%, `name` 5.2%.

`writeKoreanList_generated` 4.5 µs: `rest` 28.1%, `jlong_disjoint_arraycopy` 15.3%, `jshort_disjoint_arraycopy`(UTF-16 문자열의 `getChars`) 8.1%, `ensure` 6.8%, `string` 6.1%.

fastjson2 `writePlainList` 3.5 µs: `jlong_disjoint_arraycopy` 20.8%, `jbyte_disjoint_arraycopy` 12.1%, `StringUtils.escaped` 9.3%, `noneEscaped` 3.5%, `writeLatin1` 3.6%, `writeStringLatin1` 2.8%.
fastjson2의 한글 경로는 아직 프로파일하지 않았다.

## 작전 실행 결과 (2026-10-11)

| 단계 | 상태 | 결과 |
|---|---|---|
| 0. 측정판 확장 | 적용 | 오후에 Python 구분자·들여쓰기·큰 id·0~1자 문자열·enum 문서, `writeList_stream`, `creator=reflect` 짝, `-prof gc` 전체 실행, Temurin 21 실행을 마쳤다(7절) |
| 1. 격차 귀속 측정 | 적용 | N=1 2.71 µs가 fastjson2 3.69 µs보다 빨라 평문 격차 전체가 문자열 표현의 몫이다 |
| 2-1 한글 읽기 | 적용 | 10.96 → 5.64 µs, 예상(7.0~7.4)보다 컸다 |
| 2-2 읽기 번들 | 적용 | `readList` -9%, 역순 -15%, 직선 체인 쟁점(-8%와 -13%)은 -9% 쪽이다 |
| 2-3 쓰기 번들 | 적용 | 평문 -4~-6%, 예상(-2~-8%) 안이다 |
| 2-4 한글 쓰기 | `putInt` 적용 | -3.5%, 분할은 기각 |
| 2-5 긴 문자열 | 적용, T = 64 | -43%, `writeList` +2~2.5% |
| 2-6 보조 후보 | 적용 | 큰 정수 두 워드(18자리 -9%), 0~1자 문자열 표(-18%, -8.4 KB), enum 이름 키(쓰기 -18%, 읽기 -34%). 한 바이트 미리 읽기는 W·P가 공백 문서를 대신 닫아 하지 않았다 |
| 3. 빌린 버퍼 본문 | 적용, 측정 | `writeList_stream` -1.06 µs·-10.5 KB, HTTP `json-list` 요청당 할당 -10.7 KB(Jetty 14.4 → 3.8 KB, Tomcat 13.2 → 2.6 KB) |
| 4. 문서 | 적용 | 부록 수치를 2026-10-11 전체 실행으로 바꾸고 fastjson2의 실행 조건을 더했다. 결정 76에 2-1~2-5와 기각 둘 |

미해결 쟁점 가운데 셋은 닫혔다: 한글 쓰기는 `putInt` 단독, 한글 읽기는 접두어 확장, 긴 문자열의 인코더는 US_ASCII다.
역순 읽기의 남은 격차 약 1.9 µs는 작전이 예상한 대로, 멤버마다 실패하는 선언 순서 `nextKeyIs`와 `keyIndex` 호출이다.

## 다음 작전

다음 작전은 남은 격차를 닫기 위한 실험 순서이고, 제약은 "제약" 절과 같다.
이전의 "다음 후보" 다섯은 검증을 거쳐 이렇게 됐다.

- 후보 1(한글 `rest()`에서 0x800 이상의 비서로게이트 문자가 이어지는 동안 3바이트씩 쓰는 안쪽 루프)은 측정에서 손해로 끝났고 2-4가 대신한다.
- 후보 2(`"item-1"` 같은 6~8자 문자열의 고정 비용: `chars` 길이 검사, `getChars`의 경계 검사와 intrinsic 진입, `ensure`, 따옴표)는 문자열당 0.3 ns 이하로 닫혔다.
- 후보 3(긴 문자열에 `CharsetEncoder`의 intrinsic 빌리기)은 2-5다.
- 후보 4(문자열 밖의 몫: `number`, `name`, `beforeValue`, `end`)는 1단계와 2-3이다.
- 후보 5(새 JDK)는 그대로 남는다.
  C2가 `char`→`byte` 좁히기를 벡터화하게 되면 분기 없는 루프의 판단이 바뀐다.
  새 JDK에서 `-XX:-UseSuperWord`와의 비교만 다시 한다.

### 목표 수치

검증을 통과한 후보를 모두 적용했을 때 검증자가 수정한 예상치다.
신설 케이스와 fastjson2 역순 수치는 JMH가 아닌 프로브 값이다.

| 케이스 | 지금 | 예상 | fastjson2 |
|---|---|---|---|
| writePlainList | 4.39 µs | 4.05~4.3 µs | 3.56 µs |
| writeKoreanList | 4.42 µs | 4.2~4.3 µs | 3.54 µs |
| readList | 6.1 µs | 5.4~5.6 µs | 6.2 µs |
| readKoreanList (신설) | 11.1~11.3 µs | 7.0~7.4 µs | 7.9~8.0 µs |
| readReversedList (신설) | 8.0 µs | 6.5~6.7 µs | 약 4.5 µs |
| 200자 설명 100건 쓰기 (신설) | 13~14 µs | 9.5~11 µs | 격차 1.15~1.2배 |
| HTTP json-list 응답당 복사 | 10 KB | 0 | 해당 없음 |

## 작전 순서

### 0. 측정판 확장

지금의 벤치마크는 선언 순서로 압축해 쓴 ASCII 문서라는 silk-json의 최적 모양 하나만 잰다.
읽기 모양별 상대 프로브(배터리 전원, 비율만 유효)에서 silk-json 대 fastjson2는 다음과 같았다.

| 문서 모양 | silk / fastjson2 |
|---|---|
| 선언 순서, 압축 | 1.05 |
| 한글 문자열 | 1.40 |
| 100자 설명 | 1.18 |
| 1000자 설명 | 1.52 |
| 키 역순 | 1.81 |
| Python `json.dumps` 구분자 | 1.49~1.64 |
| 들여쓰기 | 1.44 |
| 27바이트 객체 | 0.61 |

할 일은 다음과 같다.

- `JsonBench`에 두 라이브러리 모두의 `readKoreanList`, `readReversedList`, `readPythonList`, `readPrettyList`, `readLongStringList`(100자·1000자), `writeLongStringList`, `readBigIdList`(13자리와 18자리), `writeList_stream`(`nullOutputStream`)을 더한다.
  문서는 `@Setup`에서 silk-json이나 손으로 쓴 writer로 한 번 만들고, 두 파서의 결과가 같은지 먼저 확인한다.
- fastjson2 쪽에만 `@Fork(jvmArgsAppend = "-Dfastjson2.creator=reflect")`인 readList·writeList 짝을 더한다.
- fastjson2의 쓰기 수치가 ASM writer에서 나왔는지 `-verbose:class`로 `OWG_*_Item` 로드를 확인한다.
  읽기 쪽은 `ORG_1_5_Item`으로 확인됐다.
- `-prof gc`의 `gc.alloc.rate.norm`을 두 라이브러리 모두 기록한다.
  지금까지 쓰기 쪽 할당 비교가 없다.
- 모든 수치가 Temurin 25에서 나왔으므로 대상인 Temurin 21에서 writePlainList·writeKoreanList·readList를 한 번 돈다.
  JDK 21에는 Unsafe 경고가 없다.
- deny 모드 확인은 벤치마크가 아니라 이 기록의 명령 한 줄로 남긴다.

공수는 반나절에서 하루다.

### 1. 격차 귀속 측정 (C1)

평문 쓰기 격차 0.83 µs 가운데 얼마가 문자열 표현의 몫인지 벤치마크 전용 변형으로 잰다.
`JsonOutput.string()`에 `-Dsilk.variant`로 고르는 변형 셋을 넣고 `writePlainList_fastjson2`와 같은 세션에서 연달아 돌린다.

- N=1, 바닥: `ensure(length + 2)` 뒤 폐기 예정 `s.getBytes(0, length, buf, pos + 1)`로 복사만 하고 검사하지 않는다.
  평문 목록은 이스케이프가 없어 출력이 유효하다.
- N=2, 현실적 바닥: N=1에 `LittleEndian.special`로 복사한 바이트를 8바이트씩 검사하고, 걸리면 기존 루프로 다시 쓴다.
- N=3, 출시 가능 후보: `s.getBytes(UTF_8)`로 받은 배열을 SWAR 검사 후 `arraycopy`한다.
  "문자열 쓰기"의 기각 목록은 "할당하고 더 느렸다"고 적었지만, 지금 남은 문자열당 4 ns의 여지와 비교한 수치는 없다.

판정 기준은 다음과 같다.

- N=1이 fastjson2의 0.2 µs 안에 들면 격차 전체가 문자열 표현의 몫이다.
  부록의 귀속 문장은 이 수치로 쓴다.
- N=1이 3.9 µs 이상이면 문자열 밖의 성분이 있다.
  2-3 쓰기 번들의 우선순위가 올라간다.
- N=2에서 N=1을 뺀 값이 복사 뒤 검사의 비용이다.
  `MemorySegment.setString` 같은 미래의 공개 경로도 이 비용을 낸다.
- N=3이 N=2 근처면 출시 가능한 경로가 된다.

N=1과 N=2는 UTF-16 문자열에서 틀린 바이트를 쓰므로 한글 케이스에는 돌리지 않고, 측정 뒤 지운다.

### 2. 라이브러리 변경

효과가 큰 순서다.
각 항목은 `-Dsilk.variant` 변형으로 넣고, 이전 설치본과 새 설치본을 연달아 같은 케이스로 두 번 재서 판정한다.

**2-1. 한글 읽기, 한 번만 디코드.**

`readStringSlow`에서 바이트가 0x80 이상인 문자열을 `utf8()` 대신 `decode()`로 보낸다.
`decodeChars`가 검증하면서 재사용 `char[]`에 쓰고 `new String(chars, 0, n)`으로 끝낸다.
지금은 직접 검증한 뒤 `new String(in, start, len, UTF_8)`가 다시 검증·디코드하며 배열 셋을 할당한다.
실제 코덱 측정(P코어, AC)에서 한글 100건 읽기가 11.1~11.3 µs에서 7.0~7.4 µs로 -36%이고, 같은 세션의 fastjson2는 7.9~8.0 µs다.
할당은 문자열당 배열 3개에서 2개로, 문서당 34.0 KB에서 23.1 KB로 준다.
Latin-1 악센트 문자열 -43%, 혼합 문자 -31%, 이모지 -26%도 함께 빨라진다.
ASCII 접두어가 긴 문자열은 느려진다(짧은 tail +22~25%, 300자 접두어 +117%).
그래서 다음 둘 중 하나를 tail(`"The description of item N 설명"`)과 typo(영문에 U+2019 하나) 모양으로 측정해 고른다.

- 접두어 길이 가드: `at - start < 16`이면 `decode(start)`로, 아니면 `utf8(start, at)`로 보낸다.
  8·16·24를 훑는다.
- 접두어 확장: `plainRun`이 확인한 `[start, at)`을 `chars[n++] = (char) in[p]` 루프로 넓힌 뒤 `at`부터 검증 루프를 돈다.
  `decodeChars(start, at)` 서명이 필요하고 `key()`는 `at = keyStart`를 넘긴다.
  이 넓히기 루프를 C2가 벡터화하는지는 미확인이다(비평가의 프로브에서는 SuperWord가 오히려 느렸다).

3바이트 int 로드 fast path는 벤치마크 문자열에서 100건당 0.08~0.11 µs라 보조로만 두고, 긴 한글 산문 케이스에서만 -11~-12%다.
`chars`를 생성자에서 64로 미리 잡지 않는다.
ASCII 문서마다 144바이트를 헛되이 할당한다.
테스트는 잘린 `E4 B8`, `E4 41 41`, 입력 끝의 3바이트 시퀀스, 닫는 따옴표 없는 `EA B0 80`을 `refusesMalformedUtf8`에 더한다.
공수는 몇 시간이다.

**2-2. 읽기 번들: 직선 체인과 키 머리글 switch.**

둘은 `keyIndex`와 인라인 예산을 공유하므로 따로 잰 수치의 합을 믿지 말고 묶어서 한 번 더 잰다.

- 직선 체인: `CodecSource`가 switch 루프 앞에 선언 순서의 `nextKeyIs` 체인을 내보낸다.
  체인이 끊기면 `key = keyIndex(in)`으로 지금의 루프에 떨어진다.
  새 JsonInput API는 두지 않는다(`endOfObject`는 `keyIndex`와 1% 안에서 같았다).
  플래그 속성은 체인 안에서도 `s_x = true`를 둔다.
  읽을 수 있는 속성이 32개 이하일 때만 내보낸다(넓은 타입은 8000바이트코드의 컴파일 한계에 닿는다).
  JMH readList 문서(이스케이프 포함)에서 비율 0.885~0.91이라 readList 6.1 µs가 5.4~5.6 µs가 되고, readPlaces는 같은 절대값만큼 준다.
- 키 머리글 switch: `JsonInput.keyHead()`가 `pos`의 4바이트를 `LittleEndian.getInt`로 읽기만 하고 돌려준다(`kind != OBJECT`면 예외).
  프로세서는 이스케이프가 필요하지 않은 이름(제어문자·`"`·`\`·서로게이트 없음)에 대해 첫 형태 `"` + 3바이트와 다음 형태 `,"` + 2바이트의 int 상수를 계산한다.
  머리글 값이 같은 키는 한 `case`로 묶어 선언 순서로 `nextKeyIs`를 시도한다(`description`과 `desc`는 같은 case).
  일치 확인은 지금처럼 `nextKeyIs`의 전체 바이트 비교다.
  읽을 수 있는 속성 64개 이하만 switch를 내보낸다.
  역순 문서 8.0 µs가 6.5~6.7 µs가 되고, 선언 순서 문서는 `}`에서 default를 한 번 지나 0~+1.3%로 잡음 범위다.
  공백이 있는 문서(Python 기본값, 들여쓰기)는 이 형태로는 이득이 없다.
- `nextKeyIs` 저장 축소: 적중 시 `keyStart`·`keyEnd`·`keyEscaped`·`keyAscii` 넷 대신 `matched = key` 하나를 저장한다.
  단독으로 0±1%라 번들 안에서만 측정하고, 20키 넓은 레코드 케이스에서 양방향 2% 이상일 때만 남긴다.

남는 역순 격차 약 2 µs는 멤버마다 실패하는 선언 순서 `nextKeyIs`와 인라인되지 않는 확인 호출이다.
테스트는 프로세서 테스트로 `"id"` 다음 형태의 상수 `0x6469222c`를 확인하고, 역순·혼합 순서·미지 키·이스케이프 키 문서로 생성 코덱을 돈다.
공수는 하루다.

**2-3. 쓰기 번들: 융합한 `put`.**

`put(JsonKey, long|String|boolean|double|float)`를 `name(key).value(v)`가 아니라 상태 검사 한 번, 키 저장, 값 쓰기로 다시 구현하고 `CodecSource`가 `out.put(K_x, expr)`을 내보낸다.
`NAMED` 전이가 사라지고 `member()` 호출 자리가 하나가 된다.
상태 저장은 첫 멤버에서 ensure 뒤에 한다(ensure가 던지면 `OBJECT|FIRST`가 유지돼 콤마가 새지 않는다).
double·float은 키를 쓰기 전에 `Json.finite`를 확인한다.
`put(String, ...)` 계열은 그대로 둔다.
변형 1은 상태 융합만, 변형 2는 ensure까지 융합하고, 변형 2는 변형 1보다 0.1 µs 이상 더 나올 때만 유지한다(프로브는 0.05 µs).
변형은 빌드 시점에 고른다.
`put()` 안의 `Integer.getInteger` 분기는 인라인을 막아 비교를 왜곡한다.
`PrintInlining`으로 `put(JsonKey, String)`과 `put(JsonKey, long)`이 생성 코덱의 `write`에 인라인되는지 확인한다.
기대는 writePlainList 4.39 µs가 4.05~4.3 µs(-2~-8%)이고, writeList·writeKoreanList도 같은 절대값만큼이다.
같은 빌드에 다음 둘을 얹되 따로 판정하지 않는다.

- `name(JsonKey)`의 `member()` 호출 자리 하나로 합치기: 첫 키 분기는 빈도 1/5라 35바이트 한계가 적용돼 63바이트 `member()`가 인라인되지 않는다.
  `next = current == OBJECT`로 필드를 고르고 한 자리에서 부른다.
  `words()` 분기를 빼는 모양은 47바이트로 여전히 한계를 넘어 효과가 0이다.
  0.05~0.2 µs다.
- 1000 미만 int 표: `number(long)`에서 `0 <= v < 1000`이면 1000칸 int 표의 세 자리 ASCII와 자릿수를 `LittleEndian.putInt` 하나로 쓴다.
  네 번째 바이트는 `ensure(20)` 안의 쓰레기다.
  `value(int)` 오버로드는 두지 않는다.
  0.08~0.15 µs다.

판정은 번들 합이 두 번 연속 0.3 µs를 넘지 못하면 얹은 둘을 버리는 것이다.
공수는 하루다.

**2-4. 한글 쓰기: `putInt` 대 `rest()` 분할.**

검증자 둘이 반대 결과를 냈다.
한쪽은 3바이트 분기를 `putInt` 하나로 바꾸는 것만 -1.0~-5.6%이고 분할의 증분은 0±0.1 µs였다.
다른 쪽은 `putInt`가 0이고 분할이 문자열 쓰기의 -4~-13%였다.
두 변형을 실제 코덱 모양으로 같은 세션에서 재고 이기는 쪽만 남긴다.

- `putInt`: `(c >> 12) | ((c << 2) & 0x3F00) | ((c << 16) & 0x3F0000) | 0x8080E0`을 `LittleEndian.putInt`로 쓰고 `pos += 3`한다.
  네 번째 바이트 때문에 `rest()`의 `ensure((length - from) * 3 + 1)`과 `unicodeEscape`의 `7 + charactersLeft * 3`이 필요하다.
  버퍼 끝에 정확히 9바이트가 남는 테스트를 더한다.
- 분할: 516바이트코드라 인라인되지 않는 `rest()`를 1·2·3바이트 분기만 가진 210바이트코드 이하의 뜨거운 루프와, 이스케이프·서로게이트를 처리하는 `restSlow`로 나눈다.

어느 쪽이든 writeKoreanList는 4.42 µs에서 4.2~4.3 µs 안이고 비율은 1.25에서 1.20이 상한이다.
지난 후보 1(3바이트 run 루프)은 측정에서 +26~+47% 손해로 끝났다.
남는 0.75 µs는 `getChars` 복사의 고정 비용이라 Unsafe 없이는 닫을 수 없다.
비평가가 남긴 숙제가 하나 있다: `rest()`가 같은 분기 구조인 JDK `UTF_8.Encoder.encodeArrayLoopSlow`보다 문자당 1.5~2배 느린 이유를 바이트코드와 경계 검사로 한 번 비교한다.
공수는 반나절이다.

**2-5. 긴 문자열 2-pass.**

길이가 T 이상인 문자열은 별도 메서드 `longString`에서 처리한다.
`CharsetEncoder.encode(CharBuffer, ByteBuffer, false)`로 `chars`를 버퍼에 바로 좁힌다(배열 기반 버퍼라 `implEncodeISOArray`·`implEncodeAsciiArray` intrinsic에 닿는다).
`s.indexOf('"')`와 `s.indexOf('\\')`(둘 다 `indexOfChar` intrinsic)로 이스케이프 유무를 보고, `((w - 0x2020...) | w) & 0x8080...`의 SWAR로 제어문자와 0x80 이상을 본다.
걸리면 정확한 첫 위치에서 기존 루프를 이어 쓴다(처음부터 다시 쓰지 않는다).
지금 루프 대비 64자 -37%, 100자 -28%, 256자 -53%, 1000자 -54%다(프로브).
200자 설명 100건 쓰기는 13~14 µs에서 -2.7~-4.3 µs다.
기존 케이스의 문자열은 최대 28자라 효과가 0이고, `string()`이 325바이트코드 아래로 남아 인라인을 유지하는 것이 그 조건이다.

- T는 48·64·96을 훑는다.
  HotSpot의 손익 분기는 48~64자이고 GraalVM JIT에서는 64자에서 뒤집히므로 128이 안전한 기본값이다.
- 인코더는 ISO_8859_1과 UTF_8 중 검증자가 갈렸다.
  다른 인코더를 먼저 거친 오염 상태(`encodeLoop`의 타입 프로파일이 흐려진 상태)를 `@Setup`에 넣고 한글·혼합 긴 문자열 행까지 포함해 결정한다.
- JsonOutput당 한 번 208~232바이트(인코더와 버퍼 둘)를 할당한다.
  0 B/op를 원하면 KEPT 옆에 스레드별로 둔다.
- break-loop(커밋 `474dc47` 이전의 루프 모양)을 T 이상에서만 쓰는 변형을 대조군으로 함께 잰다.
  별도 정적 메서드 `plainRun`으로만 넣는다.
  `string()` 안에 넣으면 346바이트코드가 되어 모든 기존 케이스가 +4~7% 느려진다.
- 벤치마크 포크는 이스케이프 있는 문자열로 먼저 훈련시킨다.
  평문만 돈 포크는 두 모양을 같게 컴파일해 차이가 0으로 읽힌다.

fastjson2 대비 1000자에서는 동급이지 우위가 아니다.
복사와 축소를 `Unsafe`가 아낀 만큼이 검사의 이득을 먹는다.
공수는 하루다.

**2-6. 보조 후보.**

각자의 벤치마크 행이 있을 때만 잰다.

- 8~15자리 정수: `readLong`에서 첫 워드가 8자리일 때 둘째 워드로 이어 읽는다.
  13자리 타임스탬프 100개에 0.4~0.7 µs다.
  18~19자리는 이득이 없다.
  더 단순한 변형, 즉 첫 워드의 8자리를 기존 루프의 시드로 쓰는 것을 함께 잰다.
  1~19자리, 음수, 선행 0, `.`·`e`·`E` 종결자의 퍼즈 테스트를 더한다.
- 0~1자 문자열 표: `readString`의 평문 경로에서 `n <= 1`이면 `""`나 `String[128]` 표를 돌려준다.
  문자열당 5~8 ns와 48바이트 할당이다.
  결과는 `-prof gc`의 할당 감소로 보고한다.
- enum 상수의 바이트 사전 계산: 프로세서가 상수마다 키처럼 8바이트 워드 짝을 내보내 `ordinal()` switch로 두 `putLong`으로 쓴다.
  fastjson2의 UTF-8 경로는 `writeString(name)`이라 silk-json이 구조적으로 앞설 수 있는 유일한 쓰기 항목이다.
  enum 필드 케이스를 먼저 만든다.
- 한 바이트 미리 읽기: fastjson2는 현재 바이트를 `ch` 필드에 들고 공백을 미리 건너뛴다.
  공백 많은 문서(Python 기본값, 들여쓰기)의 격차에 관련되고 아직 비용을 재지 않았다.

### 3. 빌린 버퍼 본문 (C2)

목표는 HTTP 경로에서 JSON 본문의 마지막 `Arrays.copyOf`를 없애는 것이다.
쓰기 프로파일의 `jlong_disjoint_arraycopy` 16%가 이 복사다.
사용자가 2026-10-10에 core의 `WebResponse.Bytes` 변경을 승인했다.

설계자 원안(`Bytes`에 길이와 반납을 더해 핸들러 시점에 빌린 버퍼를 담기)은 검증에서 기각됐다.
`WebResponse`는 공개 불변 값이라 상수로 두거나 필터가 들고 있을 수 있고, 반납된 배열이 KEPT로 돌아간 뒤에는 그 응답이 다른 요청의 JSON을 보게 된다.
검증자 둘이 권한 형태는 버퍼가 `AppServlet` 밖으로 나가지 않는 지연 본문이다.

- silk-json: `JsonOutput`에 `int length()`, `void writeTo(OutputStream)`, `void release()`를 더한다.
  `release()`는 지금 `toBytes()` 안에 있는 KEPT 반납이고, 반납 뒤의 `writeTo`·`length`는 `IllegalStateException`을 던진다.
- core: 값과 writer를 들고 있는 sealed `Body`의 새 종류를 둔다.
  `WebResponse.json(value, writer)`와 `json(JsonValue)`가 이것을 만든다.
  `AppServlet`은 `Template`을 `Text`로 렌더링하는 자리(dispatch 안, 예외 처리 범위)에서 KEPT 버퍼로 materialize하고, 쓰기 단계에서 Content-Length를 놓고 `write(buf, 0, length)`한 뒤 finally에서 `release()`한다.
  HEAD는 길이만 놓고 반납한다.
  재사용된 응답은 다시 직렬화할 뿐이다.
- gzip과 테스트는 `toBytes()` 복사본으로 지금처럼 동작한다.
  결정 75가 "테스트가 확인할 것이 없다"고 기각한 스트림 본문과 다른 점이 이것이다.
- 바뀌는 문서와 테스트는 `manual/modules/ROOT/pages/response.adoc`과 `manual-ko`의 같은 페이지, `skills/spider-silk/references/testing.md`의 `((WebResponse.Bytes) response.body()).data()` 패턴, `ReadmeSnippets`, `ApiControllerTest`, `WebResponseTest`, `ResponseBodiesTest`다.
- `notes/decisions.md`에 결정 75의 Rejected 줄에 답하는 항목을 더한다.

기대 효과와 측정은 다음과 같다.

- JMH `writeList_stream` 행에서 -0.6~-0.75 µs와 -10.3 KB/op다.
  fastjson2도 `JSON.writeTo`로 같은 만큼 줄어 이 행의 비율은 1.23에서 1.30으로 오른다.
  라이브러리의 이득이 아니라 프레임워크의 이득으로 적는다.
- HTTP json-list에서는 요청당 CPU의 1.5%라 처리량 편차 12% 안에 묻힌다.
  결정적 지표는 요청당 할당 10.5 KB 감소다.
- 가상 스레드에서는 KEPT 재사용이 없어 이득이 0에 가깝다.

공수는 하루 반에서 이틀이다.

2026-10-11 구현은 위 형태를 따르되 두 가지가 다르다.

- materialize 자리는 `dispatch` 안의 템플릿 자리 여럿이 아니라, 모든 필터가 답한 뒤의 `renderJson` 한 곳이다.
  필터는 직렬화 전의 값과 writer를 보고, 필터가 바꾼 본문은 직렬화되지 않는다.
  writer의 실패는 `handleException`과 `completeErrorResponse`로 가고, 응답 필터는 건너뛴다.
- gzip은 `toBytes()` 복사본이 아니라 서블릿의 버퍼에서 deflate한다.
  복사본으로 하면 압축되는 문서를 두 번 직렬화한다.

### 4. 문서 (C9)

부록 `benchmark.adoc`의 마이크로벤치 절에 다음을 더한다.
영문과 `manual-ko`에 같은 커밋으로 넣는다.

- fastjson2 2.0.57은 `--sun-misc-unsafe-memory-access=deny`에서 초기화되지 않는다는 행.
  `JDKUtils.<clinit>`의 `arrayBaseOffset`에서 실패하고 이후 모든 호출이 `NoClassDefFoundError`다.
  `-Dfastjson2.creator=reflect`로도 같다.
- JDK 24 이상은 첫 호출에서 종료 예정 경고를 찍는다는 문장.
- GraalVM 네이티브 이미지에서 fastjson2는 `imagecode` 속성을 보고 `String.value`·`coder` fast path와 ASM을 끄고 반사 경로로 가며, 사용자 타입의 reachability 메타데이터가 필요하다는 문장.
  "소스에서 읽음, 미측정"으로 표시한다.
  silk-json도 네이티브 이미지에서 측정되지 않았으므로 더 빠르다고 적지 않는다.
- JEP 471·498의 상태: JDK 26·27 GA 소스와 master의 기본값은 warn이고, deny 기본값과 제거를 정한 JEP는 아직 없다.
  fastjson2의 VarHandle 전환 브랜치(2.1.x)는 2026년 2월 이후 멈춰 있다.

선택 사항으로 json-bench를 네이티브 이미지로 빌드해 두 라이브러리의 다섯 케이스를 잰다.
"미측정"을 숫자로 바꾸는 별도 작업이다.

## 미해결 쟁점

- 평문 쓰기 격차에서 문자열 표현의 몫: 2026-10-11 오후 1단계로 닫혔다.
  검사 없는 복사가 fastjson2보다 1.0 µs 빠르므로 격차 전체가 문자열 표현의 몫이다.
- 한글 쓰기의 기전: `putInt` 단독인지 `rest()` 분할인지 검증자가 반대로 측정했다.
  2-4가 결정한다.
- 한글 읽기의 접두어 처리: 길이 가드인지 접두어 확장인지 검증자가 갈렸다.
  2-1이 tail·typo 모양으로 결정한다.
- 긴 문자열 2-pass의 인코더: ISO_8859_1인지 UTF_8인지 한글 긴 문자열에서 부호가 반대로 나왔다.
  2-5가 오염 상태를 포함해 결정한다.
- 직선 체인의 크기: -8%와 -13% 사이다.
  2-2의 JMH가 결정한다.

## 측정 방법과 함정

- 측정 전에 AC 전원인지 본다(`cat /sys/class/power_supply/AC/online`이 `1`).
  배터리에서는 모든 라이브러리가 10~15% 느려지고 오차가 세 배가 된다.
  플랫폼 프로파일은 `performance`다.
- 빌드: `cd benchmark && ../gradlew -p json-bench installDist && ../gradlew --stop`.
  설치본을 scratchpad로 복사해 두고 돌리면 측정 중에 소스를 고쳐도 섞이지 않는다.
- 실행: `taskset -c 0-3 json-bench/build/install/json-bench/bin/json-bench -jvmArgs "-Xms1g -Xmx1g" -prof gc 'JsonBench.write'`.
- 긴 실행 사이에 기계가 2~3% 흔들린다(fastjson2·Jackson도 같이 움직인다).
  전후 비교는 이전 설치본과 새 설치본을 연달아 같은 케이스로 돌려 판단한다.
- 오차가 ±0.1~0.2 µs이므로, 0.3 µs보다 작은 차이는 두 번 재서 판단한다.
- 변형 여러 개는 `static final int VARIANT = Integer.getInteger("silk.variant", ...)`로 두고 `-Dsilk.variant=N`으로 고르면 빌드 한 번으로 비교된다(C2가 상수로 접는다).
  테스트에 넘기려면 `silk-json/build.gradle`에 임시로 `test { systemProperty ... }`를 두고 끝나면 지운다.
- 변형 선택은 메서드 몸체 크기를 바꾸므로, 인라인 경계에 있는 메서드(`string()` 278바이트코드, `put`)의 변형은 런타임 분기가 아니라 빌드를 나눠 비교한다.
- 번들로 측정한 후보는 번들로 판정한다.
  따로 잰 수치의 합을 쓰지 않는다.
- 긴 문자열 벤치마크의 포크는 이스케이프 있는 문자열로 먼저 훈련시킨다.
  평문만 돈 포크는 두 루프 모양을 같은 코드로 컴파일한다.
- `CharsetEncoder`를 쓰는 변형은 다른 인코더를 먼저 거친 오염 상태를 `@Setup`에 넣어 함께 잰다.
  `encodeLoop`의 타입 프로파일은 프로세스에 하나다.
- 프로브(nanoTime 루프)의 절대값은 JMH보다 20~25% 낮고 전원·거버너 상태에 따라 2배까지 흔들린다.
  비율만 옮기고 절대값은 JMH로 다시 잰다.
- 루프 모양은 임시 JMH 클래스를 `benchmark/json-bench/src/main/java/benchmark/tmp/`에 두고 길이별로 잰 뒤 지운다.
- perf는 `perf_event_paranoid`가 4라 못 쓰고, hsdis가 없어 어셈블리도 못 본다.
  벡터화 여부는 `-XX:-UseSuperWord`와 수치를 비교해 판단한다.
- 프로파일: IntelliJ에 든 async-profiler를 itimer로 쓴다.
  `-prof "async:libPath=$HOME/.local/share/JetBrains/Toolbox/apps/intellij-idea-community-edition/lib/async-profiler/amd64/libasyncProfiler.so;event=itimer;interval=100000;output=text;dir=<scratchpad>"`, `-jvmArgs "-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints"`, `-f 1`.
  `output=flat=N`은 이 버전에서 받지 않는다.
- 인라인 여부는 `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining`으로 본다.
  111바이트 `ensure`가 "too big"으로 인라인되지 않던 것을 이렇게 찾았다.
- 결과는 부록 `benchmark.adoc`(영문·한국어)의 마이크로벤치 절과 결정 76에 한두 줄로 남기고, 기각한 시도는 `Rejected:` 줄로 남긴다.
- `@Setup`에서 다른 케이스의 문서를 쓰면 그 문자열로 `string()`·`rest()`의 프로파일이 훈련돼 다른 케이스가 바뀐다.
  2026-10-11에 한글·200자 문서를 공통 `@Setup`에서 만들자 `writePlainList`의 두 포크가 4.7과 5.1 µs로 갈렸다.
  새 문서는 그 케이스만 쓰는 `@State`에서 만든다.
- 측정 전에 `powerprofilesctl get`도 본다.
  2026-10-11에는 `balanced`로 돌아가 있었다.
- 명령줄의 `-jvmArgsAppend`는 `@Fork(jvmArgsAppend = ...)`를 덮어쓴다.
  `-verbose:class`를 명령줄로 주면 `creator=reflect` 케이스도 ASM으로 돌았으므로, 애너테이션의 인자를 함께 적어야 한다.
- 다른 JVM은 `-jvm ~/.sdkman/candidates/java/<id>/bin/java`로 고른다(Temurin 21.0.8, GraalVM CE 25.2.4의 JIT).
- `LengthBench`는 Temurin 25의 짧은 문자열 루프가 이스케이프로 훈련된 뒤 JDK 21보다 크게 느린 것을 보였다(64자 7.07 대 4.20 µs).
  64자 이상은 긴 경로가 가져가므로 실제 문서에는 닿지 않지만, 새 JDK에서 짧은 루프를 다시 잴 때 기억할 것이다.
- HTTP 요청당 할당은 앱을 `-Xlog:gc:file=...`로 띄우고 워밍업 뒤 `jcmd <pid> GC.run`으로 경계를 긋고, wrk 30초 뒤 다시 `GC.run`해 GC 줄의 `before - 이전 after` 합을 요청 수로 나눈다(스크립트는 세션 scratchpad의 `alloc.sh`).
  이 기계의 awk는 mawk라 `match`의 세 번째 인자가 없으므로 perl로 셌다.
- `pkill -f`와 `pgrep -f`의 패턴이 그 명령을 실행하는 셸의 명령줄에도 들어 있으면 셸 자신을 죽인다(exit 144).
  `[S]piderSilkBenchmark`처럼 대괄호를 넣는다.

## 작전이 나온 과정

2026-10-10에 에이전트 워크플로로 만들었다.
리더 5명이 fastjson2 2.0.57 소스, JDK 25 소스, silk-json을 읽어 사실 184개를 모았다.
설계자 5명이 후보 42개와 불가능 항목 24개를 냈고, 손으로 합쳐 21개를 검증에 넣었다.
후보마다 정확성·성능·설계 렌즈의 반박자가 붙어 16개가 살고 5개가 기각됐다.
설계자가 스스로 측정으로 기각한 7개는 비평가가 기각 이유만 재확인했다.
처음 8개 후보의 판정은 Fable이 프로브를 컴파일해 재측정하며 냈고, 나머지는 Opus medium이 설계 검토 수준으로 냈다.
프로브 코드는 세션 scratchpad에만 있어 남지 않으므로, 수치는 이 문서가 기록이다.
