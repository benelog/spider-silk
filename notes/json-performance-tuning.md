# JSON 성능 튜닝 기록

silk-json의 쓰기·읽기를 fastjson2 수준으로 끌어올린 작업의 기록이다: 무엇이 통했고, 무엇이 통하지 않았고, 무엇이 남았는가.
근거는 결정 75·76(`notes/decisions.md`), 부록 `benchmark.adoc`, 커밋 `49e3f5a`·`68f5478`·`a64dd2d`·`474dc47`이다.

## 제약

- `sun.misc.Unsafe`는 쓰지 않는다. 리플렉션도, 새 의존성도 없다(`AGENTS.md`).
- 8바이트 접근은 `LittleEndian`(`MethodHandles.byteArrayViewVarHandle`)으로 한다. 네이티브 이미지에서 설정 없이 돈다.
- 문자열 루프에서 `String.charAt`을 쓰지 않는다. `charAt`의 프로파일은 프로세스 전체에 하나라서, Latin-1 밖의 문자열이 한 번 지나가면 그것을 부르는 모든 루프가 느리게 컴파일된다.
- 출력은 유효한 JSON이어야 한다. 검사를 건너뛰는 API는 들이지 않는다.

## 지금 수치 (2026-10-10, 커밋 `474dc47`)

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

- 트리를 `StringBuilder`에 문자 단위로 채워 `String`을 만들고 서블릿이 다시 인코딩하던 경로를 없앴다. 트리를 한 번, UTF-8로 바이트 배열에 쓰고 그 배열이 응답 바디가 된다.
- 10 KB 바디가 114 KB 할당과 24 µs(Jackson 440 B·9 µs)이던 것을 줄였다. HTTP `json-list`는 Spring MVC 대비 0.6배에서 Jetty 1.05배, Tomcat 1.02배가 됐다.
- `JsonObject`는 `LinkedHashMap` 대신 배열 둘(16개 넘으면 인덱스)이다. `put(key, "x")`는 `String`을 그대로 담아, 필드 다섯 개 레코드의 할당이 열두 개 안팎에서 세 개로 줄었다.
- 문자열은 `getChars`로 한 번 복사해 그 배열에서 검사한다(`charAt` 금지의 시작).
- 한 문서에서 되풀이되는 키는 한 번 인코딩하고 그 뒤로는 복사한다.
- 플랫폼 스레드는 64 KB까지의 버퍼를 `ThreadLocal`의 `byte[]`로 다시 쓴다. 가상 스레드는 버퍼를 남기지 않는다.

### 2. 바이트 엔진과 생성 코덱 (결정 76, 커밋 `49e3f5a`)

- 모든 문서가 `JsonOutput`(바이트 생성기)과 `JsonInput`(바이트 풀 파서)을 지나고, 매핑은 그 위의 람다이거나 `silk-json-processor`가 컴파일 때 만든 코덱이다.
- 트리를 거치던 레코드 100개 목록이 쓰기 12.6 µs·29.8 KB, 읽기 25.5 µs·82 KB에서 쓰기 5.2 µs·11 KB, 읽기 6.0 µs·18 KB가 됐다. 27바이트 객체는 25 ns(Jackson 133 ns)다.
- `JsonKey.of(name)`가 쉼표가 있는 형태와 없는 형태의 바이트를 들고 있어, 생성 코덱은 레코드마다 키를 이스케이프하지 않는다. 키는 두 워드를 저장 두 번으로 쓴다.
- 생성 코덱의 읽기는 선언 순서의 다음 키를 `nextKeyIs`로 `,"key":` 통째로 제자리 비교한다. 순서 밖의 키만 `keyIndex` 헬퍼로 간다.

### 3. 8바이트씩 다루기 (`LittleEndian`, 결정 76)

- 문자열 읽기는 8바이트 SWAR로 따옴표·역슬래시·제어 문자·0x80 이상을 찾는다(`LittleEndian.special`).
- 7자리 이하 정수는 SWAR로 자릿수를 세고 곱셈 세 번으로 변환한다.
- 이 방식으로 목록 읽기가 13.0 µs에서 6.0 µs(fastjson2 동급), 쓰기가 7.0 µs에서 5.2 µs가 됐다.
- 이스케이프가 있는 문자열 읽기는 `byte[]`에 구간을 복사한 뒤 폐기 예정 `new String(bytes, 0, off, len)`(Latin-1)으로 만든다. 인라인돼서 더 빨랐다.
- 키 스캔은 바이트 루프로 둔다. 짧은 키에서는 SWAR보다 빨랐다.
- 컨테이너 상태는 배열 대신 필드에 둔다. C2가 레지스터에 둔다.
- 문자열 키 캐시는 4칸 선형 탐사다. `"name"`과 `"quantity"`가 같은 칸에 떨어져 레코드마다 서로를 밀어내던 문제를 고쳤다.

### 4. 소수의 읽기와 쓰기 (커밋 `a64dd2d`)

- `readDouble`: 유효 숫자가 2^53 이하이고 10의 지수가 -22~22이면 곱셈이나 나눗셈 한 번(Clinger), 19자리까지의 나머지는 Eisel-Lemire의 128비트 곱, 그 밖과 반올림이 정해지지 않는 경우만 `Double.parseDouble`로 간다.
  장소 100개 읽기가 12.6 µs·43.9 KB에서 5.3 µs·11.1 KB가 됐다(fastjson2 5.2 µs).
- `readFloat`는 자릿수에서 한 번만 반올림한다. `(float) readDouble()`은 두 번 반올림해 다른 float에 떨어질 수 있다.
- double 쓰기: `Double.toString`(JDK 19+)과 같은 알고리즘인 Schubfach로 가장 짧은 십진수를 찾고, 자릿수를 8개씩 워드로 버퍼에 늘어놓는다.
  장소 100개 쓰기가 16.5 µs·35.6 KB에서 9.3 µs·8.5 KB가 됐다(fastjson2 9.4 µs).
- `value(float)`는 `Float.toString`의 텍스트를 쓴다. 넓힌 double로 쓰면 `0.1f`가 `0.10000000149011612`가 된다.

### 5. 문자열 쓰기의 이스케이프와 검사 (커밋 `474dc47`)

- 따옴표와 역슬래시를 평범한 ASCII를 쓰는 루프 안에서 2바이트로 쓰고, 쓰기 위치 `at`을 한 칸 미룬다. 그 전에는 첫 따옴표에서 나머지 전부가 `rest()`로 갔다.
  공간 검사는 이스케이프 분기에서만 하고, 모자라면 남은 문자가 전부 이스케이프되는 최악을 잡는다. 이스케이프 없는 문자열의 버퍼 크기 동작은 그대로다.
- 문자 검사는 `PLAIN[c]` 표 대신 비교 연산(`c >= 0x80 || c < 0x20 || c == '"' || c == '\\'`)이다. 문자마다의 로드가 빠진다.
- `ensure`는 공간 검사만 남기고, 늘리는 일은 `makeRoom`으로 뺐다. 111바이트라 인라인되지 않던 호출(6~7%)이 사라졌다.
- 이전 빌드와 연달아 잰 결과: `writeList` 5.28 → 4.71 µs(-11%), `writePlainList` 4.82 → 4.39 µs(-9%), `writeKoreanList` 4.55 → 4.42 µs(-3%).
- 정확성은 `JsonOutputTest`의 세 테스트가 지킨다: 참조 구현과의 무작위 비교 2만 개(값·키·스트림), 0~65자 길이 경계에 특수 문자를 모든 자리에, 버퍼 끝과 스트림 송신 지점에 걸치는 문자열.

## 실패하거나 기각한 작업

### 설계

- `Class`에서 코덱으로 가는 레지스트리를 core에 두기: `json(Object)`에서 리플렉션만 빼고 이름으로 찾기는 남긴다.
- jackson-core 위에 코드 생성(avaje-jsonb 방식): 그 생성기는 같은 목록을 13.7 µs에 썼다.
- 생성 코덱 읽기에 `JsonKeys` 표 방식: 의존 로드가 많아 기각하고 `nextKeyIs` 상태 기계를 택했다.
- 검사 없이 쓰는 원시 출력 API: 목록 쓰기가 5% 빨라질 뿐이고, 코덱이 JSON이 아닌 문서를 쓸 수 있게 된다.

### 숫자

- double의 자릿수를 두 자리 표(digit pairs)로 쓰기: 장소 쓰기 10.0 µs, 워드 방식 9.3 µs.
- 숫자 자릿수를 8개씩 세기: 장소 읽기 5.7~6.1 µs, 바이트 루프 5.3 µs. 두~여섯 자리 숫자는 워드가 본전을 찾기 전에 끝난다.

### 문자열 쓰기

- `String.getBytes(UTF_8)`: 문자열마다 배열을 할당하고 더 느렸다.
- 폐기 예정 `String.getBytes(srcBegin, srcEnd, dst, dstBegin)`로 버퍼에 바로 복사: 문자열당 약 3.3 ns로 빠르지만, Latin-1 밖의 문자를 잘라 낸 바이트와 원래 바이트를 구별할 수 없다.
  `hashCode` 비교는 충돌이 있어 정확하지 않고, `equals`로 확인하려면 문자열을 하나 더 만들어야 한다. 공개 API로 String의 coder를 싸고 정확하게 알 길은 찾지 못했다.
- 4자·8자 펼친 루프: `getChars` + 단순 루프보다 느렸다.
- 분기 없는 패스로 문자열 전체를 좁혀 쓰면서 이스케이프 필요 여부만 산술로 누적하고, 필요하면 처음부터 다시 쓰기: `writeList` 9.24 µs, 이스케이프 없는 목록도 4.97 → 5.67 µs로 느려졌다(길이 문턱 12자를 둬도 8.00 µs).
  Temurin 25의 C2는 `char`→`byte`로 좁혀 쓰는 루프도, OR 누적도 벡터화하지 않는다. `-XX:-UseSuperWord`로 꺼도 수치가 같았다. 분기 없는 루프는 일만 더 한다.
- 좁혀 쓰면서 `wide |= c`만 누적한 뒤, 버퍼의 바이트를 `LittleEndian.special`로 8바이트씩 검사: `writeList` 7.35 µs, 이스케이프 없는 목록 5.27 µs(기준 5.28·4.81).
- 이스케이프를 처리하는 바깥 루프 안에 평범한 문자 루프를 중첩하기(별도 메서드 `escaped`): `writeList` 5.55~5.64 µs로 오히려 느렸다. `rest()`로 넘기는 것과 같은 속도였고, 한 루프에서 오프셋만 미는 모양이 빨랐다.
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

## 남은 격차

이스케이프 없는 문자열과 한글 문자열에서 fastjson2가 1.23~1.25배 앞선다.

- fastjson2는 `Unsafe`로 `String`의 Latin-1 바이트를 직접 읽고, 8바이트씩 SWAR로 이스케이프 여부를 본 뒤 바이트 배열을 통째로 복사한다. 문자열당 약 5.5 ns다.
- 우리는 `getChars`로 넓혔다가 문자당 약 1사이클로 다시 좁힌다. 문자열당 약 11 ns이고, 그중 `getChars`가 2~2.5 ns다.

### 프로파일 (async-profiler itimer, `ensure` 분리 전)

`writeList_generated` 4.7 µs: `JsonOutput.string` 36.2%, `jlong_disjoint_arraycopy`(`toBytes`의 결과 복사, fastjson2도 같음) 16.1%, `StringLatin1.getChars` 10.3%, `number` 6.2%, `ensure` 6.2%, `name` 5.2%.

`writeKoreanList_generated` 4.5 µs: `rest` 28.1%, `jlong_disjoint_arraycopy` 15.3%, `jshort_disjoint_arraycopy`(UTF-16 문자열의 `getChars`) 8.1%, `ensure` 6.8%, `string` 6.1%.

fastjson2 `writePlainList` 3.5 µs: `jlong_disjoint_arraycopy` 20.8%, `jbyte_disjoint_arraycopy` 12.1%, `StringUtils.escaped` 9.3%, `noneEscaped` 3.5%, `writeLatin1` 3.6%, `writeStringLatin1` 2.8%.
fastjson2의 한글 경로는 아직 프로파일하지 않았다.

### 다음 후보

1. **한글 경로(`rest()`)**: 한글 음절은 모두 3바이트이고 서로게이트가 아니다. 0x800 이상의 비서로게이트 문자가 이어지는 동안 3바이트씩 쓰는 안쪽 루프로 문자마다의 분기 사슬을 줄여 본다. 먼저 fastjson2의 한글 경로를 프로파일한다.
2. **짧은 문자열의 고정 비용**: `"item-1"` 같은 6~8자 문자열은 루프보다 고정 비용(`chars` 길이 검사, `getChars`의 경계 검사와 intrinsic 진입, `ensure`, 따옴표)이 크다. 문자열 하나만 쓰는 임시 벤치로 고정 비용과 문자당 비용을 나눠 잰다.
3. **긴 문자열에 JDK intrinsic 빌리기**: `CharsetEncoder.encode(CharBuffer, ByteBuffer, boolean)`은 배열 기반 버퍼에서 `encodeISOArray`·`encodeAsciiArray` 같은 벡터화 intrinsic으로 `char[]`를 좁힌다. 호출 경로가 길어 짧은 문자열에는 손해일 것이므로, 버퍼와 인코더를 재사용하고 길이 문턱을 두고 잰다. 이 벤치의 문자열은 최대 27자라 긴 문자열 케이스가 따로 필요하다.
4. **문자열 밖의 몫**: `number`, `name`, `beforeValue`, `end`를 fastjson2와 나란히 프로파일해 본다.
5. **새 JDK**: C2가 `char`→`byte` 좁히기를 벡터화하게 되면 분기 없는 루프의 판단이 바뀐다. 새 JDK에서 `-XX:-UseSuperWord`와의 비교만 다시 한다.

FFM(`MemorySegment`)은 JDK 21에서 프리뷰라 릴리스 대상 21로는 쓸 수 없다.

## 측정 방법과 함정

- 측정 전에 AC 전원인지 본다(`cat /sys/class/power_supply/AC/online`이 `1`). 배터리에서는 모든 라이브러리가 10~15% 느려지고 오차가 세 배가 된다. 플랫폼 프로파일은 `performance`다.
- 빌드: `cd benchmark && ../gradlew -p json-bench installDist && ../gradlew --stop`. 설치본을 scratchpad로 복사해 두고 돌리면 측정 중에 소스를 고쳐도 섞이지 않는다.
- 실행: `taskset -c 0-3 json-bench/build/install/json-bench/bin/json-bench -jvmArgs "-Xms1g -Xmx1g" -prof gc 'JsonBench.write'`.
- 긴 실행 사이에 기계가 2~3% 흔들린다(fastjson2·Jackson도 같이 움직인다). 전후 비교는 이전 설치본과 새 설치본을 연달아 같은 케이스로 돌려 판단한다.
- 오차가 ±0.1~0.2 µs이므로, 0.3 µs보다 작은 차이는 두 번 재서 판단한다.
- 변형 여러 개는 `static final int VARIANT = Integer.getInteger("silk.variant", ...)`로 두고 `-Dsilk.variant=N`으로 고르면 빌드 한 번으로 비교된다(C2가 상수로 접는다). 테스트에 넘기려면 `silk-json/build.gradle`에 임시로 `test { systemProperty ... }`를 두고 끝나면 지운다.
- 루프 모양은 임시 JMH 클래스를 `benchmark/json-bench/src/main/java/benchmark/tmp/`에 두고 길이별로 잰 뒤 지운다.
- perf는 `perf_event_paranoid`가 4라 못 쓰고, hsdis가 없어 어셈블리도 못 본다. 벡터화 여부는 `-XX:-UseSuperWord`와 수치를 비교해 판단한다.
- 프로파일: IntelliJ에 든 async-profiler를 itimer로 쓴다. `-prof "async:libPath=$HOME/.local/share/JetBrains/Toolbox/apps/intellij-idea-community-edition/lib/async-profiler/amd64/libasyncProfiler.so;event=itimer;interval=100000;output=text;dir=<scratchpad>"`, `-jvmArgs "-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints"`, `-f 1`. `output=flat=N`은 이 버전에서 받지 않는다.
- 인라인 여부는 `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining`으로 본다. 111바이트 `ensure`가 "too big"으로 인라인되지 않던 것을 이렇게 찾았다.
- 결과는 부록 `benchmark.adoc`(영문·한국어)의 마이크로벤치 절과 결정 76에 한두 줄로 남기고, 기각한 시도는 `Rejected:` 줄로 남긴다.
