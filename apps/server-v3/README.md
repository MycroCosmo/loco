# LOCO v3 — Spring 기반과 Flyway V1

현재 구현 범위는 `GET /api/health` 하나다. v2와 Flutter 코드는 변경하지 않는다.
빈 PostgreSQL DB에는 Flyway V1으로 v2와 같은 업무 테이블을 만든다. JPA 엔티티는 아직 만들지 않는다.
DB 기준은 사용자가 확정한 PostgreSQL 17(`postgis/postgis:17-3.6`)이다. 맞춰야 할 기존 운영 DB 버전은 없다.
JWT 검증과 v2의 401·404·400 응답 호환은 4단계에서 구현한다.

기준은 [업데이트된 작업 프롬프트](https://app.notion.com/p/40c140eabf0e81db996df610eed7c32f)다.
이 문서의 1~5단계는 **0번 묶음 안의 작업 순서**다. 전체 로드맵의 2단계(공간 조회·캐시)와 구분한다.
`postgis/postgis` 이미지는 지금 사용하지만 공간 확장은 전체 로드맵 2단계까지 활성화하지 않는다.
이미지 기본 초기화 스크립트도 막아 공간 테이블이 미리 생기지 않게 한다.

후속 작업에서도 v2·Flutter는 읽기만 하며, 이전 직장 코드는 사용하거나 재현하지 않는다.
외부 API는 테스트에서 mock으로 대체하고, 현재 단계 밖의 기술·보류 항목은 추가하지 않는다.
확정된 결정 밖의 새 설계 선택은 선택지와 장단점을 설명한 뒤 사용자 답을 기다린다.
각 단계가 끝나면 검증 방법을 제시하고 사용자 확인 전에는 다음 단계로 진행하지 않는다.

## 탑다운으로 읽는 순서

1. `src/main/java/com/loco/LocoApplication.java`: `main()` → `SpringApplication.run()`이 내장 HTTP 서버와 Spring을 시작한다. `@SpringBootApplication`은 이 클래스가 있는 `com.loco` 아래의 컨트롤러·설정을 검색한다.
2. `src/main/resources/application.yml`: 기본 포트는 8081, context path는 `/api`다. DB 연결 정보는 외부에서 받는다. `ddl-auto: none`은 Hibernate가 테이블을 만들거나 바꾸지 않게 한다. `open-in-view: false`는 HTTP 응답 처리까지 JPA 세션을 유지하지 않게 한다.
3. `src/main/java/com/loco/config/SecurityConfig.java`: 요청이 컨트롤러에 도달하기 전에 보안 필터가 실행된다. 서버가 context path를 분리하므로 매칭 경로는 `/health`다. GET health만 허용하며 나머지는 거절한다. 현재는 JWT·로그인·HTTP Basic 인증을 설정하지 않는다.
4. `src/main/java/com/loco/health/HealthController.java`: `@GetMapping("/health")`가 GET을 받는다. 반환한 Map은 JSON으로 직렬화되어 `200 {"status":"ok"}`가 된다. 서버의 HTTP 응답 여부를 확인하는 용도이며 DB 장애를 진단하지는 않는다.
5. `src/test/java/com/loco/health/HealthIntegrationTest.java`: 임시 DB → 임의 포트의 실제 서버 → HTTP 요청 순으로 검증한다. `@DynamicPropertySource`가 테스트 DB 접속 정보를 주입하므로 개발용 `.env`의 DB에 붙지 않는다. 공간 확장이 생성되지 않는지도 확인한다. 자원 제한은 테스트에 설정하거나 검증하지 않고 3단계 Docker Compose에서 적용한다. Docker가 없으면 테스트를 건너뛰지 않고 실패한다.
6. `build.gradle`: Java 21, Spring Boot 3.5.16, 필요한 의존성, JUnit 5 실행을 선언한다. Boot BOM은 함께 사용할 라이브러리 버전 목록이다. PostgreSQL용 Flyway 모듈은 Flyway 본체와 별도로 필요하다. `preparePrisma`는 v2 lockfile의 Prisma 버전(현재 5.22.0)을 읽어 테스트용 CLI를 `build/prisma-cli`에 설치한다. v2 파일은 수정하지 않는다.
7. `src/main/resources/db/migration/V1__baseline.sql`: Prisma가 생성한 SQL 원문이다. 테이블 11개, enum 3개, 보조 인덱스 13개(PK 제외), 외래 키 14개를 만든다. 적용한 V1은 고치지 않고 이후 변경은 다음 버전의 마이그레이션으로 추가한다.
8. `src/test/java/com/loco/migration/FlywayBaselineIntegrationTest.java`: 각 테스트마다 독립된 임시 DB를 만든다. 빈 DB에 V1을 적용하고, 기존 DB는 Prisma `db push`로 별도로 만든 뒤 baseline과 데이터 보존을 확인한다. 실제 DB와 원본 Prisma 스키마를 CLI로 비교한다.

V1 비교 테스트는 `target("1")`로 적용 범위를 고정해, V2 이후 마이그레이션이 추가되어도 V1과 v2 Prisma 스키마의 일치만 검증한다.

요청 흐름: `GET /api/health` → 내장 Tomcat의 `/api` 처리 → 보안 필터 → `HealthController` → JSON 응답.

DB 시작 흐름: DB 연결 → Flyway 이력 확인 및 V1 적용 → JPA 초기화 → HTTP 요청 처리.
`ddl-auto: none`을 유지하므로 업무 스키마 변경은 Flyway가 담당한다.

## 나머지 파일의 역할

| 파일 | 필요한 이유 / 핵심 설정 |
| --- | --- |
| `settings.gradle` | Gradle 프로젝트 이름을 지정한다. |
| `gradlew`, `gradlew.bat` | 각각 Linux/macOS, Windows에서 프로젝트의 Gradle을 실행한다. |
| `gradle/wrapper/gradle-wrapper.jar` | 지정한 Gradle 배포본을 내려받고 실행하는 공식 부트스트랩이다. |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 8.14.6과 배포 ZIP의 SHA-256을 고정한다. |
| `.env.example` | 외부에서 제공할 설정 키만 보여준다. 비밀번호·JWT 비밀 값은 넣지 않는다. |
| `.gitignore` | 빌드 결과·Gradle 캐시·실제 `.env`의 커밋을 방지한다. |
| `.gitattributes` | Wrapper 스크립트의 OS별 줄바꿈과 JAR의 바이너리 처리를 지정한다. |
| `README.md` | 실행·검증 방법, 결정 이유, 학습 순서를 기록한다. |

## 자동 검증

Java 21, 실행 중인 Docker, PATH에 등록된 Node.js/npm이 필요하다. 저장소 루트에서 PowerShell로 실행한다.

```powershell
cd apps/server-v3
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21' # 설치 위치가 다르면 변경
.\gradlew.bat clean test bootJar --console=plain
```

Linux/macOS에서는 Java 21을 설정하고 `./gradlew clean test bootJar`로 실행한다.
Gradle을 별도로 설치할 필요는 없다. 첫 실행은 Gradle·라이브러리·DB 이미지·Prisma CLI 다운로드 시간이 든다.
`clean`을 생략하면 준비된 Prisma CLI를 재사용한다. Prisma는 테스트 도구이며 Spring 실행 JAR에는 포함되지 않는다.

기대 결과:

- `BUILD SUCCESSFUL`, 테스트 6개 성공.
- 인증 없는 `/api/health`: 200, JSON 구조와 값이 정확히 `{"status":"ok"}`.
- `/health`: 404. `/api`를 거치지 않으면 컨트롤러에 접근할 수 없다.
- `/api/actuator/health`: 403. Actuator HTTP 엔드포인트는 노출하지 않는다.
- DB에는 기본 `plpgsql` 이외의 추가 확장이 생성되지 않는다.
- 빈 DB에는 V1이 한 번만 적용되며 이력의 버전은 `1`, 유형은 `SQL`이다. 다시 migrate해도 V1을 재실행하지 않는다.
- 기존 스키마에는 기본 설정으로 baseline하지 않는다. 명시적으로 허용하면 유형 `BASELINE`으로 버전 `1`을 기록하고 기존 데이터를 보존한다.
- SQL 재생성 결과는 V1과 동일하며(줄바꿈 차이 제외), 실제 DB와 Prisma 스키마의 차이는 Flyway 관리 테이블 하나뿐이다.
- 테스트 보고서: `build/reports/tests/test/index.html`.
- 실행 JAR: `build/libs/loco-server-v3-0.0.1-SNAPSHOT.jar`.

테스트가 띄운 DB는 종료 시 제거된다. 테스트 비밀번호는 실행마다 무작위로 생성한다.

스키마 비교는 테이블·컬럼 타입·nullable·기본값·enum·PK·고유 제약·인덱스·외래 키 등 Prisma가 표현하는 구조를 대상으로 한다.
Prisma가 지원하지 않는 임의의 트리거나 뷰까지 검증하는 것은 아니다. 비교 기준은 저장소의 v2 Prisma 스키마이며 기존 개발 DB에 직접 연결하지 않는다.

## V1 생성 방법과 의미

Prisma 5.22.0은 v2의 `package-lock.json`에 고정된 버전이다.
`migrate diff --from-empty --to-schema-datamodel ... --script`는 빈 스키마에서 목표 모델로 가는 SQL을 생성하며 DB에 실행하지 않는다.
그 결과를 편집 없이 V1으로 사용했다. 수작업으로 타입·컬럼명·제약을 옮길 필요가 없다.

v2의 UUID 문자열 컬럼은 DB에서 `TEXT`다. Prisma의 `uuid()`는 클라이언트가 생성하므로 SQL에 UUID 기본값을 추가하지 않는다.
`@updatedAt`도 Prisma가 처리하므로 DB 트리거나 자동 갱신 기본값을 추가하지 않는다. 후속 JPA 이관에서 이 동작을 구현한다.

`apps/server-v3`에서 아래 명령으로 별도 파일에 SQL을 재생성할 수 있다.

```powershell
.\gradlew.bat preparePrisma
New-Item -ItemType Directory -Force -Path build/baseline-review | Out-Null
Copy-Item ../server/prisma/schema.prisma build/baseline-review/schema.prisma
node build/prisma-cli/node_modules/prisma/build/index.js migrate diff `
  --from-empty --to-schema-datamodel build/baseline-review/schema.prisma `
  --script --output build/baseline-review/V1__baseline.sql
git diff --no-index --ignore-space-at-eol `
  src/main/resources/db/migration/V1__baseline.sql build/baseline-review/V1__baseline.sql
```

기대 결과는 차이 없음, 마지막 명령의 종료 코드 0이다. 스키마 복사본을 쓰므로 v2의 `.env`를 읽지 않는다.

## 기존 DB와 baselineOnMigrate

`V1__baseline.sql`은 빈 DB에 실행하는 SQL 파일이고, Flyway의 **baseline**은 기존 DB를 특정 버전으로 인정하는 이력 등록 작업이다.
baseline은 기존 테이블을 생성하거나 V1과 같은지 확인하는 작업이 아니다.

| DB 상태 | 설정과 동작 |
| --- | --- |
| 빈 DB | 기본 `baseline-on-migrate: false`로 V1을 실행한다. |
| 테이블은 있지만 Flyway 이력이 없음 | 기본값 false에서는 시작을 거절한다. |
| Prisma와 동일함을 확인한 기존 DB | 최초 실행에만 true를 지정하면 버전 1을 `BASELINE`으로 기록하고 V1 실행을 건너뛴다. |
| Flyway 이력이 이미 있음 | 다시 false로 실행한다. 적용한 V1은 반복하지 않고 이후 버전만 실행한다. |

`baseline-version: 1`은 기존 DB를 V1까지 적용된 상태로 인정한다는 뜻이다.
`baselineOnMigrate=true`를 항상 켜두면 다른 비어 있지 않은 DB도 V1 상태로 인정할 수 있으므로 기본값 false를 유지한다.

기존 개발 DB를 가져올 때는 먼저 백업하고, 위 명령으로 준비한 스키마 복사본과 실제 DB를 비교한다.
현재 대상 스키마는 `public`이다. Prisma 접속 URL의 `schema=public`과 앱 JDBC 연결의 대상 스키마를 일치시킨다.
아래 예시는 PowerShell 7의 마스킹 입력으로 Prisma 접속 URL을 받는다. 비밀번호 등은 코드나 명령 이력에 쓰지 않는다.

```powershell
$env:DATABASE_URL = Read-Host '비교할 개발 DB의 Prisma 접속 URL' -MaskInput
node build/prisma-cli/node_modules/prisma/build/index.js migrate diff `
  --from-schema-datasource build/baseline-review/schema.prisma `
  --to-schema-datamodel build/baseline-review/schema.prisma `
  --script --exit-code
$LASTEXITCODE
```

Flyway 이력이 없는 기존 DB는 `-- This is an empty migration.`과 종료 코드 0이어야 한다.
차이가 있으면 종료 코드 2가 되므로 baseline하지 말고 차이를 먼저 확인한다. 명령 실패는 종료 코드 1이다.
이 비교는 읽기 전용이며, 출력된 SQL을 DB에 실행하지 않는다.

Flyway 적용 후 같은 비교를 실행하면 관리 테이블 때문에 종료 코드 2가 된다. 다음 차이 **하나만** 있으면 업무 스키마는 동일하다.
JUnit 테스트도 이 한 문장만 허용하고 다른 차이는 모두 실패시킨다.

```sql
-- 비교 결과의 예시이며 실행할 SQL이 아니다.
DROP TABLE "flyway_schema_history";
```

이력이 없는 기존 DB의 비교가 통과한 경우에만, 앱의 `DB_URL`·`DB_USERNAME`·`DB_PASSWORD`가 비교한 DB와 같은 대상인지 확인한 뒤 실행한다.

```powershell
$env:SPRING_FLYWAY_BASELINE_ON_MIGRATE = 'true'
.\gradlew.bat bootRun --console=plain
```

최초 시작을 확인하고 Ctrl+C로 서버를 종료한 다음 해당 설정을 제거한다. 이후에는 기본 false로 실행한다.

```powershell
$env:SPRING_FLYWAY_BASELINE_ON_MIGRATE = $null
$env:DATABASE_URL = $null
.\gradlew.bat bootRun --console=plain
```

빈 DB로 시작하는 경우에는 이 수동 baseline 절차가 필요 없다.

## 직접 서버를 띄워 확인

Compose는 3단계에서 추가한다. 지금은 기존 DB에 영향을 주지 않는 임시 PostGIS 컨테이너로 확인한다.
아래 명령은 `apps/server-v3`에서 실행한다. 55432 포트가 비어 있어야 한다.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
$env:DB_URL = 'jdbc:postgresql://localhost:55432/loco'
$env:DB_USERNAME = 'loco'
$env:DB_PASSWORD = [Guid]::NewGuid().ToString('N')
$env:POSTGRES_PASSWORD = $env:DB_PASSWORD

docker run --detach --rm --name loco-v3-step1-db `
  --publish 127.0.0.1:55432:5432 `
  --tmpfs /var/lib/postgresql/data `
  --tmpfs /docker-entrypoint-initdb.d `
  --env POSTGRES_DB=loco --env POSTGRES_USER=loco --env POSTGRES_PASSWORD `
  postgis/postgis:17-3.6

docker exec loco-v3-step1-db pg_isready -h 127.0.0.1 -U loco -d loco
```

`/docker-entrypoint-initdb.d`를 빈 임시 디렉터리로 덮어 이미지의 공간 확장 설치 스크립트를 실행하지 않는다.
PostgreSQL 자체의 DB·사용자 초기화는 그대로 수행한다. 이후 스키마 변경은 Flyway가 담당한다.
CPU·메모리 제한과 `shared_buffers` 설정은 3단계 Docker Compose에서 적용한다.

`accepting connections`가 나오면 같은 터미널에서 서버를 시작한다. 초기화 중이면 `pg_isready`를 다시 실행한다.

```powershell
.\gradlew.bat bootRun --console=plain
```

다른 터미널에서 요청한다.

```powershell
curl.exe -i http://localhost:8081/api/health
```

기대 결과는 `HTTP/1.1 200`과 `{"status":"ok"}`다.
JAR 실행을 확인하려면 `bootRun`을 종료한 뒤 동일한 환경변수로 아래 명령을 실행한다.

```powershell
& "$env:JAVA_HOME/bin/java.exe" -jar build/libs/loco-server-v3-0.0.1-SNAPSHOT.jar
```

확인이 끝나면 서버 터미널에서 Ctrl+C를 누르고 임시 DB를 종료한다.
이 컨테이너의 데이터는 임시 메모리 영역에 있으므로 종료 시 사라진다.

```powershell
docker stop loco-v3-step1-db
Remove-Item Env:DB_PASSWORD, Env:POSTGRES_PASSWORD
```

기존 개발 DB를 사용할 때는 `.env.example`을 `.env`로 복사하고 값을 채울 수도 있다.
`DB_URL`은 `jdbc:postgresql://호스트:포트/DB이름` 형식이며 v2의 Prisma URL과 다르다.
Spring은 실행 디렉터리의 `.env`를 Java properties 형식으로 읽으므로 값에 따옴표를 붙이지 않는다.
환경변수가 `.env`보다 우선한다. `JWT_SECRET`은 4단계 전까지 사용하지 않으며 지금은 비워둔다.

## 결정 기록

| 결정 | 근거 / 결정 주체 |
| --- | --- |
| Java 21, Boot 3.5, JPA, Flyway, UUID 문자열 유지 | 사용자가 지정했다. 엔티티·ID 처리는 이후 묶음에서 구현한다. |
| 현재 폴더에 새 clone, 별도 작업 브랜치 | 사용자가 선택했다. 기존 체크아웃의 변경과 분리한다. |
| Gradle Groovy DSL | 사용자가 선택했다. 애플리케이션 언어는 Java다. |
| Boot 3.5.16, Gradle Wrapper 8.14.6 | 구현 선택을 위임받아 3.5·8.x의 안정 패치 버전으로 고정했다. |
| `com.loco` 아래 기능·설정별 패키지 | 구현 선택을 위임받았다. 지금은 `health`, `config`만 만든다. |
| 8081, context path `/api`, YAML | v2의 3000 및 앱 개발용 8080과 분리한다. API prefix는 설정 한 곳에서 적용한다. |
| 직접 작성한 health, GET health만 공개 | HTTP 요청 흐름을 이해하기 쉽고 기능 API 이관 없이 동작을 확인할 수 있다. |
| PostgreSQL 17, PostGIS 17-3.6 이미지 + 공간 확장 미활성화 | 사용자가 확정했다. 맞춰야 할 기존 운영 DB 버전은 없다. 공간 확장 도입은 전체 로드맵 2단계로 미룬다. |
| DB CPU 2 / 메모리 4GB / shared_buffers 1GB | 3단계 Docker Compose에서 적용한다. 테스트에는 자원 제한 설정·검증을 넣지 않는다. |
| Hibernate DDL 비활성화, Flyway V1으로 스키마 생성 | Prisma 생성 SQL을 그대로 사용하며 JPA 엔티티는 아직 만들지 않는다. |
| baselineOnMigrate 기본 false, 기존 DB 최초 실행에만 true | 사용자가 선택했다. 스키마 비교 후에만 기존 DB를 V1 상태로 인정한다. 별도 Flyway CLI를 추가하지 않는다. |
| JUnit에서 고정된 Prisma CLI로 스키마 비교 | 사용자가 선택했다. Node/npm이 필요하며 `gradlew test`로 DB 동등성까지 재검증한다. |
| Actuator HTTP 노출 없음 | 현재 공개 API는 `/api/health` 하나로 유지한다. |

## 공부할 개념

- Spring Boot 자동 설정과 컴포넌트 검색: `main()` 하나로 서버가 시작되는 이유.
- Gradle Wrapper, Java toolchain, BOM: 빌드 도구·JDK·라이브러리 버전을 각각 누가 정하는지.
- Context path, 보안 필터 체인, 컨트롤러: URL이 어느 순서로 처리되는지.
- 환경변수와 설정 파일 우선순위, JDBC URL: 비밀 값을 코드 밖에서 주입하는 방법.
- JPA의 `ddl-auto`, `open-in-view`: 스키마 변경과 DB 세션 수명을 제어하는 설정.
- JUnit 5, Testcontainers, 실제 HTTP 통합 테스트: 격리된 DB로 외부 동작을 검증하는 방법.
- 이미지 초기화 스크립트와 PostgreSQL 확장.
- Flyway 버전 SQL과 baseline의 차이, 이력 테이블·체크섬·재실행 방지.
- Prisma 스키마와 실제 DB의 차이 비교, 클라이언트에서 처리하는 UUID·updatedAt.

## 다음 단계

Prisma 기반 비교 테스트는 이관 기간 동안 기본 `test`에 포함한다. 이관이 끝나면 별도 태그로 분리할 예정이다.

2단계를 확인받은 뒤 3단계에서 v2·v3가 PostgreSQL 17 DB 하나를 공유하는 Docker Compose를 만든다.
작업 기록은 노션에서 관리한다.

### 4단계 설계 메모

- 현재 `anyRequest().denyAll()` 때문에 `/api` 아래 존재하지 않는 경로는 403(v2는 404), 토큰 없는 보호 경로도 403(v2는 401)이 된다. `/api` 밖의 `/health`가 반환하는 404와는 구분한다.
- 4단계에서 (a) 보호 경로 목록 지정, (b) 전체 인증, (c) 필터는 토큰 확인만 하고 권한은 `@PreAuthorize`로 처리하는 방식 중 선택한다. 그때 각 선택지의 장단점을 다시 제시하고 사용자 답을 기다린다.
- 앱 API용 필터 체인은 CSRF를 비활성화해야 한다. 관리자 세션용 체인의 CSRF 정책은 별도로 다룬다.
- `/api` prefix를 현재처럼 `context-path`로 둘지 Spring MVC path prefix로 둘지도 그때 함께 비교한다.

공식 참고: [Spring Boot 3.5 실행 환경](https://docs.spring.io/spring-boot/3.5/system-requirements.html),
[DB 초기화](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html),
[Testcontainers 연동](https://docs.spring.io/spring-boot/3.5/reference/testing/testcontainers.html),
[Flyway baselineOnMigrate](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-baseline-on-migrate-setting),
[Prisma migrate diff](https://docs.prisma.io/docs/cli/migrate/diff).
