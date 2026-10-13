# JSON-B·JSON-P 지원 가능성 조사

silk-json이 Jakarta JSON Binding(JSON-B)과 Jakarta JSON Processing(JSON-P)을 어디까지 지원할 수 있는지 조사한 기록이다.
결론은 둘이 다르다: JSON-B의 완전한 지원(TCK 통과)은 리플렉션 없이 불가능하고, JSON-P는 원칙을 지키면서 TCK 통과까지 노릴 수 있다.
core API의 `JsonValue`와 `JsonSink`는 JSON-P로 바꾸지 않고, JSON-P 트리는 `silk-json-p`의 `JsonWriter`와 `JsonReader`로 주고받는다("core API에 JSON-P를 쓸 수 있는가").
Jackson은 `spider-silk-jackson` 모듈로 같은 자리에 붙었고, 쓰기는 `JsonOutput` 위의 `JsonGenerator` 구현으로 한다("Jackson을 같은 방식으로 붙일 수 있는가").
근거는 `AGENTS.md`의 설계 원칙, 결정 76·77(`notes/decisions.md`), 2026-10-10 시점의 `silk-json`과 `silk-json-processor` 코드이다.
다른 라이브러리가 두 스펙을 어떻게 지원하는지는 2026-10-13에 조사해 마지막 절에 적었다.

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
