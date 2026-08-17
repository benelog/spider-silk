# Spider Silk

## 설계 방침

- **spider-silk-core는 웹 티어만 담당한다.** 라우팅, 파라미터 추출, JSON, 템플릿까지만 core의 영역이다.
  트랜잭션/DB 헬퍼(`Transactions` 등)는 core가 아니라 example-flashcard(`flashcard.service`)에 둔다.
  core의 build.gradle에 spring-jdbc 같은 데이터 계층 의존성을 추가하지 말 것.
  (core에 새 기능을 추가할 때 영속성·트랜잭션·스케줄링 등 웹 티어 밖의 것이면 example 모듈이나 별도 모듈을 제안한다.)
- **No reflection.** core에는 어노테이션 스캔, 프록시, 자동 바인딩이 없다. 이 원칙을 깨는 변경은 하지 않는다.
- DI 컨테이너 없이 `FlashcardContext`가 생성자 직접 호출로 객체 그래프를 조립한다.

## 빌드/검증

```bash
./gradlew build   # 두 모듈 컴파일 + 전체 테스트
```

- 예제 앱의 H2 DB 파일 위치: `~/db/spider-silk/flashcard`
