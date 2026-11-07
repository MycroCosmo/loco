# CMS(B) 핵심 모듈 설계안

상태: **5-A·5-B 완료, 6단계 설계 완료·C-33~C-42 선택 대기(미구현).** 6단계는 [LOCO_ADMIN_DESIGN.md](LOCO_ADMIN_DESIGN.md)에서 호스트 이력 계약·C-14 재검토·LOCO 조회·C-18 보류안을 다룬다. C-24~C-32 구현과 검증 결과는 [ADMIN_UI_DESIGN.md 9절](ADMIN_UI_DESIGN.md#9-구현-기록)에 있다. C-30은 CMS 전용 오류 code를 추가하는 B이며, C-27 E2E는 allTests와 별도 실행한다. 5-B 최종 서버 1,567건 중 성공 1,566·실패 0·HEIC 공통 제약 건너뜀 1(1,190.011초), Vitest 16건 성공, Chromium E2E 5건 성공(준비·종료 포함 36.323초), LOCO 알려진 차이 11건 불변이다.

작성일: 2026-11-07. 기준 main `9dc3c09`는 PR #2의 merge commit이며 이관 최종 `7f25789`를 부모로 보존한다. 이 main에서 `feature/cms-core`를 만들었다. 아래 1.1절과 단계별 기록이 최신 확정 내용이다. 8절의 미채택 대안은 비교 이력으로 보존한다. 아직 구현하지 않은 업무 모델·API 초안과 완료된 단계를 구분한다.

## 1. 근거와 확정 범위

- [노션 설계 v0.2](https://app.notion.com/p/40c140eabf0e81dca76de00565fe725b): ADR 6·9·10·11·14, 권한 4장, CMS 메뉴 5장, 2026-11-04 CMS(B) 결정 변경 기록을 읽었다. 조회된 마지막 편집 시각은 2026-11-05다.
- 현재 요청: 범용 core는 LOCO를 모르며 LOCO → core만 허용한다. JPA 단일, PostgreSQL JSONB 우선, 이미지 WebP/일반 파일 원본 객체 저장, 기존 앱 계약 보존. 이전 직장 코드·저장소는 열거나 입력·복제·재작성하지 않았다. 사용자가 설명한 문제만 독립 설계의 요구사항으로 사용했다.
- [이관 요약](MIGRATION_SUMMARY.md), [비교 하네스](COMPARISON_TESTS.md), [이미지 설계](IMAGE_API_DESIGN.md), [사용자 API](USER_API_DESIGN.md)를 현재 구현의 기준으로 삼는다.

| 항목 | 기존 근거 | 이번 문서에서의 취급 |
| --- | --- | --- |
| CMS 형태 | 2026-11-04 범용 core + LOCO 확장 확정 | 재논의하지 않음. C-1은 물리적 분리 방법만 결정 |
| 인증 | 노션 ADR 6: 앱 JWT/관리자 세션 확정. 관리자 계정 분리는 ADR 7에서 제안 | C-4에 기존 세션 방향을 표시하고 계정 분리·초기 생성 구체화. 토큰은 비교 대안 |
| 메뉴 | ADR 9: 고정 ID + parent_id 확정 | C-2 A가 기존 결정. 경로/클로저는 비용 비교용이며 변경은 별도 명시적 승인 필요 |
| 이력·파일 | JSONB, WebP, S3 호환 로컬 서버(SeaweedFS)→S3 | C-20 C로 기존 MinIO 계획 변경 |
| 관리자 화면 | 노션 기술 표 Vue 3/Nuxt 3 | 경험은 유지하되 지원 버전은 C-5에서 확인. Nuxt 3를 자동 채택하지 않음 |
| 노션의 미래 SQL·V2~V6 번호 | BIGINT, map_members.role, 운영 정지 등 이관 전 목표 초안 | 현재 구현 사실이 아님. V1 외 실제 마이그레이션은 아직 없음. 번호·앱 권한 변경을 그대로 가져오지 않음 |
| LOCO 파일 | 노션 초기 “1단계 MinIO”보다 이후 로컬 유지 결정이 최신 | 기존 /uploads는 유지. CMS 객체 저장소와 별도로 3단계 이전 계획에 연결 |

기존 회귀 기준은 전체 1,434건(성공1,433·실패0·HEIC 공통 제약 skip1), 알려진 차이11, 1,037.162초다. **이 문서 작성 중 재실행한 결과가 아니라 main에 기록된 이전 검증**이다.

### 1.1 사용자 확정 결정 — 2026-11-07

| 번호·항목 이름 | 확정 내용 | 근거·영향 |
| --- | --- | --- |
| C-1 모듈 분리 방식 | A: Gradle 멀티 모듈, modules/cms-core 하나만 추가 | LOCO → core만. 실행 앱은 기존 root 유지 |
| C-2 메뉴 트리 표현 | A: parent_id + 독립 UUID | 이동 시 ID 재생성 금지를 실제 이동 테스트로 고정(3단계 구현) |
| C-3 권한 모델 | B: 역할 + 업무 키별 READ/MANAGE, MANAGE⊃READ, 상속·deny 없음, 역할 템플릿 | 작은 권한 집합과 설명 가능한 판정 |
| C-4 관리자 인증·계정·초기 생성 | A: 별도 AdminAccount + 세션 + 일회성 CLI, idle30분·절대8시간·로그인5회/분 승인 | 인증은 2단계부터. 앱 JWT와 분리 |
| C-5 관리자 화면 기술 | **A 변형: Vue3 + Vite + TypeScript SPA + vue-router, Nuxt 미사용** | SSR/SEO 불필요, Nuxt 메이저 적응 비용 회피. apps/admin·개발 프록시 /admin/api 동일 출처. 구현 시 지원 안정 Vue/Vite/Node 버전 고정 |
| C-6 변경 이력 수집 | A: 서비스 명시 기록 + 같은 트랜잭션 | 업무 성공과 이력 저장 함께 커밋 |
| C-7 DB 배치·Flyway | B: 같은 DB·cms schema·기존 public history·다음 가용 버전 | 이번 V2는 빈 cms schema만 생성, 업무 테이블은 각 후속 단계에서 추가 |
| C-8 파일 저장소·LOCO 이전 | A: CMS만 객체 저장 포트 + 로컬 SeaweedFS, LOCO /uploads 유지 | C-20 C 반영. 기존 앱 파일·URL 계약 유지 |
| C-9-1 실행·HTTP 경계 | A: 한 프로세스, /admin/** 별도 SecurityFilterChain | v2 호환 전역 필터는 /admin에 적용하지 않는다. 관리자 HTTP 경로를 만드는 2단계에서 적용·회귀 검사 |
| C-9-2 트리 동시 변경 | A: 트리 메타 행 비관적 락 + revision | 이미지 3장 제한은 한 행 조건이라 조건부 UPDATE, 메뉴 순환 방지는 여러 행 조건이라 비관적 락 |
| C-9-3 CMS 파일 초기 정책 | A 승인 | 비공개, JPEG/PNG/WebP/AVIF→WebP만 보관, 일반 PDF/TXT/CSV 원본 attachment, 각각10MiB |
| C-9-4 이력 보존·수집 | A 승인 | 최소 성공 변경 정보, 초기 자동 삭제 없음. 운영 전 보존 기간 재검토 |
| C-9-5 첫 LOCO 확장 | A 승인 | 조회 메뉴부터, 운영 조치는 별도 설계 |

의사결정과 구현 완료는 다르다. 1단계 당시에는 업무 기능을 구현하지 않았으며, 2단계 구현 범위는 13절에 기록했다. 기존 선택지 중 Nuxt 권장안은 C-5 A 변형으로 대체했다. [노션 결정 변경 기록](https://app.notion.com/p/40c140eabf0e81dca76de00565fe725b)에도 동일 결정을 반영한다.

## 2. 해결할 문제와 성공 조건

| 사용자 문제 | 설계 대응 | 확인할 결과 |
| --- | --- | --- |
| 권한이 너무 세밀해 운영자·사용자 모두 어려움 | 역할 템플릿, 작은 동작 집합, 최종 권한 설명, 메뉴 위치와 권한 분리 | 운영자가 “누가 왜 접근 가능한가”를 한 화면에서 확인. 메뉴 이동으로 접근 권한이 바뀌지 않음 |
| 부모를 바꾸면 PK가 바뀌어 재입력 | UUID 식별자와 위치(parent/sort) 분리, 이동은 UPDATE | 노드·하위 노드 ID, 연결 데이터·권한·이력 참조가 모두 유지 |
| 데이터 접근 검사가 과도함 | Spring Data JPA 한 경로, 도메인 불변식과 DB 제약에 집중 | MyBatis/JDBC 업무 저장소·임의 범용 CRUD 검증 프레임워크 없음. 복잡 조회도 JPA native projection 허용 |
| 이력 저장소 복잡도 | JSONB + 검색용 일반 컬럼, 승인된 필드만 기록 | 누가·언제·무엇을·전후 값 조회, 비밀번호/세션/토큰/파일 본문 미기록 |
| 이미지와 일반 첨부의 저장 | WebP 변환과 객체 저장을 다른 책임으로 분리 | 이미지 결과 WebP, 일반 파일 체크섬 동일, 파일/DB 실패 보상 검사 |

권한 문제의 확인된 원인은 **세밀한 고객별 설정을 이해·운영하기 어려웠다는 경험**이다. deny 우선순위·역할 상속 등이 실제 옛 코드의 원인이었다고 단정하지 않는다. 여기서는 그런 복잡도를 새로 도입하지 않는 방향을 제안한다.

## 3. 범위와 의존 방향

| 범위 | 포함 | 포함하지 않음 |
| --- | --- | --- |
| CMS core | 관리자 계정·인증, 메뉴 트리, 역할/권한 판정, 변경 이력, 파일 메타데이터와 저장 포트 | User/MapRoom/Place/Report 타입, 방장·회원 등급·신고 상태 정책, LOCO 테이블 FK |
| LOCO 관리자 확장 | core 권한을 사용하는 회원·지도방·장소·리뷰·신고/피드백 관리, LOCO 메뉴/권한 등록, 관리 변경의 이력 payload 작성 | core 내부 Repository 직접 접근, 앱 회원을 관리자 principal로 재사용 |
| 조립 앱/인프라 | 설정 주입, SecurityFilterChain 조립, Flyway 순서, S3 호환 어댑터·이미지 변환 구현 연결 | core에서 LOCO 서비스를 찾아 호출하는 서비스 로케이터 |
| 이후 로드맵 | 대시보드 집계, 공지·알림 내역, 구독, 다중 서버 세션, Redis 캐시 | 이번 core MVP에 자동 포함하지 않음 |

의존 방향: `LOCO 관리자 확장 → core 공개 서비스/포트`; `조립 앱 → 둘 모두`; `저장/변환 어댑터 → core 포트`. core의 생산 코드·테스트·기본 시드·마이그레이션에는 LOCO import, 테이블명, route, 메뉴명을 넣지 않는다. core 단독 테스트는 가상 업무 `sample.documents`로 수행한다.

LOCO가 `loco.reports` 같은 불변 권한 키와 메뉴 정의를 등록한다. core는 이를 문자열 카탈로그로 저장할 뿐 의미를 해석하지 않는다. 메뉴의 routeKey도 미리 등록된 화면 키이며, 관리자가 임의 Java 클래스·SQL·외부 URL을 실행하도록 하지 않는다. 화면 컴포넌트는 LOCO UI가 routeKey로 연결한다.

**JPA만 사용한다는 것은 객체 저장까지 JPA로 한다는 뜻이 아니다.** DB 업무 접근에 적용하며 S3/SeaweedFS 통신은 저장 포트를 통한다. 테스트의 JDBC 스냅샷·실제 DB 경합 관측은 기존 검증 도구로 유지한다.

## 4. 도메인 모델 초안

모델은 C-2 A / C-3 B / C-4 A를 설명하기 위한 예시다. 기존 패턴대로 앱에서 생성한 UUID 문자열 ID와 TEXT 매핑, Clock 기반 JPA Auditing을 우선 재사용한다. 메뉴 PK를 부모·정렬·경로에서 만들지 않는다. 관계는 필요한 곳만 LAZY, 삭제 cascade는 기본 사용하지 않는다.

| 모델 | 주요 필드·제약 | 책임·경계 |
| --- | --- | --- |
| AdminAccount | id, loginId(normalized unique), passwordHash, displayName, status, mustChangePassword, authVersion, createdAt/updatedAt | 앱 User와 별개. 비활성화/비밀번호 변경 시 기존 로그인 효력 차단. 계정의 역할만으로 앱 회원 자격을 얻지 않음 |
| AdminRole | id, code(unique), name, systemProtected, version | 관리 가능한 역할. 보호된 최고관리자 역할의 권한을 임의 축소하지 않음 |
| AdminAccountRole | accountId + roleId(unique) | 다중 역할은 허용 권한의 합집합. 명시적 deny/역할 상속/개인 예외는 초기 제외 제안 |
| PermissionDefinition | key(unique), label, allowedActions | 코드가 제공하는 권한 카탈로그. LOCO 정의는 LOCO에서 등록, core 정의는 core에서 등록 |
| RolePermission | roleId + permissionKey + action(unique) | C-3 B에서 사용. 메뉴 PK가 아닌 불변 업무 권한 키를 참조 |
| MenuNode | id, parentId(nullable), sortOrder, title, type(GROUP/PAGE), routeKey, permissionKey, visible, version | parent FK, 자기 부모 금지. 메뉴명/URL 변경은 식별자와 별개. 그룹은 화면 진입 권한을 새로 부여하지 않음 |
| MenuTreeRevision | id, revision | C-9-2 A 선택 시 트리 변경 직렬화와 낡은 편집 화면 감지용. core의 한 트리에서 시작 |
| ChangeHistory | id, actorId(nullable), actorLabel, actorType, occurredAt, resourceType, resourceId, action, requestId, beforeData/afterData(JSONB), payloadVersion | 회원/지도방 FK 없음. 삭제 후에도 식별·표시 가능. actor는 서버 인증에서 얻고 요청 JSON을 신뢰하지 않음 |
| StoredFile | id, storageKey(unique), backend, originalName, detectedMediaType, byteSize, sha256, state, createdBy, createdAt | URL/파일명과 식별자 분리. 비공개 객체, 권한 통과 뒤 다운로드. 일반 파일은 바이트 불변 |
| LOCO 전용 모델(별도) | 신고 처리 상태/담당자/경합 버전, 운영 조치 사유 등 | 현재 테이블에 이미 있다고 가정하지 않음. 실제 조치 정책·마이그레이션은 C-9-5 이후 별도 설계 |

이력은 엔티티 전체를 JSON 직렬화하지 않는다. 허용 필드 DTO의 before/after만 넣어 LAZY 그래프·순환 참조·민감값 유출을 막는다. 검색은 `(occurredAt,id)`, `(resourceType,resourceId,occurredAt)` 등의 후보 인덱스를 실제 쿼리로 검증하고 JSONB 전체 GIN은 필요가 측정될 때 검토한다. DB 관리자까지 막는 불변 원장이나 위변조 방지를 보장하지 않는다.

## 5. 핵심 동작 초안

### 5.1 메뉴 이동·정렬과 권한

1. 요청은 대상 id, 새 parentId, beforeSiblingId(없으면 마지막), 기대 treeRevision을 보낸다. UI 배열 인덱스나 경로를 PK로 보내지 않는다.
2. 트리 변경 보호 구간 안에서 대상/부모 존재, 자기 자신·자손 아래 이동 금지, 형제의 새 부모 일치, revision을 확인한다. 교차 이동 A→B / B→A도 순환을 만들 수 있으므로 사전 SELECT 검사만으로 끝내지 않는다(C-9-2).
3. 대상 parentId와 필요한 양쪽 형제 sortOrder를 갱신한다. 자손 PK·연결 데이터·RolePermission은 건드리지 않는다. 형제 정렬은 연속 정수 재배치 제안, 큰 형제 집합일 때 비용 측정 후 간격/순위 방식 검토.
4. 트리 revision 갱신과 변경 이력을 같은 트랜잭션에 넣는다. 낡은 화면은 409와 새 트리 재조회 안내를 제공하며 사용자 입력을 조용히 덮어쓰지 않는다.

초기 전체 메뉴 수를 근거 없이 대규모로 가정하지 않는다. 전체 projection 1 SELECT 후 메모리 조립은 N개 노드 O(N)이며 부모마다 LAZY 순회하는 N+1을 피한다. 특정 서브트리가 필요하면 JPA native recursive CTE로 조회할 수 있다. CTE의 cycle 탐지는 읽기 안전장치이지 동시 쓰기 정합성을 대신하지 않는다. [PostgreSQL 17 WITH/재귀 조회](https://www.postgresql.org/docs/17/queries-with.html).

권장 C-3 B는 **권한 상속 없음**이다. 자식 PAGE 접근이 허용되면 상위 GROUP을 탐색 경로로만 표시한다. 메뉴 숨김은 API 거부를 뜻하지 않고, 메뉴 이동·그룹 이름 변경은 권한을 추가하지 않는다. 화면에는 “역할 X가 자료 Y의 관리 권한을 부여함”을 표시한다. 이동 미리보기는 이전/이후 위치와 “권한 변화 없음”을 보여준다.

자식이 있는 메뉴 삭제는 초기 거절, 연결된 데이터는 별도 업무 API로 관리한다. 메뉴 삭제로 업무 데이터/권한/이력이 삭제되지 않는다. PAGE를 지워도 권한 카탈로그 자체가 없어지지 않음을 UI에 알린다. 메뉴를 숨기는 동작을 계정 정지처럼 사용하지 않는다.

### 5.2 인증과 권한 변경

권장 C-4 A는 별도 관리자 계정 + 서버 세션이다. 세션에는 관리자 ID와 인증 버전 등 최소 정보만 넣고, 매 요청 계정 활성 상태·최신 역할/권한을 DB에서 확인한다. **세션을 쓰는 것만으로 권한 변경이 즉시 반영되는 것은 아니다.** 최초에는 캐시를 두지 않아 회수 커밋 후 시작한 다음 요청부터 반영한다. 이미 실행 중인 요청까지 취소하는 보장은 없으며 고위험 쓰기는 트랜잭션 안에서 권한을 다시 확인하는 계약을 정한다.

HttpOnly/Secure(운영 HTTPS)/SameSite cookie, 로그인 시 session ID 교체, 로그아웃 무효화, CSRF 보호를 관리자에 적용한다. 로그인도 CSRF 토큰 흐름에 포함한다. SPA의 CSRF 토큰 수신·로그인 후 토큰 재발급을 테스트한다. 앱 JWT 체인은 기존 stateless·CSRF off 그대로다. 관리자 세션 cookie로 앱 인증이 되거나 LOCO JWT로 관리자 권한이 생기지 않아야 한다.

초기 관리자는 공개 가입 API나 Git 시드 비밀번호 대신 일회성 CLI/bootstrap 작업을 제안한다. 외부 주입/비표시 입력 비밀값으로 생성, DB 잠금/고유 제약으로 동시 실행 중 하나만 성공, 기존 관리자 존재 시 거절, 강제 비밀번호 변경, 성공 후 bootstrap 비활성화. 재시작마다 계정을 다시 만들거나 비밀번호를 덮어쓰지 않는다. 마지막 활성 최고관리자 삭제/강등/비활성화는 경합까지 막는다.

### 5.3 변경 이력의 트랜잭션

권장 C-6 A는 서비스가 업무 변경과 이력을 **같은 DB 트랜잭션**에서 기록한다. 성공 커밋만 성공 이력을 남기고 이력 insert 실패면 업무 변경도 롤백한다. actor·requestId는 요청 경계에서 값 객체로 전달하며 core는 LOCO SecurityContext 타입에 의존하지 않는다. bulk/native UPDATE는 변경 행 수/변경 전 snapshot을 명시적으로 다룬다.

로그인 실패·권한 거부·롤백된 시도는 성공 변경 이력이 아니다. 별도 보안 로그에 최소 정보로 기록하고 비밀번호·세션 ID·JWT·객체 저장소 서명 URL을 남기지 않는다. 비동기로 나중에 이력만 저장하면 종료/큐 포화로 유실될 수 있으므로 노션의 “비동기 이력”은 후속 실험으로 남긴다. 분리 시 outbox/재처리/중복 키를 함께 설계한다.

### 5.4 파일과 기존 /uploads

core에는 `ObjectStorage`의 put/open/delete/list와 `ImageTransformer` 계약만 둔다. C-23 C로 업로드 순서를 확정했다: 권한·CSRF·입력 크기 검사 → UPLOADING 메타데이터 선커밋(성공 이력 없음) → 실제 형식 검사·이미지 변환·PUT(트랜잭션 밖) → 조건부 ACTIVE 전이+성공 이력 커밋. 객체 저장은 DB 트랜잭션과 원자적이지 않다. 실패 시 **이 요청이 만든 key만** 보상 삭제하며 FAILED/ABANDONED 재정리는20절을 따른다. 5-A에는 업무 연결을 추가하지 않는다.

삭제는 DELETE_PENDING+삭제 이력을 먼저 커밋하고 실제 객체 삭제를 재시도한다. 존재하지 않는 객체의 삭제는 성공이다. 대조는10분마다 수행하며 UPLOADING/FAILED와 메타데이터 없는 객체에10분 유예를 적용한다. DELETE_PENDING/ABANDONED는 매 회차 시도 수를 기록하고 성공 시 행을 지운다. ACTIVE는 업무 미연결 여부와 관계없이 보존한다. 업무 연결·참조 집계 정책은6단계에서 설계한다.

기존 `LocalImageStorage`에는 프로필/지도방/장소 크롭, /uploads URL, HEIC v2 호환 실패 같은 LOCO 정책이 섞여 있다. core가 이 클래스를 참조하면 분리 목표를 어긴다. 변환 포트 어댑터는 조립 쪽에 두고 기존 libvips 동작을 연결할 수 있지만 core에서 LOCO를 import하지 않는다. 공통 구현을 추출할 경우 별도 호환 리팩터링 커밋과 코덱/HTTP 회귀가 선행돼야 한다. 지금 추출하지 않는다.

3단계 LOCO 이전은 별도 작업: 기존 파일→객체 복사·체크섬/개수 확인 → `/uploads/...` URL을 보존하는 조회 어댑터 또는 승인된 URL 전환 → 읽기 검증 → 쓰기 주체 전환 → 롤백 기간 뒤 로컬 정리. 단순 endpoint 변경만으로 현재 상대 URL·공개 헤더·파일 정리 계약이 보존되지 않는다. CMS 추가 시 기존 공유 볼륨/파일을 삭제하거나 LOCO image_urls를 교체하지 않는다.

## 6. 화면·API 목록 초안

동일 프로세스 권장안의 경로 예시다. 화면 `/admin`, core API `/admin/api/core`, LOCO 관리 API `/admin/api/loco`로 구별하며 기존 앱 `/api/**`를 재사용하지 않는다. 경로가 분리돼도 서버 권한 검사는 필수다. 인증 실패401, 권한/CSRF403, 낡은 편집409, 입력400 등 CMS 계약은 새로 고정하며 LOCO의 v2 오류 문구에 종속시키지 않는다.

| 화면/흐름 | API 초안(method + core prefix 뒤 경로) | 핵심 검사 |
| --- | --- | --- |
| 로그인·로그아웃 | GET `/auth/csrf`, POST `/auth/login`, POST `/auth/logout`, GET `/auth/me` | 세션/CSRF, 실패 메시지로 계정 존재 노출 방지, 로그인 시도 제한 |
| 내 계정 | PUT `/auth/password` | 기존 비밀번호 검증, 인증 버전 변경, 다른 세션 효력 회수 |
| 관리자 계정 목록·등록·수정 | GET/POST `/accounts`, GET/PUT `/accounts/{id}`, PUT `/accounts/{id}/roles`, PUT `/accounts/{id}/status` | 최고관리자 보호, 역할 부여 권한, version. 물리 삭제보다 비활성화 우선 |
| 역할·권한 매트릭스 | GET/POST `/roles`, GET/PUT/DELETE `/roles/{id}`, GET `/permissions`, PUT `/roles/{id}/permissions` | 시스템 역할/사용 중 역할 삭제 제한, 미등록 키 거절, READ/MANAGE 관계 설명 |
| 내 탐색 메뉴 | GET `/navigation` | 현재 역할 기준 트리, 접근 가능한 자식의 조상 GROUP 포함 |
| 메뉴 편집·이동 미리보기 | GET/POST `/menus`, PUT/DELETE `/menus/{id}`, POST `/menus/{id}/move-preview`, PUT `/menus/{id}/position` | 미리보기는 잠금 예약이 아님. 저장 시 재검사, subtree ID 보존·순환·revision |
| 변경 이력 검색·상세 diff | GET `/history`, GET `/history/{id}` | actor/대상/기간 cursor, 별도 열람 권한, 민감 필드 제외. 임의 수정/삭제 API 없음 |
| 파일 업로드·다운로드·삭제 | POST `/files`, GET `/files/{id}`, GET `/files/{id}/content`, DELETE `/files/{id}` | 비공개 객체, cms.files READ/MANAGE(C-18 C), 일반 파일 attachment, 경로조작/파일명 정제 |

파일 GET은 “로그인한 관리자면 모두 다운로드”가 아니다. 5-A에서는 cms.files READ가 전체 조회·다운로드, MANAGE가 업로드·삭제도 허용한다. SYSTEM_ADMIN은 전체 허용하며 업로더 소유 검사는 하지 않는다(C-18 C). 6단계에서 연결된 업무 권한을 따르도록 확장할지 검토한다. 사용자가 보낸 resourceType만으로 권한을 인정하지 않는다.

| LOCO 전용 화면 후보 | LOCO 관리 API 초안 | 아직 필요한 업무 계약 |
| --- | --- | --- |
| 회원 조회·상태 관리 | GET `/users`, GET `/users/{id}`; 이후 PUT `/users/{id}/status` | 정지와 탈퇴 구분, 토큰 회수, 앱 응답 변화 |
| 지도방 조회·운영 조치 | GET `/maps`, GET `/maps/{id}`; 이후 POST `/maps/{id}/moderation` | 방장의 재공개 가능 여부, 상태 필드, 사유·복구 |
| 장소·리뷰 관리 | GET `/places`, GET `/places/{id}/reviews`; 이후 업무별 moderation | 관리 삭제의 이미지·집계·리뷰 처리와 기존 작성자 정책 분리 |
| 신고·피드백 | GET `/reports`, GET `/feedback`; 이후 PUT `/reports/{id}/resolution` | 신고 상태 전이, 두 관리자 동시 처리, 낙관적 버전 |
| 통계·공지·알림 내역 | 후속 로드맵 | 핵심 모듈로 넣지 않음. 집계 비용/알림 저장 정책 별도 |

목록은 검색·빈 상태·로딩·재시도·페이지 이동을 제공하고, 메뉴 이동은 드래그뿐 아니라 키보드/부모 선택·위아래 버튼으로도 가능하게 한다. 역할 저장 전 바뀌는 권한 요약, 409 발생 시 입력 보존과 재조회, 401 시 재로그인 안내, 403 시 부족한 권한 설명을 제공한다. 본인에게 없는 권한의 내부 상세를 불필요하게 노출하지 않는다.

## 7. 기존 LOCO 계약 보호와 테스트 전략

### 현재 코드에서 확인한 통합 지점

| 현재 코드 | CMS 추가 시 필요한 경계 | 회귀 기준 |
| --- | --- | --- |
| [SecurityConfig](src/main/java/com/loco/config/SecurityConfig.java): 모든 요청 permitAll, stateless, JWT 필터 | CMS 전용 matcher/우선순위·세션·CSRF, 기존 앱 체인은 유지. 적용 체인이 없는 경로가 생기지 않게 검사 | JWT/세션 상호 인증 불가, 기존401/404·CSRF off 유지 |
| [GlobalHttpFilter](src/main/java/com/loco/web/GlobalHttpFilter.java): 전역 parser→Helmet→CORS, OPTIONS 조기 종료 | CMS 경로는 별도 HTTP 정책으로 분기. 단순 SecurityFilterChain 추가만으로 이 서블릿 필터를 우회할 수 없음 | 앱/없는 경로/uploads의 기존 fixture 불변. CMS CSRF 헤더·cookie·UI CSP 별도 검사 |
| [ApiWebConfig](src/main/java/com/loco/config/ApiWebConfig.java): com.loco 컨트롤러의 ApiController 선언 강제, JwtPrincipal/회원 DB 조회 | core를 다른 패키지로 두는 것에만 의존하지 않고 CMS/LOCO 컨트롤러 종류를 명시적으로 구분. 별도 CMS 선언 누락 검사 | 앱의 기존 선언 검사 약화 금지, CMS 선언 누락은 기동 실패. 관리자 요청에서 앱 users 조회 없음 |
| [RateLimitInterceptor](src/main/java/com/loco/security/RateLimitInterceptor.java): ApiController 대상 | CMS 인증 시도 제한은 별도 정책·키 공간. 앱 핸들러 quota 재사용 안 함 | 기존429 필수 검사·카카오 외부 호출0회 유지 |
| [LocoApplication](src/main/java/com/loco/LocoApplication.java): com.loco 기본 스캔 | core의 config/entity/repository를 명시적으로 등록. 패키지 이동만으로 자동 발견된다고 가정하지 않음 | core 단독 기동, 조립 기동 각각 검증 |
| [FlywayBaselineIntegrationTest](src/test/java/com/loco/migration/FlywayBaselineIntegrationTest.java): target(1) | V1 checksum·생성 SQL 고정 | Prisma 비교 유지. CMS 전체 마이그레이션은 별도 검사 |
| [DatabaseSnapshot](src/test/java/com/loco/compat/DatabaseSnapshot.java): public 테이블 자동 수집 | CMS 별도 schema 제안. public에 넣는 안이면 업무 테이블 집합을 명시적으로 분리하고 누락 검사를 추가 | v2 비교 범위를 줄이거나 CMS와 함께 새로 생긴 업무 컬럼을 조용히 제외하지 않음 |

### 구현 이후 실행할 검증(이번에는 미실행)

| 그룹 | 사례·통과 조건 |
| --- | --- |
| 의존 경계 | core만 컴파일/기동/마이그레이션, com.loco 의존0, core 테스트에 LOCO fixture0. 의도적으로 금지 import를 넣은 규칙 테스트도 실패하는지 확인 |
| 메뉴 정합성 | 다단계 자식 있는 노드 이동 전후 ID·연결·권한 유지, 루트/빈 그룹/다른 부모/형제 재정렬, 자기/자손 이동 거절, 없는 부모/낡은 revision409, 양쪽 변경·이력 롤백 |
| 실제 DB 경합 | Testcontainers PostgreSQL17에서 A→B/B→A, 같은 형제 동시 정렬, 이동 대 삭제, 마지막 최고관리자 동시 강등. barrier 기반 실제 요청, 순환0·유실0·활성 최고관리자 최소1 |
| 권한 | 최고관리자/운영자/조회자/역할없음/비활성, 다중 역할 합집합, 숨긴 메뉴 API 직접 호출, 메뉴 이동 권한 불변, 회수 커밋 후 다음 요청403. 소유자 조건은 LOCO에서 별도 검사 |
| 인증·웹 | 세션고정 방지, 로그아웃/정지/비밀번호 변경 후 회수, 만료, bootstrap 동시 실행/재실행, CSRF 누락·불일치·로그인 후 교체, CORS·cookie·CSP. 앱JWT와 세션 경계 상호 검사 |
| 이력 | actor 위조 거절, before/after와 실제 DB 일치, 업무/이력 중 한쪽 실패 시 둘 다 롤백, bulk UPDATE 경로 포함, 민감값0, 삭제 후 열람, payloadVersion 호환 |
| 저장소 | SeaweedFS 컨테이너 대역, WebP signature/치수·일반 파일 SHA-256, MIME 위장/크기/경로·권한 없는 관리자 거절·권한 있는 관리자 간 공유, 저장 실패/DB 실패/보상 삭제 실패/재정리, 재시도 중복 삭제 안전성 |
| JPA·쿼리 | 트리·권한 목록 N+1 없음, 10/100/1,000 메뉴에서 SELECT 수/응답 크기 측정. 신규 SQL 수는 실제 구현 후 고정하며 목표 수를 실측으로 표기하지 않음 |
| DB 통합 | 빈 DB와 main V1 DB 각각 업그레이드, 기존 업무 행/스키마 유지, CMS migration 재실행 idempotency, history 위치/버전 충돌, 두 앱 동시 기동. 공유 schema C-7 A 선택 시 비교 범위 변경 자체도 검사 |
| 화면 | E2E 로그인→메뉴 이동→역할 부여/회수→이력 조회, 파일 업/다운로드, 키보드 이동·권한 없음·409 복구. 화면 숨김만으로 통과시키지 않음 |
| LOCO 전체 회귀 | 기존 test/comparisonTest/build, 1,434개 식별자 누락0·기존 알려진 차이11 동일. CMS 켠 상태의 앱 API와 CMS 권한 변경 전후에도 앱 응답·DB 상태 불변 |

기존 비교는 v2와 v3의 LOCO 계약 검사다. CMS 자체에 가짜 v2 기대값을 만들지 않고 신규 계약 테스트로 분리한다. C-7 B/C의 CMS 스키마는 별도 snapshot으로 검사해 public 비교 밖의 변경도 확인한다. CMS 테스트와 LOCO 테스트의 DB 초기화·세션·객체 namespace를 격리한다. 프로파일로 CMS를 꺼서 기존 회귀만 통과시키는 것으로 통합 검증을 대체하지 않는다.

## 8. 결정할 항목 — 선택지·장단점

아래 선택지는 설계 당시 비교 이력이며 최신 선택은 1.1절에 확정했다. **번호·항목 이름·선택지**가 없는 새 답은 어느 결정인지 확인한 뒤 반영한다. 신규 라이브러리 버전·운영 수치는 구현 승인을 받은 뒤 선택된 안 범위에서 검증하며, 설계를 바꾸는 선택은 다시 질문한다.

### C-1 모듈 분리 방식

| 선택 | 장점 | 단점 |
| --- | --- | --- |
| **A. Gradle 멀티 모듈(권장)** | core JAR에 LOCO가 compile dependency로 들어오지 않게 제한, 단독 테스트/재사용 경계가 분명 | settings·리소스·테스트 classpath·Docker 빌드 입력 정리 필요 |
| B. 단일 모듈 패키지 + ArchUnit | 기존 build/배포 경로 유지, 가장 작은 초기 변경 | 분리 보장은 테스트 실행에 의존, 재사용 시 나중에 추출 필요 |

A도 처음부터 모든 LOCO 기능을 작은 모듈로 쪼개지 않는다. 현재 `apps/server-v3` root를 실행 앱으로 유지하고 `modules/cms-core` 하나를 library subproject로 추가하는 초안이다. core 내부는 account/menu/permission/history/file 패키지, LOCO 전용은 기존 앱 안 `cmsloco` 등 별도 영역으로 둔다. Gradle 경계 외 공개 API/역방향 의존 검사는 A에서도 유용하나 ArchUnit 추가 버전은 구현 시 확정한다. [Gradle 멀티 프로젝트](https://docs.gradle.org/current/userguide/multi_project_builds.html), [ArchUnit 의존 규칙](https://www.archunit.org/userguide/html/000_Index.html).

### C-2 메뉴 트리 표현

| 선택 | 이동·정렬·조회 | 장점 / 단점 |
| --- | --- | --- |
| **A. 인접 리스트 parent_id(기존 확정·유지 권장)** | 위치 변경은 대상 parent 1행 + 필요한 형제 K행; 자손은 안 바꿈. 전체 트리 N행 또는 recursive CTE | 작은 쓰기·쉬운 JPA / 조상·자손 조회와 동시 순환 방지는 별도 필요 |
| B. 경로 열거(materialized path) | subtree S개의 경로를 갱신, 형제 sort는 별도; prefix 조회 | 하위 검색 편리 / 이동 시 후손 재기록·경로 인덱스/인코딩 고려 |
| C. 클로저 테이블 | 노드와 별도로 ancestor/descendant/depth 저장. 이동 시 조상×서브트리 관계 재작성 | 조상/자손 조회 쉬움 / 관계 행 증가, 이동·삭제 정합성 복잡 |

세 방식 모두 **ID는 독립 UUID**로 두면 문제2를 해결한다. parent_id 자체가 PK 불변을 자동 보장하는 것이 아니라 “이동 시 ID를 재생성하지 않는 서비스·제약·테스트”가 핵심이다. 순서 값도 ID에 넣지 않는다.

### C-3 권한 모델

| 선택 | 장점 | 단점·한계 |
| --- | --- | --- |
| A. 단순 고정 RBAC(최고관리자/운영자/조회자) | 설명·설정 가장 쉬움, 역할 화면 작음 | 신고만 처리하는 운영자 등 범위 분리가 어렵고 역할 추가 시 코드 변경 가능 |
| **B. 역할 + 업무 메뉴의 READ/MANAGE 두 동작(권장)** | 메뉴별 접근을 조정하되 CRUD 세분화 억제, 템플릿으로 시작 가능 | 역할 수×업무 수의 설정 부담은 남음. 작성자/소유 범위는 표현 못 함 |
| C. ABAC/정책식·개인 예외 포함 | 대상 소유·조직·상태 등 표현력 높음 | 판정 설명·디버깅·운영 복잡도가 커 문제1 반복 가능 |

B는 노션의 메뉴 단위 RBAC를 좁힌 안이다. UI 메뉴에는 permissionKey를 연결하되 실제 권한은 불변 업무 키에 부여한다. MANAGE는 READ를 포함, 역할 합집합, deny/트리 상속 없음, 역할 템플릿 제공을 함께 제안한다. core 계정/역할 관리와 이력 열람을 하나의 “LOCO 운영자” 권한으로 묶지 않는다. 업무 상태·작성자 조건은 LOCO 도메인의 별도 판정이며 CMS 권한을 앱 API의 기존 canAccess보다 우선시키는 변경은 이번에 하지 않는다.

### C-4 관리자 인증·계정·초기 생성

| 선택 | 장점 | 단점·한계 |
| --- | --- | --- |
| **A. 별도 AdminAccount + 서버 세션 + 일회성 CLI(권장, 기존 세션 방향)** | 앱 JWT와 자격 증명/권한 경계 분리, DB 재확인으로 회수 이해 쉬움, 공개 bootstrap 없음 | CSRF·cookie·세션 수명 관리 필요. 처음 메모리 세션은 재기동 시 로그아웃, 다중 서버는 Spring Session 등 후속 필요 |
| B. 별도 AdminAccount + 관리자 전용 토큰 + CLI | 외부 관리자 클라이언트 확장 쉬움 | issuer/audience/서명키 분리·refresh/회수 장치 필요, 즉시 회수에 DB 조회가 다시 필요. 기존 세션 결정 변경 |
| C. 앱 회원에 관리자 역할 추가 + 세션 | 계정 중복 적고 가입 흐름 재사용 | 앱 탈퇴/OAuth/비밀번호 정책과 관리 권한이 결합, core가 LOCO 회원에 의존하기 쉬워 이번 전제와 맞지 않음 |

A의 초기 수명은 idle 30분, 절대 8시간, 로그인 5회/분(IP와 정규화 계정 각각)으로 사용자 승인됐다. 제한은 성공 시 필요한 카운터만 정리하고 존재 여부를 노출하지 않는다. 3단계에서 프록시 신뢰·Redis 세션/요청 제한으로 이어진다. MFA/SSO는 초기 범위 밖이며 인터넷에 관리자 UI를 공개하기 전 접근 제한·MFA 정책을 별도 확인한다. [Spring Security 체인 분리](https://docs.spring.io/spring-security/reference/servlet/configuration/java.html).

### C-5 관리자 화면 기술 — A 변형 확정

| 선택 | 장점 | 단점 |
| --- | --- | --- |
| **A 변형(확정). Vue 3 + Vite + TypeScript SPA + vue-router** | Vue/TS 경험 재사용, SSR·Nuxt 없이 최소 프런트 구성 | 라우터·레이아웃·폼 구조는 앱에서 구성해야 함 |
| B. React + Vite + TypeScript | React 경험 활용, SPA 빌드 단순 | 라우터/상태/폼 조합을 추가 선택해야 함, Nuxt 경험 재사용은 적음 |
| C. Thymeleaf 서버 렌더링 | 배포 한 개, 세션/CSRF와 자연스럽게 연결, 별도 Node 서버 불필요 | 트리 드래그·권한 매트릭스 등 상호작용에 별도 JS가 늘어남, 프런트 재사용 어려움 |

**설계 초안의 Nuxt 권장안을 Vue3 + Vite + TypeScript SPA + vue-router로 변경했다. Nuxt와 SSR은 사용하지 않는다.** 사용자 경험을 재사용하되 백엔드 우선으로 프런트 구성 요소를 줄인다. 현 저장소 apps에는 mobile/server/server-v3만 있고 관리자 프런트는 없다. A/B는 `apps/admin` 후보, C는 서버 templates/static 후보다. A의 초기 실행은 SSR 없는 관리자 SPA를 제안하고, 개발 서버 프록시로 `/admin/api`를 Spring에 전달해 브라우저 동일 출처를 유지한다. 배포도 정적 앱과 API를 같은 origin 아래 제공하는 구성을 제안한다. 인증 로직을 Nuxt 서버에 중복 구현하지 않는다.

2026-11-07 확인한 [Nuxt 공식 로드맵](https://github.com/nuxt/nuxt/blob/main/docs/5.community/6.roadmap.md)은 Nuxt3 지원 종료를 2026-07-31로 명시한다. 이 근거를 검토한 사용자는 Nuxt 자체를 제외했다. Vue/Vite/Node의 지원 중인 안정 버전은 apps/admin을 구현할 때 검증·고정하며 이번 단계에서는 설치하지 않는다.

### C-6 변경 이력 수집

| 선택 | 장점 | 단점 |
| --- | --- | --- |
| **A. 서비스에서 명시 기록 + 같은 트랜잭션(권장)** | actor·업무 의미·허용 필드·전후 값 명확, JPA native/bulk 경로도 기록 가능 | 호출 누락 위험이 있어 서비스 계약 테스트와 변경 경로 목록 필요 |
| B. JPA 엔티티 리스너 | 일반 persist/update에서 반복 코드 감소 | bulk/native UPDATE를 포괄하지 못함, before 값/actor/여러 엔티티를 묶는 업무 의미 처리 어려움 |
| C. DB 트리거 | 앱 밖 SQL 변경도 행 단위 포착 | DB 세션 actor 전달/초기화, 민감값 제한·도메인 action·유지보수 복잡. JPA 단일 요구에서 DB 동작이 분산됨 |

JPA Auditing의 createdAt/updatedAt은 변경 이력의 before/after·행위자를 대신하지 않는다. A의 유실 없는 성공 이력과 트랜잭션 비용을 먼저 측정하고, 비동기 분리 요구가 생기면 outbox 비용과 함께 비교한다.

### C-7 DB 배치·Flyway 버전 관리

| 선택 | 장점 | 단점 |
| --- | --- | --- |
| A. 같은 DB/public + cms_ 접두사, 기존 history 하나 | 설정·트랜잭션 가장 단순 | public 자동 비교에 CMS가 섞임, 이름 충돌·비교 하네스 분리 필요 |
| **B. 같은 DB/별도 cms schema + 기존 public history 하나(권장)** | LOCO public 경계 유지, 같은 로컬 트랜잭션, migration 순서 한 곳 | 배포 앱이 버전 순서를 조정해야 하므로 core 마이그레이션의 독립 출시에는 제약 |
| C. 같은 DB/별도 cms schema + CMS 전용 Flyway/history | core migration 번호 독립, 다른 앱으로 재사용하기 쉬움 | 두 migration 실행·검증·실패 복구·시작 순서를 조립 앱이 관리, 기본 Boot 자동 설정만으로 끝나지 않음 |

B는 기존 V1과 public.flyway_schema_history를 그대로 두고 **다음 사용 가능한 버전**으로 cms 생성/테이블을 추가한다. core는 LOCO와 무관한 migration 리소스를 소유하고 버전 배정/위치 조립은 호스트 앱 책임이다. 이름 충돌 방지, 배포 후 파일 불변이 필요하다. C는 core 쪽 V1이 가능하지만 location·history를 분리해 LOCO V1과 섞이지 않게 한다. **어느 쪽도 기존 baselineOnMigrate를 켜서 CMS 생성 버전을 건너뛰지 않는다.**

schema 분리는 보안/장애 격리나 별도 DB와 같지 않다. DB 계정의 권한 부여는 별도이며 같은 DB 자원을 공유한다. 기존 public 엔티티에는 전역 default_schema를 cms로 바꾸지 않고 CMS 엔티티/SQL에 schema를 명시한다. core 테이블은 public 업무 테이블을 참조하지 않는다. [Flyway history 위치·버전 상태](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/flyway-schema-history-table).

### C-8 파일 저장소 추상화·LOCO 이전 범위

| 선택 | 장점 | 단점 |
| --- | --- | --- |
| **A. CMS만 객체 저장 포트 + 로컬 MinIO/운영 S3, LOCO /uploads 유지(권장)** | 새 CMS 요구 충족, 기존 앱 응답·파일 비교 유지, 3단계 이전과 분리 가능 | 한동안 객체/로컬 두 저장 방식 운영 |
| B. 지금 LOCO까지 공통 저장 포트로 옮기고 객체 저장 전환 | 단일 저장 방식, 다중 서버 준비 빠름 | 파일 이관·URL·헤더·보상 삭제·권한 재검증이 커짐, 이번 core 설계 범위 확대 |

A는 core가 S3 SDK 타입을 노출하지 않는 계약, 조립 인프라에서 S3 호환 구현과 libvips 어댑터 제공을 뜻한다. C-20 C 이후 로컬 구현은 SeaweedFS다. S3 문서상 계약과 SeaweedFS의 path-style/서명/오류/권한 동작을 어댑터 테스트로 검증한다. 실제 AWS를 호출하지 않으므로 실제 S3와 실행 비교했다고 보고하지 않는다. 비밀은 외부 설정, 예제는 키만 둔다.

### C-9 추가 결정 항목

각 하위 번호도 독립 선택이다. 아래 항목은 코드에서 확인한 통합 위험과 앞선 선택의 빈칸을 채운다.

| 번호·항목 이름 | A안 | B안 | 권장·근거 |
| --- | --- | --- | --- | --- |
| **C-9-1 실행·HTTP 경계** | 한 Spring 프로세스, `/admin/**` 별도 세션/HTTP 정책. 배포 하나·동일 DB 트랜잭션 쉬움 / 필터 적용 범위 회귀 필요 | 별도 CMS 서버. 앱 필터와 운영 분리 / 배포·설정·LOCO 관리 서비스 재사용 비용 증가 | A. Gradle 모듈 분리와 프로세스 분리는 다른 결정. 기존 앱 parser/Helmet/CORS를 전역으로 바꾸지 않고 CMS만 독립 정책 |
| **C-9-2 트리 동시 변경 보호** | 한 트리 메타 행을 JPA 비관적 락으로 잠그고 revision 검사. 이해·순환 방지 쉬움 / 메뉴 변경끼리 직렬 대기 | 트리 메타 @Version CAS로 전체 트랜잭션 충돌409. 잠금 대기 감소 / 모든 변경 경로가 공통 version을 갱신해야 하고 경합 시 실패 증가 | A. 관리자 메뉴 변경은 드문 작업. 각 노드의 @Version만으로 교차 이동을 막는다고 주장하지 않음 |
| **C-9-3 CMS 파일 초기 정책** | 비공개, 이미지 JPEG/PNG/WebP/AVIF→WebP만 저장(원본 미보관), 일반 PDF/TXT/CSV 원본 attachment, 각10MiB. 저장·공격면 작음 / 원본 재편집·다른 형식 미지원 | 이미지 원본+WebP, 더 넓은 문서 형식. 재가공·업무 호환 유리 / 저장량·보안 검사·삭제/이력 복잡 | A. HEIC는 현재 코덱 공통 제약으로 CMS에서 명시적415 제안(LOCO의 호환 실패는 그대로). 허용 MIME·내용 검증은 악성코드 검사 대체가 아니며 공용 배포 전 검사 요구를 재확인 |
| **C-9-4 이력 보존·수집 범위** | 변경 성공과 필요한 최소 actor/대상/허용 전후 값만, 자동 삭제 없이 시작. 조사 근거 보존 / 계속 증가하므로 운영 전 보존 기간 재결정 필요 | 같은 최소 수집 + 보존180일·정기 삭제. 용량 통제 / 180일은 현재 업무 근거 없는 제안이며 과거 조사 정보 소실 | A. 비밀번호·토큰·파일 본문·불필요한 IP/민감정보 제외는 두 안 공통. 법정 보존을 충족한다는 판단은 하지 않음 |
| **C-9-5 첫 LOCO 확장 범위** | core 완료 뒤 회원/지도방/장소/리뷰/신고·피드백 조회부터 연결. 권한·UI 경계 검증, 기존 앱 정책 유지 / 운영 조치 기능은 다음 묶음 | 신고 처리·회원 정지·강제 비공개 쓰기까지 포함. 운영 가치 빠름 / 현재 없는 상태·앱 API 반응·경합 계약을 먼저 결정해야 함 | A. B를 고르면 core와 분리된 LOCO 업무 설계/마이그레이션 승인 후 구현. 기존 비교 fixture를 맞추려고 새 운영 상태를 숨기지 않음 |

C-9-3의 초기 파일 크기·형식과 C-4의 세션/로그인 수명·횟수는 사용자 승인됐다. 변경할 때는 항목 이름과 변경값을 함께 기록한다. 대용량 presigned 업로드·비동기 변환은 기존 3단계 이후 백로그로 유지한다.

## 9. 승인된 구현 순서 — 현재 6단계 설계까지만

1. C-1/C-7 경계·core 단독 기동·migration 테스트. 현재 V1/기존 회귀 유지 확인.
2. 관리자 계정·초기 생성·세션/CSRF·앱 JWT와의 교차 인증 차단. 이력 기반도 함께 준비.
3. 역할/권한·메뉴 트리, 이동/정렬/순환·마지막 관리자 경합·권한 설명 화면.
4. 이력 조회와 실패 롤백, 비밀값 배제 검증. 성공 변경 누락 경로 점검.
5. **5-A 백엔드**: 파일 포트·SeaweedFS·S3 호환/변환 어댑터·파일 정책·이력·보상 정리. 로컬 SeaweedFS만으로 검증. **5-B 화면 통합**은 별도 승인 후 진행.
6. 선택된 LOCO 전용 관리 메뉴 연결, 기존 앱 전체 비교와 CMS 활성 상태 통합 회귀.

각 구현 단계는 별도 커밋·검증 수치·시간 기록을 남긴다. **5-A·5-B는 확인 완료했고, 현재 요청은 6단계 설계만이다.** 새 업무 결정은 C-번호와 항목 이름으로 답을 기다린다. 6단계 구현 순서·선택지는 [LOCO_ADMIN_DESIGN.md](LOCO_ADMIN_DESIGN.md)에 있으며 승인 전에 구현하지 않는다.

## 10. 최초 설계 커밋 dc524f7의 검증·한계(과거 기록)

- main merge commit의 두 부모와 이관 최종 커밋 포함을 확인한 뒤 main에서 분기했다.
- 노션 결정·현재 보안/HTTP/파일/마이그레이션/DB 비교 코드를 읽고 설계 근거와 추정을 구분했다.
- 변경 대상은 이 문서 한 개다. 생산 코드, SQL, build, Compose, Flutter, v2, 기존 fixture는 수정하지 않았다.
- 문서의 로컬 링크 11개 정상, C-1~C-8 및 C-9 하위 5개 선택 항목 존재를 확인했다. 커밋 전 staged diff 공백·변경 파일 범위도 확인한다. 문서만 변경하므로 JUnit/전체 회귀/build는 재실행하지 않는다.
- CMS 기능·성능·외부 저장소 연결은 아직 검증된 구현이 아니다. 메뉴 1,000개 테스트 등은 계획이며 실측 결과로 보고하지 않는다.

복사용 선택 양식(값은 비워 둠):

```text
C-1 모듈 분리 방식:
C-2 메뉴 트리 표현:
C-3 권한 모델:
C-4 관리자 인증·계정·초기 생성:
C-5 관리자 화면 기술:
C-6 변경 이력 수집:
C-7 DB 배치·Flyway 버전 관리:
C-8 파일 저장소 추상화·LOCO 이전 범위:
C-9-1 실행·HTTP 경계:
C-9-2 트리 동시 변경 보호:
C-9-3 CMS 파일 초기 정책:
C-9-4 이력 보존·수집 범위:
C-9-5 첫 LOCO 확장 범위:
```

## 11. 1단계 구현 범위와 검증 방법

- `settings.gradle`에 `:cms-core` 하나만 추가했다. 물리 경로는 `modules/cms-core`, 기존 root는 실행 앱이다. core의 생산 의존성은 기존 Boot BOM 아래 Spring Context이며 새 외부 라이브러리를 도입하지 않았다. core에는 LOCO 프로젝트 의존을 금지하는 Gradle 검사와 실제 컴파일/런타임 경계 테스트가 있다.
- `CmsCoreConfiguration`은 조립용 진입점이며 root `LocoApplication`이 명시적으로 import한다. core 자체는 실행 서버/별도 main이 아닌 library JAR다. 독립 기동은 LOCO 없는 테스트 호스트에서 Spring Boot·JPA·Flyway를 시작하는 뜻이며 별도 배포 서버를 추가하지 않았다.
- core가 소유한 `db/cms/V2__create_cms_schema.sql`은 `CREATE SCHEMA cms`만 실행한다. V2 번호는 C-7 B에 따라 호스트의 다음 가용 버전이다. 기존 `db/migration/V1__baseline.sql`과 public history를 유지하고 application.yml의 두 location을 같은 Flyway에서 실행한다. 관리되지 않은 기존 cms schema는 조용히 편입하지 않고 실패시켜 확인하게 한다.
- 후속 테이블은 다음 버전으로 추가한다. V2는 배포 후 고치지 않는다. V2 기반 업그레이드 테스트는 target(2), 기존 Prisma baseline 테스트는 target(1)을 유지한다. 실제 앱 health 테스트와 전체 회귀는 최신 마이그레이션을 실행한다.
- 기존 Dockerfile의 `COPY apps/server-v3/ ./`와 `.dockerignore`의 하위 경로 포함 규칙이 새 모듈 소스/리소스도 포함한다. Gradle bootJar가 core JAR를 런타임에 넣는지 산출물로 확인한다. Dockerfile을 불필요하게 바꾸지 않는다.
- `test`에는 core test가 포함되고 `check`에는 core check(포맷 포함)가 연결된다. `scripts/measure-tests.ps1`은 root test/comparisonTest와 core test XML을 각각 합산한다. 기존 manifest 식별자를 유지해 기존 케이스 누락 여부를 비교한다.
- 관리자 계정·메뉴·권한·이력·파일 업무 테이블/API, `/admin` 필터 체인, apps/admin·MinIO는 아직 없다. 승인된 관리자 HTTP 정책은 인증/화면 경로가 생기는 2단계에서 적용한다. 기존 전역 HTTP 필터의 앱 동작은 이번 단계에 바꾸지 않았다.

실행 위치: `apps/server-v3`, Java 21 JDK·Docker 필요. 기존 전체 비교에는 Node/npm도 필요하다.

```powershell
# core만: v2/Prisma/libvips 준비나 LOCO 앱 기동 없음
.\gradlew.bat :cms-core:test --rerun --console=plain
# 실제 LOCO 조립과 V1→V2 업그레이드 (core test도 실행)
.\gradlew.bat :test --tests '*CmsMigrationIntegrationTest' --tests '*HealthIntegrationTest' --rerun --console=plain
# 기존 전체 회귀 + core + 포맷 + build, 수치/manifest 저장
.\scripts\measure-tests.ps1 -Label cms-foundation-final
```

확인 결과는 core 독립 JPA 기동, V2 1회 적용·재실행0, 빈 DB의 V1/V2 순서, V1 사용자·public 컬럼·checksum 포함 history 보존, 미관리 cms 거절이다. core에는 V1/LOCO 클래스가 없어야 하며 root 앱에는 core가 포함되어야 한다. 최종 건수·시간은 아래 최종 검증에 기록했다.

1단계 집중 검증: 새 테스트 7건과 기존 health 6건을 합쳐 **13/13 성공·실패/skip0, 35.869초**. core3 + 호스트10이며 v2 컨테이너를 띄우지 않은 실행이다. 임시 Gradle init script로 core에 root 프로젝트 의존을 주입하자 `cms-core must not depend on a host project`로 빌드가 거절됐다(의도된 음성 검사이며 회귀 실패로 합산하지 않음).

개발 중 첫 독립 기동 검사에서 테스트용 `@TestConfiguration`만 지정해 Boot의 시작 설정 검색이 실패했다(3건 중 1건, 20.298초). LOCO 없는 테스트 호스트를 `@SpringBootConfiguration`으로 명시한 뒤 6/6 성공(27.742초), 실제 앱 조립 검사까지 더한 위 13/13 결과를 얻었다. 생산 코드의 별도 실행 앱이나 인증 기능을 추가해서 우회하지 않았다.

### 1단계 최종 검증 — 2026-11-07

`measure-tests.ps1 -Label cms-foundation-final` 결과: **전체1,441·성공1,440·실패0·skip1(기존 HEIC 공통 제약), 알려진 차이11, 1,037.621초(17분18초)**. v2 이미지 캐시 기준이며 Spotless/root build/core build 통과다.

| 작업 | 전체 | 성공 | 실패 | skip |
| --- | ---: | ---: | ---: | ---: |
| root test | 286 | 285 | 0 | 1 |
| cms-core:test | 3 | 3 | 0 | 0 |
| comparisonTest | 1,152 | 1,152 | 0 | 0 |
| 합계 | 1,441 | 1,440 | 0 | 1 |

기존 1,434개 식별자 누락0·추가7. 알려진 차이의 ID·분류 변경0(개선6·의도5·보류0), v2 빌드/최초 기동 각1·재시작17, 인증 대기0ms·시나리오 창 대기61,014ms다. 결과·manifest·quota·로그는 `build/performance/cms-foundation-final/`에 보존한다. 실행 JAR에 core library와 기존 V1이 포함되고 테스트 호스트는 포함되지 않는 것을 확인했다. core JAR에는 설정 클래스와 V2만 있고 LOCO 클래스/V1/테스트 클래스는 없다.

새 기능·실험 성능의 측정이 아니라 회귀 실행 시간이다. 기존 측정의 동시 이미지 구간 중복 한계도 유지한다. v2·Flutter·V1·기존 비교 fixture는 변경하지 않았다. 노션 결정 변경 표에 승인된 13개 항목을 기록했다. **여기서 1단계를 마치며, 관리자 인증·메뉴·프런트·MinIO 구현은 다음 승인까지 진행하지 않는다.**

## 12. 2단계 결정 변경 기록 (2026-11-07)

선택지 문자보다 사용자가 명시한 기술 이름을 기준으로 확정했다. 노션 결정 변경 표에도 같은 내용을 반영했다.

| 번호·항목 | 확정 | 근거·제약 |
| --- | --- | --- |
| C-10 관리자 비밀번호 해시 | Argon2id, DelegatingPasswordEncoder 기본 `{argon2id}` | 신규 관리자 계정은 BCrypt의 72바이트 제한을 피한다. LOCO 회원 BCrypt는 그대로 유지한다. 접두사는 향후 알고리즘 변경을 위한 것이며 다른 알고리즘 수용은 지금 추가하지 않는다. |
| C-10 의존성 추가 예외 | `org.bouncycastle:bcprov-jdk18on:1.86` | Spring Argon2PasswordEncoder 실행에 필요해 승인된 신규 의존성이다. Spring/웹/JPA/Security/Flyway는 기존 Boot BOM을 그대로 사용한다. |
| C-10 계산 파라미터 | 메모리 19MiB(19456KiB), 반복 2, 병렬도 1, salt16B, 출력32B | [OWASP 최소 권장값](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html). 해시 지연은 아래 실측으로 기록하며 하드웨어가 다른 운영 환경의 처리량으로 일반화하지 않는다. |
| C-11 SPA CSRF 전달 | JS 읽기 가능한 `XSRF-TOKEN` + `X-XSRF-TOKEN`, CookieCsrfTokenRepository | `/admin` 경로에만 적용한다. 로그인·로그아웃 시 새 토큰을 발행한다. 세션 cookie는 `CMSSESSION`, HttpOnly·SameSite=Strict·운영 Secure. LOCO CSRF는 기존대로 꺼 둔다. |
| 초기 관리자 비밀번호 입력 | `CMS_BOOTSTRAP_PASSWORD` 환경변수 또는 표준 입력, 대화형 콘솔은 비표시 입력 | CLI에서 `--password`를 포함한 미지원 인자는 Spring 시작 전에 거절하고 값을 출력하지 않는다. 비밀번호를 셸 명령 문자열에 직접 쓰지 않는다. |

참고: [Spring Security 6.5 비밀번호 저장](https://docs.spring.io/spring-security/reference/6.5/features/authentication/password-storage.html), [SPA CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html), [BouncyCastle 배포](https://www.bouncycastle.org/).

## 13. 2단계 구현·사용 방법

### 저장 경계와 파일 역할

- `V3__admin_accounts_and_history.sql`: `cms.admin_account`, 최소 `admin_role`/`admin_account_role`, 일회성 생성 상태, `change_history` JSONB를 추가한다. V1/V2는 변경하지 않고 public Flyway history를 계속 사용한다. role은 SYSTEM_ADMIN 초기 생성에 필요한 부분만이며 메뉴·업무 권한 관리 API는 3단계다.
- `AdminAccount`/저장소: 앱에서 생성하는 UUID 문자열 ID, 정규화 로그인 ID 고유 제약, 인증 버전과 강제 비밀번호 변경 상태를 저장한다. 암호 해시를 응답에 내보내지 않는다.
- `AdministrationState`: 한 행의 비관적 락으로 초기 생성과 마지막 활성 최고관리자 검사를 직렬화한다. 초기 생성 완료는 DB에 남으므로 재시작으로 초기화되지 않는다.
- `AdminService`: 계정 생성·상태 변경·비밀번호 변경과 성공 이력을 같은 트랜잭션에서 처리한다. 역할 부여·강등·전체 계정 수정은 다음 단계 범위다. 고위험 계정 변경은 트랜잭션 안에서 행 잠금 후 수행자의 현재 권한을 다시 확인한다.
- `HistoryWriter`/`ChangeHistory`: 반드시 기존 트랜잭션 안에서 호출한다. 허용된 loginId/displayName/active/mustChangePassword/역할 코드만 전후 JSONB로 기록한다. 비밀번호·해시·세션·CSRF·JWT는 기록하지 않는다. 비밀번호 변경은 값이 아닌 변경 이벤트만 남긴다. 이력 저장 실패 시 계정 변경도 롤백된다.
- `CoreAuditingConfiguration`: 기존 LOCO의 UTC Clock·밀리초 DateTimeProvider를 동일하게 core로 옮겨 core 단독 기동에도 사용한다. 기존 LOCO의 날짜 처리 의미를 바꾸지 않는다.
- `AdminWebConfiguration`/`AdminController`/`AdminSessionFilter`: 관리자 전용 SecurityFilterChain, 세션 인증 저장, 세션 ID 교체, CSRF cookie 교체, 만료·활성 상태·인증 버전 확인을 담당한다. LOCO JWT 필터는 관리자 체인에서 실행되지 않는다. `GlobalHttpFilter`는 정확한 `/admin` 및 하위 경로만 제외하며 `/administrator`는 제외하지 않는다.
- `LoginAttempts`: IP(getRemoteAddr)와 정규화 계정 각각 1분 창 5회. 성공도 한 번의 시도로 센다. 잘못된 비밀번호·없는 계정·비활성 계정은 같은 401 응답과 Argon2 검증 경로를 사용한다. 카운터는 메모리이며 만료 창은 정리한다. 재기동·다중 서버 간 공유/프록시 신뢰 설정은 후속 단계다.
- `AdminBootstrap`: 비웹 일회성 Java 실행 경로다. 마이그레이션을 하지 않으며 배포 앱이 이미 생성한 cms 테이블을 검증한 후 실행한다(14절). 비밀번호를 요청 DTO의 자동 toString이나 CLI 로그에 포함하지 않는다. 성공 시 최초 로그인 비밀번호 변경이 필수다.

세션은 idle 30분, 절대 8시간 경계에서 만료한다. 매 관리자 요청에서 활성 상태와 authVersion을 조회하므로 비활성화 또는 비밀번호 변경 커밋 다음 요청부터 기존 세션을 차단한다. 이미 진행 중인 요청을 취소하는 보장은 없다. 비밀번호 변경 후 현재 세션도 종료하고 다시 로그인한다. 서버 재시작 시 메모리 세션은 사라진다.

Cookie 방식의 CSRF 토큰 재발급은 새 cookie/header 쌍 사용을 뜻한다. 별도의 서버측 CSRF 토큰 저장소를 도입한 것은 아니다. SPA 계약은 JSON 응답에 CSRF 값을 넣지 않고 cookie를 읽어 header로 전송하며, 요청 attribute handler는 raw header 값을 검증한다. 로그인도 CSRF 검사를 통과해야 한다. cookie 경로가 `/admin`이므로 후속 SPA도 개발/운영 모두 `/admin/` 아래에 제공한다(Vite/router base 포함). 다른 경로의 문서에서는 이 cookie를 JavaScript로 읽을 수 없다. SPA 자체는 이번 단계에 만들지 않는다.

### 현재 제공 API

| 메서드·경로 (`/admin/api/core` 기준) | 동작 |
| --- | --- |
| GET `/auth/csrf` | 204, CSRF cookie와 익명 세션 준비 |
| POST `/auth/login` | `{loginId,password}`, 세션 ID·CSRF 교체 |
| GET `/auth/me` | 현재 관리자 신원, 초기 비밀번호 변경 필요 여부 |
| POST `/auth/logout` | 세션 무효화, 새 CSRF cookie, 204 |
| PUT `/auth/password` | `{currentPassword,newPassword}`, 변경 후 모든 기존 세션 효력 회수, 204 |
| GET/POST `/accounts` | 현재 최고관리자만 조회/등록. 등록 입력 `{loginId,displayName,password}`; 새 계정에는 아직 역할을 부여하지 않음 |
| PUT `/accounts/{id}/status` | `{active}`, 마지막 활성 최고관리자 비활성화 거절 |

초기 비밀번호 변경 전에는 me/password/logout/csrf/login 외 관리자 경로를 거절한다. 계정 생성·상태 변경 권한은 SYSTEM_ADMIN이며 READ/MANAGE 카탈로그·역할 편집은 다음 단계에 구현한다. 유효하지 않은 입력 400, 인증 실패 401, 권한/CSRF 실패 403, 중복/마지막 최고관리자 충돌 409, 제한 429를 사용한다. CMS 오류를 LOCO Nest 호환 오류 형식에 결합하지 않는다.

### 로컬 실행과 초기 생성

Java 21, PostgreSQL, `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` 환경변수를 준비한다. 일반 앱 실행에는 기존 JWT 설정도 필요하다. `.env.example`에는 키만 있고 실제 값은 넣지 않았다. CLI는 비웹 core 호스트만 시작하므로 LOCO JWT/Firebase 설정이 필요 없다.

```powershell
# apps/server-v3에서 실행. DB 환경변수는 별도 주입하고 배포 앱을 먼저 기동해 마이그레이션을 완료한다.
.\gradlew.bat bootJar
java -jar build/libs/loco-server-v3-0.0.1-SNAPSHOT.jar cms-bootstrap --login-id=admin --display-name=Administrator
# 콘솔에서 비표시로 비밀번호 입력. 명령줄 --password 옵션은 없다.
```

CI 등에서는 비밀 저장소가 `CMS_BOOTSTRAP_PASSWORD`를 해당 일회성 프로세스에만 주입하거나 stdin으로 전달한다. 일반 앱/Compose 서비스에 bootstrap 비밀번호를 상시 주입하지 않는다. root `.env`를 쓸 경우 datasource 값은 CLI 프로세스 환경에도 설정해야 한다. 최초 생성 후 bootstrap 재실행은 실패하며 기존 비밀번호를 덮어쓰지 않는다.

운영 기본값은 `CMS_COOKIE_SECURE=true`다. 로컬 HTTP 실행만 `CMS_COOKIE_SECURE=false`로 명시하고 개발 Compose에는 이 로컬 기본값을 넣었다. 운영에서는 HTTPS와 Secure=true를 사용한다. SPA는 동일 출처 `/admin/api`로 호출하고, 쿠키의 XSRF-TOKEN을 읽어 변경 요청의 X-XSRF-TOKEN에 담는다. 로그인·로그아웃 이후 cookie 값을 다시 읽어야 한다. 관리자는 LOCO 앱 API에 접근하려면 별도의 LOCO JWT가 필요하다.

### 검증

- core 테스트: Argon2 파라미터/72바이트 초과 검증, 해시 시간, 실제 PostgreSQL의 bootstrap 경합·한 번만 생성·이력 롤백, CLI 프로세스 입력/출력, CSRF/세션 고정/만료/비활성 계정/인증 버전/제한/최고관리자 보호.
- 실제 Tomcat: cookie의 HttpOnly/Strict/Secure 속성을 HTTP 응답으로 검사한다. MockMvc의 servlet cookie 표현 차이에 의존하지 않는다.
- LOCO 조립 테스트: 유효한 앱 JWT는 앱 보호 API에서 성공하지만 관리자 API에서는 401, 유효 관리자 세션을 강제로 앱 경로에 보내도 401. 관리자에 v2 parser/Helmet/CORS가 적용되지 않고 비슷한 이름의 경로는 기존 동작을 유지한다.
- core 독립 기동은 최신 V3까지 검증한다. 기존 V2 이관 테스트 target(2), Prisma V1 비교 target(1)은 목적에 맞게 고정해 둔다.

```powershell
.\gradlew.bat :cms-core:test --rerun --console=plain
.\gradlew.bat :test --tests '*HealthIntegrationTest' --rerun --console=plain
.\scripts\measure-tests.ps1 -Label cms-auth-final
```

Argon2id 해시 실측: Windows 11 / Java 21.0.5, 동일 프로세스 준비 실행 3회 후 단일 스레드 10회. 19MiB·t=2·p=1 기준 최소 22.217ms / 중앙값 24.180ms / 최대 25.173ms. 비밀번호·해시는 출력하지 않고 소요 시간만 테스트 XML에 남겼다. 운영 Linux의 처리량이나 동시 부하 결과를 뜻하지 않는다. 최종 테스트 건수·전체 실행 시간은 회귀 완료 후 기록한다. 이번 작업은 2단계에서 멈추며 메뉴·권한 모델·SPA·MinIO는 구현하지 않는다.

### 2단계 최종 결과 (2026-11-07)

`measure-tests.ps1 -Label cms-auth-final`: **전체 1,468건 / 성공 1,467 / 실패 0 / 기존 HEIC 공통 제약 skip 1**, **980.358초(16분 20초)**. v2 이미지 캐시 사용, v2 재시작 17회. root 빠른 테스트 290건(성공289·skip1), core26건 전부 성공, v2 비교1,152건 전부 성공. Spotless 검사와 root/core build도 성공했다.

기존 stage1 manifest 1,441개는 **누락 0, 신규 27개**다. 기존 알려진 차이 **11건(개선6·의도5·보류0)**의 case/kind/reason을 이전 결과와 대조해 모두 동일함을 확인했다. 전체 시간은 stage1 1,037.621초에서 이번 980.358초였으나, 최적화 작업이나 동일 부하 반복 측정이 아니므로 성능 개선으로 해석하지 않는다. 실행 결과·로그·manifest는 `build/performance/cms-auth-final/`에 있다.

검증 중 기존 Flyway-only V2 테스트가 최신 core의 JPA 설정을 함께 가져와 `jpaMappingContext` 없이 시작하려다 3건 실패했다. V2 검사는 production Flyway location/target(2) 검증에 한정하고, 최신 core 기동은 별도 통합 테스트에서 검증하도록 경계를 분리했다. V2→V3 public schema/history 보존 검사를 추가한 집중 실행 4/4 성공 후 위 전체 회귀를 다시 실행했다. V1/V2 SQL은 수정하지 않았다.

실행 JAR에 core와 BouncyCastle 1.86이 포함된 것을 확인했고, 실제 bootJar의 `cms-bootstrap --unsupported`는 DB/웹 시작 전 종료코드1로 거절했다. 비밀번호의 stdin/환경변수 입력과 미출력은 별도 Java 프로세스 통합 테스트로 검증했다. v2·Flutter·기존 비교 fixture는 변경하지 않았다.

**2단계 완료. 다음 사용자 확인 전에는 3단계(메뉴·역할/업무 권한)로 진행하지 않는다.**

## 14. 초기 관리자 CLI의 마이그레이션 경계 수정 (2026-11-07)

마이그레이션은 **배포된 앱 기동 시에만 적용한다.** core는 자체 migration 리소스를 제공하지만, 위치·버전 순서 조립과 적용은 호스트 앱 책임이다. 초기 관리자 CLI는 호스트의 `classpath:db/migration` 위치나 public history 설정을 알지 않는다.

`AdminBootstrap`은 다음을 명시한다.

- `spring.flyway.enabled=false`: CLI에서 Flyway 실행 금지.
- `spring.sql.init.mode=never`: 별도 schema.sql/data.sql 초기화도 실행 금지.
- `spring.jpa.hibernate.ddl-auto=validate`: JPA가 기대하는 cms 스키마·테이블·컬럼을 읽기 전용으로 검사. 없거나 불완전하면 생성/수정하지 않고 종료코드1과 “배포 앱을 먼저 기동해 필요한 CMS 마이그레이션을 적용하라”는 안내를 반환한다.

정상 CLI는 검증을 통과한 DB에 초기 관리자와 이력을 기록한다. DB 준비가 안 된 경우 CLI 실행으로 고치지 않는다. 비밀번호 stdin/환경변수 입력·비출력, 일회성 상태, 동시 생성 보호는 유지한다.

회귀 테스트는 테스트 컨테이너 안에 별도 DB를 만들고 실제 CLI 프로세스를 실행한다. (1) cms 없는 빈 DB, (2) cms와 일부 테이블만 있는 DB 각각의 스키마·관계·컬럼 목록을 실행 전후 비교해 불변을 검사한다. 수정 전에는 두 경우 모두 실패해 CLI의 DDL 부작용을 재현했다. 정상 스키마의 CLI 성공·재실행 거절·비밀번호 미출력 검사도 유지한다.

### CLI 경계 수정 검증 결과

`./gradlew.bat spotlessApply test build --console=plain`: **전체 1,470건 / 성공1,469 / 실패0 / 기존 HEIC skip1**, **1,071.149초(17분51초)**. root 빠른 테스트290(성공289·skip1), core28 전부 성공, v2 비교1,152 전부 성공. build의 check가 비교 테스트를 포함하므로 전체 비교까지 실행했다. 기존 v2 이미지 캐시가 있는 상태, 재시작17회다.

직전 1,468개 manifest 대비 누락0·신규2다. 알려진 차이11(개선6·의도5·보류0)의 case/kind/reason은 모두 동일하다. 변경은 CLI·회귀 테스트·이 문서뿐이며 V1/V2/V3 SQL, v2, Flutter, 기존 비교 fixture는 바꾸지 않았다. 로그·시간·건수·manifest는 `build/cms-cli-boundary*`에 보존했다. 수정 전 재현은 새 음성 검사2건 모두 실패했고, 수정 후 위 전체 실행에서 통과했다.

빠른 재확인은 `./gradlew.bat :cms-core:test --rerun --console=plain`으로 한다. 3단계 운영 코드와 교차 이동 실험은 16절에서 이어간다.

## 15. 3단계 구현 전 추가 결정

C-2/C-3/C-9-2의 트리·권한 계산·잠금 방식은 확정됐다. 아래 두 정책은 2026-11-07 사용자 답변으로 모두 A가 확정됐다. 미채택 B는 비교 이력으로 보존한다.

### C-12 보안 관리 권한의 위임 범위

2단계는 관리자 계정 쓰기를 SYSTEM_ADMIN으로 제한했다. 3단계에서 역할 생성·권한 편집·계정 역할 배정을 누구에게 허용할지 정해야 한다. 메뉴 편집 권한이나 업무 MANAGE가 보안 관리 권한을 자동으로 뜻하지는 않는다.

| 선택 | 범위 | 장점 | 단점 |
| --- | --- | --- | --- |
| **A. 최고관리자만 보안 관리 (확정)** | 계정 관리·역할 CRUD·권한 편집·역할 배정은 SYSTEM_ADMIN만. 일반 관리자는 자기 접근 근거를 조회 | 2단계 정책 유지, 역할 편집을 통한 자기 권한 상승 경로가 단순하게 차단됨 | 역할 설정·계정 배정 업무가 최고관리자에게 집중 |
| B. 별도 보안 관리 권한으로 위임 | 계정/역할 관리 키를 나누어 위임. 부여 가능한 권한은 수행자 보유 범위 안으로 제한하고 SYSTEM_ADMIN 부여·보호는 최고관리자만 | 운영 담당자가 제한된 범위의 권한 설정을 직접 처리 | 공유 역할 편집·자기 역할 변경까지 포함한 권한 상승 방지 검사와 설명이 더 복잡 |

두 안 모두 일반 업무 키는 READ/MANAGE·역할 합집합이고, MANAGE가 READ를 포함한다. 업무 권한과 메뉴 위치를 결합하지 않는다.

### C-13 역할 템플릿 관리 방식

템플릿은 역할 생성 시 권한을 복사하는 출발점으로 제안한다. 생성된 역할은 독립적이며, 이후 템플릿 수정이나 카탈로그에 새 업무 키가 추가되어도 기존 역할의 권한을 자동 변경하지 않는다. 런타임 역할/트리 상속은 도입하지 않는다.

| 선택 | 범위 | 장점 | 단점 |
| --- | --- | --- | --- |
| **A. 코드 등록 템플릿 (확정)** | 조회자(선택한 업무 키의 READ), 관리자(선택한 업무 키의 MANAGE) 등의 고정 템플릿에서 일반 역할 생성 | 추가 템플릿 편집 API·테이블 없이 이해하기 쉬움, 기존 역할에 권한이 조용히 전파되지 않음 | 템플릿 종류 변경에는 코드 배포 필요 |
| B. DB에서 편집하는 템플릿 | 권한 있는 관리자가 이름·업무 키·동작 조합을 저장하고 복사해 역할 생성 | 조직별 조합을 배포 없이 재사용 | 템플릿 CRUD·권한·버전·이력과 일반 역할의 차이를 관리해야 함 |

### 3단계 필수 검증

- `sample.documents`만 core 가상 업무로 등록해 READ/MANAGE, 합집합, 권한 회수, 접근 근거를 검사한다.
- 트리 메타 행 비관적 락을 잡은 뒤 revision 검사와 이동/정렬/이력을 한 트랜잭션에서 처리한다. 이동 전후 대상·모든 자손의 ID, 연결 데이터·권한·이력 참조, 설명 결과를 비교한다.
- LOCO의 DatabaseRequestRace에 의존하지 않고 core 테스트에서 같은 방식의 DB gate/pg_stat_activity 관측을 사용한다. 실제 두 HTTP 이동 요청이 메타 행에서 대기한 것을 확인한 뒤 해제한다. 낡은 revision은409, 순환0을 필수 검사한다.
- 락 없는 비교는 운영 우회 옵션을 추가하지 않고 테스트 전용 트랜잭션에서 두 사전 검사를 먼저 완료시킨 뒤 A→B/B→A 쓰기를 실행한다. 최종 순환 수·성공/거절 수를 기록하고, 메타 락+revision 실행 결과와 구분한다.
- API/스키마·이력은 다음 가용 V4에서 추가하며, CLI는 그때도 마이그레이션하지 않는다.

## 16. 3단계 확정·구현 기록

C-12 A: 보안 관리(계정·역할 CRUD·권한 편집·역할 배정)는 SYSTEM_ADMIN만 수행한다. 일반 관리자는 자신의 접근 근거만 조회한다. **보안 관리 업무가 최고관리자에게 집중**되는 한계가 있다. 관리자 수 증가 자체만으로 B로 자동 전환하지 않는다. 역할 배정/회수의 반복적인 승인 지연, 최고관리자 부재 시 대응 지연, 부서별 관리 담당자 필요가 운영 기록으로 확인되면 B를 별도 설계한다. 이때 위임할 업무 키 범위, 수행자 보유 권한 상한, 공유/자기 역할 편집에 의한 권한 상승 방지, 마지막 최고관리자 보호·감사·회수 테스트를 승인받은 뒤 전환한다.

C-13 A: 코드 등록 READ_ONLY/MANAGER 템플릿에서 선택한 업무 키의 권한을 **생성 시 한 번 복사**한다. 기존 역할은 템플릿·카탈로그 변경에 자동 동기화되지 않는다. 노션 결정 변경 기록에도 두 결정을 반영했다.

### 파일과 저장·인증 경계

- `V4__roles_permissions_and_menu_tree.sql`: 역할의 version과 `cms.role_permission`, `cms.menu_node`, `cms.menu_tree_revision`을 추가한다. 메뉴 ID는 TEXT UUID이고 parent FK와 별개다. 기존 V1~V3와 public 테이블은 변경하지 않는다. CLI는 계속 Flyway OFF·JPA validate만 실행한다.
- `PermissionCatalog`: 코드가 제공하는 업무 키/라벨/허용 동작과 화면 키를 등록한다. 호스트는 `Contribution` bean으로 확장하며 core는 업무 의미를 해석하지 않는다. 카탈로그는 코드가 원본이므로 별도 편집 DB 테이블/API를 만들지 않았다. 중복 키·등록되지 않은 화면/권한 조합은 거절한다.
- `Action`/`PermissionService`: MANAGE⊃READ, 역할 합집합으로 판정한다. 설명 응답은 accountId, permissionKey, 요청 동작, allowed, reason, 부여 역할 ID/code/동작 및 MANAGE로 READ가 포함되는지를 반환한다. 일반 관리자는 자기 자신만 설명을 볼 수 있다. SYSTEM_ADMIN도 등록된 키/동작만 사용하지만 그 범위에서는 보호된 최고관리자 자격으로 허용된다.
- `AdminRole`/`RoleService`: 고정 code·UUID와 수정 가능한 이름/권한, JPA @Version을 둔다. 권한은 업무 키마다 READ 또는 MANAGE 하나를 저장하고 READ 포함은 판정 시 계산한다. SYSTEM_ADMIN 역할은 편집/삭제 불가, 사용 중 역할 삭제 거절, 낡은 version409다. READ_ONLY/MANAGER 템플릿은 선택한 키만 복사한다. `cms.accounts`·`cms.roles`는 SYSTEM_ADMIN 전용이며 일반 역할에 그 권한을 넣는 요청도 거절한다.
- 계정 역할 배정은 기존 `AdministrationState` 행 잠금 안에서 수행자 자격과 마지막 활성 최고관리자를 다시 검사한다. expected authVersion이 다르면409다. 배정 저장 시 authVersion을 증가시켜 기존 세션은 다음 요청에서401이 된다. 역할 자체의 권한 편집은 세션을 종료하지 않고 다음 업무 요청부터 새 권한으로 판정한다(권한 회수403 검증). 비활성 계정도 다음 요청에서401이다.
- `MenuNode`/`MenuRepository`: parentId와 sortOrder만 위치로 사용한다. 부모는 GROUP만 허용하고 PAGE는 자식 없는 화면 항목이다. 화면 키/권한 키는 코드 카탈로그에 등록된 짝이어야 한다. 자기 자신·자손 아래 이동, 없는 부모, 다른 부모의 beforeSiblingId, 자식 있는 메뉴 삭제를 거절한다. 메뉴 삭제로 업무 데이터/권한/이력을 cascade 삭제하지 않는다.
- `MenuTreeRevision`/`MenuTreeRepository`/`MenuService`: 생성·수정·이동·삭제 모두 동일한 메타 행 SELECT FOR UPDATE 이후 권한과 revision을 검사한다. 형제 순서는 연속 정수로 재배치한다. 변경과 revision 증가·이력은 한 트랜잭션이다. 읽기는 REPEATABLE_READ 스냅샷에서 revision과 전체 메뉴 목록을 읽고 메모리에서 정리한다. 부모별 LAZY 순회는 하지 않는다.
- 이동 미리보기는 잠금을 예약하지 않는다. 저장 시 다시 검사하며, 미리보기에는 이전/새 부모·위치와 `permissionsChanged:false`, `권한 변화 없음`을 반환한다. 이동은 권한 테이블을 변경하지 않는다. GROUP 권한 상속은 없으며, 탐색에는 허용된 PAGE와 그 조상 GROUP만 포함한다. visible=false는 해당 탐색 가지를 숨기지만 업무 API의 권한을 회수하지 않는다.
- `GovernanceController`: 관리자 체인 안에 역할·메뉴·설명 API를 연결한다. `HistoryWriter.record`는 허용된 DTO snapshot을 같은 트랜잭션에 기록하며 계정·역할·메뉴의 resourceType/resourceId를 구분한다. 기존 계정 이력의 형식은 유지한다.
- core가 일반 java-library이므로 Spring MVC 인자 바인딩과 Spring Data의 이름 있는 쿼리를 위해 `-parameters`를 명시했다. 라이브러리는 추가하지 않았다. `CoreStandaloneIntegrationTest`는 최신 V4를 실행하고, 기존 V2/V3 검사는 각각 target을 유지한다. V3→V4 업그레이드 테스트는 기존 관리자 ID·역할 연결·public 컬럼·이전 Flyway 이력 보존을 확인한다.

### 3단계 API

아래 경로는 `/admin/api/core` 기준이다. 모든 변경 요청은 관리자 세션과 CSRF가 필요하다.

| API | 권한·입력 |
| --- | --- |
| GET/POST `/roles`, GET/PUT/DELETE `/roles/{id}` | SYSTEM_ADMIN. 생성 `{code,name,template?,permissionKeys?}`, 이름 수정 `{name,version}`, 삭제 `?version=` |
| PUT `/roles/{id}/permissions` | SYSTEM_ADMIN. `{permissions:{업무키:READ또는MANAGE},version}` 전체 교체 |
| PUT `/accounts/{id}/roles` | SYSTEM_ADMIN. `{roleIds:[...],authVersion}` 전체 교체 |
| GET `/role-templates`, GET `/permissions` | SYSTEM_ADMIN. 코드 템플릿/카탈로그 조회 |
| GET `/permissions/explain?permissionKey=...&action=READ` | 본인 설명. `accountId`로 다른 계정을 지정하는 것은 SYSTEM_ADMIN만 |
| GET `/menus` | cms.menus READ. `{revision,nodes:[...]}` 평탄 목록 |
| POST `/menus` | cms.menus MANAGE. `{title,type,parentId?,beforeSiblingId?,routeKey?,permissionKey?,visible,revision}` |
| PUT `/menus/{id}` | cms.menus MANAGE. `{title,routeKey?,permissionKey?,visible,revision}`. GROUP/PAGE 유형은 생성 후 불변 |
| POST `/menus/{id}/move-preview`, PUT `/menus/{id}/position` | cms.menus MANAGE. `{parentId?,beforeSiblingId?,revision}`. 부모 누락/null은 루트, beforeSiblingId 누락/null은 마지막 |
| DELETE `/menus/{id}?revision=...` | cms.menus MANAGE. 자식/업무 FK 참조가 있으면 삭제 거절 |
| GET `/navigation` | 로그인한 관리자의 현재 접근 권한에 따른 탐색. 메뉴 편집 권한은 필요 없음 |

역할 및 트리 version/revision은 낡은 편집 내용으로 덮어쓰는 것을 막는다. 409 시 최신 값을 다시 읽고 변경을 검토한다. 메뉴 편집 권한은 보안 관리 권한이나 해당 업무 데이터 접근 권한을 부여하지 않는다. 프런트 화면과 LOCO 전용 업무 등록은 이번 단계에 포함하지 않는다.

### 실제 DB 경합 관측

`GovernanceIntegrationTest`와 core 테스트 전용 `DatabaseRequestRace`를 사용했다. 운영 코드에 락을 끄는 옵션은 없다. 테스트 클래스패스에는 LOCO가 없으며, 업무는 `sample.documents`와 테스트 전용 `sample.document` FK로만 구성했다.

| 비교 | 재현 방법 | 5회 관측 결과 |
| --- | --- | --- |
| 락 없는 사전 검사→UPDATE | 각 트랜잭션이 A→B/B→A의 순환 검사를 마친 뒤 barrier로 함께 쓰기. 시험용 JDBC 트랜잭션만 사용 | 10건 커밋, **순환 발생 5/5회**, 매회 2개 노드 참여(합계10) |
| 운영 비관적 락+revision | DB gate가 메타 행을 잡은 동안 실제 HTTP 요청2건의 pg_stat_activity Lock 대기를 확인한 뒤 해제 | **200 5건 / 409 5건, 순환0/5회**, revision은 회당1 증가 |

락 없는 경우의 10건 커밋은 정합성 성공이 아니다. 두 사전 검사가 모두 통과할 수 있는 일정으로 경쟁을 강제한 재현이며, 운영에서 발생할 확률이나 처리량 측정으로 해석하지 않는다. 보호된 경로의 후발 요청은 갱신된 revision 때문에409로 거절된다. 최신 revision으로 재시도해도 자손 아래 이동은 별도 순환 검사로 거절한다.

같은 형제 동시 정렬, 이동 대 삭제도 실제 두 요청을 gate에서 만나게 해 각200/409와 정렬·순환 불변식을 확인했다. 마지막 활성 최고관리자 동시 강등은 administration_state gate에서 재현해200/409 및 활성 최고관리자1명을 확인했다.

### 15절 검증 대응

| 요구 | 실제 검사 |
| --- | --- |
| core가 업무를 모름 | core 독립 기동/컴파일 경계 유지. 가상 sample.documents의 READ/MANAGE·읽기 전용 sample.archive만 테스트 등록 |
| 하위 메뉴 이동 시 ID·연결 유지 | 대상/모든 자손 ID, 자손 전체 DTO(권한 키 포함), sample.document의 기존 ID/내용/menu_id, role_permission 전후 동일, 기존 history ID/resourceId/JSON 불변 |
| 권한 변화 없음·접근 근거 | 미리보기 문구/false 및 이동 전후 Explanation 완전 동일. 역할 합집합·MANAGE의 READ 포함·없는 역할·비활성·일반 관리자의 타인 설명 거절 |
| 회수·메뉴 숨김 | 역할 권한 회수 후 같은 세션의 업무 쓰기403/읽기200. 숨긴 메뉴의 업무 API 접근은 기존 권한에 따라200. 그룹만으로 권한을 얻지 않음 |
| 트리/역할 이력 원자성 | 이력 CHECK 실패 주입 후 메뉴·형제 순서·revision·이력 및 역할 권한/version 전체 롤백 |
| 동시 이동·정렬·삭제 | 위 경합 표 및200/409, 순환0·정렬 연속·낡은 revision 거절 |
| 스키마/기존 앱 보존 | V3→V4 이관 보존, core 최신 기동, CLI 스키마 없는 DB 불변 회귀 유지, LOCO 전체 회귀·알려진 차이 비교 |

재실행:

```powershell
# apps/server-v3, Java21/Docker
.\gradlew.bat :cms-core:test --rerun --console=plain
# 경합 및 sample.documents 권한·메뉴 검사만
.\gradlew.bat :cms-core:test --tests '*GovernanceIntegrationTest' --rerun --console=plain
# 기존 LOCO 전체 비교 + core + build + 시간/manifest
.\scripts\measure-tests.ps1 -Label cms-governance-final
```

집중 실행에서 core44건과 호스트 health·migration15건을 합쳐59건 모두 성공했다(Gradle 1분33초). 최종 전체 회귀 수치와 시간은 아래에 기록한다. 이번 작업은 3단계에서 중단한다.

### 최종 전체 회귀·build 결과 (2026-11-07)

`measure-tests.ps1 -Label cms-governance-final`을 v2 이미지 캐시가 있는 상태에서 실행했다. 전체 벽시계 시간은 **1,103.009초(18분23초)**이며, Gradle build·Spotless 검사를 포함한다.

| 작업 | 전체 | 성공 | 실패 | 제외 |
| --- | ---: | ---: | ---: | ---: |
| test (v3) | 291 | 290 | 0 | 1 |
| cms-core:test | 44 | 44 | 0 | 0 |
| comparisonTest (기존 LOCO 전체) | 1,152 | 1,152 | 0 | 0 |
| 합계 | **1,487** | **1,486** | **0** | **1** |

제외1건은 기존 HEIC 공통 제약이다. 알려진 차이 **11건(개선6·의도5·보류0)**의 case/kind/reason은 이전 보고서와 모두 동일하다. v2 재시작은17회였다. 기존 CLI 수정 회귀의1,470건과 manifest를 대조했으며, Governance16건과 V3→V4 업그레이드1건이 추가됐다. 기존 파라미터 테스트2건은 `-parameters` 적용으로 표시명만 `[1] false`/`[2] true`에서 `[1] partial=false`/`[2] partial=true`로 바뀌었다. 테스트 삭제는 없다.

측정 산출물은 로컬 `build/performance/cms-governance-final/`의 `result.json`, `test-manifest.txt`, `comparisonTest-summary.json`, `gradle.log`에 남긴다. 운영 core JAR에는 테스트 전용 경합 도구·가상 업무 컨트롤러가 포함되지 않는다. core 운영 코드의 LOCO 의존과 호스트 migration 위치 참조는 없으며, v2·Flutter·V1~V3는 수정하지 않았다. **CMS 3단계 완료. 4단계는 사용자 확인 후 진행한다.**

## 17. CMS 4단계 — 이력 조회·수집 검증

### C-14 변경 이력 조회 권한 (A 확정)

목록과 상세 모두 SYSTEM_ADMIN만 허용한다. 일반 관리자의 자기 접근 근거 조회는 기존 C-12대로 유지하지만, 자기 변경 이력 열람 권한으로 확대하지 않는다. B(별도 cms.history READ로 감사 담당자에게 위임)는 초기 core 이력이 관리자 계정·역할·권한 변경 정보를 포함하고, 현재 확정 범위가 최고관리자 전용 보안 관리이므로 채택하지 않았다. C-12와 같은 경계를 유지해 권한을 이해하기 쉽게 한다.

한계는 감사·조사 업무도 최고관리자에게 집중되고 독립 감사 담당자를 지정할 수 없다는 점이다. **6단계에서 LOCO 업무 이력이 생기면 “core 보안 이력은 SYSTEM_ADMIN, 업무 이력은 해당 업무 권한 READ 보유자”로 열람 범위를 나눌지 다시 결정한다.** 이번 구현에서 업무 권한으로 이력 열람을 자동 허용하지 않는다.

### 테스트 실행 범위

CMS core만 변경하면 `:cms-core:test :test`(core 전체 + root 빠른 테스트)를 실행한다. LOCO 앱 src/main·공통 설정·빌드 구성 변경 단계와 머지 전에는 `allTests`를 실행한다. 실제 실행 범위·성공/실패/제외·시간을 커밋 본문에 적는다. `build`는 현재 check에서 비교 테스트까지 실행하므로 core 전용 단계에서 관성적으로 실행하지 않는다. 이번 변경은 core 내부와 문서에 한정한다.

### 구현 파일과 API 계약

- `HistoryQueryService`: SYSTEM_ADMIN을 서비스에서 다시 확인한 뒤 JPA EntityManager로 조회한다. 조건에 실제로 지정된 필터만 SQL에 추가하며 값은 모두 바인딩한다. 엔티티 전체나 관리자 비밀번호 해시는 응답으로 직렬화하지 않는다.
- `HistoryController`: 기존 관리자 SecurityFilterChain 안에 목록·상세 GET을 연결한다. 관리자 세션 필요, 일반 관리자403, 없는 상세404다. 권한 검사가 상세 존재 확인보다 먼저 실행된다.
- `ChangeHistory.View`: ID·actorId·action·resourceType/resourceId·occurredAt·before/after·payloadVersion만 반환한다. DB의 timestamp는 기존 UTC Clock 기준이며 HTTP 시간은 UTC Instant다.
- `HistoryPayload`: account/role/menu별 허용 필드와 값 타입을 검사한 복사본만 HistoryWriter에 전달한다. 메뉴 snapshot도 엔티티/객체 직렬화 대신 명시적인 필드 map으로 바꿨다. 알 수 없는 resource·필드·중첩 객체·byte[]는 저장 전에 거절한다. 새 업무 이력은 해당 payload 계약과 테스트를 등록해야 한다.
- `HistoryIntegrationTest`: 실제 PostgreSQL·HTTP, 13개 쓰기 성공/롤백, 서비스 메서드 목록, 비밀값 배제, 1만 건 SQL/EXPLAIN을 검증한다. 운영 코드에는 테스트 대역이나 경합 우회 설정을 추가하지 않았다.

| API | 입력·응답 |
| --- | --- |
| GET `/admin/api/core/history` | 선택 필터 resourceType, resourceId, actorId, from, until; cursor, size(기본50, 1~100). `{items:[...],nextCursor:string|null}` |
| GET `/admin/api/core/history/{id}` | 동일 필드의 이력 상세1건. 대상/행위자 삭제 후에도 당시 ID와 snapshot으로 조회 가능 |

기간은 ISO-8601 offset/UTC 입력을 UTC로 변환하며 **from 포함·until 제외**다. from≥until, 빈 문자열 필터, 잘못된 시간·cursor·페이지 크기는400이다. 정렬은 `occurred_at DESC,id DESC`; 같은 시각에도 ID를 보조 기준으로 써서 중복/누락 없이 다음 페이지로 이동한다. cursor는 시간/UUID의 Base64URL이고 권한 정보가 아니다. 다음 페이지에도 동일한 필터를 전달한다. size+1건만 읽어 다음 cursor를 계산하고 전체 COUNT 쿼리는 실행하지 않는다. HTTP GET에 CSRF 변경 요청 검사를 추가하지 않으며, 기존 모든 쓰기 요청의 CSRF 검사는 유지한다.

### 모든 업무 쓰기 경로와 이력 계약

| 서비스 메서드 | resource / action | 검증 |
| --- | --- | --- |
| AdminService.bootstrap | admin-account / CREATE (actor null) | 일회성 상태·계정·역할 연결·이력 함께 커밋/롤백 |
| AdminService.createAccount | admin-account / CREATE | 계정 생성, 초기 비밀번호/해시 미기록 |
| AdminService.changePassword | admin-account / PASSWORD_CHANGE | 비밀번호·authVersion·초기 변경 상태 롤백, 전후 비밀번호 값 미기록 |
| AdminService.setActive | admin-account / STATUS_CHANGE | 활성 상태·authVersion·이력 롤백 |
| RoleService.create | role / CREATE | 템플릿 권한 포함 역할 생성과 이력 |
| RoleService.rename | role / RENAME | 이름·version·이력 롤백 |
| RoleService.permissions | role / PERMISSIONS_CHANGE | 권한 컬렉션·version·이력 롤백 |
| RoleService.delete | role / DELETE | 삭제 후에도 before 이력 조회, 실패 시 역할/권한 복원 |
| RoleService.assign | admin-account / ROLES_CHANGE | 역할 연결·authVersion·이력 롤백 |
| MenuService.create | menu / CREATE | 메뉴·형제 순서·트리 revision·이력 롤백 |
| MenuService.update | menu / UPDATE | 제목/표시 등 필드·revision·이력 롤백 |
| MenuService.move | menu / MOVE | 부모/형제 순서·불변 ID·revision·이력 롤백 |
| MenuService.delete | menu / DELETE | 삭제·형제 순서·revision·이력 롤백 |

각 경로를 실제 서비스 프록시로 실행해 **추가 이력 정확히1건**, actor/action/resource·payloadVersion, 허용 JSON 필드·전후 변경을 검사한다. 같은13개 경로에 이력 테이블 CHECK 실패를 주입하고 CMS 업무 테이블 전체 snapshot이 이전과 완전히 같은지 비교한다. 단순히 HistoryWriter 메서드 호출 횟수만 검사하지 않는다.

메서드 목록 검사는 core 클래스패스의 *Service, @Service, 클래스/메서드 @Transactional 타입에서 public 메서드를 발견한다. 읽기 메서드는 명시적으로 분류하고, 업무 쓰기는 실행 가능한 Mutation 시나리오와 반드시 대응한다. 새 메서드/새 서비스/오버로드를 분류 없이 추가하면 실패한다. 쓰기 메서드의 트랜잭션 제거도 실패한다. HistoryWriter.account/record는 동일 트랜잭션 안의 수집 기반으로 별도 분류하고13개 경로 및 금지 payload 검사에서 실제 실행한다. 엔티티 setter나 Repository는 업무 진입점 목록이 아니며, 업무 쓰기를 서비스 밖으로 옮겨 이 검사를 우회하는 구조는 허용하지 않는다.

### 비밀값 배제

엔티티 전체나 요청 객체를 JSONB에 넣지 않는다. 계정 이력은 loginId/displayName/active/mustChangePassword/역할 코드, 역할 이력은 code/name/권한, 메뉴 이력은 명시적 노드 필드/순서/revision만 허용한다. mustChangePassword는 비밀번호 값이 아닌 상태다. 비밀번호 변경도 전후 비밀번호·해시 대신 이벤트와 상태만 남긴다.

모든13개 성공 경로에서 before/after의 정확한 최상위 키와 금지 키를 검사하고, 실제 입력 비밀번호·DB 해시가 없는지 대조한다. 추가10건은 password/hash/session/CSRF/파일 본문 및 중첩 map/list/byte[]가 수집 경계를 통과하지 못하고 함께 실행한 업무 UPDATE도 롤백되는지 확인한다. 실제 HTTP 계정 생성에서는 세션/CSRF 토큰, 비밀번호, 위조 actorId와 추가 비밀 필드가 이력에 들어가지 않으며 actor가 서버의 인증 principal에서 정해짐을 검사한다. 파일 저장 기능은 아직 없으므로 파일 본문은 수집 경계의 거절 입력으로 검증하며 파일 업로드 이력은 후속 단계의 쓰기 경로 목록에 추가해야 한다.

### 1만 건 조회 측정 방법

PostgreSQL17, 이력10,000건, 메뉴/역할 각각5,000건, 대상10개(각1,000건), 행위자100명(각100건), 같은 시각2건씩 분포시킨다. 테스트 fixture는 계정/권한과 관계없는 합성 ID와 공개 문자열만 사용한다. ANALYZE 후 실제 Hibernate StatementInspector가 포착한 SQL을 같은 바인딩으로 `EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)` 실행한다. planner 설정/seqscan을 강제하지 않는다. DB가 캐시에 올라온 로컬 소규모 관측이며 운영 SLA·부하 성능 수치가 아니다.

현재 인덱스는 V3의 `change_history_time(occurred_at,id)`, `change_history_resource(resource_type,resource_id,occurred_at)` 그대로다. 행위자 전용 인덱스와 대상 인덱스의 id 추가는 실제 이력 규모·조회 비율 증가 시 비교할 후속 후보로 남긴다. 이번 단계에는 새 마이그레이션이나 인덱스를 추가하지 않는다.

### 1만 건 실제 SQL·EXPLAIN 결과

최종 core 실행의 관측이다. 최신순은 시간 인덱스 역방향 스캔(0.029ms), 대상은 대상 인덱스 역방향 스캔+Incremental Sort(0.053ms), 행위자는 Seq Scan+Sort(0.498ms, 9,900행 필터 제외), 기간은 시간 인덱스 역방향 범위 스캔(0.028ms)이었다. 아래 시간은 단일 EXPLAIN 실행의 DB Execution Time이며 HTTP 응답 시간/반복 측정 평균이 아니다. 최신/대상/기간은 인덱스를 사용했고, 행위자는 사용하지 않았다.

```text
PostgreSQL 17.11 (Debian 17.11-1.pgdg13+2); rows=10000; ANALYZE; default planner; size=50 (fetch 51)

recent
SELECT h.* FROM cms.change_history h ORDER BY h.occurred_at DESC, h.id DESC fetch first ? rows only
bindings=[51]
Limit  (cost=0.29..5.19 rows=51 width=127) (actual time=0.012..0.020 rows=51 loops=1)
  Buffers: shared hit=4
  ->  Index Scan Backward using change_history_time on change_history h  (cost=0.29..962.03 rows=10000 width=127) (actual time=0.011..0.016 rows=51 loops=1)
        Buffers: shared hit=4
Planning Time: 0.054 ms
Execution Time: 0.029 ms

target
SELECT h.* FROM cms.change_history h WHERE h.resource_type = ? AND h.resource_id = ? ORDER BY h.occurred_at DESC, h.id DESC fetch first ? rows only
bindings=[menu, resource-0, 51]
Limit  (cost=1.80..78.62 rows=51 width=127) (actual time=0.035..0.045 rows=51 loops=1)
  Buffers: shared hit=13
  ->  Incremental Sort  (cost=1.80..754.95 rows=500 width=127) (actual time=0.034..0.042 rows=51 loops=1)
        Sort Key: occurred_at DESC, id DESC
        Presorted Key: occurred_at
        Full-sort Groups: 2  Sort Method: quicksort  Average Memory: 33kB  Peak Memory: 33kB
        Buffers: shared hit=13
        ->  Index Scan Backward using change_history_resource on change_history h  (cost=0.29..732.87 rows=500 width=127) (actual time=0.020..0.031 rows=52 loops=1)
              Index Cond: ((resource_type = 'menu'::text) AND (resource_id = 'resource-0'::text))
              Buffers: shared hit=13
Planning Time: 0.051 ms
Execution Time: 0.053 ms

actor
SELECT h.* FROM cms.change_history h WHERE h.actor_id = ? ORDER BY h.occurred_at DESC, h.id DESC fetch first ? rows only
bindings=[actor-0, 51]
Limit  (cost=333.32..333.45 rows=51 width=127) (actual time=0.486..0.490 rows=51 loops=1)
  Buffers: shared hit=205
  ->  Sort  (cost=333.32..333.57 rows=100 width=127) (actual time=0.485..0.487 rows=51 loops=1)
        Sort Key: occurred_at DESC, id DESC
        Sort Method: quicksort  Memory: 50kB
        Buffers: shared hit=205
        ->  Seq Scan on change_history h  (cost=0.00..330.00 rows=100 width=127) (actual time=0.006..0.473 rows=100 loops=1)
              Filter: (actor_id = 'actor-0'::text)
              Rows Removed by Filter: 9900
              Buffers: shared hit=205
Planning Time: 0.041 ms
Execution Time: 0.498 ms

period
SELECT h.* FROM cms.change_history h WHERE h.occurred_at >= ? AND h.occurred_at < ? ORDER BY h.occurred_at DESC, h.id DESC fetch first ? rows only
bindings=[2026-01-01 00:10:00.0, 2026-01-01 00:11:00.0, 51]
Limit  (cost=0.29..75.31 rows=51 width=127) (actual time=0.012..0.019 rows=51 loops=1)
  Buffers: shared hit=4
  ->  Index Scan Backward using change_history_time on change_history h  (cost=0.29..176.81 rows=120 width=127) (actual time=0.011..0.015 rows=51 loops=1)
        Index Cond: ((occurred_at >= '2026-01-01 00:10:00'::timestamp without time zone) AND (occurred_at < '2026-01-01 00:11:00'::timestamp without time zone))
        Buffers: shared hit=4
Planning Time: 0.068 ms
Execution Time: 0.028 ms
```

재실행 명령 (apps/server-v3, Java21/Docker):

```powershell
# 이번 단계 실행 범위: core 전체 + root 빠른 테스트
.\gradlew.bat :cms-core:test --rerun :cms-core:spotlessCheck :spotlessCheck --console=plain
.\gradlew.bat :test --rerun --console=plain
# 이력만 집중 검사, SQL/EXPLAIN 산출물도 재생성
.\gradlew.bat :cms-core:test --tests '*HistoryIntegrationTest' --rerun --console=plain
# LOCO src/main·공통 설정·빌드 구성 변경 단계 또는 머지 전
.\gradlew.bat allTests --rerun-tasks --console=plain
```

EXPLAIN 산출물은 `modules/cms-core/build/reports/history-query-plans.txt`다. 실제 실행 SQL을 수집하므로 쿼리가 변경되면 함께 다시 측정한다. 특정 인덱스가 항상 선택되도록 테스트를 고정하지는 않는다(통계·버전에 따라 planner 선택 가능).

### 4단계 최종 검증 결과 (2026-11-07)

| 실행 범위 | 전체 | 성공 | 실패 | 제외 | 명령 벽시계 시간 |
| --- | ---: | ---: | ---: | ---: | ---: |
| cms-core:test + core/root Spotless 검사 | 86 | 86 | 0 | 0 | 64.679초 |
| root :test (core는 위 실행 결과 재사용) | 291 | 290 | 0 | 1 | 113.771초 |
| 합계 | **377** | **376** | **0** | **1** | **178.450초(약2분58초)** |

시간 합계는 두 Gradle 명령의 벽시계 시간 합이며 명령 사이 문서 작업 시간은 포함하지 않는다. 기존 HEIC 공통 제약1건만 제외됐다. 3단계 manifest의 core/root335건과 비교해 삭제0·추가42건이다(새 HistoryIntegrationTest: 성공 경로13·롤백13·금지 payload10·목록 검사/조회/API/HTTP 비밀값/EXPLAIN6). 실제 PostgreSQL17 컨테이너에서 실행했다.

이번 단계는 **core 코드와 설계 문서만 변경**했다. LOCO src/main, 공통 설정, 빌드 구성, 기존 Flyway SQL은 변경하지 않았다. 승인된 범위 기준에 따라 **allTests·v2 comparisonTest·전체 build는 미실행**이다. 알려진 차이11건의 전체 비교 재검증은 이번 수치에 포함하지 않으며, 머지 전 allTests로 다시 확인한다. 포맷 검사는 통과했다. 측정 결과와 manifest는 `build/performance/cms-history-final/`에 남겼다.

**CMS 4단계 완료 후 중단. 5단계는 사용자 확인 후 진행한다.**

## 18. 5-A 착수 전 검토와 6단계 백로그

### 6단계 시작 전 필수 설계: 호스트 등록형 이력 계약

2026-11-08 설계 반영: [LOCO_ADMIN_DESIGN.md 2절](LOCO_ADMIN_DESIGN.md#2-호스트-등록형-이력-계약-c-33-a-제안)에 등록 API·예약 보호·이름 충돌·중첩 타입·payloadVersion·쓰기/민감값 검사 연결을 제안했다. C-33~C-42 미승인으로 구현은 아직 없다. C-14 권한과 C-18 파일 연결 재검토도 같은 문서에 있다. 아래는 5-A 당시 발견한 제약 기록이다.

현재 `HistoryPayload`는 core 내부의 admin-account/role/menu 허용 목록과 필드·타입을 고정한다. 따라서 현 구조로 LOCO 업무 이력을 추가하려면 core를 수정해야 한다. **6단계 시작 시 호스트가 리소스별 이력 계약(허용 필드·타입)을 등록하는 확장 지점을 먼저 설계한다. 지금 구현하지 않는다.**

검토 범위: core 자체 계약과 호스트 계약의 조립, resource 이름 충돌/예약 core 이름 보호, 미등록 resource의 거절, 중첩 구조·값 타입 검증, payloadVersion 호환, 모든 쓰기 경로의 누락/민감값 검사 연결. LOCO 타입을 core에서 참조하지 않아야 한다. 등록 방식/계약 API는 6단계에서 선택한다. 이때 C-14의 “core 보안 이력 SYSTEM_ADMIN / 업무 이력 해당 업무 READ” 열람 범위도 함께 재검토한다. 5-A의 파일은 core 자체 리소스이므로 승인된 core 파일 계약만 추가하고 호스트 확장 지점을 앞당겨 구현하지 않는다.

### 이미 정해진 경계

- core에는 ObjectStorage/ImageTransformer 포트와 파일 업무만 둔다. 호스트 어댑터만 SDK·libvips를 안다.
- C-9-3: 비공개 객체, 이미지 JPEG/PNG/WebP/AVIF→WebP만 보관, PDF/TXT/CSV는 원본 attachment, 각각10MiB. 기존 LOCO /uploads와 응답·파일 정책은 유지한다.
- 객체 저장 성공 → 메타데이터/성공 이력 같은 DB 트랜잭션 → 응답. DB 실패 시 이 요청 객체만 삭제. **DB 커밋 성공 후 응답 전송 실패는 보상 삭제 사유가 아니다.** 그 객체는 정상 메타데이터와 연결돼 있어 고아가 아니다.
- 삭제는 기존5.4절대로 논리 삭제/DELETE_PENDING+이력을 먼저 커밋하고 물리 삭제를 재시도한다. 논리 삭제 상태는 파일 본문 조회에서 제외한다. 이미 없는 객체 삭제는 성공이다.
- 테스트는 로컬 SeaweedFS만 사용한다(C-20 C). 실제 AWS endpoint·환경 자격증명 체인·키는 사용하지 않는다. 테스트 자격증명은 테스트에서 생성하고 SDK endpoint를 컨테이너로 명시한다. .env.example은 키만 둔다.
- DB와 객체 저장은 하나의 ACID 트랜잭션이 아니다. 장애 순간 객체가 전혀 남지 않는다는 보장과, 장애 후 재정리해 영구 고아를 남기지 않는다는 보장을 구분한다.

### 최초 제안 선택지 (과거 기록 — 최신 확정은19절)

| 번호·항목 이름 | A | B |
| --- | --- | --- |
| **C-15 S3 호환 SDK** | **권장: AWS SDK for Java v2의 S3 모듈을 호스트에만 추가.** AWS용 클라이언트에 로컬 endpoint/path-style을 명시. 향후 S3 연결과 같은 API를 사용 / SDK 전이 의존성·HTTP transport 관리 필요 | MinIO Java SDK를 호스트에 추가. MinIO 중심 API가 간단 / 이후 AWS 설정·예외 대응도 이 SDK 기준으로 검증 필요 |
| **C-16 객체 키 규칙** | **권장: `cms/files/{fileId UUID}`.** 원래 이름/관리자 ID/업무 PK/확장자를 키에 넣지 않음. 이동·이름 변경과 무관하고 경로가 단순 / 날짜별 도구 탐색은 메타데이터 조회 필요 | `cms/files/{UTC yyyy/MM}/{fileId UUID}`. 월별 탐색·정리 범위 지정 편리 / 생성 날짜가 키에 남고 경로 규칙이 하나 더 생김 |
| **C-17 다운로드 방식** | **권장: 서버 경유 스트리밍.** 매 요청 관리자 세션·권한 검사, 권한 회수 즉시 반영 / 앱 서버 연결·트래픽을 점유 | 짧은 수명 서명 URL. 객체 저장소가 직접 전송 / 만료 전 URL을 회수하기 어렵고 URL 전달·보관 위험, 브라우저 CORS·만료 정책 추가 필요 |
| **C-18 초기 파일 소유·권한** | **권장: 업로더 소유 + SYSTEM_ADMIN 예외.** 일반 관리자는 cms.files READ로 자기 조회/다운로드, MANAGE로 자기 업로드/삭제. 다른 관리자 파일은 거절. 역할 부여는 C-12대로 최고관리자만 / 6단계 업무 연결 시 소유/업무 판정을 추가 설계 | 5-A는 SYSTEM_ADMIN만 업로드·조회·삭제. 가장 단순 / 일반 관리자 첨부 흐름을 검증할 수 없고 추후 API 권한 확대 필요 |
| **C-19 응답 유실·보상 실패 복구** | **권장: 영속 업로드 작업 기록 + 멱등 요청 키.** 객체 저장 전에 별도 작업 기록을 남기고 최종 파일 메타데이터/성공 이력은 저장 성공 후 커밋. 동일 관리자·요청 키·내용 재시도는 같은 결과, 다른 내용은409. DB 실패/PUT 결과 불명확은 작업 상태로 복구 / 작업 테이블·동시 재요청 처리가 추가됨 | 객체 저장→DB 저장과 동기 보상만, 남은 객체는 prefix 전체 목록과 DB를 대조해 재정리. 최초 쓰기 흐름이 짧음 / 응답 유실 후 재업로드가 중복 파일을 만들 수 있고 전체 객체 목록 대조 비용이 증가 |
| **C-20 로컬 MinIO 배포·자원** | **권장: 공식 소스 commit·빌드 도구 버전을 고정해 개발/테스트용 이미지를 직접 빌드, CPU1/메모리1GiB로 시작.** 출처·재현성 통제 / 첫 빌드 비용과 유지 책임. 버킷/볼륨 기존 데이터는 지우지 않음 | 가져올 수 있는 공식 과거 이미지를 digest로 고정, CPU1/메모리1GiB로 시작. 준비가 간단 / 레지스트리 가용성과 오래된 바이너리 한계. 사용 가능한 이미지가 확인된 경우에만 가능 |
| **C-21 CMS 이미지 변환 세부 정책** | **권장: 비율 유지·확대/크롭 없이 긴 변 최대2048px, WebP Q80·메타데이터 제거·애니메이션 첫 프레임.** 관리 화면용 용량/응답을 줄임 / 원래 픽셀 크기와 애니메이션이 보존되지 않음 | 원래 픽셀 크기 유지, 나머지 Q80·메타데이터 제거·첫 프레임 동일. 픽셀 크기 보존 / 변환 비용·결과 파일이 커짐. 두 안 모두 디코딩 전40MP 상한을 제안하며 기존 LOCO 정책에는 적용하지 않음 |
| **C-22 저장소 timeout·복구 주기** | **권장: 연결2초·읽기5초·호출 전체10초, SDK 자동 재시도 없음.** 영속 작업 재처리는60초마다100건, 10분 이상 갱신 없는 업로드 작업만 회수 대상으로 검사. 실패는 삭제하지 않고 다음 회차 재시도·시도 수 기록 / 요청 실패가 빨리 드러나고 복구 책임이 명확하지만 일시 오류도 최초 요청 실패 가능 | 연결2초·읽기5초·전체30초, SDK 최대2회 시도(최초 포함), 같은 복구 주기. 일시 장애를 요청 안에서 흡수 / 요청 점유 시간이 길고 PUT 응답 유실 때도 반복 실행 가능 |

C-19 A의 영속 작업 기록·멱등 키는 미채택이며 이후 실험 백로그로 보존한다. 현재는 C-19 B의 동기 보상·주기 목록 대조를 사용한다. 기존60초/100건 제안은 폐기하고10분마다 전체 대조하는 확정값으로 바꾼다. 확정값·추가 C-23 경합 보호 검토는19절을 따른다. SDK 버전·전이 의존성·이미지 digest와 실제 자원 사용은 구현 검증으로 고정한다.

C-21과 별도로 일반 파일은 원본 바이트를 그대로 저장하고 SHA-256으로 검증한다. MIME 헤더/확장자만 믿지 않고 형식을 확인한다. 문서 형식 검사는 악성코드 검사나 문서 안전성 보증을 뜻하지 않는다는 기존 C-9-3 한계는 유지한다.

### 외부 자료 확인 (2026-11-07)

- [AWS SDK v2 endpoint 설정](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/endpoint-config.html): endpointOverride로 로컬 구현을 지정할 수 있다. 실제 AWS 호출 없이 MinIO 대역을 지정하는 근거다.
- [MinIO Java SDK](https://github.com/minio/minio-java): SDK 자체와 서버 배포의 지원 상태는 구분한다.
- [MinIO 공식 서버 저장소](https://github.com/minio/minio): 2026-04-25 archive 표시 및 source-only 배포 안내를 확인했다. 최초 제안은 MinIO 유지였으나 이후 사용자 C-20 C 결정으로 SeaweedFS로 변경했다. 과거 단계/대안의 MinIO 언급은 당시 검토 이력이며 현재 실행 대상이 아니다.

### 선택 후 수행할 검증

1. core 포트에 SDK/libvips/LOCO 참조 없음, core 단독 대역 계약 테스트.
2. 실제 로컬 SeaweedFS에 이미지 WebP와 일반 파일 원본 바이트/체크섬 확인, 형식/크기/권한/경로 입력 경계. cms.files READ/MANAGE를 가진 다른 관리자도 공유 가능해야 한다.
3. 객체 PUT 성공 후 최종 DB/이력 실패: 성공 파일 메타데이터·성공 이력0, 해당 요청 객체만 보상 삭제.
4. 보상 DELETE 실패·프로세스 중단·PUT 응답 유실: 주기 전체 목록 대조가 생성10분 미만 객체를 보존하고,10분 이상·메타데이터 없는 객체만 삭제. 재정리 후 영구 고아0, 정상 파일 보호. DELETE_PENDING은 매 회차 재시도·시도 수 기록. 목록 페이지 수·대조 객체/메타데이터 수·시간을 측정한다. C-23 C에 따라 오래된 UPLOADING/FAILED를 먼저 ABANDONED로 전이한 뒤 정리하며, ACTIVE 전이와의 두 순서를 DB gate로 검증한다.
5. 최종 DB 커밋 후 HTTP 응답 실패: 커밋 객체/메타데이터/성공 이력 유지. C-19 B는 재업로드 시 새fileId·중복 파일이 생길 수 있으며 한계로 고정한다. 각 커밋 파일은 실체가 있고 각 성공 변경에 이력1건이 있어야 한다. 같은fileId 재사용/멱등 키 검사는 이번 범위에서 제외한다.
6. 삭제 DB 실패 시 기존 파일 유지, 논리 삭제 커밋 후 물리 삭제 실패 시 다운로드 차단·재정리 성공, 중복 삭제 안전성.
7. 파일 쓰기 경로를 4단계 inventory/JSONB 허용 계약/민감값·롤백 테스트에 추가. 파일 본문·서명 URL 미기록.
8. 호스트·Compose·빌드 변경 단계이므로 전체 allTests와 포맷/필요 build 검증, 실제 실행 범위·시간을 커밋 본문에 기록하고 push. 5-A 종료 후 중단.

## 19. 5-A 확정 결정과 추가 경합 보호 선택

### C-15~C-22 확정

| 항목 | 최종 결정 | 이유·한계 |
| --- | --- | --- |
| C-15 S3 호환 SDK | A: AWS SDK for Java v2 S3, 호스트에만. endpointOverride·path-style | core는 포트만 알며 SDK 타입과 libvips에 의존하지 않음 |
| C-16 객체 키 규칙 | A: cms/files/{fileId UUID} | 이름·업무 위치와 식별자 분리 |
| C-17 다운로드 방식 | A: 서버 경유 스트리밍 | 매 요청 관리자 권한 확인, 서명 URL을 응답/이력에 넣지 않음 |
| C-18 초기 파일 소유·권한 | **C: 소유 개념 없음. cms.files READ는 전체 조회·다운로드, MANAGE는 업로드·삭제, SYSTEM_ADMIN 전체 허용** | C-3의 단순 READ/MANAGE 유지. 업로더 소유는 관리자 간 파일 공유를 막아 과도한 권한 문제를 반복하므로 미채택. createdBy는 감사 정보일 뿐 소유 권한이 아님. 6단계에서 연결된 업무 권한을 따르도록 확장 검토 |
| C-19 복구 | **B: 동기 보상+주기 대조. C-23 C로 UPLOADING 선등록→객체 저장→조건부 ACTIVE+이력 커밋으로 구체화** | 응답 유실 후 재업로드하면 중복 파일 가능. 각 파일/메타데이터의 정합성과 이력은 유지. 영속 업로드 작업 기록+멱등 키(A)는 이후 실험 백로그 |
| C-20 로컬 저장소 | **C: SeaweedFS S3 게이트웨이, 이미지 digest 고정, CPU1·메모리1GiB** | 기존 MinIO→S3 계획을 S3 호환 로컬 서버(SeaweedFS)→S3로 변경. 사용자 근거는 MinIO 커뮤니티 이미지 미배포·저장소 archive 이후 유지보수 위험. SDK/포트 구조로 저장 서버를 교체하며 실제 AWS 호출·키 사용은 금지 |
| C-21 이미지 변환 | A: 비율 유지, 확대·크롭 없이 긴 변 최대2048px, WebP Q80·메타데이터 제거·애니메이션 첫 프레임, 디코딩 전40MP 상한 | 기존 LOCO 이미지 처리에는 적용하지 않음 |
| C-22 timeout·대조 | 연결2초·읽기5초·호출 전체10초, SDK 자동 재시도 없음. **10분마다 전체 대조, 메타데이터 없는 객체는 생성 후10분 이상일 때만 삭제. DELETE_PENDING은 매 회차 재시도·시도 수 기록** | 목록 대조 비용을 실제 객체 수·페이지 수·DB 조회 수·시간으로 측정. 생성10분 미만은 보존 |

추가 문서 정리: 이전 단계의 MinIO 선택지·과거 완료 기록은 이력으로 보존하고 현재 실행 기준은 이 절과1.1절/5.4절/9절을 따른다. LOCO /uploads는 여전히 로컬 유지이며 별도 이전 작업이다. 현재 .env.example에는 MINIO 접속 키가 없음을 확인했다. 공급자 중립적인 `CMS_STORAGE_ENDPOINT`, `CMS_STORAGE_REGION`, `CMS_STORAGE_BUCKET`, `CMS_STORAGE_ACCESS_KEY`, `CMS_STORAGE_SECRET_KEY` 등의 키만 추가했으며 값은 남기지 않았다. AWS 기본 자격증명 체인을 테스트에서 사용하지 않는다.

SeaweedFS/S3 계약은 로컬 게이트웨이에서 path-style 서명 PUT/GET/HEAD/LIST/DELETE, 페이지 continuation, 권한 거부/누락 객체 오류, 중복 DELETE, SDK 재시도0을 검증하고 S3 문서상 계약과의 차이를 기록한다. **실제 AWS S3와 실행 비교했다는 뜻은 아니다.** 계약 관측은 구현 검증 이후 기록한다.

착수 조사에서 공식 배포 이미지 `chrislusf/seaweedfs:4.52`를 pull해 digest `sha256:57877e32bb90073b37dc109b438ba66e84fd5ed9ced5f5c04f56444ef243ce59`를 확인했다. Maven Central의 AWS SDK S3 release 메타데이터는2.57.11이었다. 이것은 이미지 캐시/배포 메타데이터 확인이며 컨테이너 기동·SDK 호환성·실제 S3 계약 검증 결과가 아니다. 구현 시 해당 버전의 의존성과 계약을 확인한 뒤 사용한다.

### C-23 최초 대안 검토 (과거 기록 — 새 안 C 확정은20절)

10분 유예와 삭제 직전 DB 존재 확인만으로는 다음 일정이 가능하다. 이는 아직 실행 측정한 결과가 아니라 설계상 가능한 경쟁 일정이다.

1. 요청이 객체 PUT을 완료하고 최종 DB 트랜잭션 전에 지연된다.
2. 10분이 지난 뒤 대조 작업이 “메타데이터 없음”을 확인한다.
3. 업로드 요청이 메타데이터를 커밋한다.
4. 대조 작업이 객체를 삭제해 정상 메타데이터만 남는다.

반대로 객체 존재를 확인한 직후 업로드 요청이 지연되면, 삭제가 먼저 끝난 뒤 메타데이터가 커밋될 수도 있다. SDK 호출10초 timeout은 DB 대기·프로세스 정지까지 포함한 전체 업로드 생명주기 제한이 아니므로 이 경합을 배제하지 못한다.

두 선택 모두 이미지 변환과 PUT, 전체 LIST는 잠금 밖에서 한다. 업로드의 **최종 객체 HEAD→메타데이터/성공 이력 커밋**과 대조의 **메타데이터 재확인→고아 DELETE**가 같은 잠금 규약을 따라야 한다. DB가 확인되지 않거나 최종 커밋 결과가 불명확하면 성급히 객체를 지우지 않고 후속 대조로 넘긴다. 기존 C-19 B의 흐름을 유지하며 영속 업로드 작업/멱등 키를 도입하는 선택이 아니다.

| 선택 | 방식 | 장점 | 단점 |
| --- | --- | --- | --- |
| **A. 파일 정리 메타 행 비관적 락 (권장)** | CMS 전용 singleton 행을 JPA PESSIMISTIC_WRITE로 잠금. 업로드 최종 확인/커밋과 대조의 객체1건 재확인/삭제만 보호 | 기존 메뉴 트리 잠금 패턴과 같고 JPA로 이해하기 쉬움. 여러 서버에서도 동일 DB로 조정 | 서로 다른 파일도 최종 구간에서 직렬화. 잠금 안의 HEAD/DELETE 지연이 다른 파일에 영향 |
| **B. 객체 키별 PostgreSQL transaction advisory lock** | 동일 key의 업로드 최종 구간·고아 삭제만 JPA native SQL의 pg_advisory_xact_lock으로 조정 | 서로 다른 객체를 병렬로 마무리, singleton 병목 감소 | PostgreSQL 전용 잠금·키 해시 규약이 추가됨. 모든 관련 경로가 규약을 지켜야 하고 해시 충돌 시 불필요한 직렬화 가능 |

A라면 전체 목록 대조를 한 트랜잭션/한 번의 잠금으로 감싸지 않는다. 후보 객체1건씩 잠금을 잡고 최신 DB와 객체를 재확인해 처리한다. B도 목록에서 읽은 과거 상태를 그대로 사용하지 않는다. 어느 안이든 외부 객체를 직접 삭제하는 관리 도구까지 원자성을 보장하지는 않는다.

이 절은 C-23 결정 전의 검토 기록이다. 이후 사용자가 새 안 C를 확정했으며 구현·검증은20절을 따른다. A/B의 명시적 잠금은 채택하지 않았다.

## 20. C-23 C 확정 — 파일 선등록·조건부 상태 전이

C-23 C: 파일 메타데이터를 UPLOADING으로 먼저 커밋한다(성공 이력 없음). 변환·PUT은 트랜잭션 밖에서 실행한다. 최종 UPDATE는 id와 status=UPLOADING 조건으로 ACTIVE 전이하고 성공 이력을 같은 트랜잭션에 넣는다. 0행이면 실패 응답·자기 객체 삭제 시도. PUT/최종 DB 실패는 UPLOADING→FAILED 조건부 전이·자기 객체 보상 삭제로 처리한다. 커밋 결과가 불명확하거나 DB를 확인할 수 없으면 ACTIVE 객체를 추측으로 삭제하지 않고 후속 대조로 넘긴다.

대조는 생성10분 경과 UPLOADING/FAILED를 조건부 UPDATE로 ABANDONED에 먼저 전이해 승자를 정한다. 그 후 객체 DELETE·행 정리. DELETE 실패는 ABANDONED·시도 수를 유지하고 다음 회차에 재시도한다. DELETE_PENDING도 같은 정리 경로를 사용한다. metadata 없는 cms/files/{UUID} 객체는 생성10분 경과 시 제거한다. 조회/목록/다운로드는 ACTIVE만 노출한다. 늦은 PUT이 ABANDONED 정리 이후 끝나도 ACTIVE 전이는0행이며 자기 객체를 다시 삭제한다. 프로세스가 중단되면 후속 대조가 새로 남은 오래된 고아를 처리한다.

**한 행 조건=조건부 UPDATE(이미지3장 제한·파일 상태) / 여러 행 조건=비관적 락(메뉴 트리)**다. 파일에는 별도 작업 테이블·멱등 키·SELECT FOR UPDATE·advisory lock을 추가하지 않는다. 조건부 UPDATE가 사용하는 DB 내부 행 잠금은 존재하며, 명시적인 비관적 잠금 조회를 쓰지 않는다는 의미다. C-19 B의 응답 유실 재업로드 중복 한계는 유지한다. C-19의 이전 '객체 저장 후 첫 메타데이터 작성' 순서는 이번 C-23의 '선등록 후 성공 전이'로 갱신한다.

18절 경합 검증은 실제 DB gate와 로컬 SeaweedFS로 ACTIVE 전이 우선/ABANDONED 전이 우선을 각각 강제한다. ACTIVE가 가리키는 객체 누락0, 오래된 고아 정리,10분 미만 진행 중 업로드 보존, 늦은 PUT 보상 정리를 확인한다. A/B 잠금 대안은 미채택 기록으로 보존한다.

### 5-A 파일 역할과 확정 계약

| 파일/경계 | 필요한 이유·핵심 설정 |
| --- | --- |
| core `V5__stored_files.sql`, `StoredFile`, `StoredFileRepository` | public Flyway history의 다음 버전으로 cms 파일 상태·삭제 시도 수를 추가. 기존 V1~4 불변. UUID와 객체 키는 한 번 생성하며 상태 전이는 변경 행 수로 승패를 판단 |
| core `ObjectStorage`, `ImageTransformer` | 저장·변환 포트만 공개. core 컴파일/런타임에 AWS SDK·LOCO 변환기가 없음을 경계 테스트로 확인 |
| core `FileService` | REQUIRES_NEW TransactionTemplate로 선등록/성공 전이/정리 상태의 짧은 커밋을 분리. PUT·변환·DELETE는 그 트랜잭션 밖. 명시적 행 잠금 조회 없음 |
| core `FileController`, `AdminAdvice` | 관리자 세션·CSRF 아래 파일 API와400/413/415 오류. attachment·no-store·nosniff 서버 경유 스트리밍. 정적/공개 객체 URL 없음 |
| core `HistoryPayload`, `PermissionCatalog` | `cms.files` READ/MANAGE와 file 이력 계약. 기존 호스트 확장 백로그를 앞당기지 않고 core 파일 계약만 추가 |
| 호스트 `S3ObjectStorage`, `CmsStorageConfiguration` | AWS SDK v2 S3 **2.57.11**, Apache5 transport. netty transport 제외. 명시적 endpoint·static credentials·path-style만 사용하여 로컬 테스트가 AWS 기본 인증 체인으로 새지 않음 |
| 호스트 `CmsImageTransformer` | 기존 저수준 libvips 포트를 사용하되 LOCO 크롭·로컬 저장 정책은 호출하지 않음. 40MP 사전 검사, 긴 변2048px·비율 유지·확대 없음·첫 프레임·Q80·메타데이터 제거 |
| Compose·`.env.example` | SeaweedFS4.52 digest 고정, CPU1/1GiB, 새 `cms-objects` 볼륨. 기존 DB/업로드 볼륨 보존. 접속 정보 키는 CMS_STORAGE_*; 값은 예제에 없음 |

`FileService.upload/4`, `delete/2`, `reconcile/0`을 쓰기 목록 검사에 추가했다. 성공 업로드/논리 삭제만 UPLOAD/DELETE 이력을 남긴다. UPLOADING·FAILED·ABANDONED와 재시도는 성공 업무가 아닌 내부 복구 상태라 성공 이력을 남기지 않는다. 파일 이력의 허용 필드는 originalName/mediaType/byteSize/sha256/createdBy/status다. body·서명 URL은 수집 경계에서 거절하며 세션·CSRF·접속 키·본문은 HTTP 업로드의 실제 이력에도 없어야 한다. 이력 INSERT 실패는 ACTIVE 전이나 DELETE_PENDING 전이를 함께 롤백한다.

일반 파일은 최대10MiB 원본 바이트를 저장한다. TXT/CSV는 UTF-8·제어문자, PDF는 `%PDF-` 시작/끝부분 `%%EOF`를 확인한다. 확장자만으로 파일을 신뢰하지 않으며 PDF 문법 전체·악성코드 안전성까지 보증하지 않는다. 이미지도 입력/변환 후10MiB를 검사한다. 객체 키는 파일명과 무관한 `cms/files/{UUID}`다. 파일명은255자 이하, 경로 구분자·제어문자·빈 값·`.`/`..`를 거절한다. 응답에는 메타데이터와 UUID만 있고 객체 키/서명 URL은 없다.

### 장애·경합 검증 대응

| 상황 | 실제 검증과 기대 결과 |
| --- | --- |
| ACTIVE 우선 | 실제 DB gate로 ACTIVE UPDATE를 먼저 대기시킨 후 ABANDONED UPDATE를 대기. 잠금 해제 후 ACTIVE+UPLOAD 이력1, 객체 존재. ACTIVE 객체 누락0 |
| ABANDONED 우선 | 반대 순서로 대기. 업로드 ACTIVE UPDATE0행→409, UPLOAD 이력0, 객체 정리. ACTIVE 객체 누락0 |
| 긴 변환/늦은 PUT | PUT 전에 멈춤. 생성10분 미만은 보존, 오래된 행은 정리. 행 정리 후 늦게 도착한 PUT도 ACTIVE로 공개되지 않고 자기 객체 삭제 |
| PUT 응답 유실·DB/이력 실패 | 실제 SeaweedFS PUT 성공 뒤 테스트 포트가 예외를 내거나 DB CHECK로 이력을 실패. FAILED·성공 이력0, 자기 객체 보상. DELETE도 실패하면 유예 후 대조가 재정리 |
| 논리 삭제·물리 삭제 실패 | DELETE_PENDING은 목록/다운로드에 보이지 않음. 매 회차 시도 수 증가, 성공 뒤 행 제거. 이력 실패면 ACTIVE+객체 그대로 |
| 성공 커밋 뒤 HTTP 응답 실패 | 테스트 ResponseBodyAdvice에서 실제 응답 작성 전에 실패. ACTIVE+객체+이력 보존, 재업로드는 별도 UUID로 중복 가능(C-19 B 한계) |
| 공개·다른 관리자 접근 | 익명 객체 GET403. 관리자에 cms.files READ만 있으면 다른 업로더 파일도 조회/다운로드 가능, MANAGE만 쓰기 허용 |
| 마이그레이션 | core 단독 V5 기동·호스트 V4→V5에서 기존 public 스키마, V1~4 checksum/history, 역할 보존. CLI는 여전히 migration 미실행 |

테스트 전용 `FOR UPDATE`는 두 UPDATE의 대기 순서를 재현하는 gate이며 운영 구현에 사용한 잠금 방식이 아니다. 시간 경계는 DB timestamp(3)에 맞춰 Java 생성 시각도 밀리초로 저장한다.

### 로컬 S3 계약과 이후 실제 S3 전환의 경계

로컬 SeaweedFS에서 path-style 서명 PUT/GET/HEAD/LIST/DELETE, byte 일치, 익명 GET403, 없는 객체 GET404/NoSuchKey, 중복 DELETE 성공을 검증했다. 1,001개 객체가 continuation으로2페이지에 걸쳐 모두 읽히며 ACTIVE 객체는 삭제되지 않는다. 다음 비교는 **AWS 공식 문서와 로컬 관측의 대조이며 실제 AWS 호출 결과가 아니다**.

- [GetObject](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html): 일반 S3 버킷은 path-style을 지원하나 directory bucket은 지원하지 않는다. S3의 없는 객체 응답은 ListBucket 권한에 따라403/404가 달라진다. 로컬 단일 관리자 자격 증명에서 본404를 모든 AWS IAM 구성의 응답으로 일반화하지 않는다.
- [DeleteObject](https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteObject.html): 현재 어댑터는 버전ID를 쓰지 않는다. 실제 S3 버전 관리 버킷에서는 삭제 마커/과거 버전 보존이 있으므로 물리 제거 의미가 다르다. 버전 관리·IAM·TLS·리전 설정은 실제 S3 이전 시 별도 계약 검증 대상이다.
- [ListObjectsV2](https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectsV2.html): 페이지 크기1,000과 continuation을 따른다. ETag를 파일 해시로 쓰지 않고 앱 SHA-256을 저장한다.

[SDK timeout](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/timeouts.html)은 연결2초·소켓 무활동5초·API 전체10초, SDK 시도1회(자동 재시도0)다. 반환된 다운로드 스트림도 호출 시작부터10초에 abort하도록 보완했다. 루프백 HTTP 장애 대역에서500 요청1회, 소켓 무활동 제한과 바이트가 계속 도착하는 스트림 전체 제한을 검증한다. 저장소의 시간 제한이며 이미지 변환+DB+다운로드를 합친 HTTP 요청 전체10초를 뜻하지 않는다.

### 목록 대조 비용과 한계

단일 로컬 SeaweedFS CPU1/1GiB, ACTIVE 객체1,001개·작은 텍스트 본문·warm 서버에서 전체 대조를 측정한다. LIST2회, 대조 객체1,001, 삭제0, 실패0이 기준이다. 파일 보고서의 메타데이터 존재 SELECT1,001+unfinished SELECT1은 **코드 경로에서 계산한 횟수**이며 별도 SQL 계측값은 아니다. 모든 객체가10분 이상 된 조건에서 매 객체 존재 확인을 수행한다. 행/객체 수에 비례하는 현재 비용은 이후 배치 조회/작업 기록 대안의 기준으로 남긴다. 전체 목록을 메모리에 한꺼번에 올리지 않고 페이지로 순회하지만 미완료 행 ID 목록은 한 번에 읽는다.

프로세스 종료 뒤 남은 객체/행은 같은 상태를 만든 장애 검증으로 확인했다. 실제 kill/restart를 모든 명령 사이에 삽입한 검증은 아니다. 여러 인스턴스의 동시 대조는 중복 DELETE·시도 수 증가가 가능하며, 조건부 상태 전이와 멱등 DELETE로 ACTIVE를 보호한다. 실행 잠금·정확히 한 번 정리는 이번 범위가 아니다. C-19 A의 영속 작업+멱등 키는 실험 백로그이며 응답 유실 중복은 유지한다.

### 첫 PUT 실패의 원인과 기동 검증 보완

전체 빠른 테스트에서 최초 PUT이5초 소켓 timeout으로 두 번 실패했고, 집중 실행에서는 통과했다. 저장소 로그를 보존해 두 번째 실패에서14:24:15~18 UTC에 `FILER LOCK ... no lock server found`가 반복되는 것을 확인했다. 같은 로그의 `the cluster has 0 filers`와 맞물렸다. 이전 준비 조건(S3 익명 요청403, master cluster status)은 파일러가 내부 잠금 서비스에 등록되기 전에 성공할 수 있었다. 파일러 `/readyz`도 해당 고정 버전에서는 메타데이터 저장소 읽기만 확인하므로 이 조건을 대체하지 못한다.

Compose와 Testcontainers는 이제 `weed shell`의 읽기 전용 `cluster.ps`에서 파일러 등록을 확인한 뒤 시작한다. 셸 연결2초·프로세스4초 제한으로 준비 확인을 반복하며 업무 PUT은 재시도하지 않는다. C-22 연결2초/소켓5초/전체10초는 그대로다. SeaweedFS 내부 잠금 서비스 준비와 C-23의 애플리케이션 DB 상태 전이 방식은 별개다. 로컬 서버의 익명 통계 전송은 `-master.telemetry=false`로 비활성화했다.

근거는 고정 이미지의 commit 기준 [cluster.ps](https://github.com/seaweedfs/seaweedfs/blob/984eac6a7/weed/shell/command_cluster_ps.go), [파일러 health handler](https://github.com/seaweedfs/seaweedfs/blob/984eac6a7/weed/server/filer_server_handlers.go)와 실제 컨테이너 로그다. 초기 실패 기록은 `build/performance/cms-files-final/storage-failure.xml`, 재현 로그는 `build/performance/cms-files-complete/seaweedfs-failure.log`에 보존했다. 이후 실행도 `build/reports/cms-storage/seaweedfs.log`에 접속 값 제거 후 로그를 남긴다.

### 검증 수치 (최종 전체 실행의 파일 검증)

- DB gate ACTIVE 우선: 객체 누락0, 성공 이력1. ABANDONED 우선: 성공 이력0, 자기 객체 정리, ACTIVE 객체 누락0. 각 순서를1회씩 강제했으며 대규모 확률 경합 측정은 아니다.
- CPU1/1GiB SeaweedFS,1,001개 ACTIVE 객체·2페이지: 대조 **1.421초**, 준비+대조2.760초, 삭제0·실패0. warm 로컬 단일 실행값이며 성능 개선 수치나 운영 SLA가 아니다. 생성 후11분으로 Clock을 진행시켜 모든 객체를 실제 존재 비교 대상으로 만들었다.
- 루프백 장애 대역:500 응답 요청1회. 계속 바이트가 오는 GET은10.016초에 중단, 무활동 GET은5.015초에 중단, 각각 요청1회. 연결2초는 SDK 설정으로 확인했고 원격 블랙홀 네트워크를 만들어 시간을 측정하지는 않았다.
- 실제 Linux Compose Java21/libvips/SeaweedFS: JPEG·PNG·WebP·AVIF·애니메이션 WebP 5개 각각 업로드201→다운로드200(WebP)→삭제204→상세404. 별도 프로젝트로 실행 후 컨테이너만 종료했고 기존 볼륨을 삭제하지 않았다. Windows Java21에서도 같은 입력의 실제 libvips 변환·저장 통합 테스트 통과.
- 준비 조건 보완 후 전체 실행의 SeaweedFS 로그에서 `no lock server found`0건. 애플리케이션 재시도0과 승인된 timeout은 변경하지 않았다.

### 최종 전체 회귀·빌드 (2026-11-07)

실행 범위: 호스트/공통 설정/Compose/빌드가 바뀌므로 **core + root 빠른 테스트 + v2 comparisonTest + allTests + build/Spotless** 전체를 실행했다. v2 이미지 캐시 조건이다.

```powershell
./gradlew.bat spotlessApply :cms-core:spotlessApply :cms-core:test --rerun :test --rerun :comparisonTest --rerun allTests build --console=plain
```

| 범위 | 전체 | 성공 | 실패 | 건너뜀 |
| --- | ---: | ---: | ---: | ---: |
| cms-core:test | 99 | 99 | 0 | 0 |
| root test | 312 | 311 | 0 | 1 |
| comparisonTest | 1,152 | 1,152 | 0 | 0 |
| 합계 | **1,563** | **1,562** | **0** | **1** |

전체 벽시계 시간 **1,145.692초(19분5.692초)**, build/Spotless 통과. 건너뜀1은 기존 HEIC 공통 제약이다. 알려진 차이 **11건(개선6·의도5·보류0)**의 case/kind/reason/v2/v3 전체 값은 이전 결과와 동일하다. 이전 core/root 케이스는 파라미터 번호와 byte[] 객체 표시 주소를 정규화해 누락0을 확인했고, v2 비교1,152개 케이스 목록도 그대로다. v2 재시작17회, 시나리오 내 창 대기61초는 기존 하네스 정책이며 변경하지 않았다.

결과·manifest·시간 분해는 `build/performance/cms-files-verified/`에 남긴다. 이 시간에는 최초 실패한 두 번의 실행·원인 조사·집중 재검증·Compose 수동 검증은 포함하지 않았다. 실패 이력과 원인은 바로 앞 절에 보존했다. v2/Flutter 코드·기존 비교 fixture·V1~V4 migration은 변경하지 않았다. 실제 AWS 호출/키 사용은 없고 **5-A에서 종료**한다.
