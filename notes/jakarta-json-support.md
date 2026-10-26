# JSON-B·JSON-P 지원 가능성 조사

silk-json이 Jakarta JSON Binding(JSON-B)과 Jakarta JSON Processing(JSON-P)을 어디까지 지원할 수 있는지 조사한 기록이다.
결론은 둘이 다르다: JSON-B의 완전한 지원(TCK 통과)은 리플렉션 없이 불가능하고, JSON-P는 원칙을 지키면서 TCK 통과까지 노릴 수 있다.
core API의 `JsonValue`와 `JsonSink`는 JSON-P로 바꾸지 않고, JSON-P 트리는 `silk-json-p`의 `JsonWriter`와 `JsonReader`로 주고받는다("core API에 JSON-P를 쓸 수 있는가").
Jackson은 `spider-silk-jackson` 모듈로 같은 자리에 붙었고, 쓰기는 `JsonOutput` 위의 `JsonGenerator` 구현으로 한다("Jackson을 같은 방식으로 붙일 수 있는가").
근거는 `AGENTS.md`의 설계 원칙, 결정 76·77(`notes/decisions.md`), 2026-10-10 시점의 `silk-json`과 `silk-json-processor` 코드이다.
다른 라이브러리가 두 스펙을 어떻게 지원하는지는 2026-10-13에 조사해 마지막 절에 적었다.
JSON-P는 2026-10-23에 `silk-json-jsonp`로 구현했고, JSON-P TCK 2.1.1의 178개 테스트를 통과한다("구현 결과", 결정 80).

## 전제가 되는 원칙

- core와 silk-json에는 리플렉션, 어노테이션 스캐닝, 프록시, 자동 바인딩이 없다.
- silk-json은 실행 시 JDK 외에 아무것에도 의존하지 않는다.
- 생성 코드는 생성자와 접근자만 부른다: 레지스트리도, `ServiceLoader`도, 실행 시 이름으로 찾는 것도 없다.
- `Class`에서 codec으로 가는 레지스트리는 결정 76이 거부했다.

## JSON-B

### 원칙을 지키면 불가능한 것

JSON-B API의 일부는 시그니처나 기본 동작 자체가 리플렉션을 전제한다.

- **어노테이션 없는 임의 객체의 기본 매핑**: `Jsonb.toJson(Object)`와 `fromJson(String, Type)`은 컴파일 시점에 모르는 클래스도 public 필드와 getter/setter 규칙으로 매핑해야 한다.
  TCK도 대부분 어노테이션 없는 POJO로 이것을 검사한다.
  프로세서는 `@JsonBound`가 붙은 타입만 본다.
- **`PropertyVisibilityStrategy`**: 메서드가 `isVisible(java.lang.reflect.Field)`와 `isVisible(java.lang.reflect.Method)`라서 API 시그니처 자체가 리플렉션이다.
  `@JsonbVisibility`를 컴파일 에러로 거부하는 이유이다.
- **`JsonbBuilder.create()`**: API jar의 `JsonbProvider.provider()`가 `ServiceLoader`를 쓰고, 찾지 못하면 `Class.forName`으로 넘어간다.
- **`JsonbConfig.withAdapters(...)`와 `withSerializers(...)`**: 실행 시 등록한 어댑터가 어느 타입에 적용되는지 알려면 `getGenericInterfaces()`로 타입 인자를 읽어야 한다.
- **`Class`로 codec을 찾는 디스패치**: `toJson(obj)`가 `obj.getClass()`로 codec을 찾는 것은 엄밀히 리플렉션은 아니지만, 결정 76이 거부한 레지스트리이다.

### 가능하지만 silk-json의 선택과 충돌하는 것

- **프로퍼티 순서**: JSON-B의 기본값은 사전순이고, silk-json은 선언 순서를 택했다(결정 76).
- **누락된 프로퍼티**: JSON-B는 null이나 기본값으로 두고, silk-json은 `@Nullable`이나 `Optional`이 아니면 필수로 다룬다(결정 34).
- **실행 시 `JsonbConfig`**: naming strategy, null 처리, formatting, locale, binary 전략을 생성 코드는 컴파일 시점에 고정한다.
  실행 시 설정을 따르려면 codec이 매번 config를 참조해야 하고, 성능 이점이 일부 줄어든다.
- **JSON-P 의존**: `JsonbSerializer`와 `JsonbDeserializer`는 `jakarta.json.stream.JsonGenerator`와 `JsonParser`를 받는다.
  JSON-P 구현이 먼저 있어야 하고, silk-json의 의존 원칙을 지키려면 별도 모듈이어야 한다.

### 원칙을 지키면서 더 할 수 있는 것

현재 컴파일 에러로 거부하는 항목 상당수는 컴파일 시점에 풀 수 있다.

| 항목 | 방법 |
|---|---|
| `@JsonbNumberFormat` | `DecimalFormat`은 JDK에 있으므로 생성 코드가 부르면 된다 |
| `@JsonbDateFormat(TIME_IN_MILLIS)` | 단순한 변환이다 |
| `@JsonbTypeInfo`, `@JsonbSubtype` | 서브타입 목록이 어노테이션에 있으므로 분기 코드를 생성한다. 결정 77이 남은 일로 적어 두었다 |
| 제네릭 바운드 타입 | 타입 인자의 codec을 받는 codec을 생성한다. 지금은 "A generic type cannot be bound"로 거부한다 |
| `@JsonbTypeSerializer`, `@JsonbTypeDeserializer` | `JsonOutput`과 `JsonInput` 위에 JSON-P 어댑터를 얹은 별도 모듈로 가능하다 |
| `Jsonb` 파사드 | `@JsonBound` 타입만 받고 나머지는 `JsonbException`으로 거부한다. `JsonbBuilder`를 거치지 않고 직접 생성한다 |

### 정리

목표는 "JSON-B 호환 구현체"가 아니라 "JSON-B 어노테이션 어휘를 컴파일 시점에 해석하는 라이브러리"이다.
avaje-jsonb도 같은 이유로 스펙 준수를 표방하지 않는다.
TCK 통과가 꼭 필요하다면 리플렉션 fallback을 silk-json 밖의 opt-in 모듈로 두는 길뿐이고, 이는 원칙의 예외를 공식화하는 결정이다.
Micronaut Serialization이 바로 이 길을 택했다(아래 "생태계의 사례").

## JSON-P

### 가능한 이유

JSON-P는 객체를 클래스에 바인딩하지 않는다.
파서, 생성기, 트리, Pointer·Patch만 다루므로 리플렉션이 끼어들 곳이 없다.

### 별도 모듈이어야 하는 이유

- **의존성**: `jakarta.json.spi.JsonProvider`와 `jakarta.json.JsonObject` 같은 인터페이스를 구현하려면 `jakarta.json-api`에 의존해야 한다.
  silk-json은 JDK만 의존하므로 `silk-json-p` 같은 모듈이 silk-json과 API에 의존한다.
- **부트스트랩**: `Json.createParser(...)`는 API jar의 `JsonProvider.provider()`를 거쳐 `ServiceLoader`로 구현체를 찾는다.
  모듈은 `META-INF/services` 파일을 제공할 뿐 `ServiceLoader`를 직접 부르지 않는다.
  `new SilkJsonProvider()`로 직접 만드는 경로도 함께 연다.
  GraalVM native image가 services 파일을 자동으로 등록하는지는 확인이 필요하다.

### 현재 엔진과의 간극

| JSON-P가 요구하는 것 | 현재 silk-json | 필요한 작업 |
|---|---|---|
| `JsonParser.next()`가 다음 이벤트를 알려 준다 | `JsonInput`은 호출자가 `object()`, `readString()`처럼 기대하는 모양을 먼저 말한다 | 다음 토큰의 종류를 보는 peek를 추가한다. `skipValue()`가 이미 첫 바이트로 분기하므로 작은 일이다 |
| `InputStream`과 `Reader`의 스트리밍 파싱 | `JsonInput.of(byte[])`, 문서 전체가 메모리에 있다 | 처음에는 전부 읽어 들인다. 진짜 스트리밍은 버퍼 재충전이 필요하고, 8바이트씩 읽도록 튜닝한 hot path를 건드린다 |
| UTF-16·32 자동 감지, `Reader` 입력 | UTF-8 전용 | 입구에서 UTF-8로 변환한다 |
| `JsonLocation`(줄, 열, offset) | 줄을 추적하지 않는다 | 위치를 물을 때 문서 앞부분을 다시 훑어 계산한다 |
| `JsonObject extends Map<String, JsonValue>`, 불변, `JsonNumber`는 `BigDecimal` 의미 | 자체 트리 `JsonObject`, `JsonArray`, `JsonPrimitive`(sealed)이고, silk-json은 jakarta 인터페이스를 구현할 수 없다 | JSON-P 모듈에 별도 트리 구현이나 뷰를 둔다. 숫자는 `readNumber()`에서 `BigDecimal`로 만든다 |
| `JsonGenerator`의 `Writer` 출력과 pretty printing | `JsonOutput`은 API 모양이 거의 같지만(`object(key)`, `end()`, `BigDecimal` 등) UTF-8 `OutputStream` 전용이고 `newline()`만 있다 | 들여쓰기와 `Writer` 출력 경로를 추가한다 |
| `JsonPointer`, `JsonPatch`, `JsonMergePatch`, `JsonCollectors`, `getArrayStream()` 등, 2.1의 중복 키 전략 | 없다 | RFC 구현이라 원칙과 무관하지만 분량이 상당하다 |

### 기대할 수 있는 것과 없는 것

- **성능**: JSON-P API는 구조상 이벤트 루프, `getString()`의 할당, `Map` 기반 트리라는 비용을 진다.
  생성 codec만큼의 속도는 기대하기 어렵고, 파서 계층에서 Parsson보다 빠를 여지는 있다.
- **연결 효과**: JSON-P가 있으면 `@JsonbTypeSerializer`와 `@JsonbTypeDeserializer` 지원의 바탕이 생긴다.
  결정 77이 "JSON-P and JSON-B runtimes"를 남은 일로 적어 둔 것과 맞는다.

### 제안하는 순서

1. `JsonInput`에 토큰 종류 peek를 추가한다. 작고 본체에도 쓸모가 있다.
2. `silk-json-p` 모듈을 만든다. 파서는 메모리에 전부 읽는 방식으로 시작하고, `JsonGenerator`는 `JsonOutput` 위에 얹는다.
   JSON-P 트리용 `JsonWriter`와 `JsonReader`도 이 모듈에 둔다(아래 "core API에 JSON-P를 쓸 수 있는가").
3. JSON-P TCK를 돌려 실패 목록으로 범위를 정한다.
4. 진짜 스트리밍 입력은 벤치마크로 hot path 영향을 확인한 뒤 따로 결정한다.
   2026-10-23에 `JsonInput`을 건드리지 않는 모듈 안의 스트리밍으로 했다("구현 결과").

### 구현 결과

2026-10-23에 1~3단계를 마쳤다.

- **이름**: 가칭 `silk-json-p` 대신 `silk-json-jsonp`이다.
  빌드 규칙상 프로젝트 이름이 패키지가 되므로, `silk-json-p`의 패키지는 `net.benelog.silkjson.p`가 된다.
  `JsonP`도 `JacksonCodecs`에 맞춰 `JsonpCodecs`로 했다.
- **silk-json의 변경**: `JsonInput.peek()`와 `JsonInput.Kind`, 그리고 `JsonInput.position()`뿐이다.
  위치는 바이트 인덱스이고, 파서가 물을 때 마지막으로 계산한 자리부터 이어 세어 줄과 열을 만든다.
- **TCK**: 모듈의 `tck` 태스크가 `jakarta.json-tck-tests` 2.1.1을 `check`에서 돌린다.
  시그니처 테스트를 뺀 23개 스위트 178개 테스트가 통과한다.
  처음 실행의 실패는 넷이었다: 파서는 생성 시점에 스트림의 I/O 오류와 판별할 수 없는 인코딩을 `JsonException`으로 던져야 하고, `createValue(Number)`는 `Short`, `Float`, 임의의 `Number`도 받아야 하며, 포인터의 `~0`·`~1`이 아닌 물결표는 오류가 아니어야 했다.
  리더는 반대로 `read()` 시점에 스트림을 읽어야 했다.
- **스펙이 열어 둔 동작**은 Parsson을 따랐다.
  pretty printing은 Parsson의 배치를 압축된 바이트 위의 필터로 만든다.
  double은 `Double.toString`대로 쓴다.
- **`JsonpCodecs.object(fn)`**: 트리의 접근자는 JSON-P가 정한 예외를 표시된 하위 클래스로 던지고, 함수 안에서 난 그 예외는 그 메시지 그대로 `JsonException`이 된다.
  트리가 던지지 않은 NPE·CCE는 일반적인 메시지의 `JsonException`이 되어, 프로그램의 메시지가 클라이언트에 가지 않는다.
- **네이티브 이미지**: 2026-10-23에 GraalVM CE 25.2.4로 확인했다.
  `JsonProvider.provider()`와 `Json.*`의 `ServiceLoader` 경로, 생성자, `JsonpCodecs`가 설정 없이 동작하고, 출력은 JVM과 같다.
  처음에는 `Charset.forName("UTF-32BE")`가 `Documents`의 정적 초기화에서 `UnsupportedCharsetException`을 던져 UTF-8 문서도 읽지 못했다.
  UTF-32는 JDK의 확장 문자셋이라 `-H:+AddAllCharsets` 없이는 이미지에 들지 않으므로, 모듈이 직접 디코딩한다.
- **성능 측정**: `benchmark/json-bench`의 `JsonpBench`가 Parsson과 같은 100개 레코드·100개 장소로 트리 읽기, 이벤트 파싱, 트리 쓰기, 생성기, 빌더를 비교한다.
  setup에서 두 프로바이더가 같은 트리로 읽고 같은 텍스트로 쓰는지 확인한다.
  2026-10-24에 측정했다("측정 결과").
- **스트리밍 입력**: `JsonInput`을 고치지 않고 모듈 안에서 했다(결정 80).
  1 MiB 이하의 문서는 지금처럼 통째로 읽는다.
  그보다 큰 문서는 `StreamingJsonParser`가 버퍼에 든 바이트를 64 KiB까지 묶음(run)으로 `JsonInput` 하나에 넘기고, 그 입력이 바이트에 든 만큼 값을 읽는다("스트리밍 개선").
  묶음의 바이트가 값 중간에서 끝나면 묶음은 그 단계 앞에서 멈추고, 파서가 묶음이 있던 컨테이너에 들어가 그 단계를 스트림에서 읽는다.
  묶음에 들지 않는 값은 저마다의 `JsonInput`에 맡긴다.
  숫자와 리터럴은 숫자·단어를 이루는 바이트로 잘라 맡기고, 파서가 남긴 바이트(`0.1E5E5`의 `E5`)는 스트림으로 돌려보내므로 실패와 위치가 통째로 읽을 때와 같다.
  같은 문서를 1~3바이트씩 끊어 읽으며 여러 조각 크기로 이벤트·값·실패·위치를 대조하는 테스트와, 모든 문서를 16바이트 조각으로 스트리밍하는 두 번째 TCK 실행(`tckStreamed`)이 이를 확인한다.
  묶음이 끝나는 자리를 1~300바이트 조각으로 옮겨 가며 이벤트, 트리 읽기, 건너뛰기를 대조하는 테스트가 묶음이 멈추는 모든 경로를 지난다.
  128 MB 문서를 64 MB 힙에서 파싱하는 테스트가 문서 전체를 쥐지 않음을 보인다.
- **남은 것**: `JsonInput` 자체의 버퍼 재충전은 hot path 비용을 벤치마크로 확인한 뒤 따로 정한다.
  묶음 방식으로 스트리밍 경로가 모든 크기에서 Parsson보다 빨라졌으므로, 재충전으로 얻을 것은 통째 읽기와의 남은 격차뿐이다("스트리밍 개선").

### 측정 결과

2026-10-24에 `JsonpBench`를 README의 설정대로 돌렸다.
Temurin 25+36, JMH 1.37, 포크 2, 워밍업·측정 각 1초 5회, `-Xms1g -Xmx1g -prof gc`, CPU 0-3(P코어) 고정, AC 전원, `performance` 프로파일이고, 전체 13분 12초 걸렸다.
Parsson은 1.1.9이다.

100개 레코드(10 KB)와 100개 장소에서는 모든 케이스가 Parsson보다 빠르다.

| 케이스 | silk-json-jsonp | Parsson | 배율 | 할당(silk / Parsson) |
|---|---:|---:|---:|---:|
| `readTree` | 15.6 µs | 31.1 µs | 2.00 | 72.6 / 113.1 KB |
| `readTree_codecs` | 13.8 µs | | 2.25 | 45.4 KB |
| `readPlaces` | 14.7 µs | 27.5 µs | 1.87 | 76.7 / 119.4 KB |
| `parseEvents` | 17.7 µs | 24.9 µs | 1.41 | 50.0 / 33.8 KB |
| `writeTree` | 10.4 µs | 19.1 µs | 1.84 | 19.6 / 27.3 KB |
| `writeTree_codecs` | 9.5 µs | | 2.02 | 11.3 KB |
| `generate` | 9.5 µs | 12.4 µs | 1.31 | 19.6 / 24.8 KB |
| `build` | 4.0 µs | 5.7 µs | 1.42 | 37.9 / 54.3 KB |

- 오차는 모두 평균의 5% 안이다.
- `parseEvents`만 Parsson보다 많이 할당한다.
  1 KB 문서를 통째로 읽을 때도 21 KB를 할당하므로, 스트림을 바이트 배열로 모으는 버퍼가 문서보다 크게 잡히는 것으로 보인다.

크기별 케이스는 두 읽기 경로를 모두 두는 이유를 확인해 준다.
항상 스트리밍하면 1 MiB 이하의 문서가 1.5~3.8배 느려지고, 모든 크기에서 Parsson보다도 느려진다.

| 레코드 | 문서 | 이벤트: 통째 | 스트리밍 | Parsson | 트리: 통째 | 스트리밍 | Parsson |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 | 1 KB | 2.67 µs | 3.94 µs | 3.29 µs | 2.59 µs | 4.70 µs | 4.17 µs |
| 1,000 | 108 KB | 186 µs | 524 µs | 242 µs | 160 µs | 602 µs | 312 µs |
| 9,000 | 997 KB | 1.73 ms | 3.93 ms | 2.26 ms | 1.47 ms | 3.87 ms | 2.86 ms |
| 100,000 | 11 MB | 20.1 ms | 42.0 ms | 25.9 ms | 27.5 ms | 45.6 ms | 34.6 ms |

- 스트리밍은 통째 읽기보다 이벤트에서 1.5~2.8배, 트리에서 1.7~3.8배 느리다.
  108 KB의 트리 스트리밍은 오차가 ±180 µs로 커서, 3.8배는 상한에 가깝게 봐야 한다.
- 기본 설정에서 11 MB 문서는 스트리밍으로 읽는다.
  이때 Parsson보다 이벤트에서 1.62배, 트리에서 1.32배 느리지만, 같은 문서를 통째로 읽으면 Parsson보다 빠르다.
- 11 MB 문서의 이벤트 파싱 할당은 통째 45.2 MB, 스트리밍 171.9 MB, Parsson 25.8 MB이다.
  레코드 하나(약 110바이트)에 통째 452바이트, 스트리밍 1,719바이트이다.
- 원인은 64 KiB 이하의 값마다 새 `SilkJsonParser`와 `JsonInput`을 만드는 데 있다(`StreamingJsonParser.whole`).
  값 하나에 바이트 복사, `Locator`·`Counter`·컨테이너 배열, 그리고 `JsonInput`의 `KeyTable`(64칸 배열 두 개)이 새로 생긴다.
  `KeyTable`이 값마다 비워지므로 레코드마다 같은 키 다섯 개를 다시 할당한다.
  통째 읽기는 문서 하나에 `KeyTable` 하나를 쓰므로 이 비용이 없다.
- 줄일 후보는 둘이다.
  하나는 스트리밍 파서가 값마다 `KeyTable`과 파서 상태를 이어 쓰는 것이고, silk-json에 그 길을 여는 API가 필요하다.
  다른 하나는 미뤄 둔 `JsonInput` 버퍼 재충전으로, 값마다 새 입력을 만드는 일 자체를 없앤다.
  어느 쪽이든 hot path에 드는 비용을 `JsonBench`로 확인한 뒤 정한다.
- 2026-10-27에는 silk-json을 고치지 않는 세 번째 길로 했다: 값 여러 개를 `JsonInput` 하나에 넘기는 묶음이다("스트리밍 개선").

### 스트리밍 개선

2026-10-27에 `StreamingJsonParser`를 묶음 방식으로 바꿔, 스트리밍 경로가 모든 크기에서 Parsson보다 빨라졌다.
11 MB 문서의 이벤트 파싱은 47.0 ms에서 23.5 ms로, 트리 읽기는 48.5 ms에서 22.8 ms로 줄었다.
silk-json은 바뀌지 않았다.

- **묶음**: 파서가 선 자리부터 버퍼에 든 바이트를 64 KiB까지 복사해 `SilkJsonParser` 하나에 넘긴다.
  바이트 앞에는 파서가 들어가 있는 컨테이너마다 괄호를 붙여, 묶음이 그 컨테이너 안에서 파싱하고 중첩 깊이도 문서의 뿌리부터 센다.
  레코드마다 만들던 파서·`JsonInput`·`KeyTable`·`Locator`가 묶음 하나에 하나로 줄었다.
- **바이트가 값 중간에서 끝날 때**: 끝에 닿은 단계는 예외를 던지거나, 뒤가 이어질 수 있는 숫자를 바이트 끝까지 읽는다.
  그러면 그 단계를 돌려주지 않고 묶음이 그 단계 앞에 섰던 자리로 돌아간다.
  파서는 묶음의 컨테이너 스택을 이어받아 그 단계를 스트림에서 읽고, 뒤따르는 값은 다음 묶음에 넘긴다.
  묶음 안의 실패도 같은 길로 다시 읽으므로, 실패의 메시지와 위치는 스트림에서 읽는 쪽이 정한다.
- **미리 훑지 않는다**: 바이트를 먼저 훑어 마지막 온전한 값의 끝을 찾으면 모든 바이트를 두 번 지난다.
  대신 묶음이 끝에 닿은 단계에서 멈추고, 멈추는 일이 드물도록 두 가지로 끝을 짐작한다.
  하나는 첫 값이 끝나는 바이트 뒤에 오는 쉼표 가운데 마지막 것(cut)이고, 다른 하나는 남은 바이트가 지금까지 가장 큰 값보다 적은 때이다.
  짐작이 틀려도 묶음이 멈출 뿐이라 결과는 같다.
- **예외를 피한다**: 버퍼 끝에 남은 바이트가 직전 묶음의 가장 큰 값, 또는 직전에 따로 읽은 값보다 적으면 묶음 없이 그 값 하나를 따로 읽는다.
  묶음이 첫 값에서 멈추면, 따로 읽은 값이 짧아질 때까지 다음 값들도 따로 읽는다.
  100 KiB 문자열의 배열처럼 64 KiB를 넘는 값이 이어지는 문서에서, 값마다 묶음이 실패하며 예외를 만드는 일이 이것으로 없어졌다.
  두 경우 모두 스트림에서 더 읽으려고 블록하지 않는다.
- **문서 자체의 객체·배열**도 묶음으로 시작해, 64 KiB를 미리 훑던 `containerLength`를 거치지 않는다.
  그 객체·배열을 트리로 읽을 때는, 문서가 묶음보다 길면 묶음 바이트를 끝까지 파싱했다가 실패하지 않도록 처음부터 값 단위로 읽는다.
- **그 밖**: 줄·열을 세는 `Counter`는 줄바꿈이 없는 8바이트를 한 워드로 센다.
  창 버퍼는 읽기가 버퍼를 가득 채울 때마다 두 배로, 64 KiB까지 자라므로 작은 문서에 큰 버퍼를 잡지 않는다.

측정은 2026-10-24와 같은 설정이되, 플랫폼 프로파일이 `balanced`였다.
표의 모든 열은 같은 날 같은 설정으로 쟀고, 이전 스트리밍 열은 바꾸기 전 빌드의 것이다.

| 레코드 | 문서 | 이벤트: 통째 | 이전 스트리밍 | 스트리밍 | Parsson | 트리: 통째 | 이전 스트리밍 | 스트리밍 | Parsson |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 | 1 KB | 2.86 µs | 3.74 µs | 2.31 µs | 3.27 µs | 2.60 µs | 4.38 µs | 2.49 µs | 4.20 µs |
| 1,000 | 108 KB | 183 µs | 527 µs | 228 µs | 242 µs | 159 µs | 600 µs | 214 µs | 314 µs |
| 9,000 | 997 KB | 1.71 ms | 4.23 ms | 2.01 ms | 2.24 ms | 1.53 ms | 4.05 ms | 1.87 ms | 2.92 ms |
| 100,000 | 11 MB | 20.1 ms | 47.0 ms | 23.5 ms | 26.0 ms | 33.1 ms | 48.5 ms | 22.8 ms | 37.0 ms |

- 108 KB 이상에서 스트리밍의 이벤트 파싱은 통째 읽기의 1.17~1.24배가 됐다.
  바꾸기 전에는 2.3~2.9배였다.
- 트리는 108 KB와 997 KB에서 통째 읽기의 1.34배와 1.22배이고, 바꾸기 전에는 3.8배와 2.6배였다.
- 11 MB 문서의 트리는 스트리밍이 통째 읽기보다 빠르다.
  통째 읽기는 11 MB 배열을 한 번에 잡고 그 위에 트리를 만들어 GC가 길다: 측정 10회 동안 GC 시간이 통째 3.3초, 스트리밍 0.1초, Parsson 0.6초이다.
- 1 KB 문서는 스트리밍이 통째 읽기보다 빠르다.
  통째 읽기는 21 KB, 스트리밍은 13 KB를 할당한다.
  문서를 배열로 모으는 `readNBytes`의 버퍼가 8 KiB 창보다 크게 잡히는 것으로 보인다.
- 기본 설정(1 MiB 넘는 문서만 스트리밍)에서 11 MB 문서는 이벤트 23.1 ms, 트리 20.5 ms이다.
- 11 MB 문서의 이벤트 파싱 할당은 189.5 MB에서 34.4 MB로 줄어, 통째 읽기(45.2 MB)보다 적다.
  Parsson은 25.8 MB이다.
  트리는 195.2 MB에서 57.7 MB가 됐다(통째 67.8 MB, Parsson 96.7 MB).
- 108 KB~1 MiB에서는 스트리밍이 통째 읽기보다 아직 18~34% 느리므로, 두 읽기 경로를 모두 두는 결론은 그대로다.

레코드 배열이 아닌 문서에서도 퇴행이 없는지 모양 일곱 가지를 기본 설정으로 쟀다.
JMH 없이 10회 워밍업 뒤 15회의 중앙값이고, 문서는 10~26 MB이다.

| 문서 | 이벤트: 이전 | 이후 | 트리: 이전 | 이후 |
|---|---:|---:|---:|---:|
| 레코드 배열(20.8 MB) | 75.7 ms | 37.7 ms | 103.6 ms | 41.3 ms |
| 들여쓴 레코드 배열(11.3 MB) | 43.1 ms | 24.3 ms | 54.1 ms | 26.7 ms |
| 수 배열(26.0 MB) | 165.3 ms | 114.6 ms | 295.3 ms | 200.7 ms |
| 40 KiB 객체 배열(20.5 MB) | 60.0 ms | 45.6 ms | 60.8 ms | 47.1 ms |
| 100 KiB 문자열 배열(20.5 MB) | 27.7 ms | 21.2 ms | 29.8 ms | 23.0 ms |
| 레코드 맵 객체(23.0 MB) | 94.7 ms | 50.1 ms | 164.8 ms | 65.4 ms |
| 한글 레코드 배열(10.3 MB) | 53.9 ms | 29.5 ms | 78.8 ms | 31.4 ms |

- 100 KiB 문자열 배열은 첫 구현에서 35.5 ms로 오히려 느렸다.
  값마다 묶음이 첫 값에서 실패해 예외를 만든 탓이고, 첫 값에서 멈춘 뒤 따로 읽게 해 고쳤다.

## core API에 JSON-P를 쓸 수 있는가

core의 `JsonSink`는 JSON-P로 대신할 수 없다.
`JsonValue`는 대신할 수 있지만, 대신하지 않는 편이 낫다.
JSON-P 트리가 주는 이점은 core API를 바꾸지 않고 `silk-json-p`의 `JsonWriter`와 `JsonReader`로 얻는다.
템플릿 엔진처럼 JSON 구현도 갈아끼울 수 있게 하려는 검토였다.
JSON-P의 동작은 2026-10-13에 jakarta.json-api 2.1.3과 Parsson 1.1.7로 확인했다.

### 템플릿과 JSON의 비대칭

- 템플릿 엔진은 core API에 드러나지 않는다.
  핸들러는 이름과 `Model`만 넘기고, 엔진은 메서드 하나짜리 `TemplateRenderer` 뒤에 있다.
- JSON은 핸들러가 직접 쓰는 어휘이다.
  `JsonWriter<T>`, `JsonReader<T>`, `bodyJson()`이 돌려주는 `JsonValue`, `JsonSink`, `TestRequest.jsonBody(JsonValue)`가 모두 silk-json 타입이다.
- 그래서 JSON 구현을 바꾸면 핸들러가 쓰는 어휘가 바뀐다.
  JSON-P로 옮겨도 어휘가 `jakarta.json`으로 바뀔 뿐, 이 구조는 그대로이다.

### `JsonSink`

`JsonSink`는 프레이밍을 응답에 두고, writer에게는 값만 쓰게 한다.
JSON-P의 대응물인 `JsonGenerator`는 이 계약을 지키지 못한다.

- NDJSON을 생성기 하나로 쓸 수 없다.
  Parsson은 두 번째 루트 값에서 `JsonGenerationException: Illegal method during JSON generation, not valid in current context IN_NONE`을 던진다.
  줄마다 생성기를 새로 만들고, `close()`가 응답 스트림을 닫지 않도록 감싸야 한다.
- `jsonArray`에 생성기를 넘기면 writer가 `writeEnd()`나 `close()`로 배열을 닫을 수 있다.
  `JsonSink`가 막는 것이 이것이다.
- 주로 쓰는 형태는 `sink.write(card, Codecs.CARD)`이고, 그 `JsonWriter<T>`는 silk-json 타입이다.

`JsonSink`는 core의 웹 개념으로 남는다.
바꿀 수 있는 것은 `write(JsonValue)`의 매개변수 타입뿐이다.

### `JsonValue`

바뀌는 공개 API는 `WebResponse.json(JsonValue)`, `WebRequest.bodyJson()`, `JsonSink.write(JsonValue)`, `TestRequest.jsonBody(JsonValue)`, `TestClient`의 `postJson`·`putJson`·`patchJson(JsonValue)`이다.
spider-silk-openapi가 트리를 만드는 15곳, `manual/`의 9쪽과 `manual-ko/`의 같은 쪽, `skills/`의 4개 파일이 함께 바뀐다.

- **구현 교체가 생기지 않는다.**
  core는 받은 `jakarta.json.JsonValue`를 인터페이스로 순회해 `JsonOutput`에 쓰면 되므로, 결정 78의 버퍼는 지킬 수 있다.
  그러면 provider가 바꾸는 것은 `bodyJson()`의 파싱뿐이고, 후보는 Parsson과 Johnzon이다.
  Jackson, Gson, fastjson2, avaje-jsonb는 JSON-P provider가 아니다.
  `json(value, writer)`와 `bodyJson(reader)`는 silk-json 타입으로 남으므로, core API에 JSON 어휘가 두 벌이 된다.
- **400 계약이 깨진다.**
  silk-json의 `JsonException`은 `IllegalArgumentException`이므로, 키가 없거나 타입이 틀린 본문은 400이 된다(결정 8).
  core가 아래의 NPE와 CCE를 400으로 바꾸면, 핸들러의 버그도 400으로 가려진다.

  | 상황 | JSON-P(Parsson)가 던지는 예외 |
  |---|---|
  | `getString`의 키가 없다 | `NullPointerException` |
  | `getString`의 값이 숫자이다 | `ClassCastException` |
  | 문법 오류 | `JsonParsingException`, `jakarta.json.JsonException`을 거쳐 `RuntimeException`이다 |

- **숫자가 조용히 잘린다.**
  `getInt("id")`는 `1.5`를 1로 돌려주고, `getJsonNumber("id").longValue()`도 1을 돌려준다.
  `JsonObject`에는 `getLong`이 없어서, long id는 `getJsonNumber("id").longValueExact()`로 읽는다.
  silk-json의 `asLong()`은 `1.5`를 거부한다.
- **표준 진입점이 원칙에 어긋난다.**
  `Json.createObjectBuilder()`는 호출마다 `JsonProvider.provider()`를 거친다.
  `provider()`는 시스템 프로퍼티를 본 뒤 캐시 없이 `ServiceLoader.load`를 부르고, 찾지 못하면 기본 구현을 `Class.forName`으로 만든다.
  core는 `new SilkJsonProvider()`로 이 경로를 피할 수 있지만, JSON-P 문서의 관용구를 쓰는 핸들러는 요청마다 이 경로를 탄다.
  매뉴얼이 `Json.*`를 쓰지 말라고 가르쳐야 하므로, 익숙한 표준 어휘라는 이점이 줄어든다.
- **결정 44·75와 충돌한다.**
  JSON-P의 `JsonObject`는 `Map`이고 불변이어서, `put`이 `UnsupportedOperationException`을 던진다.
  결정 44는 `Map`을 내주지 않기로 했고, 결정 75는 `put`으로 채우는 객체에 `String`을 그대로 담기로 했다.
  `Json.object().put("imported", n)`은 `provider.createObjectBuilder().add("imported", n).build()`가 된다.
- **`silk-json-p`가 core의 필수 의존이 된다.**
  앞의 간극 표 가운데 적어도 트리, 리더, provider가 끝나야 core를 옮길 수 있다.
  core는 `api 'jakarta.json:jakarta.json-api'`를 갖고, `silk-json-p`의 완성도가 core의 일정을 묶는다.

얻는 것은 표준 트리이다.
PATCH 엔드포인트가 `JsonPointer`, `JsonPatch`(RFC 6902), `JsonMergePatch`(RFC 7396)를 바로 쓴다.
JSON-P 트리를 다루는 라이브러리와 값을 그대로 주고받는다.
둘 다 core API를 바꾸지 않고 얻을 수 있다.

### 대안: `silk-json-p`의 `JsonWriter`와 `JsonReader`

`JsonWriter<T>`와 `JsonReader<T>`가 이미 seam이고, JSON-P 트리는 그 `T`의 하나이다.
`JsonP`는 가칭이다.

```java
// silk-json-p
JsonWriter<jakarta.json.JsonValue> WRITER // walks the interface, so any provider's values
JsonReader<jakarta.json.JsonObject> OBJECT // builds the tree off JsonInput

// in a handler
JsonObject patch = req.bodyJson(JsonP.OBJECT);
return WebResponse.json(patched, JsonP.WRITER);
sink.write(value, JsonP.WRITER);
```

- core API는 바뀌지 않는다.
  `jakarta.json-api`에는 `silk-json-p`를 고른 애플리케이션만 의존한다.
- `WRITER`는 `JsonOutput`에 바로 쓰므로, 결정 78의 버퍼가 유지된다.
  인터페이스로 순회하므로, Parsson이 만든 값도 쓴다.
- `OBJECT`는 `JsonInput` 위에서 읽으므로, 문법 오류는 `JsonException`이 되고 400이 유지된다.
- 트리를 읽다 나는 NPE와 CCE는 `JsonReader.object(fn)`와 같은 모양의 `JsonP.object(fn)`이 매핑 람다 안에서만 `JsonException`으로 바꾼다.
- `OBJECT`는 토큰 종류 peek가 있어야 하므로, 앞의 제안하는 순서에서 1단계 뒤에 온다.

## Jackson을 같은 방식으로 붙일 수 있는가

Jackson도 core API를 바꾸지 않고 `JsonWriter<T>`와 `JsonReader<T>`의 자리에 붙는다.
읽기는 silk-json을 고치지 않고 되고, 쓰기는 Jackson의 `JsonGenerator`를 `JsonOutput` 위에 구현해야 한다.
2026-10-13의 core와 silk-json 코드를 근거로 검토했고, 같은 날 `spider-silk-jackson`으로 구현했다.
2026-10-14에 읽기를 측정했고, 중첩 경로를 빠르게 하려고 silk-json에 `JsonInput.readValueBytes()`를 더했다.

| 층 | 방법 | silk-json의 변경 |
|---|---|---|
| 바인딩 읽기 | `fromJsonBytes`를 재정의해 `mapper.readValue(utf8, type)`를 부른다 | 없다. 중첩 경로에 `readValueBytes()`를 더했다 |
| 바인딩 쓰기 | Jackson의 `JsonGenerator`를 `JsonOutput` 위에 구현한다 | 없다. 모듈의 작업이 크다 |
| 트리(`JsonNode`) | JSON-P 트리의 `WRITER`, `OBJECT`와 같다 | 읽기에 peek가 필요하다 |

### 읽기

core의 두 입구 `bodyJson(reader)`와 `bodyNdjson(reader)`는 모두 `reader.fromJsonBytes(utf8)`를 부른다(`WebRequest.java`).
`fromJsonBytes`는 `JsonReader`의 default 메서드이므로, 어댑터가 재정의하면 Jackson이 바이트를 직접 읽는다.

```java
public T fromJsonBytes(byte[] utf8) {
    try {
        return mapper.readValue(utf8, type);
    } catch (InvalidDefinitionException e) {
        throw e; // a mapping bug, so it stays a 500
    } catch (JacksonException e) {
        throw new JsonException(e.getMessage()); // bad input, so a 400
    }
}
```

- Jackson 3의 예외는 unchecked이다.
  입력의 오류만 `JsonException`으로 바꾸면 core의 400 계약(결정 8)을 지킨다.
- `InvalidDefinitionException`은 매핑 정의의 오류, 곧 프로그래머의 버그이므로 500으로 남긴다.
- `read(JsonInput)`은 `JsonReader.list(...)` 같은 중첩에서만 불린다.
  구현은 `JsonInput.readValueBytes()`로 다음 값의 바이트를 쓰인 그대로 꺼내 Jackson에 넘긴다.
  이 메서드는 2026-10-14에 이 경로를 위해 silk-json에 더했다.
  `skipValue()`와 같은 문법 검사를 거친 바이트의 복사본을 돌려준다.
  처음 구현은 `readValue()`로 트리를 읽어 바이트로 되쓴 뒤 넘겼고, 값 하나를 세 번 처리했다.
  두 구현의 수치는 `notes/json-performance-tuning.md`의 10절에 있다.
- JSON null 본문은 `JsonException`으로 거부해, 핸들러가 값 대신 null을 받지 않게 한다.
- 생성자가 던진 `IllegalArgumentException`은 Jackson이 `ValueInstantiationException`으로 감싸므로, 원인을 꺼내 다시 던져 400을 지킨다.

### 쓰기

core는 `writer.write(value, out)`로 `JsonOutput`에 쓰게 하고, `JsonOutput`에는 직렬화된 바이트를 넣는 메서드가 없다.

- **Jackson의 `JsonGenerator`를 `JsonOutput` 위에 구현한다(권장).**
  `GeneratorBase`를 상속해 토큰을 `object()`, `name()`, `value()`로 옮기고, `mapper.writeValue(generator, value)`로 쓴다.
  Jackson의 Smile·CBOR 백엔드가 쓰는 확장 지점이다.
  모든 토큰이 검사하는 `JsonOutput`을 지나므로, 결정 78의 버퍼와 `Content-Length`가 그대로이다.
  분량은 수백 줄이다.
  `@JsonRawValue`가 부르는 `writeRaw*`는 검사 없는 출력이므로, 거부하거나 검증한다.
- **`JsonOutput`에 직렬화된 바이트를 넣는 메서드를 둔다.**
  시제품으로는 가장 빠르다.
  검사하지 않으면 결정 76이 거부한 "JSON이 아닌 문서를 쓸 수 있는 출력"이 된다.
  검사하면 복사와 스캔이 한 번씩 는다.

### 지금의 연동 방식과 비교

매뉴얼의 External JSON Binding 쪽(`json-interop.adoc`)은 `rawJson(String)`과 `bodyStream()`으로 연동한다.
어댑터가 더하는 것은 넷이다.

- `bodyJson(reader)`는 `BodyLimits` 안에 있고, `bodyStream()`은 밖에 있다.
- 400 계약이 핸들러마다 두던 `try`나 예외 핸들러 없이 성립한다.
- `JsonSink`는 `JsonValue`나 `JsonWriter`만 받으므로, 지금은 `jsonArray`와 `ndjson`에 Jackson을 쓸 수 없다.
  어댑터가 있으면 `sink.write(card, CARD)`가 된다.
- `bodyNdjson(reader)`가 줄마다 Jackson으로 읽는다.

```java
static final JacksonCodecs JACKSON = new JacksonCodecs(JsonMapper.builder().build());
static final JsonWriter<Deck> DECK = JACKSON.writer(Deck.class);
static final JsonReader<NewDeck> NEW_DECK = JACKSON.reader(NewDeck.class);

NewDeck body = req.bodyJson(NEW_DECK);
return WebResponse.json(deck, DECK);
```

검토 때의 가칭은 `Jackson.writer(MAPPER)`와 `Jackson.reader(MAPPER, NewDeck.class)`처럼 매퍼를 매번 인자로 받았다.
구현은 매퍼를 `JacksonCodecs`의 생성자에서 한 번 받아, 메서드 인자에 매퍼가 되풀이되지 않게 했다.

### 모듈: `spider-silk-jackson`

어댑터는 core에 의존하는 `spider-silk-jackson` 모듈이고, silk-json은 core를 거쳐 받는다.

- **어댑터가 필요한 까닭이 core에 있다.**
  core의 입구 `bodyJson(reader)`, `json(value, writer)`, `JsonSink.write(value, writer)`가 `JsonWriter`와 `JsonReader`로만 말하기 때문이다.
  silk-json만 쓰는 애플리케이션은 Jackson을 직접 부르면 되므로, 어댑터가 필요 없다.
- **리플렉션은 선례가 있는 자리에 든다.**
  `AGENTS.md`의 리플렉션 금지는 core와 silk-json에 적용된다.
  리플렉션은 Jackson databind의 것이고, FreeMarker와 Thymeleaf 모듈도 엔진이 리플렉션으로 객체를 읽는다.
  앞의 "파사드 모듈에 한해 원칙의 예외를 둘지"는 이 모듈에서는 묻지 않아도 된다.
- **silk-json을 저장소로 떼어 낼 때 깔끔하다.**
  이 모듈은 이 저장소에 남아 프레임워크와 함께 릴리스된다.
  silk-json의 저장소에는 Jackson의 코드가 들어가지 않는다.

### 기각한 안

- **`silk-json-jackson`.**
  silk-json만 쓰는 애플리케이션에 필요 없는 모듈이 그 라이브러리에 딸린다.
  리플렉션 원칙의 예외도 silk-json의 이름 아래 두어야 한다.
- **`JsonValue`, `JsonWriter`, `JsonReader`를 core로 옮겨, Jackson 모듈이 core의 타입에 의존하게 하는 것.**
  - 엔진이 이 타입을 받는다: `JsonOutput.value(JsonValue)`, `put(key, value, JsonWriter)`, `Json.parse`가 그렇고, `JsonValue`는 silk-json의 파일 10개가 참조한다.
  - 생성 코덱은 `JsonCodec<T>`를 구현하고, `JsonKey`, `JsonOutput`, `JsonInput`을 쓴다.
  - 옮기면 silk-json이 core에 의존해야 한다.
    core가 `api`로 끄는 Jetty와 jte가 silk-json만 쓰는 애플리케이션에 들어가고, 결정 77이 silk-json을 떼어 낸 까닭이 사라진다.
  - 순환을 피하려고 core가 엔진과 무관한 JSON 추상화를 정의하면, 앞의 "core API에 JSON-P를 쓸 수 있는가"를 자체 스펙으로 되풀이하는 것이고 비용도 같다.
  - 트리와 writer·reader는 엔진과 함께 바뀐다.
    core에 두면 저장소를 나눈 뒤 두 저장소에 걸친 변경이 오히려 는다.

### 남은 것

- **native image.**
  이 모듈을 고른 애플리케이션은 바인딩하는 타입마다 리플렉션 메타데이터가 필요하다.
  설정 없는 native image를 그 애플리케이션이 포기한다.
- **Jackson의 버전.**
  2.x(`com.fasterxml.jackson`)와 3.x(`tools.jackson`)는 패키지가 다르다.
  json-bench가 3.1.5를 쓰므로 3.x부터 시작한다.

## 생태계의 사례

두 스펙을 함께 지원하는 라이브러리는 대부분 JSON-B 구현을 JSON-P 구현 위에 얹는다.
빌드 타임 생성 codec 위에 두 스펙을 모두 얹은 것은 Micronaut Serialization 하나이다.
2026-10-13에 웹 문서, 저장소, Maven Central로 확인한 내용이다.

### 스펙이 이미 두 API를 잇는다

JSON-B API는 JSON-P API에 의존하므로 연결의 일부는 스펙에 들어 있다.

- `JsonbSerializer`와 `JsonbDeserializer`는 JSON-P의 `JsonGenerator`와 `JsonParser`를 받는다.
- `JsonbBuilder.create().withProvider(JsonProvider)`로 JSON-B 구현이 쓸 JSON-P provider를 고른다.
- JSON-B 구현은 `JsonValue`, `JsonObject`, `JsonArray` 같은 JSON-P 타입을 매핑 대상으로 다룬다.

### 리플렉션 기반 구현

| 프로젝트 | JSON-P | JSON-B | 관계 |
|---|---|---|---|
| Eclipse Parsson + Yasson | Parsson | Yasson | 사실상의 참조 구현 쌍이다. Yasson은 어떤 JSON-P provider 위에서도 동작한다. Parsson의 전신은 `org.glassfish:jakarta.json`이다 |
| Apache Johnzon | `johnzon-core` | `johnzon-jsonb` | 한 프로젝트가 둘 다 제공한다. 사이에 자체 `johnzon-mapper` 층이 있다. TomEE와 Meecrowave가 쓴다 |

통합 계층은 이 구현들을 그대로 노출한다.
Jersey(`jersey-media-json-processing`, `jersey-media-json-binding`)와 RESTEasy(`resteasy-json-p-provider`, `resteasy-json-binding-provider`)는 스펙마다 `MessageBodyReader`와 `MessageBodyWriter`를 붙인다.
Quarkus, Helidon MP, Open Liberty도 두 스펙을 따로 제공하고, 내부 구현은 대개 Parsson과 Yasson이다.
Jackson은 `jackson-datatype-jakarta-jsonp`로 JSON-P 값 타입을 다루지만 JSON-B API는 구현하지 않는다.

### Micronaut Serialization 3.1 이후

Micronaut Serialization은 컴파일 시점에 생성한 serde를 기본 경로로 두고, 그 위에 JSON-P provider와 JSON-B provider를 별도 모듈로 얹었다.
PR #1331 "JSON-B and JSON-P Spec Compliance"가 2026-06-11에 `3.1.x` 브랜치로 머지되어 3.1.0에 들어갔고, 2026-10-13 시점의 최신 버전은 3.2.4이다.

- **`micronaut-serde-jsonp-impl`**: `jakarta.json.spi.JsonProvider` 구현(`MicronautJsonProvider`)이다.
  문서는 Parsson에 의존하지 않고 리플렉션을 쓰지 않는다고 밝힌다.
- **`micronaut-serde-jsonb`**: `JsonbProvider` 구현(`MicronautJsonbProvider`)이다.
  기본 경로는 생성된 serde 메타데이터이고, 예제의 바인딩 타입에는 모두 `@Serdeable`이 붙어 있다.
- **`micronaut-serde-jsonp`**: 기존 JSON-P 스트림 통합이며 Parsson을 쓴다.
- **스펙 호환의 대가**: 두 provider는 `META-INF/services` 파일로 등록된다.
  생성 코드가 감당하지 못하는 JSON-B 런타임 동작에는 리플렉션 기반 introspection fallback이 있다.
  PR이 밝힌 fallback 범위는 메타데이터 탐색, visibility, creator 검증, transient 처리, property naming, 어노테이션 조회이다.
  콜백 컴포넌트는 Micronaut bean, CDI bean, 기본 생성자 순으로 찾는다.
- **TCK**: JSON-P TCK와 JSON-B TCK를 CI에서 돌리고 결과를 GitHub Pages에 게시한다.
  known-failure 추적 장치가 함께 들어갔으므로 전부 통과한다고 단정할 수는 없다.

JSON-B 절의 "리플렉션 fallback을 opt-in으로 둔다"는 길을 실제로 택한 사례이다.
그 known-failure 목록은 리플렉션 없이 통과할 수 없는 TCK 항목을 가늠하는 자료가 된다.

### 빌드 타임 방식의 다른 사례

| 프로젝트 | JSON-P | JSON-B 어노테이션 | JSON-B API | 비고 |
|---|---|---|---|---|
| jsonbap (t1) | 없음 | 읽는다 | 목표 | 어노테이션 프로세서로 JSON-B를 구현하려 한다. 바인딩 타입에 `@Bindable`이 필요하고, `PropertyVisibilityStrategy`는 지원하지 않는다. JSON-B TCK의 약 21%를 통과하고, README가 "very early stage"라고 밝힌다 |
| Helidon 4 `helidon-json`, `helidon-json-binding` | 자체 API | 읽지 않는다(`@Json.Entity`) | 없음 | 파서, 생성기, 트리, 바인딩을 모두 `io.helidon.json` 아래 자체 API로 제공한다. 사용자 정의 converter는 Helidon Service Registry로 찾는다 |
| avaje-jsonb | 없음 | 읽지 않는다(`@Json.Property` 등) | 없음 | apt로 adapter를 생성하고 리플렉션이 없다 |
| jjq-mapper (Hyperfoil) | 없음 | 브리지 모듈로 읽는다 | 없음 | 2026-09에 `AnnotationBridge` SPI와 `jjq-mapper-jsonb` 모듈이 들어가 `@JsonbProperty`, `@JsonbTransient` 등을 읽는다 |

Micronaut Serialization은 `micronaut-serde-jsonb` 없이도 `@JsonbProperty`, `@JsonbCreator` 같은 JSON-B 어노테이션을 읽는다.
이때 `jakarta.json.bind-api`는 `compileOnly` 의존이면 된다.
silk-json-processor가 JSON-B 어노테이션을 이름으로 읽는 방식과 같은 위치이다.

### silk-json에 주는 시사점

- 생성 codec을 기본 경로로 두고 스펙 파사드를 별도 모듈로 얹는 구조는 Micronaut가 실제로 가능함을 보였다.
- 그 구조도 스펙 진입점의 `ServiceLoader`와 리플렉션 fallback은 피하지 못했다.
  silk-json이 같은 길을 가려면 파사드 모듈에 한해 원칙의 예외를 둘지 결정해야 한다.
- 원칙을 지키는 쪽의 선례는 jsonbap이다.
  스펙의 일부만 지원하고 실행 시 설정을 컴파일 시점 opt-in으로 바꾼 대가로 TCK 통과율이 낮다.

### 출처

- [Micronaut Serialization guide (3.2.4)](https://micronaut-projects.github.io/micronaut-serialization/latest/guide/)
- [micronaut-serialization PR #1331](https://github.com/micronaut-projects/micronaut-serialization/pull/1331)
- [micronaut-serialization releases](https://github.com/micronaut-projects/micronaut-serialization/releases)
- [Maven Central: micronaut-serde-jsonb](https://central.sonatype.com/artifact/io.micronaut.serde/micronaut-serde-jsonb)
- [t1/jsonbap](https://github.com/t1/jsonbap)
- [Helidon 4 JSON](https://helidon.io/docs/v4/se/json)
- [avaje-jsonb](https://github.com/avaje/avaje-jsonb)
- [Hyperfoil/jjq issue #78](https://github.com/Hyperfoil/jjq/issues/78)
