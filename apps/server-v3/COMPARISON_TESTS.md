# v2·v3 응답 비교 테스트 하네스

기반 하네스는 **비교 도구와 테스트 환경**이다. 1번 묶음의 실제 API 검증은 AuthenticationCases와 AuthenticationFlowTest에서 추가한다.
v2는 계정 상태 회귀 수정이 포함된 저장소 소스를 그대로 빌드한다.
첫 비교 케이스는 없는 경로 404와 미인증 보호 경로 401이다.
401과 쓰기 비교의 v3 컨트롤러는 테스트 전용이다. 실제 기능 API의 이관 완료를 뜻하지 않는다.

## 실행과 결과 읽기

저장소 루트에서 PowerShell로 실행한다. Java 21, Node/npm, 실행 중인 Docker가 필요하다.

~~~powershell
cd apps/server-v3
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
.\gradlew.bat comparisonTest --rerun --console=plain
~~~

전체 검증은 154건이다. 기존 149건에 이미지 코덱 5건을 추가했다. 기존 하네스·엔티티 62건, 회원가입·로그인 검증 15건, 재발급·로그아웃 검증 20건, 비밀번호 찾기·재설정 15건, OAuth 계약 18건과 URL 검증 1건, 보호 경로 회원 상태 검증 4건을 포함한다. 기존 429 알려진 차이 1건을 필수 HTTP 검증 10건으로 바꾸고 제한 계산 테스트 5건을 추가했다.
`test` 53건과 `comparisonTest` 101건을 합친 기대 결과는 **성공 152 / 실패 0 / 코덱 알려진 차이 1 / 코덱 공통 지원 제약 1**이다. API 비교 101건의 알려진 차이는 0이다.
HEIC·AVIF 코덱 2건은 사유가 있는 SKIPPED이며 [코덱 보고서](CODEC_COMPATIBILITY.md)에서 v2 읽기 결과와 구분해 확인한다. HTTP 오류 응답까지 비교 완료한 것으로 해석하지 않는다.
429도 필수 통과 대상이며 JUnit abort/skip으로 처리하지 않는다. OAuth 18건은 v3 계약 검증이고 v2 HTTP 비교와 구분한다.
기본 `test`는 단위·v3 단독 검증만 실행한다. `comparisonTest`는 `@Tag("v2-comparison")` 클래스만 실행하고 v2·Mailpit을 한 번 준비한다. Prisma 스키마 검증도 이 작업에 포함한다. 두 그룹 전체는 `allTests`로 실행한다(`check`·`build`도 전체 검증).

- JUnit HTML: [build/reports/tests/comparisonTest/index.html](build/reports/tests/comparisonTest/index.html), v3 단독·단위 검증은 [test/index.html](build/reports/tests/test/index.html)
- 환경·대기 시간과 알려진 차이: [build/reports/compatibility/summary.json](build/reports/compatibility/summary.json)
- Gradle 콘솔: 전체 빌드 실행 시간, v2 빌드·기동 횟수, 인증 요청 대기 밀리초, 알려진 차이 개수
- `environmentMillis`: 컨테이너 준비 시작부터 공유 환경 종료 처리 전까지의 벽시계 시간이다. 개별 테스트 실행 시간의 합과 다르다.
- 초기 이미지 다운로드·빌드 시간은 환경 시간에 포함된다. 첫 실행과 캐시된 실행 시간을 구분해서 읽는다.

404·401 케이스만 재실행할 수 있다.

~~~powershell
.\gradlew.bat comparisonTest --tests '*ResponseComparisonTest' --rerun --console=plain
~~~

기존 4단계 인증 검증은 `--tests '*ApiCompatibilityIntegrationTest'`,
필수 요청 제한 비교는 `--tests '*RateLimitCasesTest'`로 실행한다.
필터 실행 시 선택하지 않은 케이스까지 검증했다고 해석하지 않는다.

## 탑다운으로 읽는 순서

모든 Java 파일은 `src/test/java/com/loco/compat/`에 있다.

| 파일 | 역할 |
| --- | --- |
| `FoundationCases.java` | 0번 묶음의 404·401 요청과 기대 상태를 선언한다. |
| `ComparisonCase.java` | 이름으로 시작하는 빌더로 시드·순차 요청·DB 규칙을 선언한다. |
| `ComparisonStep.java` | 한 요청의 v2/v3 경로·본문·인증·기대 상태·응답 예외·저장할 값을 선언한다. |
| `ScenarioValues.java` | 한 시나리오에서 서버별 응답 값을 보관하고 다음 요청에 전달한다. |
| `ScenarioCaseTest.java` | 불완전한 선언, 값 누락·중복, 서버 간 토큰 혼용을 검사한다. |
| `ResponseComparisonTest.java` | 케이스마다 JUnit 테스트를 실행한다. |
| `CompatibilityTestBase.java` | v3 테스트 앱 연결, HTTP 요청, 초기화 → 요청 → 응답·DB 비교 순서를 연결한다. |
| `SharedHarness.java` | 전체 JUnit 실행에서 DB·Mailpit·v2를 한 번 준비하고 종료한다. 시드, 직접 JWT 서명, 메일 수집과 보고서도 담당한다. |
| `JsonComparison.java` | JSON 키·타입·null·배열 길이·순서를 확인하고 선언된 말단 경로의 값만 제외한다. |
| `DatabaseSnapshot.java` | 업무 테이블 전체를 읽고 정렬·UUID 대응·시간/토큰 해시 검증 후 비교한다. |
| `AuthRequestBudget.java` | 인증 API 케이스만 요청량을 관리하고 필요한 시간만 기다린다. |
| `HarnessControllers.java` | 인증·DTO·쓰기 비교를 위한 테스트 대역이다. 운영 JAR에 들어가지 않는다. |
| `HarnessInfrastructureTest.java` | 실제 HTTP 쓰기·DB 초기화·관계 비교·Mailpit 수신을 검증한다. 기능 이관 검증과 구분한다. |
| `JsonComparisonTest.java`, `AuthRequestBudgetTest.java` | 제외 규칙이 차이를 숨기지 않는지, 제한 횟수·대기 정책이 맞는지 검사한다. 대기 정책의 단위 테스트는 가상 시계를 사용한다. |
| `RateLimitCasesTest.java` | 모든 인증 API 한도와 429 응답·헤더, JSON 파싱·인증 순서를 비교한다. |

`src/test/resources/compat/seed.sql`은 고정 회원 ID와 기본 데이터다.
비밀번호는 파일에 저장하지 않고 v2의 bcrypt로 실행 중 생성해 주입한다.
`feedback.json`은 쓰기 하네스 자체 검증용 요청 데이터다.

기존 `security/ApiCompatibilityIntegrationTest.java`의 24개 검증은 공유 환경을 사용한다.
준비 토큰은 직접 서명한다. 실제 v2 로그인 발급 토큰 검증은 해당 테스트 안에서만 로그인 API를 호출한다.
Health·Flyway·선언 누락 테스트까지 기존 35개 검증을 유지한다.

## 환경 수명과 초기화

- Gradle test JVM 한 개, JUnit 병렬 실행 비활성화.
- JUnit root store가 공유 환경을 보관하며 이미지 빌드·최초 v2 기동은 각각 한 번이다. C 방식으로 HTTP 비교 케이스 사이 같은 컨테이너를 재시작한다.
- PostgreSQL 17(`postgis/postgis:17-3.6`) 한 개에 `loco_v2_test`, `loco_v3_test`를 만든다.
- 두 DB 모두 같은 Flyway migration 경로와 `target("1")`로 준비한다. v3 테스트 앱의 Flyway target도 1이다.
- 하네스 준비에는 Prisma db push를 사용하지 않는다. 별도 Flyway baseline 테스트의 기존 DB 인수 검증은 그대로 유지한다.
- DB와 메일은 개발 Compose와 격리된다. 연결 주소는 생성한 Testcontainers에서 얻으며 외부 DB 설정을 초기화 대상으로 받지 않는다.
- 각 케이스는 업무 테이블 전체 TRUNCATE → 시드 SQL → 동일 bcrypt 해시 주입 → 메일 비우기 순서로 시작한다.
- Flyway 이력은 TRUNCATE와 업무 데이터 비교에서 제외한다.
- 초기 상태 동등성을 확인한 뒤 요청한다. 읽기 전용 케이스는 요청 뒤 각 DB가 이전 상태와 정확히 같은지도 검사한다.
- 여러 요청이 필요한 시나리오는 초기화 한 번 뒤 요청들을 순서대로 실행하고 **각 단계 뒤** 응답과 DB 상태를 비교한다. 중간 실패 시 다음 단계는 실행하지 않는다.
- 환경 준비 실패를 캐시해 같은 실행에서 반복 빌드·기동하지 않는다.

`build.gradle`은 v2 소스·lockfile·설정·Dockerfile·시작 스크립트를 test 입력으로 등록한다.
비교 대상이 바뀌면 이전 UP-TO-DATE 결과를 재사용하지 않는다. 테스트 Java/리소스는 Gradle의 기본 입력이다.

## 준비용 JWT와 비밀번호

JWT는 테스트의 동일한 JWT_SECRET으로 HS256 서명한다. sub·email과 iat·exp를 담으며 기본 수명은 7일이다.
준비를 위해 로그인이나 회원가입 API를 호출하지 않는다. 서명 키와 비밀번호는 실행마다 생성한다.

시드 비밀번호는 v2 이미지에 설치된 bcrypt로 `hash(password, 10)`을 한 번 실행해 생성한다.
양쪽 DB에 동일 해시를 넣는다. 실제 v2 로그인 테스트가 이 시드를 검증한다.

추후 비밀번호 변경 API를 이관하면 서로 다른 salt로 만든 bcrypt 문자열을 단순 제외해서는 안 된다.
해당 케이스에 비용·형식·`bcrypt.compare` 의미 검증을 함께 추가해야 한다.
기본값은 비밀번호 해시의 정확한 비교다. 인증 케이스는 Rules.passwords에 이메일과 기대 비밀번호를 지정해 $2b$·cost 10·비밀번호 일치를 확인한 후 값만 정규화한다.

## HTTP 비교 규칙

기대 상태는 양쪽 각각 확인한다. 그 뒤 JSON 객체 키·타입·null·배열 길이·배열 순서와 값을 비교한다.
오류 위치만 보고하며 토큰·비밀번호가 들어갈 수 있는 응답 전체를 실패 메시지에 출력하지 않는다.

값 제외는 각 단계의 `valueException(pointer, reason)`에 경로와 이유를 등록한다.

~~~java
// ComparisonStep 빌더에서 해당 단계에 필요한 예외만 선언한다.
.valueException("/id", "각 DB에서 생성한 UUID")
.valueException("/items/*/id", "배열 원소별 생성 UUID")
.valueException("/message", "깨진 JSON: 런타임 파서 문구 차이")
~~~

경로 문법은 JSON Pointer의 `~0`·`~1` 이스케이프와 배열 인덱스, 배열 인덱스 자리의 `*`다.
일반 JSONPath 해석기가 아니다. 객체·배열 전체를 지우는 방식은 허용하지 않는다.
제외 경로가 실제 말단 값과 매칭되지 않으면 실패한다. 빈 배열의 `/*/...`도 매칭이 없으므로 실패한다.
제외된 값도 타입/null 여부는 같아야 한다.

깨진 JSON의 메시지 예외는 비교기 단위 테스트로 검증한다.
실제 로그인 API의 깨진 JSON은 상태·키·타입과 케이스별 문구 예외를 검증한다. 다른 API는 이관 시 각각 검증한다.
실제 API 케이스를 등록할 때 구조가 다르면 실패하며, 예외로 숨기지 않는다.
일반 ValidationPipe 메시지 배열은 정확히 비교한다.

## DB 비교 규칙과 한계

1. 전체 업무 테이블과 컬럼을 읽는다. 누락된 테이블 규칙·컬럼·행은 실패한다.
2. 테이블 이름과 컬럼 순서를 고정한다.
3. 행은 ID·시간·토큰 해시를 제외한 컬럼으로 정렬한다.
4. 시드 UUID는 원래 값을 유지한다.
5. 정렬된 행의 새 PK UUID를 등장 순서대로 `$id1`, `$id2`에 대응시킨 뒤 외래 키에도 같은 대응표를 적용한다.
6. 대응표는 테이블별로 나누지 않고 한 DB 전체에서 공유한다. 양쪽 DB는 각자의 대응표를 만든다.
7. UUID 형식, null 여부, 모든 행과 참조 관계를 비교한다.

PK를 먼저 대응시키므로 외래 키가 잘못 바뀌어도 별칭을 다시 만들어 오류를 감추지 않는다.
폴리모픽 `reports.targetId`와 FK가 없는 `place_marker_cells.placeId`도 UUID 대상으로 명시한다.
OAuth providerId 같은 일반 문자열은 UUID로 취급하지 않는다.

시간 컬럼은 유효한 날짜·시간으로 읽히는지 검사한 뒤 값만 제외한다.
`refresh_tokens.tokenHash`, `password_reset_tokens.tokenHash`는 64자리 소문자 16진수인지 검사한 뒤 값만 제외한다.
모두 null 여부는 보존한다. 만료 의미나 코드와 해시 관계는 케이스에서 별도로 검사한다.

안정적인 정렬 키가 같은 행이 여러 개라면 자동으로 통과시키지 않는다.
케이스의 `DatabaseSnapshot.Rules`에 비-ID 식별 컬럼을 지정해야 한다.
자체 식별 컬럼이 없는 관계 테이블은 관련 테이블의 email·title 등을 JOIN해 `__sort_...` 컬럼으로 제공할 수 있다.

~~~java
new DatabaseSnapshot.Rules(
    Map.of("favorites", List.of("__sort_map")),
    Map.of("favorites",
        "SELECT f.*, m.title AS \"__sort_map\" FROM favorites f JOIN maps m ON m.id=f.\"mapId\""));
~~~

추가 정렬 컬럼은 비교 결과에서 제거한다. 원본 업무 컬럼과 전체 행 수는 유지해야 한다.
SQL은 테스트 작성자가 관리하는 SELECT이며 사용자 입력을 받지 않는다.
모든 그래프의 동형성을 자동 판정하는 도구는 아니다. 식별 기준이 모호하면 데이터나 케이스 기준을 명확히 해야 한다.

## 요청 제한과 C 방식

v2 코드를 수정하거나 제한을 비활성화하지 않는다. 준비 토큰은 직접 서명한다.
기본 한도는 처리 메서드·접속 IP별 120회/분이다. 인증 API는 register 5, login 10, forgot-password 3, reset-password 5, oauth 10, refresh/logout 120회/분이다.
OAuth provider 경로는 같은 핸들러라 한도를 공유한다. DTO 오류·로그인 실패도 포함하지만 Express 파싱 단계에서 거절되는 깨진 JSON은 포함하지 않는다.

사용자가 선택한 C 방식은 각 JUnit HTTP 비교 케이스 시작 전에 같은 v2 컨테이너를 Docker restart한다. DB·Mailpit·이미지는 재생성하지 않는다.
하나의 ComparisonCase 안에서 이어지는 요청과 동시 요청 도중에는 재시작하지 않는다. 준비 완료는 없는 경로의 404로 확인해 한도를 소모하지 않는다.
Docker 재시작 직후 포트 정보가 잠시 비거나 임의 호스트 포트가 달라질 수 있으므로, 같은 컨테이너 ID를 inspect해 현재 포트 게시와 HTTP 준비를 함께 확인한다.
v2 HTTP를 사용하지 않는 엔티티·라이브러리·OAuth 계약 검증은 재시작을 생략한다.
v3는 HarnessRateLimitTime을 통해 제한 계산의 시계만 다음 61초 구간으로 이동한다. 구간 안에서는 실제 시간이 흐르며 JWT·JPA 시계에는 영향을 주지 않는다.
이는 케이스 간 상태 초기화다. 실제 제한 알고리즘과 HTTP interceptor는 그대로 실행하며, 구간 내 제한·만료·재차단은 HTTP/가상 시계 테스트로 검증한다.

AuthRequestBudget은 양쪽 인증 API 요청을 기록하며 한 시나리오가 실제 한도를 넘으면 필요한 시간만 대기한다. 예상하지 않은 429를 재시도해 숨기지 않는다.
RateLimitCasesTest는 별도 probe로 실제 한도를 넘겨 status·JSON·Limit/Remaining/Reset·Retry-After를 비교한다. 시간 헤더는 유효 범위로 검증한다.
7개 인증 API의 한도, 공개 OAuth provider 간 공유, 보호 경로의 429 우선순위, X-Forwarded-For로 IP를 위조해도 한도가 바뀌지 않는지 확인한다.
깨진 JSON을 11번 보내도 로그인 잔여 횟수가 9로 시작하는지 비교한다. JSON 문구 차이만 해당 케이스에 명시한다.

summary.json에는 최초 빌드·기동 수, 재시작 수, 양쪽 인증 대기 시간, 알려진 차이 목록을 기록한다. 429는 알려진 차이 등록과 abort를 제거한 필수 케이스다.

## Mailpit

`axllent/mailpit:v1.27.4` 하나를 공유한다.
v2의 기존 SMTP 환경변수를 mailpit:1025로 연결하고 테스트 전용 인증 허용 설정을 사용한다.
외부 릴레이는 설정하지 않는다. v2 메일 코드는 수정하지 않는다.

메일 API로 우편함을 비우고, 수신자 기준으로 최대 10초 동안 수신을 기다린다.
v2/v3 메일 비교를 추가할 때는 한쪽 요청·메일 수집 → 우편함 비우기 → 다른 쪽 요청·메일 수집 순서로 실행한다.
현재 자체 검증은 실제 v2 비밀번호 찾기 요청으로 수신자·제목·6자리 코드·DB의 SHA-256 관계를 확인한다.
PasswordRecoveryTest는 양쪽 실제 메일의 발신자·수신자·제목·HTML(코드만 제외), 코드 해시·15분 만료, 재설정 흐름을 비교한다.

## 1번 묶음부터 케이스 추가

묶음별 `AuthenticationCases`, 이후 지도·장소 케이스 파일에 Java 케이스 목록을 추가한다.
SQL 초기 데이터와 JSON 요청은 `src/test/resources/compat/` 아래에 둔다.
필요한 묶음이 생길 때만 파일을 만들며 빈 파일이나 별도 DSL을 미리 추가하지 않는다.

각 이관 API에는 정상·미인증·잘못된 입력·DB 변경 사례를 추가한다.
단순 요청도 여러 요청도 `ComparisonCase.named(...)`와 `ComparisonStep.named(...)`로 표현한다.
시드 기본값은 `compat/seed.sql`, DB 규칙 기본값은 `Rules.defaults()`다. `databaseUnchanged()`는 모든 단계 후 초기 상태와 정확히 같은지도 확인한다.
기대 HTTP 상태에는 기본값이 없으며 반드시 지정한다. `.authenticated()`는 준비용 JWT를 직접 서명한다.
메일 수집·암호의 의미 검증은 같은 base를 사용하는 JUnit 메서드에서 추가한다. 복잡한 동작을 JSON 설정 언어로 확장하지 않는다.

### 테스트 대역과 실제 API를 구분하는 규칙

`HarnessControllers`의 모든 경로는 **`/api/__test/harness/...`** 아래에 둔다.
로그인·회원·피드백 대역도 각각 `/api/__test/harness/auth/login`, `/api/__test/harness/users/me`, `/api/__test/harness/feedback`이다.
대역의 GET/PUT/POST를 실제 `/api/auth/login`, `/api/users/me`, `/api/feedback`에 등록하지 않는다.
테스트 소스의 `@TestComponent`와 명시적 `@Import`로만 등록하며 운영 JAR에는 포함하지 않는다.

비교 케이스의 `path`는 v2 실제 경로다. 대역 비교일 때만 `v3Path`로 별도 경로를 지정한다.
`v3Path`를 생략하면 양쪽이 같은 `path`를 사용한다. 대역 경로로 자동 우회하는 로직은 없다.

실제 API를 이관하는 커밋에서 다음을 함께 수행한다.

1. 해당 비교 케이스를 묶음별 파일(예: `AuthenticationCases`)로 옮기고 대역임을 나타내는 이름을 제거한다.
2. `v3Path`를 제거해 양쪽 실제 경로로 비교한다. 기존 직접 `request(v3Url(), ...)` 호출도 실제 경로로 변경한다.
3. 실제 정상·오류·DB 변경을 검증한다. 대역에서만 통과하던 케이스를 실제 API 통과로 세지 않는다.
4. 하네스 자체 검증용 대역은 테스트 namespace에 남길 수 있지만 API 호환 검증과 구분한다.
5. `HarnessInfrastructureTest`의 아직 미이관인 실제 경로 404 확인에서 이관한 경로를 제거한다. 나머지 경로 검증은 유지한다.
6. 로그인 이관 시 `RateLimitCasesTest`의 v3 경로도 실제 로그인으로 옮긴다. 요청 제한까지 구현되면 knownDifferences/abort를 제거하고 429를 필수 통과로 바꾼다.

404 메시지에 경로가 포함되는 사례는 양쪽에 같은 없는 경로를 요청한다.
테스트 대역의 미지원 메서드 404는 별도로 확인하며 경로 차이를 숨기기 위해 기본 비교를 느슨하게 하지 않는다.

### 연속 요청과 이전 응답 재사용

`capture("이름", "/JSON/Pointer")`로 응답을 저장한다. 각 서버의 값은 독립된 `ScenarioValues`에 보관한다.
다음 단계에서 `values.text("이름")` 또는 타입을 유지하는 `values.value("이름")`을 본문·경로·Bearer에 사용할 수 있다.
문자열 치환 대신 Java Map/JsonNode를 본문으로 넘겨 JSON escaping을 유지한다. 경로에 일반 문자열 값을 넣을 때는 URI 인코딩한다.
저장할 값이 누락/null이거나 이름이 중복되면 실패한다. 갱신 토큰은 `loginRefresh`, `rotatedRefresh`처럼 다른 이름을 쓴다.
단계 이름도 중복을 금지하며 실패 결과에 케이스·단계 이름을 표시한다. 응답 원문과 비밀 값은 오류에 출력하지 않는다.

아래는 구현된 인증 API를 연결하는 작성 예시다. 실제 실행 케이스는 `auth/AuthenticationFlowTest`, `auth/RefreshFlowTest`에 있으며 DB 역할 규칙과 토큰 의미 검증도 함께 지정한다.
`email`, `password`, `nickname`은 테스트에서 준비하는 값이다.

~~~java
ComparisonCase.named("1번 묶음: 회원가입 → 로그인 → 재발급")
    .step(ComparisonStep.named("회원가입").method(HttpMethod.POST)
        .path("/api/auth/register").expectedStatus(201)
        .body(v -> Map.of("email", email, "password", password, "nickname", nickname))
        .valueException("/accessToken", "서버별 사용자 ID와 발급 시각")
        .valueException("/refreshToken", "무작위 토큰").build())
    .step(ComparisonStep.named("로그인").method(HttpMethod.POST)
        .path("/api/auth/login").expectedStatus(201)
        .body(v -> Map.of("email", email, "password", password))
        .valueException("/accessToken", "서버별 사용자 ID와 발급 시각")
        .valueException("/refreshToken", "무작위 토큰")
        .capture("loginAccess", "/accessToken").capture("loginRefresh", "/refreshToken").build())
    .step(ComparisonStep.named("재발급").method(HttpMethod.POST)
        .path("/api/auth/refresh").expectedStatus(201)
        .body(v -> Map.of("refreshToken", v.text("loginRefresh")))
        .valueException("/accessToken", "서버별 사용자 ID와 발급 시각")
        .valueException("/refreshToken", "무작위 토큰")
        .capture("rotatedRefresh", "/refreshToken").build())
    .build();
~~~

보호 API 단계에서는 `.bearer(v -> v.text("loginAccess"))`로 이전 응답의 토큰을 사용한다.
공개 refresh API 자체에는 Bearer가 필요 없다. `.authenticated()`와 `.bearer(...)`를 동시에 지정하면 실패한다.
본문도 `.bodyResource(...)`와 `.body(...)` 중 하나만 지정한다.

회원가입·로그인·재발급 시나리오는 bcrypt 의미 검증과 응답 토큰 해시 기반 역할 정렬을 사용한다. `auth/AuthenticationSupport`가 공통 규칙을 제공한다.
비밀번호 의미 검증을 명시하지 않았거나 여러 토큰 행의 대응 기준이 없으면 자동 제외하지 않는다. databaseRules(values -> ...)로 각 서버의 캡처 값에서 규칙을 만들 수 있다. runCase는 서버별 ScenarioValues를 반환해 JWT 서명·claim·DB 해시 같은 추가 검증에 사용한다.
지금 실행되는 시나리오 자체 검증은 피드백 쓰기 → 이전 메시지로 두 번째 쓰기 → 서버별 생성 ID를 사용한 없는 경로 요청이다.
동일한 DB를 계속 사용하며 각 단계의 응답·DB 상태와 각 서버에서 생성된 ID의 실제 존재를 확인한다.

보호 경로의 탈퇴·없는 회원 차단과 현재 email 사용은 MemberAuthenticationTest에서 검증한다.

후속 묶음의 검증 항목:

- 아직 이관하지 않은 DTO의 전체 검증 규칙
- 후속 API의 예외·트랜잭션 경계
- 후속 API의 깨진 JSON 문구 예외 등록
- 이후 묶음의 실제 엔드포인트와 DTO 검증

공식 참고: [Mailpit SMTP](https://mailpit.axllent.org/docs/configuration/smtp/),
[Mailpit API](https://mailpit.axllent.org/docs/api-v1/),
[Nest Throttler 6.5.0](https://github.com/nestjs/throttler/tree/v6.5.0).
