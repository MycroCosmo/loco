# CMS 5-B 관리자 화면 설계

상태: **C-24~C-32 승인, 7절 순서로 구현 중.** 2026-11-08, CMS 5-A 완료 커밋 `0309bf0` 기준으로 설계했다.

이 문서는 화면을 기존 서버 계약에 연결하고 구현 근거를 보존한다. 상위 결정은 [CMS_CORE_DESIGN.md](CMS_CORE_DESIGN.md)의 C-1~C-23을 따른다. 6절의 대안은 검토 기록이며 최종 선택은 다음 표다.

| 결정 | 승인 내용 |
|---|---|
| C-24 A / C-25 A / C-26 A | 기본 HTML·공통 Vue, 앱 수준 단일 composable·요청 중복 억제, 버튼 이동·부모/위치 선택·서버 미리보기 |
| C-27 A | Vitest + 핵심 Chromium E2E. E2E는 Gradle allTests에 넣지 않고 별도 실행. 로그인·첫 변경, CSRF 실패, 세션 만료, 트리 revision 충돌, 파일 업로드/다운로드만 자동 브라우저 흐름으로 고정 |
| C-28 A / C-29 A | Spring이 SPA 제공, shell 공개·API 보호. 고정 관리 도구+서버 편집 메뉴 |
| C-30 B | CMS 응답 code: SESSION_EXPIRED, PASSWORD_CHANGE_REQUIRED, CSRF_INVALID, PERMISSION_DENIED, STALE_REVISION. CSRF 예외와 access.require에서 원인을 구분. LOCO 응답 불변 |
| C-31 A | SYSTEM_ADMIN 전용 역할 배정 조회. 일관된 roleIds/authVersion |
| C-32 A | Node 24.22.2 / npm 11.19.1 exact 고정 |

C-30은 서버가 아는 거절 원인을 UI가 추측하지 않도록 해 “왜 막혔는지 알 수 없음”을 줄인다. [노션 결정 변경 기록](https://app.notion.com/p/40c140eabf0e81dca76de00565fe725b)에 같은 승인을 반영했다. 최종 allTests와 별도 E2E 후 5-B에서 멈춘다.

## 1. 범위와 경계

- Vue 3 + Vite + TypeScript SPA, vue-router, 위치 `apps/admin`. Nuxt·SSR·SEO는 범위 밖이다.
- 관리자 계정·역할·메뉴·접근 근거·이력·파일 화면을 만든다. LOCO 회원·지도방·장소 관리 화면은 6단계 범위다.
- 브라우저는 상대 경로 `/admin/api/core/**`만 호출한다. LOCO JWT를 사용하지 않으며, CMS 세션을 LOCO API 인증으로 사용하지 않는다.
- core가 LOCO나 Vue를 알게 하지 않는다. 화면 조립과 서버 정적 리소스 배포는 호스트 책임이다. UI 때문에 core 도메인에 화면 컴포넌트 이름을 넣지 않는다.
- 최소 의존성 기준안: Vue·vue-router, 플랫폼 `fetch`·`FormData`·`Intl`, 필요한 Vite/TypeScript 도구. Axios·날짜 라이브러리·범용 폼 프레임워크를 기본 추가하지 않는다.
- 오류·로딩·빈 결과·키보드 조작·작은 화면 레이아웃을 각 화면에 포함한다. 화면 숨김과 비활성 버튼은 사용성일 뿐 **보안 경계는 서버의 인증·권한 검사**다.

## 2. 현재 서버 계약과 구현 전 간극

근거: [AdminController](modules/cms-core/src/main/java/com/cms/core/web/AdminController.java), [GovernanceController](modules/cms-core/src/main/java/com/cms/core/web/GovernanceController.java), [PermissionCatalog](modules/cms-core/src/main/java/com/cms/core/permission/PermissionCatalog.java), [AdminWebConfiguration](modules/cms-core/src/main/java/com/cms/core/web/AdminWebConfiguration.java), [AdminSessionFilter](modules/cms-core/src/main/java/com/cms/core/web/AdminSessionFilter.java), [HistoryController](modules/cms-core/src/main/java/com/cms/core/web/HistoryController.java), [FileController](modules/cms-core/src/main/java/com/cms/core/web/FileController.java).

| 현재 확인한 계약 | 화면 설계에 미치는 영향 |
|---|---|
| `GET /auth/csrf`는 204, JSON 토큰 응답 없음 | 응답 쿠키 `XSRF-TOKEN`을 읽는다. JSON 파싱하지 않는다. |
| 로그인 응답과 `/auth/me`는 `AdminIdentity` | `id/loginId/displayName/active/authVersion/mustChangePassword/systemAdmin`. 역할 ID·만료 시각은 없다. |
| 비밀번호 변경은 204 후 세션 무효화 | 성공 후 다시 로그인한다. 기존 세션으로 계속 이동하지 않는다. |
| 계정 목록에 배정 역할이 없고 역할 배정 조회 API도 없음 | 편집 초기값을 안전하게 읽는 계약을 C-31에서 정한다. 빈 역할로 덮어쓰거나 explain 결과로 배정을 추측하지 않는다. |
| `/permissions`와 `/role-templates`는 SYSTEM_ADMIN 전용 | 일반 메뉴 편집자에게 전체 권한 카탈로그 호출을 강제하지 않는다. |
| `PermissionCatalog.Screen`은 등록된 routeKey·permissionKey 조합만 허용 | 현재 운영 Screen 등록·화면 카탈로그 GET은 없다. C-29에서 등록/탐색 방법을 정한다. |
| `cms.history` 권한 키 없음; 이력은 SYSTEM_ADMIN 직접 검사 | 화면을 위해 존재하지 않는 권한 키를 호출하지 않는다. C-14를 유지한다. |
| `/admin/**`는 로그인·CSRF API 외 모두 인증 필요 | 현재 설정 그대로 Spring에 로그인 HTML을 넣으면 로그인 화면부터 차단된다. C-28의 공개 정적 경로 설계가 필요하다. |
| 첫 비밀번호 변경 대상은 세션 필터의 API 허용 목록 외 403 | SPA HTML·asset 공개뿐 아니라 이 필터의 정적 경로 처리도 검증해야 한다. |
| Spring 기본 정적 매핑 비활성, LOCO 컨트롤러 선언 검사 존재 | 정적 핸들러를 명시적으로 구성해야 한다. 일반 `com.loco` 컨트롤러 추가가 자동으로 동작한다고 가정하지 않는다. |

이하 API 표의 경로는 `/admin/api/core` 기준이다. **제안 API**는 현행 API와 구분한다.

## 3. 화면 목록과 API 연결

| 화면 / 제안 UI 경로 | 기능과 현행 API | 접근·버튼 조건 |
|---|---|---|
| 로그인 `/admin/login` | GET `/auth/csrf`, POST `/auth/login`; 일반 오류 표시, 로그인 ID·비밀번호 입력 | 비로그인 허용. 초기 관리자 생성은 기존 일회성 CLI로만 수행 |
| 첫/일반 비밀번호 변경 `/admin/password` | GET `/auth/me`, PUT `/auth/password` (`currentPassword/newPassword`) | 본인 세션. `mustChangePassword=true`면 다른 업무 화면 대신 이 화면으로 이동 |
| 관리자 계정 `/admin/accounts` | GET/POST `/accounts`, PUT `/accounts/{id}/status`; 계정 생성·활성/비활성, 역할 배정 PUT `/accounts/{id}/roles` | SYSTEM_ADMIN. 역할 배정 조회는 C-31 선택 후 추가. 임의 계정 삭제·비밀번호 초기화·이름 수정 API는 현재 없음 |
| 역할·권한 `/admin/roles` | GET/POST `/roles`, GET/PUT/DELETE `/roles/{id}`, PUT `/roles/{id}/permissions`, GET `/permissions`, `/role-templates` | SYSTEM_ADMIN. 보호 역할·사용 중 역할 등의 서버 거절 표시 |
| 메뉴 트리 `/admin/menus` | GET `/menus`, POST `/menus`, PUT/DELETE `/menus/{id}`, POST `/menus/{id}/move-preview`, PUT `/menus/{id}/position` | `cms.menus READ` 조회, MANAGE 생성·편집·이동·정렬·미리보기·삭제 |
| 내 접근 근거 `/admin/access` | GET `/permissions/explain?permissionKey=...&action=READ 또는 MANAGE` | 비밀번호 변경을 마친 본인. 일반 관리자는 accountId 생략; 타인 조회는 SYSTEM_ADMIN만 |
| 변경 이력 `/admin/history` | GET `/history`, GET `/history/{id}`; 대상·행위자·기간 필터, 전후 JSON | 목록·상세 모두 SYSTEM_ADMIN(C-14). 수정·삭제·내보내기 기능 없음 |
| 파일 `/admin/files` | GET/POST `/files`, GET `/files/{id}`, GET `/files/{id}/content`, DELETE `/files/{id}` | `cms.files READ` 전체 목록·상세·다운로드, MANAGE 업로드·삭제. 소유자 제한 없음(C-18) |
| 공통 헤더·사이드바 | GET `/auth/me`, GET `/navigation`, 필요한 키의 `/permissions/explain`, POST `/auth/logout` | 사용자 이름, 접근 근거, 비밀번호 변경, 로그아웃. C-29에 따라 고정 관리 도구와 편집 메뉴를 구분 |

### 3.1 권한 표시와 편집

- 서버 `navigation` 결과의 표시 가능한 PAGE와 조상 GROUP만 업무 메뉴로 렌더링한다. 전체 `/menus`를 일반 사용자의 탐색 메뉴로 대신 쓰지 않는다. `/navigation`은 별도 `cms.menus READ`를 요구하지 않는다.
- routeKey는 프런트의 등록된 화면 매핑에서만 해석한다. 서버 문자열을 임의 URL·동적 import 경로·HTML로 실행하지 않는다. 알 수 없는 키는 진입 링크를 만들지 않고 구성 오류로 표시한다.
- 화면 진입/변경 버튼은 서버 explain의 `allowed` 결과로 판단한다. 권한 조회 중에는 변경 버튼을 허용하지 않는다. 오류를 허용으로 취급하지 않는다.
- 역할 편집은 권한 키마다 **없음 / READ / MANAGE** 중 하나다. MANAGE가 READ를 포함함을 표시하며, 별도 deny·상속 체크박스는 없다. 서버의 `allowedActions`, `systemOnly`를 따른다.
- `systemOnly` 권한은 일반 역할에 넣어 위임하는 UI를 만들지 않는다. SYSTEM_ADMIN 여부는 `/auth/me`의 서버 값으로 읽는다. 이력 화면은 별도 키가 없는 C-14 예외다.
- explain의 `reason`, 역할 `grants`, `impliesRead`로 “어떤 역할의 MANAGE 때문에 READ가 가능한지”를 설명한다. `SYSTEM_ADMIN` 허용은 역할 목록을 꾸며내지 않고 그 이유를 그대로 표시한다.
- 역할 템플릿 READ_ONLY/MANAGER는 생성 시 복사만 한다. “이후 템플릿 변경이 기존 역할에 전파되지 않음”을 생성 화면에 표시한다.
- 권한 캐시는 현재 세션의 메모리에만 둔다. 계정/역할 변경 성공·401·403 시 폐기하고 필요한 값을 다시 조회한다. 서버는 요청마다 다시 판단한다. 주기적 keep-alive 조회로 idle 만료를 막지 않는다.

### 3.2 메뉴 이동·정렬·미리보기

1. GET `/menus`의 `revision`과 평탄한 nodes를 ID 기준 트리로 표시한다. 컴포넌트 key는 독립 UUID다.
2. 대상 메뉴, 새 부모(GROUP 또는 루트), 새 위치를 선택한다. 요청은 `parentId`, `beforeSiblingId`, `revision`; `beforeSiblingId=null`은 마지막이다. sortOrder 숫자를 직접 보내지 않는다.
3. POST move-preview로 기존/새 부모와 “권한 변화 없음”을 확인한다. 조회 성격이어도 POST이므로 MANAGE와 CSRF가 필요하다.
4. 미리보기 후 선택이 바뀌면 미리보기를 폐기한다. 적용은 PUT position이며, 미리보기는 락 예약이 아니다.
5. 성공 응답의 전체 Tree로 화면을 갱신한다. 저장 전에 서버 트리가 바뀐 것처럼 확정 표시하지 않는다. 이동해도 대상·자손 ID가 바뀌지 않음을 브라우저 테스트에서도 확인한다.

화면의 자기 자신·자손 이동 차단은 실수 예방용이다. 서버의 비관적 락+revision·순환 검사가 최종 기준이다. 삭제는 하위 메뉴가 있으면 서버가 거절하며, UI에서 암묵적으로 자손까지 삭제하지 않는다. PAGE/GROUP 타입은 생성 후 변경 불가다. C-26 어느 방식이든 키보드·터치로 이동 가능한 수단을 둔다.

### 3.3 변경 이력과 파일

- 이력 필터는 `resourceType/resourceId/actorId/from/until/cursor/size`다. 행위(action) 필터는 현재 API에 없다. 빈 필터는 쿼리에서 생략한다. 시간 입력은 Asia/Seoul 표시를 명시하고 UTC ISO 시각으로 변환한다. `from` 포함, `until` 제외이며 시작 < 종료를 검증한다.
- 이력 페이지는 `{items,nextCursor}`다. 전체 개수·임의 페이지 점프를 만들지 않는다. 커서는 해석하지 않고 전달하며, 필터 변경 시 초기화한다. 전후 JSON은 이스케이프된 텍스트/필드 비교로 표시하고 `v-html`을 쓰지 않는다.
- 파일은 10MiB, 한 요청에 multipart `file` 하나. JPEG/PNG/WebP/AVIF는 서버에서 WebP로 변환하며 HEIC는 미지원이다. PDF/TXT/CSV는 원본 정책을 따른다. 클라이언트 크기/확장자 검사는 안내이며 서버 내용 검사를 대체하지 않는다.
- 파일 목록은 `page/size` 기반 **배열**이고 total이 없다. 이전/다음으로 탐색한다. 반환 수가 size와 같으면 다음 페이지가 비어 있을 수 있음을 허용한다. 검색·상태 필터 API를 가정하지 않는다.
- ACTIVE만 보인다. UPLOADING/FAILED/ABANDONED/DELETE_PENDING 관리 화면은 이번 범위가 아니다. 파일 메타데이터에는 객체 키나 서명 URL이 없으며 화면에서도 만들지 않는다.
- 업로드는 FormData로 전송하고 Content-Type을 직접 지정하지 않는다. fetch 기준 정확한 업로드 백분율 대신 진행 중·완료·실패를 표시한다. 파일 본문·세션·비밀번호를 브라우저 저장소나 로그에 남기지 않는다.
- 다운로드 기준안은 동일 출처 fetch로 상태를 먼저 확인한 뒤 Blob 다운로드다. 최대 10MiB 파일을 브라우저 메모리에 보유하는 비용이 있고, object URL은 사용 후 해제한다. 서버는 기존 스트리밍 계약을 유지한다. 일반 파일을 HTML로 인라인 실행하지 않는다.
- 삭제 성공은 “목록에서 제거됨”으로 안내한다. 물리 삭제는 재정리될 수 있어 즉시 완전 삭제를 보장한다고 쓰지 않는다.
- 응답 유실·사용자 취소는 서버 작업 취소/롤백의 증거가 아니다. 업로드 성공 여부가 불명확하면 목록을 확인하도록 하고 POST를 자동 재전송하지 않는다. 수동 재업로드의 중복 가능성은 C-19 B의 한계로 표시한다.

## 4. 세션·CSRF·오류 사용자 흐름

### 4.1 부팅과 로그인

1. 공개 SPA shell을 연다. 업무 데이터는 포함하지 않는다. 상대 URL로 GET `/auth/csrf`를 호출해 쿠키를 준비하고 `/auth/me`로 세션을 확인한다.
2. 쿠키는 `CMSSESSION`(HttpOnly, SameSite=Strict)과 `XSRF-TOKEN`(JS 읽기 가능), 모두 Path=/admin이다. 운영 Secure를 유지한다. 토큰을 localStorage에 복사하지 않는다.
3. 변경 요청마다 **현재 쿠키**에서 읽은 CSRF 값을 `X-XSRF-TOKEN`에 넣는다. 요청은 `credentials: 'same-origin'`이다. 기본 fetch에 없는 전역 재시도 기능을 추가하지 않는다.
4. 로그인 시 세션 ID와 CSRF가 교체된다. 응답의 `mustChangePassword`를 먼저 처리하고, 그 다음 navigation/explain을 가져온다. 비밀번호 필드는 성공/이탈 시 지운다.
5. 첫 비밀번호 변경 성공 후 세션이 무효화되므로 “변경 완료, 다시 로그인”을 안내한다. 로그아웃 후에도 CSRF가 재발급되므로 이전 값을 계속 사용하지 않는다.

### 4.2 오류별 처리

| 응답/상황 | 사용자 흐름 | 금지하는 동작 |
|---|---|---|
| 401 세션 만료·계정 비활성·인증 변경 | 메모리의 계정/권한/업무 데이터와 민감 입력을 지우고 로그인 화면으로 이동. 복귀 경로만 검증된 `/admin` 내부 route로 유지 | JWT refresh 시도, 임의 외부 return URL, 실패한 쓰기 자동 재전송 |
| 403 `Password change required` | `/auth/me`로 상태 확인 후 비밀번호 변경 화면 | 일반 권한 부족으로 오인해 전체 로그아웃 반복 |
| 403 일반 `Forbidden` | 아래 C-30에 따라 세션/권한/CSRF를 구분. 권한 없음이면 접근 불가·내 근거 링크 표시 | 모든 403을 CSRF라고 단정하거나 자동 POST 재시도 |
| 409 트리 revision 불일치 | “다른 관리자가 변경함”, 최신 트리 조회. 이동 의도는 임시 표시만 유지하고 새 대상·위치를 사용자가 다시 확인, 미리보기 후 적용 | revision만 최신 값으로 바꿔 조용히 덮어쓰기 |
| 409 순환·자식 있는 메뉴 삭제·보호 역할 등 | 해당 충돌 이유 표시, 입력 수정/취소. 역할 version·계정 authVersion 충돌은 해당 데이터를 다시 읽음 | 모든 409를 트리 재조회로 처리 |
| 400 입력, 413 크기, 415 파일 형식 | 입력 근처의 오류와 요약. 파일 자체/서버 내부정보는 출력하지 않음 | 정의되지 않은 필드별 오류 구조를 가정 |
| 로그인 429 | IP·계정 각각 5회/분 안내, 잠시 후 수동 재시도. 계정 존재 여부는 노출하지 않음 | 자동 로그인 반복, 서버에 없는 Retry-After로 정확한 해제 시각 단정 |
| 네트워크 오류·5xx·응답 유실 | 읽기는 사용자 재조회, 쓰기는 결과 불명확 안내 후 현황 확인 | 성공/롤백 단정, 업로드·역할 배정 자동 재전송 |

서버는 idle 30분·절대 8시간을 판단한다. API 요청 자체가 idle 시간을 갱신하므로 폴링을 만료 검사용으로 쓰지 않는다. UI가 자체 타이머를 사용해도 서버 만료 판정을 대신할 수 없다. 세션 만료 후 변경 요청은 CSRF 검사 때문에 먼저 403이 될 수 있으므로 401만 처리해서는 안 된다. 다른 탭의 로그인/로그아웃으로 CSRF가 바뀔 수 있어 요청마다 쿠키를 읽는다.

## 5. 빌드·개발·운영 배치

### 5.1 개발

- 제안 구조는 `apps/admin/src/{api,composables,router,views,components}` 정도로 시작한다. 범용 컴포넌트는 실제 중복이 생긴 폼 오류·버튼·페이지 틀만 둔다. 프런트에서 backend 도메인 모델을 별도 구현하지 않는다.
- Vite `base: '/admin/'`, 브라우저 진입 `/admin/login`, `/admin/api`만 Spring으로 프록시한다. prefix를 지우지 않는다. 브라우저 관점에서는 같은 출처이므로 CORS 예외를 추가하지 않는다.
- 로컬 HTTP에서만 기존 `CMS_COOKIE_SECURE=false`를 명시한다. 운영은 HTTPS·Secure=true다. localhost/127.0.0.1을 혼용하지 않는다. 쿠키 Domain/Path를 광범위하게 재작성하지 않는다.
- dev 서버는 로컬 인터페이스에 바인딩한다. 브라우저에 배포되는 `VITE_*`에는 DB·S3·세션 비밀값을 넣지 않는다. 외부 키 없이 로컬 DB·SeaweedFS로 검증한다.
- 선택 후 사용할 명령 계획: `npm ci`, `npm run dev`, `npm run typecheck`, `npm run test:unit`, `npm run build`, `npm run test:e2e` (`apps/admin` 기준). **아직 package.json이 없으므로 현재 실행 가능한 명령은 아니다.**

### 5.2 운영

C-28의 두 후보 모두 `/admin/api`를 같은 외부 origin으로 유지한다. Vue Router HTML5 history를 기준으로 알려진 UI 경로의 새로고침을 shell에 연결하고, `/admin/api/**`와 누락 asset은 HTML fallback에서 제외한다. API 오류를 200 index.html로 바꾸지 않는다. [Vue Router 배포 주의](https://router.vuejs.org/guide/essentials/history-mode.html), [Vite 서버 옵션](https://vite.dev/config/server-options.html).

Spring 제공안에서는 `dist`를 호스트 JAR의 전용 정적 디렉터리로 복사하고 명시적 리소스 핸들러로 제공한다. 소스 디렉터리에 빌드 산출물을 커밋하지 않는다. 현재 `spring.web.resources.add-mappings=false`와 [ApiWebConfig](src/main/java/com/loco/config/ApiWebConfig.java)의 컨트롤러 검사를 고려한다. 기존 [UploadWebConfig](src/main/java/com/loco/images/UploadWebConfig.java)의 정적 핸들러 방식처럼 API 컨트롤러와 분리할 수 있다.

공개 범위는 알려진 SPA shell·asset의 GET/HEAD뿐이다. `/admin/** permitAll`로 API까지 열지 않는다. 첫 비밀번호 변경 상태에서도 shell·asset을 읽게 하되 업무 API 제한은 유지한다. [GlobalHttpFilter](src/main/java/com/loco/web/GlobalHttpFilter.java)의 `/admin` 제외를 유지하며 `/error` forward 때문에 LOCO 전역 필터로 새지 않는지도 검증한다. HTML은 재검증하도록 하고 해시 asset만 장기 캐시한다. CMS API·파일의 기존 보안 헤더와 no-store 정책을 유지한다.

별도 서버안은 nginx 등에서 shell·asset을 제공하고 `/admin/api`를 Spring에 전달한다. 브라우저 출처가 다르지 않게 구성한다. 이때 로그인 IP 제한은 현재 `getRemoteAddr` 기준이라 프록시 IP 하나로 합쳐질 수 있다. 이 안을 선택하면 기존 후속 과제였던 **신뢰 프록시/IP 처리**를 배포 전에 별도 결정·검증해야 한다. 임의 X-Forwarded-For를 신뢰하지 않는다.

### 5.3 버전 후보와 고정 방법

2026-11-08 공식 npm registry `latest` 메타데이터와 Node 배포 목록을 조회한 **설계 후보**다. 설치·빌드 호환성을 검증한 결과는 아니다. 구현 시 다시 확인하여 exact 버전·lockfile을 함께 고정하고, 아래에서 달라지는 주요 버전/새 의존성은 이유를 기록한다.

| 도구 | 확인한 안정 버전 후보 | 용도 |
|---|---|---|
| Node / npm | C-32 A: 24.22.2 / 11.19.1, B: 26.13.0 / 11.20.0 | 로컬·CI·빌드 이미지에 같은 exact 버전 |
| Vue / vue-router | 3.5.45 / 5.4.0 | SPA·라우팅, Pinia peer는 선택 사항 |
| Vite / @vitejs/plugin-vue | 8.3.6 / 6.0.10 | 개발 프록시·SFC 빌드 |
| TypeScript / vue-tsc | 7.0.2 / 3.3.14 | TS·SFC 타입 검사; 실제 typecheck 성공을 고정 조건으로 삼음 |
| Vitest | 5.1.0 | C-27 채택 시 단위 테스트 |
| @playwright/test | 1.65.0 | C-27 채택 시 E2E, 해당 버전의 브라우저 revision 함께 고정 |

조회 출처: [Node 24 릴리스](https://nodejs.org/en/blog/release/v24.22.2), [Node 26 릴리스](https://nodejs.org/en/blog/release/v26.13.0), [Node 배포 목록](https://nodejs.org/dist/index.json), [Vue](https://registry.npmjs.org/vue/latest), [vue-router](https://registry.npmjs.org/vue-router/latest), [Vite](https://registry.npmjs.org/vite/latest), [plugin-vue](https://registry.npmjs.org/@vitejs%2Fplugin-vue/latest), [TypeScript](https://registry.npmjs.org/typescript/latest), [vue-tsc](https://registry.npmjs.org/vue-tsc/latest), [Vitest](https://registry.npmjs.org/vitest/latest), [Playwright](https://registry.npmjs.org/@playwright%2Ftest/latest).

선택 후 `apps/admin/package.json` exact 버전(범위 기호 없음), `package-lock.json`, Node 버전 파일·CI 버전·packageManager npm 버전을 함께 관리하고 CI는 `npm ci`를 사용한다. 선언된 peer/engine 범위는 후보끼리 맞지만 실제 설치·Windows/Linux typecheck·build로 확인해야 한다. 선택하지 않은 UI/상태/드래그 패키지는 설치하지 않는다. v2의 Node 20·기존 lockfile·루트 저장소 구조는 그대로 둔다. Vite 빌드는 타입 검사와 별개이므로 `vue-tsc`를 필수 검사로 둔다.

## 6. 결정할 항목

아래는 승인 전 비교 기록이다. 최종 선택은 문서 상단의 승인 표를 따른다(C-30은 권장 A 대신 B 채택). 이후 선택 답변도 **번호와 항목 이름**을 함께 적는다.

### C-24 UI 컴포넌트 라이브러리

| 선택 | 장점 | 단점 |
|---|---|---|
| **A. 없음: 기본 HTML + 필요한 공통 Vue 컴포넌트 (권장)** | 런타임 의존성 최소, 단순 폼·표 중심 범위에 맞음 | 일관된 스타일·포커스·오류 안내를 직접 검증해야 함 |
| B. 경량 조합: Reka UI의 필요한 headless primitive만 | 복잡한 팝업 등의 상호작용 재사용, 스타일 통제 | CSS 작업은 남고 의존성 추가; 실제 번들 크기는 측정 필요 |
| C. 전체 UI 키트: Element Plus | 표·폼·대화상자 구성 속도, 일관된 화면 | 사용법·스타일 결합과 의존성 증가, 필요 부분 import 관리 |

근거: [Reka UI](https://reka-ui.com/docs/overview/introduction), [Element Plus](https://element-plus.org/en-US/guide/quickstart.html). 어떤 안이든 label·오류 연결·키보드·대화상자 포커스 복귀를 검증한다. 라이브러리 사용만으로 접근성 완료라고 판단하지 않는다.

### C-25 상태 관리

| 선택 | 장점 | 단점 |
|---|---|---|
| **A. composable만 (권장)** | 추가 의존성 없이 세션·navigation·권한 공유 가능, 페이지 폼은 로컬 상태 | 공유 상태가 커지면 추적·개발 도구 지원이 부족 |
| B. Pinia | 명시적인 store, 개발 도구로 변경 추적 용이 | 작은 초기 범위에서 추가 설정·의존성 발생 |

A도 페이지마다 별도 세션을 만들지 않고 앱 수준에서 제공하는 단일 상태와 요청 중복 억제를 둔다. 두 안 모두 권한 캐시는 허가의 최종 근거가 아니며 토큰·비밀번호를 영속 저장하지 않는다. [Pinia 소개](https://pinia.vuejs.org/introduction.html).

### C-26 메뉴 트리 이동 구현

| 선택 | 장점 | 단점 |
|---|---|---|
| **A. 버튼 이동 + 부모/위치 선택 (권장)** | 의존성 없음, 키보드·터치 대응 단순, preview/revision 흐름 명확 | 드래그보다 여러 번 조작해야 함 |
| B. 기본 HTML5 DnD + 버튼 대안 | 라이브러리 없이 마우스 이동 제공 | 중첩 트리·터치·키보드·스크롤 처리 직접 구현 |
| C. SortableJS + 버튼 대안 | 드래그 동작 재사용, 포인터 조작 편의 | DOM 순서와 Vue/서버 상태 조율, 의존성·중첩 트리 검증 추가 |

어떤 안이든 서버 미리보기 후 적용한다. 드래그 완료만으로 즉시 저장하지 않는다. [SortableJS](https://github.com/SortableJS/Sortable).

### C-27 프런트 테스트 범위

| 선택 | 장점 | 단점 |
|---|---|---|
| **A. Vitest + 핵심 Playwright Chromium E2E (권장)** | 빠른 로직 검사와 실제 쿠키·라우팅·화면 흐름 모두 확인 | 브라우저·격리된 서버/DB 준비 필요 |
| B. Vitest + 수동 브라우저 확인 | 자동화 의존성과 초기 실행 비용 최소 | 쿠키·CSRF·새로고침 회귀를 수동으로 반복해야 함 |
| C. Vitest + Chromium/Firefox/WebKit E2E | 브라우저별 차이까지 자동 확인 | 실행 시간·브라우저 관리·실패 분석 비용 증가 |

Vitest는 요청/오류 분기·상태·트리 입력 변환 중심으로 시작한다. 별도 DOM 모사 라이브러리는 기본 추가하지 않는다. E2E는 화면을 사용자가 조작하는 방식으로 검사한다. [Vitest](https://vitest.dev/guide/), [Playwright 권장 방식](https://playwright.dev/docs/best-practices).

### C-28 운영 정적 파일 제공 방식

| 선택 | 장점 | 단점 |
|---|---|---|
| **A. Spring 호스트가 빌드된 SPA 제공 (권장)** | 한 배포 단위·동일 출처, 별도 운영 서버 불필요 | 프런트 변경도 앱 배포 필요, 정적 경로·SecurityFilterChain·필터 예외 검증 필요 |
| B. 별도 정적 서버 + 동일 출처 API 프록시 | 정적 파일/백엔드 독립 배포·캐시 운영 | 프록시·TLS·배포 호환 관리 추가, 신뢰 프록시/IP 제한 설계를 앞당겨야 함 |

두 안 모두 UI shell은 공개하고 API는 보호한다. C-28 A가 광범위한 정적 매핑 활성화나 `/admin/**` 공개를 뜻하지 않는다. B도 별도 origin+CORS 전환을 뜻하지 않는다.

### C-29 관리 도구와 편집 메뉴·화면 카탈로그

| 선택 | 장점 | 단점 |
|---|---|---|
| **A. 고정 관리 도구 + 서버 편집 메뉴 분리 (권장)** | 관리 진입점이 메뉴 삭제로 사라지지 않음, 현행 navigation/explain 활용, 추가 카탈로그 API 불필요 | 고정 도구와 편집 메뉴가 구분됨; 서버·프런트 화면 키 목록 일치 검증 필요 |
| B. 관리 화면도 서버 메뉴로 통합 + 화면 카탈로그 조회 API | 탐색·화면 선택을 서버 목록 중심으로 관리 | 초기 메뉴 시드·카탈로그 조회 권한·이력 화면 권한 키 설계 추가, 관리 진입점 보호 필요 |

A에서는 기본 도구(계정·역할·메뉴·내 접근 근거·이력·파일)를 고정 영역에 배치하되 auth/me와 explain 결과로 표시한다. 이력은 C-14 SYSTEM_ADMIN 예외다. 편집 메뉴는 `/navigation`만 사용한다. 호스트의 기존 `PermissionCatalog.Contribution` 확장점에 PAGE 후보를 등록하고 프런트의 제한된 routeKey 목록과 계약 테스트로 맞춘다. 초기 후보는 기존 권한 키가 있는 계정·역할·메뉴·파일이며, 이력/인증/내 근거는 고정 도구로 둔다. 일반 메뉴 MANAGE 사용자도 이 제한된 후보를 선택할 수 있지만 서버가 최종 조합을 검증한다. 같은 화면의 고정 링크와 편집 링크가 공존할 수 있음을 허용한다. 자동 DB 메뉴 시드는 하지 않는다.

B에서는 읽기 전용 화면 카탈로그를 메뉴 편집 권한 보유자가 조회하게 하고 초기 메뉴를 멱등 생성해야 한다. 이력까지 PAGE로 넣으려면 C-14의 SYSTEM_ADMIN 제한을 유지하는 등록 계약도 필요하다. core가 LOCO 화면을 직접 등록하는 구조는 두 안 모두 금지한다.

### C-30 403 원인 구분 계약

| 선택 | 장점 | 단점 |
|---|---|---|
| **A. 현행 응답 유지 + 안전한 상태 조회 (권장)** | 서버 오류 계약 변경 없이 구현 | 일반 Forbidden만으로 CSRF와 권한을 완전히 구분할 수 없어 보수적인 안내 필요 |
| B. CMS 전용 오류 code 추가 | 세션·CSRF·권한·revision 오류를 명확하게 분기 | 필터/예외 응답·계약 테스트 변경 필요. LOCO 응답에는 적용 금지 |

A는 실패한 쓰기를 반복하지 않고 `/auth/me`를 1회 확인한다. 401이면 로그인, 변경 필수이면 비밀번호 화면, 해당 explain이 거절하면 접근 근거 화면으로 안내한다. 세션·권한이 유효하면 `/auth/csrf`로 갱신하고 “보안 상태를 갱신했습니다. 내용을 확인한 뒤 다시 시도하세요”라고 안내한다. 여전히 거절되면 일반 접근 오류로 남긴다. “CSRF 오류로 확정”하지 않는다. 동시 실패 요청은 복구 조회 하나로 합치며 무한 재조회하지 않는다.

### C-31 관리자 역할 배정 조회 계약

| 선택 | 장점 | 단점 |
|---|---|---|
| **A. SYSTEM_ADMIN 전용 GET `/accounts/{id}/roles` 추가 (권장)** | 편집할 계정만 조회, 정확한 roleIds와 authVersion으로 전체 교체 안전성 확보 | 읽기 API·권한 검사·계약 테스트 추가 |
| B. 계정 목록 응답에 roleIds 포함 | 별도 상세 요청 없이 편집 초기화 | 매 목록 조회 비용·응답 증가, 기존 AdminIdentity와 목록 DTO 분리 필요 |

A의 제안 응답은 `{accountId,roleIds,authVersion}`이며 한 일관된 읽기에서 가져온다. 두 안 모두 PUT의 authVersion 충돌 검사를 유지한다. explain은 허용에 기여한 역할만 보여주므로 전체 배정 목록의 대체가 아니다. 조회 전 저장 버튼을 열지 않는다. 역할 배정 변경이 대상 세션을 무효화할 수 있음을 확인 화면에 표시한다.

### C-32 Node 빌드 기준

| 선택 | 장점 | 단점 |
|---|---|---|
| **A. Node 24 LTS, 확인 버전 24.22.2 (권장)** | 확인한 Vite/Vitest engine 범위 충족, 지원 중인 LTS에서 도구 조합 검증 가능 | Node 26보다 지원 잔여 기간이 짧음 |
| B. Node 26 LTS, 확인 버전 26.13.0 | 더 긴 지원 기간, 새 프로젝트 최신 LTS 기준 | 최근 LTS 전환 버전이라 도구·빌드 이미지 조합을 새로 검증해야 함 |

두 안 모두 npm과 직접 의존성을 5.3절 방식으로 고정한다. 현재 조회한 peer 범위 통과와 실제 설치/빌드 성공은 구분한다. 사용자의 시스템 Node나 v2 CI를 함께 바꾸지 않는다.

## 7. 선택 후 구현·검증 계획

다음 순서로 구현하며 E2E는 allTests에 포함하지 않고 별도 명령으로 실행한다.

1. C-24~C-32 결정 기록 후 최소 `apps/admin` 구성과 typecheck/build, 개발 프록시를 만든다. C-28에 따른 공개 shell·보호 API 경계를 먼저 검증한다.
2. 로그인·첫 비밀번호 변경·로그아웃, 공통 요청 처리와 오류 흐름을 만든다. 기존 계정/역할/파일 비밀값을 프런트 로그나 캡처에 남기지 않는다.
3. 계정·역할·권한·내 접근 근거와 C-31 조회 계약, C-29 화면 등록을 만든다.
4. 메뉴 트리·미리보기·이동·정렬, 이력 필터·페이지, 파일 업로드·다운로드·삭제를 연결한다.
5. 선택한 테스트 범위와 실제 배포 형태에서 직접 URL 진입·새로고침·누락 asset/API 경계를 확인한다. 구현 단계마다 파일의 역할과 핵심 설정을 설명하고 커밋한다.

| 검증 대상 | 확인할 결과 |
|---|---|
| 인증·CSRF | 로그인 세션 ID 교체, 최신 XSRF 쿠키로 변경 요청, CSRF 누락/오래된 값 거절, 로그인·로그아웃 후 새 토큰 사용 |
| 강제 변경·만료 | 강제 변경 중 shell/asset 로드 가능·업무 API 거절, 변경 후 재로그인, idle/절대 만료 시 화면 복구. 서버 Clock 기반 테스트로 30분/8시간을 실제 기다리지 않음 |
| 교차 인증 | LOCO JWT로 CMS 거절, CMS 세션으로 LOCO 인증 불가. UI 숨김을 우회해 직접 호출해도 거절 |
| 역할별 화면 | SYSTEM_ADMIN, 메뉴 READ/MANAGE, 파일 READ/MANAGE, 권한 없는 계정의 링크·버튼·직접 진입. MANAGE⊃READ와 template 복사 표시 |
| 권한 변경·경합 | 기존 화면을 연 뒤 권한 회수/비활성화해도 API 거절. C-31 초기값 정확성·authVersion 충돌·잘못된 전체 교체 방지 |
| 트리 | 하위 트리 이동 전후 UUID 불변, 권한 변화 없음 표시, 두 브라우저 context의 같은 revision 편집 시 충돌→재조회→재미리보기, 잘못된 이동 차단 |
| 이력 | 일반 관리자 목록/상세 거절, 정확한 대상·행위자·기간/커서, 전후 값 escaping, 성공 변경의 이력 표시. 기존 서버 비밀값 배제 검사는 유지 |
| 파일 | READ/MANAGE, 허용·초과·잘못된 형식, 실제 로컬 SeaweedFS 업로드→목록→다운로드 바이트/변환 결과→삭제, 응답 유실 후 자동 재업로드 없음 |
| 정적 배포 | 비로그인/강제 변경 상태의 새로고침 성공, `/admin/api` 미인증·없는 API가 HTML 200으로 바뀌지 않음, 없는 asset 404, LOCO `/api`·`/uploads` 불변 |
| 사용성 | 키보드 전체 조작, 포커스 복귀, 오류 읽기, 좁은 화면, 데이터 없는 표·네트워크 실패 |

E2E는 격리된 테스트 DB·로컬 SeaweedFS·합성 관리자만 쓴다. 실제 AWS·메일·OAuth·FCM을 호출하지 않는다. 테스트용 초기 계정 생성은 기존 CLI/fixture를 사용하고 운영용 테스트 endpoint를 만들지 않는다. 로그인 제한을 끄지 않고 fixture 격리로 시나리오 간 간섭을 막는다. 로그인/파일 본문/쿠키가 포함될 수 있는 trace·영상·스크린샷·네트워크 로그는 기본 수집하지 않으며 공개 CI 산출물에 올리지 않는다.

테스트 범위는 기존 사용자 기준을 따른다. core만 변경하면 core test + root 빠른 test. 호스트 앱·공통 설정·빌드 구성이 바뀌면 전체 allTests이며 머지 전에도 전체 회귀를 실행한다. 이번 5-B 구현은 호스트 경계/빌드 변경이 예상되므로 완료 검증에 allTests·build와 선택한 프런트 검사를 포함한다. LOCO 알려진 차이 11건은 UI 때문에 추가·삭제하지 않는다. 실행 시간은 실제 수행한 범위별로 측정하고 커밋 본문에 남긴다.

## 8. 이번 설계의 확인 범위

현행 컨트롤러·권한·세션·정적 핸들러와 문서의 API/권한을 대조했다. 패키지 버전은 공식 배포 메타데이터에서 조회했다. 문서의 상대 링크·차이 공백 검사를 수행한다. 구현·의존성 설치·브라우저 검증·Gradle 회귀는 수행하지 않는다. 패키지 설치 호환성과 실제 화면 동작은 선택 후 검증할 항목이다.

## 9. 구현 기록

### 1단계: 빌드·정적 경계

- `apps/admin`은 Vue/Vite/TypeScript만으로 시작한다. Node 24.22.2/npm 11.19.1은 `prepare-node.ps1`의 프로젝트 전용 경로와 Docker 빌드에서 고정한다. 시스템 Node와 v2 설정은 바꾸지 않는다.
- `AdminPublicResources`는 core가 호스트의 공개 UI 리소스를 판단하는 확장점이다. GET/HEAD만 허용하고 `/admin/api`는 이 확장점으로 공개할 수 없다. 호스트 `AdminUiConfiguration`은 등록 화면·해시 asset만 제공하며 없는 API에 index.html을 반환하지 않는다.
- 초기 후보 TypeScript 7.0.2는 `vue-tsc 3.3.14`가 요구하는 `typescript/lib/tsc`를 export하지 않아 `ERR_PACKAGE_PATH_NOT_EXPORTED`로 실패했다. 안정 JavaScript compiler API를 제공하는 **TypeScript 6.0.3**으로 exact 고정해 typecheck/build 통과. 새 의존성·검사 우회는 추가하지 않았다. [공식 Vue 도구의 TS7 지원 논의](https://github.com/vuejs/language-tools/issues/5381).
- 최초 설계 당시의 “미구현·미검증” 기록(8절)과 이후 구현 결과를 구분한다.
