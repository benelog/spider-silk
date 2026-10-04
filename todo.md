# TODO

## 템플릿 모델 API 변경의 남은 작업

core와 호출부는 바뀌었고 `./gradlew build`가 통과한다(아직 커밋하지 않음).
jte를 클래스패스에서 뺀 FreeMarker 앱도 정상 동작함을 확인했다.

- `WebResponse.template(name)`: 모델이 없는 템플릿.
- `WebResponse.template(name, Model)`: `Map`이 아니라 `Model` 값 타입을 받는다.
- `Model`: `Map`을 돌려주던 유틸리티에서 불변 값 타입이 되었다.
  - `Model.of(...)`: 열 쌍까지, null 값 허용, 순서 유지, null 키는 `NullPointerException`, 중복 키는 `IllegalArgumentException`.
  - `model.with(key, value)`: 항목을 더한 새 `Model`을 돌려준다. 조건에 따라 항목이 늘어나는 모델에 쓴다.
  - `model.asMap()`: 읽기 전용 Map. Map을 받는 엔진에 렌더러가 넘기는 용도다.
- `TemplateRenderer.render(String template, Model model, Writer out)`: 엔진 연결 인터페이스도 `Map` 대신 `Model`을 받는다. 렌더러 네 개(jte, FreeMarker, Handlebars, Thymeleaf)는 `model.asMap()`을 엔진에 넘긴다.
- `WebResponse.Template`은 다시 record이며 `Template(String name, Model model)`이다.
- 제거: `template(name, Map)`.
- core는 jte-models를 지원하는 API를 따로 두지 않는다. 애플리케이션은 `WebResponse.html(templates.deck(deck).render())`로 쓴다.

### 한국어 매뉴얼 (`manual-ko/`)

코드 블록은 이미 `template(name, Model.of(...))`로 바뀌었고, 아래는 문장을 고치는 일이다.

- [ ] `templates.adoc`: "`Model.of`는 읽기 전용 맵을 답한다", "조건에 따라 항목이 늘어나는 모델은 `HashMap`이며 `template(name, model)`은 둘 다 받는다"를 고친다.
  - `Model`은 맵이 아니라 값 타입이고, 조건부 항목은 `with`로 더한다.
- [ ] `templates.adoc`: jte-models 절을 추가한다.
  - 빌드 설정: `jte { jteExtension('gg.jte.models.generator.ModelExtension') }`, `jteGenerate`와 `implementation`에 `gg.jte:jte-models`
    - 이 설정은 아직 이 저장소에서 빌드해 보지 않았다. 예제 앱에 적용해 확인한 뒤 문서에 싣는다.
  - 핸들러: `WebResponse.html(templates.deck(deck).render())`
    - 렌더링은 핸들러 안에서 일어나므로 예외는 그대로 예외 핸들러로 간다.
    - `template()`과 달리 after-route 필터보다 먼저 렌더링한다는 점을 적는다.
  - `StaticTemplates`(운영)와 `DynamicTemplates`(개발 중 바로 반영, `@param`을 바꾸면 다시 빌드)
  - `App.templates(...)`를 거치지 않는다는 점
- [ ] `index.adoc`의 jte 절: "`Map`이나 `Model.of`로 넘기는 모델은 컴파일러가 검사하지 않는다"는 비용 문장을 jte-models로 해소할 수 있다는 내용으로 고친다.
- [ ] `sessions-and-flash.adoc`, `api-summary.adoc`, `response.adoc`: `Map`으로 모델을 넘기는 설명과 `template(name, model)`의 시그니처를 고친다.

### 다이어그램

- [ ] `manual/diagrams/template-rendering.drawio`에 `template("deck", model)`이 남아 있다. 새 시그니처에 맞는지 확인하고, 고쳤다면 `npm run diagrams`로 SVG를 다시 내보낸다(두 매뉴얼이 같은 그림을 쓴다).

### 에이전트 스킬 (`skills/spider-silk/`)

- [ ] `SKILL.md`: "`Model.of`는 읽기 전용 맵"이라는 안내를 값 타입과 `with`로 고친다.
- [ ] `references/content.md`: 같은 안내를 고치고, jte-models 패턴(`WebResponse.html(templates.deck(deck).render())`)을 추가한다.
- [ ] `evals/`에 `template(name, Map)`이나 `Map.of`로 모델을 넘기는 기대가 있는지 확인한다.

### 기록

- [ ] `CHANGELOG.md`의 Unreleased에 깨지는 변경으로 적는다: `template(name, Map)`이 `template(name, Model)`로 바뀜, `Model`이 값 타입이 됨(`with`, `asMap`), `TemplateRenderer.render`가 `Map` 대신 `Model`을 받음(직접 구현한 렌더러는 `model.asMap()`으로 고친다).
- [ ] `notes/decisions.md`에 새 결정을 추가한다.
  - 템플릿 모델은 `Map`이 아니라 불변 `Model` 값 타입이다. 핸들러가 Map을 만들거나 넘기는 API를 없앴다.
  - `TemplateRenderer`도 `Model`을 받는다. Map은 엔진에 넘기는 경계인 `asMap()` 한 곳에서만 나온다. 엔진 모듈이 다른 패키지에 있으므로 `asMap()`은 public이다.
  - jte-models는 core가 따로 지원하지 않는다. `JteModel.render()`가 문자열을 돌려주므로 `WebResponse.html(...)`로 충분하다.
- [ ] 결정 71의 `Model.of` 항목("mirrors `Map.of`", 맵을 돌려준다)을 새 결정에 맞춰 고친다.
- [ ] 결정 72의 "caller's side is not type-checked" 비용 항목에 jte-models와 `html(model.render())`로 해소할 수 있다고 적는다.

### 정리

- [ ] `.claude/worktrees/agent-aa9661317e7b1944c`를 되돌린다: `git -C .claude/worktrees/agent-aa9661317e7b1944c checkout -- .`
  - 일괄 변환 스크립트가 실수로 이 worktree의 파일 30개도 바꿨고, 원래 그 worktree에 있던 변경은 없었다.

## 영문 매뉴얼 (`manual/`)을 한국어 매뉴얼에 맞추기

한국어 매뉴얼(`manual-ko/`)을 먼저 고치고, 영문 매뉴얼은 아래 항목을 모아 한 번에 반영한다.
영문 페이지가 원본이라는 CLAUDE.md의 규칙과 달리, 이 기간에는 한국어 페이지가 앞서 있다.

### 소개 페이지 재구성 (커밋 `9c027b0`)

- [ ] `manual/modules/ROOT/pages/index.adoc`를 `manual-ko`의 같은 페이지 구조로 옮긴다.
  - 추구하는 가치: 빠른 부팅 시간, 명확한 코드 추적성, 오픈소스 기술 생태계와의 조화
  - 가치를 위한 기술 선택: 리플렉션 없음, 최소한의 콜 스택 추가, API의 단순성
  - 디폴트 기술 스택(Jetty, jte), 확장성 있는 모듈 구조, 개발도구 생태계 연동
- [ ] "세 가지 핵심 원칙", "한눈에 보기", "포지셔닝과 로드맵" 절이 영문에만 남아 있으므로, 옮기면서 함께 정리한다.

### 템플릿 모델 API 변경

- [ ] 아래 페이지의 코드 블록은 일괄 변환 스크립트가 이미 `template(name, Model.of(...))`로 바꿨다. 문장은 아직 옛 API를 설명한다.
  - `freemarker.adoc`, `handlebars.adoc`, `handlers.adoc`, `index.adoc`, `request.adoc`, `route-introspection.adoc`, `templates.adoc`, `thymeleaf.adoc`
- [ ] `Map.of`와 `HashMap`으로 모델을 만드는 설명을 `Model` 값 타입(`Model.of`, `with`)으로 바꾼다.
  - `templates.adoc`, `api-summary.adoc`, `sessions-and-flash.adoc`, `response.adoc`
- [ ] `templates.adoc`에 jte-models 절을 한국어 페이지에 맞춰 추가한다.
