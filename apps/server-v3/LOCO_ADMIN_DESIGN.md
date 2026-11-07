# CMS 6단계 — 호스트 이력 계약과 LOCO 조회 화면 설계

상태: **설계만 작성, C-33~C-42 선택 대기. 구현·마이그레이션·새 테스트 실행 없음.** 2026-11-08, CMS 5-B 완료 `9877ad8` 기준. 권장안은 확정 결정이 아니다.

상위 근거: [CMS_CORE_DESIGN.md](CMS_CORE_DESIGN.md)의 C-9-5 A, C-14 A와 18절 백로그, C-18 C / [ADMIN_UI_DESIGN.md](ADMIN_UI_DESIGN.md)의 C-24~C-32. 승인 전 운영 코드·프런트·의존성·DB를 바꾸지 않는다.

## 1. 이번 범위와 확인한 제약

- 범용 core는 LOCO를 모른다. 호스트가 권한·화면·이력 계약을 등록하고 `com.loco.cmsadmin`에서 LOCO 조회를 조립하는 안이다. 새 Gradle 모듈은 추가하지 않는다.
- 기존 `/api/**`·`/uploads`·JWT·앱 쓰기 경로·v2 fixture는 그대로 둔다. 관리자 API는 `/admin/api/loco/**`, UI는 `/admin/loco/**`, CMS 세션과 CMS 오류 code를 사용한다.
- 이번 업무 API는 GET만이다. 신고 처리·회원 정지·강제 비공개·업무 파일 연결·내보내기는 다음 묶음이다. GET으로 상태 변경·앱 알림·마커 재집계·성공 변경 이력을 발생시키지 않는다.
- 관리자 보안 변경은 기존 core 이력으로 남는다. LOCO 조회만 추가하면서 가짜 업무 변경 이력을 만들거나 기존 앱의 모든 변경에 CMS 이력을 삽입하지 않는다. 업무 이력이 없는 상태와 이력 수집 누락은 구분한다.

| 현재 코드 근거 | 확인한 사실 / 설계 영향 |
|---|---|
| [HistoryPayload](modules/cms-core/src/main/java/com/cms/core/history/HistoryPayload.java) | `admin-account`, `role`, `menu`, `file` 허용 목록·타입이 core에 고정. null 전후 값도 미등록 리소스를 우회하지 못함. 호스트 확장점 필요 |
| [HistoryWriter](modules/cms-core/src/main/java/com/cms/core/history/HistoryWriter.java), [ChangeHistory](modules/cms-core/src/main/java/com/cms/core/history/ChangeHistory.java) | MANDATORY 트랜잭션, 검증한 복사본 저장. payloadVersion 컬럼은 있지만 현재 생성 값은 1로 고정 |
| [HistoryQueryService](modules/cms-core/src/main/java/com/cms/core/history/HistoryQueryService.java) | 목록·상세 모두 SYSTEM_ADMIN. 필터+size+1 cursor, 전체 COUNT 없음 |
| [HistoryIntegrationTest](modules/cms-core/src/test/java/com/cms/core/HistoryIntegrationTest.java) | core 서비스 public 메서드 발견 → 읽기 분류 또는 실행 가능한 쓰기 시나리오와 대조. 이름+인자 수로 분류하므로 향후 오버로드에는 완전한 시그니처 식별 필요 |
| [PermissionCatalog](modules/cms-core/src/main/java/com/cms/core/permission/PermissionCatalog.java) | 코드 Contribution으로 권한·화면 등록, 중복 키 거절. 이력 등록도 같은 조립 패턴을 재사용 가능 |
| [PlaceQueryRepository](src/main/java/com/loco/places/PlaceQueryRepository.java), [MapQueryRepository](src/main/java/com/loco/maps/MapQueryRepository.java) | 앱 회원 권한·Prisma 호환 순서에 결합. `p.*`, `m.*`, 초대 코드·프로필 URL 등을 포함하므로 관리자 DTO에 그대로 사용 불가 |
| [UserResponses](src/main/java/com/loco/users/UserResponses.java) | 앱 응답에 원문 이메일 포함. 관리자 마스킹 계약으로 그대로 재사용 불가 |
| [V1 스키마](src/main/resources/db/migration/V1__baseline.sql), [Report](src/main/java/com/loco/reports/Report.java) | 신고 처리 상태·정지 상태 없음. 신고 targetId는 다형 참조이며 대상 삭제 후에도 남을 수 있음. 존재하지 않는 상태 필터를 만들지 않음 |
| [ApiWebConfig](src/main/java/com/loco/config/ApiWebConfig.java), [AdminAdvice](modules/cms-core/src/main/java/com/cms/core/web/AdminAdvice.java) | 모든 `com.loco` 컨트롤러에 앱 `@ApiController`를 요구하고 CMS Advice는 core package에 한정. 호스트 관리자 컨트롤러를 그대로 추가할 수 없어 C-42로 선언·오류 처리 경계를 정함 |

### 호스트 CMS HTTP 경계 (C-42 A 제안)

core에 LOCO를 모르는 `@CmsController` 선언을 제공하고 호스트 관리자 컨트롤러에 표시한다. 기동 검사는 CMS 표시와 앱 `@ApiController`의 동시 선언을 거절하고, CMS의 모든 매핑이 `/admin/api/**` 내부인지 검사한다. 표시 없는 기존 앱 컨트롤러의 필수 선언 검사는 그대로 유지한다. 단순 package 예외로 검사를 통째로 끄지 않는다.

CMS 오류 처리는 기존 core 컨트롤러와 명시적으로 표시된 호스트 CMS 컨트롤러에만 적용한다. `/api` prefix·앱 권한 interceptor·기존 전역 HTTP 필터는 CMS에 적용하지 않으며, 관리자 chain·세션·CSRF·C-30 code를 재사용한다. core는 annotation과 범용 formatter만 알고 `com.loco.cmsadmin` 문자열/타입을 참조하지 않는다. 실제 선택 후 anonymous/잘못된 필터/권한 거절/500에서 CMS code가 나오고 같은 상황의 LOCO fixture는 불변인지 검사한다. 새 shell 경로도 API fallback과 분리한다.

## 2. 호스트 등록형 이력 계약 (C-33 A 제안)

### 2.1 등록 API와 책임

아래는 설계용 시그니처이며 소스 구현이 아니다. REST로 계약을 등록하거나 DB에서 임의 스키마를 로드하지 않는다.

```text
HistoryContractContribution.contracts() -> Collection<HistoryContract>
HistoryContract(resourceType, currentVersion, versions, visibility)
PayloadContract(version, allowedActions, beforeSchema, afterSchema)
Visibility = SYSTEM_ONLY | BUSINESS_READ(permissionKey)
HistoryContractRegistry.require(resourceType, payloadVersion)
HistoryWriter.record(actor, action, resourceType, resourceId, before, after)
```

- core가 가진 불변 기본 계약 + Spring으로 받은 호스트 Contribution을 기동 시 한 번 조립한다. writer는 요청 body에서 버전을 받지 않고 등록된 currentVersion으로 저장한다. 과거 버전 입력은 별도 데이터 이관 작업 없이는 허용하지 않는다.
- 이름은 소문자 영문·숫자·점·하이픈의 명시적 이름으로 제한한다. 기존 4개 이름은 그대로 보존하고 `cms`/`cms.*`도 예약한다. 호스트 이름은 `loco.report`처럼 조직/업무 namespace가 있는 형식이다. `sample.documents`는 core 단독 테스트에서만 쓴다.
- 호스트가 기존 core 이름/예약 prefix를 등록하면 시작 실패. 리소스 중복, 같은 리소스의 버전 중복, currentVersion 누락, 양수 아닌 버전, 알 수 없는 권한 키·READ 미지원, 무제한/순환 schema도 시작 실패한다. 등록 순서에 따른 덮어쓰기·느슨한 합치기 없음.
- 동일 리소스의 소유 Contribution은 하나다. 이름이 같고 내용까지 같아도 중복은 오류다. visibility는 버전마다 바뀌지 않는 리소스 정책이다. 권한 변경은 배포 시 정책 변경이며 과거 기록에도 영향을 주므로 별도 승인/기록 대상이다.
- 미등록 리소스는 before/after 모두 null이어도 기록 거절. actor는 기존 서버 인증에서 얻고, resourceId는 문자열 식별자로 취급한다. core가 LOCO 엔티티·DTO·테이블을 참조하거나 업무 대상 FK를 만들지 않는다.

### 2.2 중첩 구조·타입·민감값

- 작은 불변 FieldSpec 조합: 문자열(길이·허용 enum/UUID 형식), boolean, 정수(범위), 유한 소수, object(필드별 required/nullable), array(요소 schema·최대 길이), 제한된 key-map. 객체의 추가 키는 모든 깊이에서 거절한다. JSON 문자열을 다시 파싱하는 우회는 허용하지 않는다.
- `required`와 `nullable`을 구분한다. 전후 snapshot 전체 null 허용은 CREATE/DELETE 등 action별 계약으로 정한다. v1 core의 현재 선택 필드·null 규칙을 유지하고 새 계약 때문에 과거 이력이 무효가 되지 않게 한다.
- 기존 role.permissions와 menu.sortOrders는 임의 JSON map이 아닌, 각각 권한 키→READ/MANAGE, UUID→0 이상 정수의 제한된 map으로 표현한다. 기존 Action enum은 경계에서 허용 문자열로 바꾸며 엔티티/임의 POJO 직렬화는 거절한다.
- 신규 호스트 계약의 기본 제안 한도: 깊이 8, 배열·key-map 각 1,000개, JSON UTF-8 합계 64KiB(before+after). 유한 스키마와 데이터 한도를 둘 다 검사하고 순환 객체·byte[]·NaN/Infinity·잘못된 숫자 타입을 거절한다. 한도 초과도 업무 트랜잭션 롤백 사유다. 이 한도는 C-33 A 선택에 포함하며 대량 업무 변경 시 한계가 될 수 있다. 기존 core v1의 쓰기·과거 읽기에 새 크기 제한을 소급 적용하지 않는다. core 제한 추가는 기존 큰 역할/메뉴 이력의 분포 확인 후 별도 결정한다.
- 비밀번호·해시·세션·CSRF/JWT/FCM 토큰·providerId·파일 본문·서명 URL은 계약의 허용 필드로 등록할 수 없다. 필드 이름의 대소문자·구분자 변형도 재귀 검사하되 `mustChangePassword`, 파일 무결성 `sha256` 같은 기존 비밀 아닌 상태 필드는 정확한 경로/타입 예외로 유지한다.
- 스냅샷은 명시적 필드 추출만 허용한다. 검증 후 깊은 복사로 입력 변경의 영향을 끊고 저장한다. serializer로 엔티티 전체를 먼저 만들고 금지 키를 지우는 방식은 쓰지 않는다.
- **필드 allowlist만으로 자유 문자열 속 모든 개인정보·비밀을 판별할 수는 없다.** 실제 비밀번호/해시/쿠키/토큰에 표식 값을 넣어 모든 기록 경로의 JSON·로그와 대조한다. 업무 자유 본문은 원칙적으로 이력 필드에서 제외하고 꼭 필요하면 별도 계약 검토한다. 운영에서 임의 비밀 문자열을 완벽히 탐지한다고 주장하지 않는다.

### 2.3 payloadVersion과 읽기

- 버전은 리소스별 양의 정수다. 기존 core 기록은 v1 그대로, 새 형식은 새 버전으로 등록한다. 옛 버전 descriptor는 보존하고 JSONB 과거 행을 덮어쓰지 않는다. payload_version 기존 컬럼을 재사용한다.
- 읽을 때도 저장 버전의 계약으로 구조를 확인한다. JSON 숫자는 역직렬화 후 Integer/Long 구현형 자체가 아닌 정수 여부·범위로 판단하고, 문자열로 숫자를 보내면 거절한다. 구버전 데이터를 새 버전 schema에 억지로 맞추지 않는다.
- 알 수 없는 리소스/버전 또는 손상된 payload를 raw JSON fallback으로 내보내지 않는다. payload를 제외한 진단 로그와 CMS 오류 `HISTORY_CONTRACT_UNAVAILABLE`(503)로 실패하며, 누락된 구버전 계약을 복원한다. 목록에서 몰래 제외하지 않는다. 배포 전 DB의 distinct(resource_type,payload_version)와 등록 목록을 대조하는 검증으로 이런 서비스 거절을 예방한다.
- 앱 사용자 입력 오류와 계약/등록 오류를 구분한다. 계약 실패 응답·로그에 입력 payload 원문을 남기지 않는다. writer의 MANDATORY와 업무+이력 원자성은 유지한다.

### 2.4 쓰기 경로 검사 연결

- core 단독 테스트는 `sample.documents` 계약만 호스트 대역으로 등록해 중첩 schema·미등록/예약/충돌·구버전 읽기·민감값 거절·트랜잭션 롤백을 검증한다. core 테스트 의존성에 LOCO를 넣지 않는다.
- 공통 계약 테스트 도우미는 generic schema와 시나리오 인터페이스만 안다. 호스트 테스트가 대상 package·실제 서비스 호출·안전 snapshot 기대값을 제공한다. 현재 core 목록 검사와 실행 가능한 쓰기 테스트를 없애지 않고 이 구조에 연결한다.
- 발견한 public 서비스 메서드는 **읽기 / 이력이 필요한 쓰기 / 근거와 테스트가 있는 수명주기 예외** 중 하나와 정확히 대응한다. 완전한 클래스명+메서드명+인자 타입으로 식별한다. 이름만 보고 GET/읽기로 추정하지 않는다.
- 호스트 검사 범위는 신규 CMS 업무 package다. 기존 LOCO 앱 쓰기까지 갑자기 CMS 이력을 요구하지 않는다. 컨트롤러→CMS 서비스→조회 계층 경계 검사로 Repository 직접 쓰기를 우회하지 못하게 한다. 읽기 전용 선언 자체는 DB 불변의 보장이 아니므로 실제 요청 전후 public 테이블 snapshot과 캡처한 SQL의 DML 없음도 검사한다.
- 각 쓰기 시나리오는 성공 이력 수/actor/action/버전/전후값, 이력 INSERT 실패 시 업무 롤백, 실제 비밀 표식 부재를 함께 증명한다. 새 메서드·미등록 쓰기·과도한 예외 분류는 실패한다. 이번 LOCO 조회 서비스는 읽기로만 등록하며 업무 이력 0건을 확인한다.

## 3. C-14 재검토와 UI 영향

C-34 A는 이번 단계에 기존 SYSTEM_ADMIN 목록·상세를 유지한다. 실제 LOCO 쓰기/이력을 도입할 때 다시 결정한다. 조회만 있는 이번 단계에서 열람 대상을 새로 만들 필요가 없다는 근거다.

C-34 B를 고르면 계약의 SYSTEM_ONLY/BUSINESS_READ를 사용한다. 기존 4개 core 리소스(계정·역할뿐 아니라 메뉴·파일 이력 포함)는 SYSTEM_ADMIN으로 유지하고, 업무 이력만 대응 업무 READ로 분리한다. `cms.files READ`가 core 파일 이력 읽기를 자동 허용하지 않는다.

- 목록은 서버가 현재 권한으로 만든 허용 resourceType 집합을 **SQL WHERE에 넣은 뒤** 필터·정렬·size+1을 적용한다. 조회 후 메모리 필터링으로 빈 페이지/허용되지 않은 cursor·건수를 노출하지 않는다. 허용 집합 없음은403, 허용된 집합에 기록 없음은200 빈 목록이다.
- 요청의 resourceType/permissionKey는 권한 근거가 아니다. 명시적으로 권한 없는 리소스를 요청하면403. 상세는 권한 조건을 만족하는 ID만 조회하며 비존재/타 업무 이력 모두404로 통일해 존재를 드러내지 않는다. 익명401·미변경 비밀번호403·비활성/회수 즉시 차단 유지.
- 대상이 삭제돼도 현재 리소스 READ로 과거 이력을 판단한다. actorId만 표시하고 관리자 계정 정보 조회 권한까지 확대하지 않는다. 민감한 운영 사유는 READ 전체에 공개되는 한계가 있으므로 향후 snapshot에 넣기 전에 재검토한다.
- 일반 관리자에게 HistoryView의 SYSTEM_ADMIN 문구·router 제한을 그대로 두지 않는다. 서버가 반환하는 이력 열람 가능 리소스 목록(예: GET `/admin/api/core/history/resources`)으로 메뉴/필터를 구성하고 모든 API에서 다시 검사한다. 원래 `/permissions` SYS 전용 경계는 유지한다.
- B의 업무 이력 권한 검증은 `sample.documents` 등 대역 기록으로 수행한다. 운영 LOCO 변경 이력이 생겼다고 보고하지 않는다. C-34 C(별도 감사 권한)는 업무 READ와 감사 책임을 나누지만 권한 개념을 늘린다.

## 4. 첫 LOCO 화면과 API 초안

C-35 A는 C-9-5 A 원문에 있던 **회원·지도방·장소·리뷰·신고·피드백 조회 6종**을 포함한다. B는 회원·지도방·장소·신고 4종만 먼저 하고 나머지는 다음 조회 묶음으로 이연한다. 아래 표는 A 기준이다. 모든 목록/상세는 각각 해당 READ가 필요하고, 역할 등록의 allowedActions도 READ만 제공한다. MANAGE·숨겨진 쓰기 API는 미리 열지 않는다.

| 화면 / routeKey·권한 키 | GET `/admin/api/loco` 하위 | 목록·상세의 핵심 값 | 검색 조건 초안 |
|---|---|---|---|
| 회원 / `loco.users` | `/users`, `/users/{id}` | UUID·닉네임·마스킹 이메일·provider·가입/수정/탈퇴 시각 | id 정확 일치, 닉네임 포함, provider, ACTIVE/DELETED/ALL, 생성 기간 |
| 지도방 / `loco.maps` | `/maps`, `/maps/{id}` | UUID·제목·ownerId·visibility·tags·생성/수정, 상세 description | id/ownerId 정확 일치, 제목 포함, visibility, tag 정확 일치, 생성 기간 |
| 장소 / `loco.places` | `/places`, `/places/{id}` | UUID·이름·mapId·createdById·생성/수정, 상세 주소·좌표·분류·메모·리뷰 평점/개수 | id/mapId/createdById 정확 일치, 이름/주소 포함, tag 정확 일치, 생성 기간 |
| 리뷰 / `loco.reviews` | `/reviews`, `/reviews/{id}` | UUID·placeId·userId·평점·생성/수정, 상세 content | id/placeId/userId 정확 일치, rating 1~5, 생성 기간 |
| 신고 / `loco.reports` | `/reports`, `/reports/{id}` | UUID·reporterId·targetType/targetId·reason·생성, 상세 detail | id/reporterId/targetId 정확 일치, targetType MAP/PLACE/USER, 생성 기간 |
| 피드백 / `loco.feedback` | `/feedback`, `/feedback/{id}` | UUID·userId(nullable)·생성, 상세 content | id/userId 정확 일치, anonymous 여부, 생성 기간 |

- host PermissionCatalog.Contribution·Screen 등록·프런트 화면 catalog·Spring shell 경로를 함께 추가하는 계획이다. `loco.*` 권한은 일반 역할에 자동 부여하지 않는다. 기존 역할 템플릿은 생성 시 복사 규칙을 유지한다. 메뉴 생성은 기존 SYS/메뉴 권한 흐름으로 하며 배포 시 편집 트리를 임의로 덮어쓰지 않는다.
- 관련 업무 링크는 해당 화면 READ를 서버 explain으로 확인한 경우에만 보인다. 신고 권한만으로 회원 이메일·비공개 지도방 내용을 JOIN해 노출하지 않는다. 상세 대상의 존재 확인/확장은 대상 업무 READ가 있을 때만 한다. 삭제된 target은 “대상 없음”으로 표시하되 신고 원래 ID·reason은 보존한다.
- 공통 UI는 서버 navigation/explain, 로딩·빈 결과·오류·401/403 흐름을 재사용한다. URL의 검색 조건에 세션/토큰을 넣지 않는다. 조회 API에 대해 admin 프런트 client의 core prefix를 host별 명시적 클라이언트로 분리하며 임의 URL 전달 기능은 만들지 않는다.

### 개인정보와 비공개 데이터 (C-37·C-38)

권장 C-37 A: SYS를 포함해 원문 이메일은 응답하지 않고 서버에서 앞 Unicode 문자 1개와 고정 `***@***`만 남긴다(형식이 비정상이면 전체 `***`). FCM 토큰·providerId·비밀번호·각종 tokenHash·세션·초대 코드는 조회 DTO/로그에서 제외한다. 토큰 끝 4자리도 불필요하므로 보내지 않는다. 필요하면 `hasFcmToken` 같은 존재 여부를 새 요구로 별도 결정하며 이번에는 제외한다.

- UUID·닉네임·업무 관계 ID도 개인과 연결될 수 있는 정보다. 마스킹을 익명화라고 부르지 않는다. 목록에는 자유 본문을 넣지 않고 상세에서 업무 READ 보유자만 escaped text로 확인한다. 본문에 사용자가 직접 넣은 개인정보를 자동으로 완벽히 제거할 수 없다는 한계를 명시한다.
- profileImageUrl·coverImageUrl·imageUrls와 썸네일은 이번 조회 DTO에서 제외하는 최소안이다. 기존 `/uploads` 자체의 접근성은 바꾸지 않지만 CMS가 새 이미지 링크를 확산시키지 않는다. 원문 이메일 검색·대량 CSV·개인정보 원문 버튼은 A 범위 밖이다.
- C-38 A는 해당 업무 READ에 비공개 지도방·그 장소를 포함한 전체 조회 권한을 부여한다. 화면/역할 설명에 “비공개 포함”을 명시한다. 관리자 계정을 임의 LOCO 회원으로 바꾸어 회원 가입 권한을 우회하지 않는다.
- C-38 B는 비공개 지도방·장소는 SYSTEM_ADMIN만, 일반 READ에는 공개 범위만 준다. 비공개 대상의 리뷰·지도방/장소 신고 행도 일반 목록·상세에서 제외해 본문을 통한 유출을 막는다. 공개 여부를 확인할 수 없는 삭제된 지도방/장소 target의 신고는 SYS만 열람하고, 회원 대상 신고는 신고 READ를 따른다. 이는 역할+업무 키 외에 visibility 조건을 더하고 운영 조사를 최고관리자에 집중시키는 모델 변화이므로 선택이 필요하다.

## 5. 데이터 접근·페이지·SQL 계획

### 재사용 경계 (C-36)

권장 A는 기존 User/MapRoom/Place/PlaceReview/Report/Feedback 매핑과 DB를 재사용하되, 관리자용 read repository/projection을 호스트에 둔다. JPA EntityManager의 native projection 또는 DTO 조회만 쓰고 엔티티를 HTTP 직렬화하지 않는다. 기존 앱 서비스나 `SELECT *` projection을 관리자 플래그로 바꾸지 않는다. 관리자가 여러 업무를 조회할 때 N+1 관계 순회를 만들지 않는다.

기존 집계 SQL의 수학적 의미(리뷰 count/avg, 없으면 0)는 참고할 수 있지만 기존 앱 응답·권한·cursor 버그까지 관리자에 복제할 필요는 없다. 목록은 기본 필드만 1 SELECT, 상세는 필요 집계를 포함한 projection 1 SELECT를 목표로 한다. 이것은 **업무 데이터 SQL** 목표이며 관리자 세션/계정/권한 SQL과 구분해 별도 계측한다. 요청 전체 SQL도 시작 시 기준을 잡아 증가를 테스트로 고정한다. 관리자 조회가 places/marker_cells 또는 updatedAt을 변경하지 않아야 한다.

### 페이지·검색 (C-39 A 제안)

- `createdAt DESC, id DESC` keyset cursor, 기본 size 50·범위1~100, size+1건으로 nextCursor 계산. 전체 COUNT·총 페이지 수 없음. 응답 `{items,nextCursor}`. 상세 없음404, 조건 결과 없음200 빈 목록이다.
- cursor는 형식 버전·UTC timestamp(밀리초)·UUID·검색 조건 지문을 담은 길이 제한 Base64URL 값이다. 조작돼도 권한이 늘지 않으며 매 요청 서버 권한 조건을 다시 적용한다. 다른 검색 조건의 cursor는400, 조건 변경 시 첫 페이지부터다. 클라이언트는 이전 cursor stack으로 뒤로 가고 임의 페이지 점프는 제공하지 않는다.
- ID/enum/시각/size/cursor 잘못된 타입·미등록 query key는400. 기간은 from 포함/until 제외, UTC offset 필수, from≥until 거절. 빈 선택 필터는 미지정으로 정규화한다. search는 trim 후1~100문자, `%`, `_`, escape 문자는 literal로 이스케이프하고 바인딩한다. 자유 SQL/정렬식 입력은 받지 않는다.
- 태그는 배열 원소 정확 일치, 다중 정렬/전문 검색·평점순은 이번 범위 밖이다. 삭제 회원 조회는 deletedAt 상태로 하며 존재하지 않는 “정지” 상태를 만들지 않는다. 삭제된 회원을 포함한 운영 조회가 앱 인증 차단 정책을 바꾸지 않는다.
- 페이지 간 동시 생성/삭제에서 cursor 행 자체가 없어도 다음 조회가 가능하다. 새 행은 첫 페이지 새로고침 때 보인다. 검색 대상 필드가 중간에 바뀌면 전체 탐색의 일관된 snapshot은 보장하지 않는다. 긴 DB 트랜잭션을 화면 탐색 동안 유지하지 않는다.

### SQL·EXPLAIN 기준 (C-40)

아래는 실행 전 SQL 초안이다. 조건이 있는 경우에만 WHERE 절을 조립하고 값은 바인딩한다. 테이블·컬럼·정렬은 서버의 고정 코드다. 실제 SQL/plan/시간 측정 결과로 오해하지 않는다.

```sql
-- 회원 목록: 비밀 컬럼을 SELECT하지 않는다. 이메일은 서버 DTO 경계에서 마스킹.
SELECT u.id, u.nickname, u.email, u.provider, u."deletedAt", u."createdAt", u."updatedAt"
FROM public.users u
WHERE (u."createdAt", u.id) < (:cursorTime, :cursorId)
ORDER BY u."createdAt" DESC, u.id DESC LIMIT :sizePlusOne;

-- 장소 목록: mapId 없는 전체 목록은 해당 조건 자체를 생략.
SELECT p.id, p.name, p."mapId", p."createdById", p."createdAt", p."updatedAt"
FROM public.places p
WHERE p."mapId" = :mapId AND (p."createdAt", p.id) < (:cursorTime, :cursorId)
ORDER BY p."createdAt" DESC, p.id DESC LIMIT :sizePlusOne;

-- 신고 목록: targetId는 업무 권한 없이 대상 테이블과 JOIN하지 않는다.
SELECT r.id, r."reporterId", r."targetType", r."targetId", r.reason, r."createdAt"
FROM public.reports r
WHERE r."targetType" = CAST(:targetType AS public."ReportTargetType")
ORDER BY r."createdAt" DESC, r.id DESC LIMIT :sizePlusOne;
```

V1에는 위 최신순 복합 인덱스가 없다. users.email UNIQUE, places(mapId,latitude,longitude), reviews(placeId,userId) UNIQUE 등은 있으나 위 정렬·부분 문자열 검색을 모두 보장하지 않는다. keyset만으로 전체 scan/sort가 사라진다고 주장하지 않는다. B-tree의 ORDER BY/LIMIT 활용 조건은 [PostgreSQL 17 공식 설명](https://www.postgresql.org/docs/17/indexes-ordering.html)에 따른다.

- 구현 후 격리 PostgreSQL17에 선택된 각 업무 테이블 10만 건, 공개/비공개·탈퇴·동일시각·0/1/다수 관계·누락 target을 고정 seed로 준비한다. 1만/10만 크기에서 첫 페이지·깊은 cursor·ID 정확 검색·일반/희소 텍스트 검색·FK/상태 필터를 각각 측정한다. 운영 데이터/외부 키는 사용하지 않는다.
- ANALYZE 후 실제 바인딩 값을 사용해 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`을 기록한다. scan 방식·Index Cond/Filter·rows removed·추정/실제 행 수·loops·sort/temp spill·buffer hit/read·planning/execution time, HTTP 응답 시간과 전체 SQL 수를 구분한다. warmup1회 후10회 중앙값/최대값을 기록하고 환경·CPU/메모리·통계·cache 상태를 남긴다.
- 모든 목록/상세의 실제 SQL과 plan 파일을 문서에서 연결한다. 이력도 기존1만 건 core + 테스트 업무 계약의 혼합 데이터에서 권한 조건이 LIMIT 전에 적용되는지 측정한다(C-34 B 선택 시).
- 계획 노드/고정 밀리초를 환경 불문 테스트 통과 조건으로 만들지 않는다. 결과 정확성·업무 SQL 수·bounded page·N+1 없음은 필수 검사, plan/시간은 비교 근거다. EXPLAIN의 비용은 실제 밀리초와 다르며 실행 시 계측 오버헤드가 있음을 구분한다. [PostgreSQL 17 EXPLAIN](https://www.postgresql.org/docs/17/using-explain.html).
- C-40 A는 **이번에 public 인덱스를 추가하지 않고 기준을 기록**, 미흡하면 다음 실험 후보로 남긴다. B는 측정 후 필요한 `(createdAt,id)` 및 `(mapId,createdAt,id)` 등만 새 Flyway 버전으로 추가한다. B는 V1 파일을 고치지 않아도 실제 public 스키마/쓰기 비용에 영향을 주므로 별도 승인 사항이다. trigram/PostGIS·검색 엔진을 자동 도입하지 않는다.

## 6. C-18 파일 연결 검토

이번 화면은 CMS 파일을 업무에 연결하는 쓰기를 하지 않는다. **C-41 A 권장: 연결 권한 도입은 보류**, 기존 `cms.files READ` 전체 조회/다운로드·MANAGE 업로드/삭제·SYS 전체 허용 유지. LOCO 로컬 `/uploads`는 CMS 파일로 간주하지 않고 SeaweedFS로 옮기지 않는다.

향후 “연결된 업무 READ를 따른다”는 연결 테이블과 서버 검증된 target/permission resolver가 있어야 한다. 클라이언트 resourceType만 믿거나 LOCO 타입을 core에 넣을 수 없다. 한 파일이 여러 업무에 연결될 때 ANY/ALL, 미연결 파일, 공개 URL, 참조 중 삭제, 권한 회수, `cms.files READ`로 업무 권한을 우회하는 경로까지 함께 결정해야 한다. 이번에 연결 API 없이 다운로드 권한만 바꾸면 기존 공유 파일 사용을 깨므로 앞당기지 않는다.

## 7. 사용자 결정 목록

**선택할 때 번호와 항목 이름을 함께 적는다. 아래 권장은 미승인이다.**

| 번호·항목 이름 | A | B | C / 권장 근거 |
|---|---|---|---|
| **C-33 호스트 이력 계약 등록 방식** | **권장: 불변 typed schema descriptor + Contribution.** 2절의 예약 이름·중첩 검사·버전·한도·민감값/쓰기 검사 연결. 새 라이브러리 없이 core 단독 검증 / 작은 schema 표현식·진단을 직접 유지 | 호스트 DTO + Bean Validation. Java 타입 재사용 / 알 수 없는 키·직렬화 포함 필드·과거 버전·임의 객체 차단을 별도로 통제해야 함 | JSON Schema 라이브러리. 표준 표현력 / 의존성·dialect·과거 schema 운영 비용. A의 한도는 깊이8·요소1000·전후64KiB를 함께 검토 |
| **C-34 이력 조회 권한 재검토(C-14)** | **권장: 이번에는 SYSTEM_ADMIN 유지**, 실제 업무 쓰기 묶음에서 분리 재결정. 불필요한 화면/API 확대 없음 / 감사 업무 집중 한계 지속 | 지금 core=SYS, 업무=해당 READ로 분리. 담당자 자율·현재 권한으로 목록/상세 검사 / 일반 READ의 과거 사유 노출, UI·SQL 권한 필터 추가. 이번 검증은 대역 이력 | 별도 업무 감사 READ 키. 직무 분리 / 역할 키가 늘고 문제1의 복잡성 재발 가능 |
| **C-35 첫 LOCO 조회 화면 범위** | **권장: 회원·지도방·장소·리뷰·신고·피드백 6종.** C-9-5 A 원문 충족 / 화면·검증 양 증가 | 회원·지도방·장소·신고 4종 우선. 작은 전달 단위 / 리뷰·피드백은 명시적 이연 | 어느 안도 처리·정지 쓰기, export, 파일 연결 없음 |
| **C-36 LOCO 조회 계층 재사용** | **권장: 기존 매핑+호스트 전용 JPA projection.** 앱 응답/권한 불변, 필드 최소화 / 일부 SQL 중복 | 기존 앱 서비스·projection 공통화. 중복 감소 / 관리자 분기·마스킹·Prisma 호환까지 얽히고 앱 회귀 영향 증가 | core는 두 안 모두 LOCO 타입 미참조 |
| **C-37 개인정보 노출 범위** | **권장: 이메일 서버 마스킹, FCM 등 토큰/외부계정ID/초대코드·이미지 URL 제외, 원문 검색 없음.** 최소 노출 / 이메일 기반 지원 제한 | 목록 마스킹, SYS 상세 원문 이메일+정확 이메일 검색. 지원 편의 / 원문 접근 로그·보존·검색 파라미터 미기록 정책이 추가로 필요 | 어느 안도 비밀번호/해시/FCM 원문 노출 없음. 자유 본문은 상세 권한으로 제한하되 자동 비식별 보장 없음 |
| **C-38 비공개 지도방·장소 운영 조회** | **권장: 해당 업무 READ에 비공개 포함 전체 범위를 명시.** 단순 역할 모델·운영 조사 가능 / READ 부여 영향이 큼 | 일반 READ는 공개만, 비공개는 SYS. 민감 범위 축소 / 가시성 추가 규칙과 관련 리뷰·신고 연계 필터 필요, 업무 집중 | 원래 앱의 회원·가입 권한은 두 안 모두 그대로 |
| **C-39 대량 목록·검색 계약** | **권장: 5절 keyset, size50/max100, 생성순 고정·전체 COUNT 없음.** 깊은 페이지 비용/삭제 cursor 안정성 / 페이지 번호 점프 없음, 인덱스 없으면 scan은 남음 | offset+COUNT. 번호 이동·전체 건수 / 깊은 페이지 비용, 동시 변경 중 중복/누락, 큰 COUNT | 필터 종류·검증·개인정보 제한은 4~5절 기준 |
| **C-40 조회 인덱스 변경 범위** | **권장: 현재 인덱스로 SQL/EXPLAIN 기준만 기록.** public 스키마 보존·측정과 최적화 분리 / 대량 조회가 느릴 수 있음 | 측정 후 최소 인덱스를 새 Flyway로 추가. 응답 개선 가능 / 앱 쓰기 비용·배포/DDL·기준 스키마 변화 검토 필요 | 승인 전 인덱스 추가·성능 개선 없음. 수치가 없으므로 성능 달성 약속 없음 |
| **C-41 파일의 업무 권한 연결(C-18)** | **권장: 이번에는 보류, cms.files 현행 유지.** 실제 연결 없는 조회 범위와 일치 / 업무 권한으로 첨부 열람은 다음 묶음 | 연결 모델·포트·다운로드 권한까지 이번에 도입. 미래 작업 앞당김 / 쓰기·공유/삭제 정책까지 범위 확대, 현재 요구와 부합하지 않음 | 후속 재검토 조건과 ANY/ALL·미연결·우회 문제는 6절에 보존 |
| **C-42 호스트 CMS HTTP 경계** | **권장: 범용 CMS controller 표시+경로/중복 선언 검사, CMS Advice 적용 범위 확장.** core 독립·명시적 경계 / 공통 기동 검사·오류 처리 변경과 전체 회귀 필요 | 호스트 관리자 HTTP 어댑터를 `com.loco` 밖 별도 package에 두고 명시 import·전용 Advice로 조립. 기존 앱 검사 코드 유지 / package 이름에 경계가 의존하고 CMS 처리 중복·별도 누락 검사 필요 | 두 안 모두 CMS chain 재사용, LOCO API 응답·권한·prefix 불변. 편의를 위해 `@ApiController`를 CMS에 붙이는 안은 제외 |

## 8. 선택 후 구현·검증 순서 (이번에는 실행하지 않음)

1. 호스트 등록형 이력 계약과 구버전 core 동작 보존. 예약/중복/미등록/null/중첩/타입/한도·민감 표식, input 변경 후 deep copy, 실제 DB rollback, 서비스 목록 검사를 수행. core만 바뀌면 core test + root 빠른 test.
2. C-34 선택에 따른 이력 권한/화면 연결. A는 기존 SYS 불변 확인, B는 목록·상세·커서·actor·삭제된 업무 대상·권한 회수·mixed history 격리 및 generic 대역 검증. 권한 분리를 앱 사용자 JWT로 검사하지 않는다.
3. C-42의 호스트 HTTP 경계를 먼저 고정한 뒤 `loco.*` 권한·조회 DTO/service/repository/API와 공통 목록 컴포넌트 연결. 회원→지도방→장소→신고→선택 시 리뷰/피드백 순서. 각 단계마다 정상/미인증/권한 없음/타 업무 READ/탈퇴·삭제·고아 대상/필터경계/개인정보 제외/공개·비공개·DB 불변을 검사.
4. 페이지 첫/끝/동일시각·cursor 행 삭제·조건 변경·병렬 추가, ID SQL 바인딩·literal wildcard, 목록/상세 SQL 수를 고정. 현재 앱 응답과 CMS DTO가 다른 것은 관리 API 계약이며 LOCO 알려진 차이를 새로 만드는 일이 아님을 구분.
5. 고정 데이터 SQL/EXPLAIN과 시간 기준 기록. C-40 B일 때만 필요한 migration을 별도 검토·커밋한다. core 독립 기동과 호스트 조립에 LOCO가 역참조되지 않는지 검증한다.
6. 호스트/화면/보안 경계 변경이므로 최종 `allTests build`, Vitest, 별도 Chromium E2E. E2E는 기존5개에 일반 READ의 조회·금지 화면/직접 API, 권한 회수, 개인정보 마스킹·페이지를 소수 흐름으로 추가. CMS 업무 요청이 기존 public 데이터를 바꾸지 않는 것과 LOCO 알려진 차이11건 불변을 확인하고 결과·시간을 커밋 본문에 남긴다.

이번 문서 검증은 코드/기존 결정과의 대조, 링크·Markdown·diff 범위 확인뿐이다. 새 API/화면/등록 API·성능 수치·EXPLAIN 결과는 아직 없으며, 기존 5-B 테스트 결과를 이번 설계의 실행 결과로 재사용하지 않는다.
