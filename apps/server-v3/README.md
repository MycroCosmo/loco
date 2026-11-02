# LOCO v3 — 1단계: Spring 프로젝트 기반

현재 구현 범위는 `GET /api/health` 하나다. v2와 Flutter 코드는 변경하지 않는다.
PostgreSQL 연결은 필요하지만 엔티티와 업무 테이블은 아직 만들지 않는다.
Flyway 의존성은 포함하며, V1을 만드는 2단계까지 실행을 비활성화한다.
JWT 검증과 v2의 401·404·400 응답 호환은 4단계에서 구현한다.

## 탑다운으로 읽는 순서

1. `src/main/java/com/loco/LocoApplication.java`: `main()` → `SpringApplication.run()`이 내장 HTTP 서버와 Spring을 시작한다. `@SpringBootApplication`은 이 클래스가 있는 `com.loco` 아래의 컨트롤러·설정을 검색한다.
2. `src/main/resources/application.yml`: 기본 포트는 8081, context path는 `/api`다. DB 연결 정보는 외부에서 받는다. `ddl-auto: none`은 Hibernate가 테이블을 만들거나 바꾸지 않게 한다. `open-in-view: false`는 HTTP 응답 처리까지 JPA 세션을 유지하지 않게 한다.
3. `src/main/java/com/loco/config/SecurityConfig.java`: 요청이 컨트롤러에 도달하기 전에 보안 필터가 실행된다. 서버가 context path를 분리하므로 매칭 경로는 `/health`다. GET health만 허용하며 나머지는 거절한다. 현재는 JWT·로그인·HTTP Basic 인증을 설정하지 않는다.
4. `src/main/java/com/loco/health/HealthController.java`: `@GetMapping("/health")`가 GET을 받는다. 반환한 Map은 JSON으로 직렬화되어 `200 {"status":"ok"}`가 된다. 서버의 HTTP 응답 여부를 확인하는 용도이며 DB 장애를 진단하지는 않는다.
5. `src/test/java/com/loco/health/HealthIntegrationTest.java`: 임시 DB → 임의 포트의 실제 서버 → HTTP 요청 순으로 검증한다. `@DynamicPropertySource`가 테스트 DB 접속 정보를 주입하므로 개발용 `.env`의 DB에 붙지 않는다. Docker가 없으면 테스트를 건너뛰지 않고 실패한다.
6. `build.gradle`: Java 21, Spring Boot 3.5.16, 필요한 의존성, JUnit 5 실행을 선언한다. Boot BOM은 함께 사용할 라이브러리 버전 목록이다. PostgreSQL용 Flyway 모듈은 Flyway 본체와 별도로 필요하다.

요청 흐름: `GET /api/health` → 내장 Tomcat의 `/api` 처리 → 보안 필터 → `HealthController` → JSON 응답.

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

Java 21과 실행 중인 Docker가 필요하다. 저장소 루트에서 PowerShell로 실행한다.

```powershell
cd apps/server-v3
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21' # 설치 위치가 다르면 변경
.\gradlew.bat clean test bootJar --console=plain
```

Linux/macOS에서는 Java 21을 설정하고 `./gradlew clean test bootJar`로 실행한다.
Gradle을 별도로 설치할 필요는 없다. 첫 실행은 Gradle·라이브러리·DB 이미지 다운로드 시간이 든다.

기대 결과:

- `BUILD SUCCESSFUL`, 테스트 3개 성공.
- 인증 없는 `/api/health`: 200, JSON 구조와 값이 정확히 `{"status":"ok"}`.
- `/health`: 404. `/api`를 거치지 않으면 컨트롤러에 접근할 수 없다.
- `/api/actuator/health`: 403. Actuator HTTP 엔드포인트는 노출하지 않는다.
- 테스트 보고서: `build/reports/tests/test/index.html`.
- 실행 JAR: `build/libs/loco-server-v3-0.0.1-SNAPSHOT.jar`.

테스트가 띄운 DB는 종료 시 제거된다. 테스트 비밀번호는 실행마다 무작위로 생성한다.

1단계 검증 기록: `clean test bootJar` 성공, 실제 PostGIS·HTTP 통합 테스트 3개 통과(실패·건너뜀 0개).
실행 JAR 생성과 Wrapper 체크섬 검증까지 완료했으며, JAR 단독 실행은 아직 검증하지 않았다.

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
  --cpus=2 --memory=4g `
  --publish 127.0.0.1:55432:5432 `
  --tmpfs /var/lib/postgresql/data `
  --env POSTGRES_DB=loco --env POSTGRES_USER=loco --env POSTGRES_PASSWORD `
  postgis/postgis:17-3.6

docker exec loco-v3-step1-db pg_isready -U loco -d loco
```

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
| DB 필수, PostGIS 17-3.6 임시 테스트 DB | 실제 PostgreSQL 연결까지 검증한다. 기존 DB의 버전이나 스키마를 변경하지 않는다. |
| Hibernate DDL 비활성화, Flyway는 2단계부터 실행 | 1단계에서 업무 스키마를 만들거나 변경하지 않는다. |
| Actuator HTTP 노출 없음 | 현재 공개 API는 `/api/health` 하나로 유지한다. |

## 공부할 개념과 다음 단계

- Spring Boot 자동 설정과 컴포넌트 검색: `main()` 하나로 서버가 시작되는 이유.
- Gradle Wrapper, Java toolchain, BOM: 빌드 도구·JDK·라이브러리 버전을 각각 누가 정하는지.
- Context path, 보안 필터 체인, 컨트롤러: URL이 어느 순서로 처리되는지.
- 환경변수와 설정 파일 우선순위, JDBC URL: 비밀 값을 코드 밖에서 주입하는 방법.
- JPA의 `ddl-auto`, `open-in-view`: 스키마 변경과 DB 세션 수명을 제어하는 설정.
- JUnit 5, Testcontainers, 실제 HTTP 통합 테스트: 격리된 DB로 외부 동작을 검증하는 방법.

1단계를 확인받은 뒤 2단계에서 Prisma 생성 SQL과 Flyway V1 baseline을 다룬다.
현재 `/api` 아래 health 이외의 요청은 보안 설정상 거절되며, v2와 동일한 401·404 응답은 아직 보장하지 않는다.

공식 참고: [Spring Boot 3.5 실행 환경](https://docs.spring.io/spring-boot/3.5/system-requirements.html),
[DB 초기화](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html),
[Testcontainers 연동](https://docs.spring.io/spring-boot/3.5/reference/testing/testcontainers.html).
