# CMS 관리자 화면

Vue 3 + Vite + TypeScript SPA다. `src/api`는 서버 응답 검증과 HTTP/CSRF, `src/composables`는 단일 세션·권한 상태, `src/views`는 업무 화면, `src/components`는 확인 대화상자·서버 navigation을 담당한다. 추가 UI 키트·Pinia·드래그 라이브러리는 사용하지 않는다.

결정과 서버 계약은 [ADMIN_UI_DESIGN.md](../server-v3/ADMIN_UI_DESIGN.md)에 있다. 메뉴와 버튼을 숨기는 것은 보안 경계가 아니며, 서버의 세션·권한 검사가 최종 기준이다.

## 실행

Node **24.22.2**, npm **11.19.1**, 서버는 Java 21이 필요하다. Windows PowerShell에서 이 디렉터리를 기준으로 실행한다.

```powershell
. ./prepare-node.ps1
npm.cmd ci
npm.cmd run dev
```

준비 스크립트는 시스템 Node를 바꾸지 않고 `apps/server-v3/build/admin-node`에 내려받아 SHA-256을 검증한다. 환경변수 변경은 현재 셸에만 적용된다. Linux/macOS는 동일 버전의 Node/npm을 준비하고 `npm`을 사용한다. Vite 주소는 기본 `http://127.0.0.1:5173/admin/login`이며 `/admin/api`를 `http://127.0.0.1:8081/admin/api`로 전달한다. 개발 프록시는 URL prefix를 제거하지 않는다.

백엔드 실행은 [서버 README](../server-v3/README.md)를 따른다. 로컬 HTTP에서만 `CMS_COOKIE_SECURE=false`로 실행한다. 운영은 HTTPS와 Secure=true다. localhost와 127.0.0.1을 섞으면 쿠키가 전달되지 않을 수 있다. 초기 관리자는 기존 bootstrap CLI로 생성하고, 첫 로그인에서 비밀번호를 변경한다. 비밀번호는 CLI 인자가 아닌 환경변수 또는 표준 입력으로 넣는다. 명령·필수 변수는 [CMS 설계 13절](../server-v3/CMS_CORE_DESIGN.md#13-2단계-구현사용-방법)을 참고한다.

운영 Compose는 Spring 호스트가 빌드된 화면을 제공한다. 앱 기본 포트 기준 `http://localhost:8081/admin/login`으로 연다. Gradle `processResources`가 `npm ci`와 typecheck/build를 거쳐 `dist`를 JAR의 `admin-ui`에 넣는다. Node는 빌드 단계에만 필요하다. dist·node_modules·브라우저 테스트 산출물은 커밋하지 않는다.

## 검증

```powershell
. ./prepare-node.ps1
npm.cmd run test:unit
npm.cmd run build
npm.cmd exec playwright install chromium
npm.cmd run test:e2e
```

E2E 전에 Docker Desktop을 실행하고 `JAVA_HOME`을 설치된 Java 21 JDK 경로로 지정한다. 다른 개발 서버·DB는 필요 없다. `test:e2e`가 별도 테스트 호스트와 PostgreSQL/SeaweedFS 컨테이너를 준비하고, 동적 로컬 포트에서 실제 Spring이 제공하는 SPA를 연다. 테스트 후 호스트·컨테이너를 종료한다. 기존 Compose 볼륨은 건드리지 않는다.

E2E는 기존 로그인·첫 비밀번호 변경, CSRF 실패, 세션 만료, 메뉴 revision 충돌, 파일 업로드/다운로드 5건과 LOCO 4종 조회·일반 READ 경계 2건, 신고 READ/MANAGE·종결·이력과 두 관리자409 충돌 2건의 **9건**이다. LOCO 흐름은 이메일 마스킹·페이지·비공개·리뷰 집계·삭제된 신고 대상·관련 업무 권한·권한 회수 후 화면 데이터 제거를 검사한다. CSRF 케이스는 한 요청의 헤더만 제거하고 실제 서버 오류 코드를 검사한다. 세션 만료는 테스트 전용 Clock을 전진시킨다. 테스트 제어 서버는 별도 loopback 포트·난수 토큰을 사용하며 클래스는 `src/test`에만 있다. 운영 API·JAR에는 테스트 제어 기능이 없다.

`npm run test:e2e`는 Gradle **allTests/check에 포함하지 않는다**. 별도로 서버 디렉터리에서 `gradlew.bat allTests build`를 실행해야 기존 LOCO 비교 11건의 알려진 차이까지 재검증된다. 호스트 또는 빌드 구성이 바뀌면 전체 서버 회귀를 수행한다.

결과는 `test-results/summary.json`, 호스트 시작 로그는 `apps/server-v3/build/admin-e2e/host.log`다. trace·스크린샷·영상은 수집하지 않는다. 테스트용 난수 계정 정보는 무시되는 build 파일에 잠시 저장하고 종료 시 지운다. 실제 외부 키·AWS·FCM·OAuth·메일 서버를 사용하지 않는다. `@types/node`는 E2E 설정까지 검사하기 위한 개발 타입 선언이며 브라우저 번들에 포함되지 않는다.

## 사용자 흐름의 한계

`/admin/loco/users`, `/admin/loco/maps`, `/admin/loco/places`는 조회 전용이며 `/admin/loco/reports`는 신고 조회·처리를 제공한다. SYSTEM_ADMIN 또는 해당 `loco.* READ` 역할이 필요하고, 신고 상태 변경은 `loco.reports MANAGE`를 명시 배정해야 한다(기존 역할에 자동 전파하지 않음). 지도방·장소는 **비공개 포함**이며 화면과 역할 설명에 범위를 표시한다. 관련 업무 링크는 서버 explain 허용 후 표시하고 직접 API도 서버가 검사한다. 이메일은 서버에서 마스킹하며 원문 이메일 검색·내보내기·회원 정지는 없다. 상세는 목록 버튼, 관련 링크는 업무 ID 필터로 이동한다.

검색은 생성 기간(입력은 한국 시각), ID·업무별 조건과 기본50/최대100건의 cursor 페이지다. 총 건수·임의 페이지 이동은 제공하지 않는다. 조건 변경은 첫 페이지, 새 데이터는 새로고침으로 확인한다. 리뷰·피드백 화면과 파일 업무 연결은 이연했다. 결정·전체 SQL 기준은 [LOCO_ADMIN_DESIGN.md](../server-v3/LOCO_ADMIN_DESIGN.md)를 참고한다.

신고는 접수→검토 중→검토 완료/기각으로만 전이한다. 종결 사유는 필수이며 종결 후 수정·재개는 없다. 원문은 처리 행만, 이력은 `hasNote`만 저장한다. 409 충돌이면 입력 메모를 폐기하고 최신 목록·상세를 읽으며 자동 재전송하지 않는다. 신고 READ는 해당 처리 이력을 열람하고 core 4종 이력은 SYSTEM_ADMIN만 읽는다. 표시 범위는 서버 `/history/resources`와 explain 결과를 따른다. 회원 정지·강제 비공개·앱 알림은 실행하지 않는다. [신고 처리 계약](../server-v3/REPORT_PROCESSING_DESIGN.md)을 참고한다.

- 403은 CMS `code`로 원인을 구분한다. CSRF 갱신 뒤에도 쓰기 요청은 사용자가 다시 눌러야 한다.
- revision 충돌은 최신 트리를 읽고 이동 미리보기를 폐기한다. 새 revision으로 자동 저장하지 않는다.
- 세션이 만료되면 로그인으로 이동하고 메모리의 업무 상태를 폐기한다. 비밀번호·파일 본문을 브라우저 저장소에 저장하지 않는다.
- 업로드 응답 유실은 저장 실패와 같지 않다. 목록 확인 후 다시 업로드하면 중복이 생길 수 있다(C-19 B). 멱등 키는 후속 실험이다.
- 최초 자동 E2E는 Chromium만 검증한다. 다른 브라우저와 전체 화면 조합의 자동화는 이번 범위에 포함하지 않는다.
