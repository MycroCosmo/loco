# v2·v3 응답 비교 테스트 하네스

5단계의 구현 범위는 **비교 도구와 테스트 환경**이다. 운영 API를 추가하지 않는다.
v2는 계정 상태 회귀 수정이 포함된 저장소 소스를 그대로 빌드한다.
첫 비교 케이스는 없는 경로 404와 미인증 보호 경로 401이다.
401과 쓰기 비교의 v3 컨트롤러는 테스트 전용이다. 실제 기능 API의 이관 완료를 뜻하지 않는다.

## 실행과 결과 읽기

저장소 루트에서 PowerShell로 실행한다. Java 21, Node/npm, 실행 중인 Docker가 필요하다.

~~~powershell
cd apps/server-v3
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
.\gradlew.bat test bootJar --rerun-tasks --console=plain
~~~

전체 검증은 55건이다. 기대 결과는 **성공 54 / 실패 0 / 알려진 차이 1**이다.
알려진 차이도 실제 요청을 실행한다. JUnit의 aborted/skipped 분류를 사용하지만 이름·이유를 명시하며 성공으로 집계하지 않는다.
현재 항목은 로그인 11번째 요청의 v2 429 / v3 테스트 컨트롤러 400이다.

- JUnit HTML: [build/reports/tests/test/index.html](build/reports/tests/test/index.html)
- 환경·대기 시간과 알려진 차이: [build/reports/compatibility/summary.json](build/reports/compatibility/summary.json)
- Gradle 콘솔: 전체 빌드 실행 시간, v2 빌드·기동 횟수, 인증 요청 대기 밀리초, 알려진 차이 개수
- `environmentMillis`: 컨테이너 준비 시작부터 공유 환경 종료 처리 전까지의 벽시계 시간이다. 개별 테스트 실행 시간의 합과 다르다.
- 초기 이미지 다운로드·빌드 시간은 환경 시간에 포함된다. 첫 실행과 캐시된 실행 시간을 구분해서 읽는다.

404·401 케이스만 재실행할 수 있다.

~~~powershell
.\gradlew.bat test --tests '*ResponseComparisonTest' --rerun-tasks --console=plain
~~~

기존 4단계 인증 검증은 `--tests '*ApiCompatibilityIntegrationTest'`,
알려진 차이 관측은 `--tests '*RateLimitCasesTest'`로 실행한다.
필터 실행 시 선택하지 않은 케이스까지 검증했다고 해석하지 않는다.

## 탑다운으로 읽는 순서

모든 Java 파일은 `src/test/java/com/loco/compat/`에 있다.

| 파일 | 역할 |
| --- | --- |
| `FoundationCases.java` | 0번 묶음의 404·401 요청과 기대 상태를 선언한다. |
| `ComparisonCase.java` | 요청, 시드 파일, 기대 상태, 응답 예외, DB 규칙을 담는 값 객체다. |
| `ResponseComparisonTest.java` | 케이스마다 JUnit 테스트를 실행한다. |
| `CompatibilityTestBase.java` | v3 테스트 앱 연결, HTTP 요청, 초기화 → 요청 → 응답·DB 비교 순서를 연결한다. |
| `SharedHarness.java` | 전체 JUnit 실행에서 DB·Mailpit·v2를 한 번 준비하고 종료한다. 시드, 직접 JWT 서명, 메일 수집과 보고서도 담당한다. |
| `JsonComparison.java` | JSON 키·타입·null·배열 길이·순서를 확인하고 선언된 말단 경로의 값만 제외한다. |
| `DatabaseSnapshot.java` | 업무 테이블 전체를 읽고 정렬·UUID 대응·시간/토큰 해시 검증 후 비교한다. |
| `AuthRequestBudget.java` | 인증 API 케이스만 요청량을 관리하고 필요한 시간만 기다린다. |
| `HarnessControllers.java` | 인증·DTO·쓰기 비교를 위한 테스트 대역이다. 운영 JAR에 들어가지 않는다. |
| `HarnessInfrastructureTest.java` | 실제 HTTP 쓰기·DB 초기화·관계 비교·Mailpit 수신을 검증한다. 기능 이관 검증과 구분한다. |
| `JsonComparisonTest.java`, `AuthRequestBudgetTest.java` | 제외 규칙이 차이를 숨기지 않는지, 제한 횟수·대기 정책이 맞는지 검사한다. 대기 정책의 단위 테스트는 가상 시계를 사용한다. |
| `RateLimitCasesTest.java` | 실제 v2 제한을 초과시켜 알려진 429 차이를 관측한다. |

`src/test/resources/compat/seed.sql`은 고정 회원 ID와 기본 데이터다.
비밀번호는 파일에 저장하지 않고 v2의 bcrypt로 실행 중 생성해 주입한다.
`feedback.json`은 쓰기 하네스 자체 검증용 요청 데이터다.

기존 `security/ApiCompatibilityIntegrationTest.java`의 24개 검증은 공유 환경을 사용한다.
준비 토큰은 직접 서명한다. 실제 v2 로그인 발급 토큰 검증은 해당 테스트 안에서만 로그인 API를 호출한다.
Health·Flyway·선언 누락 테스트까지 기존 35개 검증을 유지한다.

## 환경 수명과 초기화

- Gradle test JVM 한 개, JUnit 병렬 실행 비활성화.
- JUnit root store가 공유 환경을 보관하며 이미지 빌드·v2 기동은 각각 한 번이다.
- PostgreSQL 17(`postgis/postgis:17-3.6`) 한 개에 `loco_v2_test`, `loco_v3_test`를 만든다.
- 두 DB 모두 같은 Flyway migration 경로와 `target("1")`로 준비한다. v3 테스트 앱의 Flyway target도 1이다.
- 하네스 준비에는 Prisma db push를 사용하지 않는다. 별도 Flyway baseline 테스트의 기존 DB 인수 검증은 그대로 유지한다.
- DB와 메일은 개발 Compose와 격리된다. 연결 주소는 생성한 Testcontainers에서 얻으며 외부 DB 설정을 초기화 대상으로 받지 않는다.
- 각 케이스는 업무 테이블 전체 TRUNCATE → 시드 SQL → 동일 bcrypt 해시 주입 → 메일 비우기 순서로 시작한다.
- Flyway 이력은 TRUNCATE와 업무 데이터 비교에서 제외한다.
- 초기 상태 동등성을 확인한 뒤 요청한다. 읽기 전용 케이스는 요청 뒤 각 DB가 이전 상태와 정확히 같은지도 검사한다.
- 여러 요청이 필요한 시나리오는 초기화 한 번 뒤 요청들을 순서대로 실행하고 마지막에 상태를 비교한다.
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
현재 비교기는 시드 비밀번호 해시를 정확히 비교한다.

## HTTP 비교 규칙

기대 상태는 양쪽 각각 확인한다. 그 뒤 JSON 객체 키·타입·null·배열 길이·배열 순서와 값을 비교한다.
오류 위치만 보고하며 토큰·비밀번호가 들어갈 수 있는 응답 전체를 실패 메시지에 출력하지 않는다.

값 제외는 케이스의 `valueExceptions`에 경로와 이유를 등록한다.

~~~java
Map.of("/id", "각 DB에서 생성한 UUID");
Map.of("/items/*/id", "배열 원소별 생성 UUID");
Map.of("/message", "깨진 JSON: 런타임 파서 문구 차이");
~~~

경로 문법은 JSON Pointer의 `~0`·`~1` 이스케이프와 배열 인덱스, 배열 인덱스 자리의 `*`다.
일반 JSONPath 해석기가 아니다. 객체·배열 전체를 지우는 방식은 허용하지 않는다.
제외 경로가 실제 말단 값과 매칭되지 않으면 실패한다. 빈 배열의 `/*/...`도 매칭이 없으므로 실패한다.
제외된 값도 타입/null 여부는 같아야 한다.

깨진 JSON의 메시지 예외는 비교기 단위 테스트로 검증한다.
현재 v3의 깨진 JSON 실응답 전체 구조까지 호환된다고 주장하지 않는다.
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

## 요청 제한과 알려진 차이

v2 코드를 수정하거나 제한을 비활성화하지 않는다.
요청량 관리·대기는 인증 API 자체를 검증하는 POST /api/auth/... 케이스에만 적용한다.
준비 토큰은 직접 서명하므로 이 제한을 소모하지 않는다.

인증 경로별 제한은 현재 v2 기준이다.
register 5, login 10, forgot-password 3, reset-password 5, oauth 10, refresh/logout 120회/분이다.
OAuth provider 경로는 같은 핸들러이므로 예산도 공유한다. 잘못된 입력·로그인 실패 요청도 횟수에 포함한다.
60초 구간에 1초의 여유를 더해 필요한 경우만 대기한다. 예상하지 않은 429는 재시도하지 않고 실패한다.

429 검증은 별도 `RateLimitCasesTest`에서 수행한다.
이전 로그인 요청의 제한 구간을 비운 뒤 잘못된 DTO를 11번 보낸다.
처음 10회는 양쪽 400 응답을 정확히 비교하고, 11번째는 v2의 429와 Retry-After를 확인한다.
v3 테스트 대역은 제한이 없어 400이다. 관측 결과를 summary.json과 JUnit 보고서에 “알려진 차이”로 남긴다.
후속 인증 테스트는 이 차단 구간도 기다린다.

v3가 429를 반환하기 시작하면 이 테스트는 실패하며 필수 통과 케이스로 전환하라고 알린다.
그때 양쪽 응답·차단 시점을 비교하는 정상 테스트로 바꾸고 knownDifferences 등록과 abort를 제거한다.

대기 총량이 180초를 넘으면 보고서와 콘솔에서 C(같은 컨테이너 재시작) 전환 검토를 제안한다.
이는 보고용 기준이며 자동으로 재시작하거나 사용자 선택을 바꾸지 않는다.

## Mailpit

`axllent/mailpit:v1.27.4` 하나를 공유한다.
v2의 기존 SMTP 환경변수를 mailpit:1025로 연결하고 테스트 전용 인증 허용 설정을 사용한다.
외부 릴레이는 설정하지 않는다. v2 메일 코드는 수정하지 않는다.

메일 API로 우편함을 비우고, 수신자 기준으로 최대 10초 동안 수신을 기다린다.
v2/v3 메일 비교를 추가할 때는 한쪽 요청·메일 수집 → 우편함 비우기 → 다른 쪽 요청·메일 수집 순서로 실행한다.
현재 자체 검증은 실제 v2 비밀번호 찾기 요청으로 수신자·제목·6자리 코드·DB의 SHA-256 관계를 확인한다.
v3 메일 API는 아직 없으므로 양쪽 기능 호환 검증으로 집계하지 않는다.

## 1번 묶음부터 케이스 추가

묶음별 `AuthenticationCases`, 이후 지도·장소 케이스 파일에 Java 케이스 목록을 추가한다.
SQL 초기 데이터와 JSON 요청은 `src/test/resources/compat/` 아래에 둔다.
필요한 묶음이 생길 때만 파일을 만들며 빈 파일이나 별도 DSL을 미리 추가하지 않는다.

각 이관 API에는 정상·미인증·잘못된 입력·DB 변경 사례를 추가한다.
단순 요청은 `ComparisonCase`, 여러 요청·메일·암호 검증이 필요한 시나리오는 같은 base를 사용하는 명시적인 JUnit 메서드로 작성한다.
복잡한 동작을 JSON 설정 언어로 확장하지 않는다.

후속 미구현 차이:
- 보호 경로에서만 탈퇴/없는 회원 조회, DB의 현재 email 사용
- forbidNonWhitelisted의 추가 필드 400
- v2 기본 500 응답
- 깨진 JSON 실응답의 구조 차이
- 실제 엔드포인트의 요청 제한·메일 기능

공식 참고: [Mailpit SMTP](https://mailpit.axllent.org/docs/configuration/smtp/),
[Mailpit API](https://mailpit.axllent.org/docs/api-v1/),
[Nest Throttler 6.5.0](https://github.com/nestjs/throttler/tree/v6.5.0).
