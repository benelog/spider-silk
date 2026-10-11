# 남은 작업

2026-10-11에 silk-json 성능 작전 2-1~2-5를 커밋하고 부록 수치와 결정 76을 갱신한 뒤 남은 일이다.
수치, 기각한 변형, 작전의 세부는 `notes/json-performance-tuning.md`에 있고, 이 파일은 할 일의 목록만 적는다.

## 1. 빌린 버퍼 본문 (작전 3단계)

- 목표는 HTTP 경로에서 JSON 본문의 마지막 `Arrays.copyOf`를 없애는 것이다.
  사용자가 2026-10-10에 core의 `WebResponse.Bytes` 변경을 승인했다.
- 형태는 값과 writer를 든 지연 `Body`를 `AppServlet`이 materialize하는 것이고, 세부는 노트의 "3. 빌린 버퍼 본문" 절에 있다.
- `release()` 중복 호출, gzip·HEAD 경로의 반납 누락을 테스트한다.
- 문서화된 테스트 패턴 `((WebResponse.Bytes) response.body()).data()`가 바뀌는 곳(`response.adoc` 영문·한국어, `skills/spider-silk/references/testing.md`, `ReadmeSnippets`, `ApiControllerTest`, `WebResponseTest`, `ResponseBodiesTest`)을 같은 커밋에 고친다.
- 측정은 `writeList_stream` 행과 HTTP `json-list`의 요청당 할당이다.

## 2. 측정판의 나머지 (작전 0단계)

- `readPythonList`(`json.dumps` 구분자), `readPrettyList`(들여쓰기), `readBigIdList`(13자리·18자리), `writeList_stream`(`nullOutputStream`)을 두 라이브러리 모두에 더한다.
- fastjson2 쪽에 `-Dfastjson2.creator=reflect`인 readList·writeList 짝을 더한다.
- fastjson2의 쓰기 수치가 ASM writer에서 나왔는지 `-verbose:class`로 `OWG_*_Item` 로드를 확인한다.
- 대상인 Temurin 21에서 writePlainList·writeKoreanList·readList·writeLongStringList를 한 번 돈다.
  2-5의 US_ASCII 인코더는 JDK 21에서도 `encodeASCII` intrinsic을 쓰지만(소스에서 확인), 손익 분기는 재지 않았다.
- 새 문서는 그 케이스만 쓰는 `@State`에서 만든다.
  공통 `@Setup`에서 만들면 다른 케이스의 JIT 프로파일이 바뀐다.

## 3. 남은 격차

- `writeList` +2~2.5%: 2-5를 얹은 뒤의 회귀이고 원인을 찾지 못했다.
  인라인 결정은 전후가 같았으므로, `string()`의 길이 분기를 호출자 쪽으로 옮기는 변형이나 async-profiler로 본다.
- 짧은 평문 문자열 쓰기 1.14~1.18배: 작전 1단계(N=1~3 변형으로 격차 귀속)가 이것을 숫자로 가른다.
  지금 평문 격차는 0.62 µs다.
- 긴 문자열 쓰기 1.39배: 검사를 `special` SWAR 대신 `String.indexOf('"')`·`indexOf('\\')`(intrinsic)와 제어 문자 SWAR로 나누는 변형, GraalVM JIT에서의 기준 길이(작전은 128자를 안전값으로 봤다)를 잰다.
- 역순 키 읽기 1.30배: 남은 약 1.9 µs는 멤버마다 실패하는 선언 순서 `nextKeyIs`와 `keyIndex` 호출이다.
  체인이 한 번 끊긴 객체는 다음 키부터 `keyIndex`를 먼저 부르는 변형을 잰다.
- 긴 문자열 읽기 1.26배: fastjson2가 `IMPL_LOOKUP`으로 묶은 JDK 내부 메서드의 몫이라 공개 API로는 닫기 어렵다고 본다.

## 4. 보조 후보 (작전 2-6)

- 8~15자리 정수 `readLong`의 두 워드 읽기, 0~1자 문자열 표, enum 상수의 바이트 사전 계산, 한 바이트 미리 읽기.
- 각자의 벤치마크 행을 먼저 만든다.

## 5. 기계 상태

- 측정을 위해 2026-10-11에 전원 프로파일을 `balanced`에서 `performance`로 바꿨고(`powerprofilesctl set performance`), 그대로 두었다.
