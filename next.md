# 남은 작업

2026-10-11 오후에 측정판의 나머지, 작전 1단계, 2-6, 빌린 버퍼 본문의 측정, 남은 격차의 변형을 실행하고 부록·결정 76·노트를 갱신한 뒤 남은 일이다.
수치, 기각한 변형, 작전의 세부는 `notes/json-performance-tuning.md`(7절과 "2026-10-11 오후 전체 실행")에 있고, 이 파일은 할 일의 목록만 적는다.

## 1. 커밋

- 이번 변경은 모두 작업 트리에 있고 커밋하지 않았다.
  silk-json(공백 키, 큰 정수, 0~1자 문자열, enum 이름 키, 긴 문자열 `indexOf`·LS2), 프로세서(체인 뒤 예측 제거, enum 표), 테스트, json-bench 케이스, 문서(부록·json·json-codegen·api-summary 영문과 한국어, CHANGELOG, 결정 76, 노트, benchmark README)다.

## 2. 남은 격차 (fastjson2 대비, 2026-10-11 오후)

- 긴 문자열 읽기 1.25배: fastjson2가 `IMPL_LOOKUP`으로 묶은 JDK 내부 메서드의 몫이라 공개 API로는 닫기 어렵다고 본다.
- 역순 키 읽기 1.19배: 남은 비용은 키마다의 `keyHead` switch와 `nextKeyIs` 확인이다.
- 짧은 평문 문자열 쓰기 1.12~1.16배: 1단계로 전부 문자열 표현의 몫임이 확인됐다.
  공개 경로는 JDK 22 이상의 FFM `MemorySegment.setString`뿐이라 multi-release jar로만 가능하다.
- 들여쓴 문서 읽기 1.10배: 줄바꿈과 들여쓰기를 `skipWhitespace`가 바이트 루프로 건너뛴다.
  공백을 워드로 건너뛰는 변형이나 2-6의 한 바이트 미리 읽기를 잰다.
- 긴 문자열 쓰기 1.08배.

## 3. 더 할 수 있는 것

- enum 이름 키는 직접 속성과 `Optional` 안에만 쓴다.
  `List<Enum>` 같은 컬렉션 원소는 아직 `name()`·`valueOf`다.
- HTTP 전체 벤치마크(`benchmark/run.sh`)의 재실행은 하지 않았다.
  부록의 처리량 표는 이전 실행 그대로이고, 시작은 사용자 지시 후다.
- json-bench를 네이티브 이미지로 빌드해 두 라이브러리를 재는 것(부록의 "미측정")은 하지 않았다.

## 4. 기계 상태

- 측정을 위해 2026-10-11에 전원 프로파일을 `balanced`에서 `performance`로 바꿨고(`powerprofilesctl set performance`), 그대로 두었다.
