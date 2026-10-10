# JSON-B·JSON-P 지원 가능성 조사

silk-json이 Jakarta JSON Binding(JSON-B)과 Jakarta JSON Processing(JSON-P)을 어디까지 지원할 수 있는지 조사한 기록이다.
결론은 둘이 다르다: JSON-B의 완전한 지원(TCK 통과)은 리플렉션 없이 불가능하고, JSON-P는 원칙을 지키면서 TCK 통과까지 노릴 수 있다.
근거는 `AGENTS.md`의 설계 원칙, 결정 76·77(`notes/decisions.md`), 2026-10-10 시점의 `silk-json`과 `silk-json-processor` 코드이다.

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
3. JSON-P TCK를 돌려 실패 목록으로 범위를 정한다.
4. 진짜 스트리밍 입력은 벤치마크로 hot path 영향을 확인한 뒤 따로 결정한다.
