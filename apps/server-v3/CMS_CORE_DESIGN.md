# CMS(B) 핵심 모듈 설계안

상태: **2026-11-07 사용자 결정 확정. 9절 1단계(core 경계·단독 기동·마이그레이션)만 구현하며 완료 후 중단한다.**

작성일: 2026-11-07. 기준 main `9dc3c09`는 PR #2의 merge commit이며 이관 최종 `7f25789`를 부모로 보존한다. 이 main에서 `feature/cms-core`를 만들었다. 아래 1.1절이 최신 확정 기록이다. 8절의 미채택 대안은 비교 이력으로 보존한다. 아직 구현하지 않은 업무 모델·API 초안과 완료된 1단계 기반을 구분한다.

## 1. 근거와 확정 범위

- [노션 설계 v0.2](https://app.notion.com/p/40c140eabf0e81dca76de00565fe725b): ADR 6·9·10·11·14, 권한 4장, CMS 메뉴 5장, 2026-11-04 CMS(B) 결정 변경 기록을 읽었다. 조회된 마지막 편집 시각은 2026-11-05다.
- 현재 요청: 범용 core는 LOCO를 모르며 LOCO → core만 허용한다. JPA 단일, PostgreSQL JSONB 우선, 이미지 WebP/일반 파일 원본 객체 저장, 기존 앱 계약 보존. 이전 직장 코드·저장소는 열거나 입력·복제·재작성하지 않았다. 사용자가 설명한 문제만 독립 설계의 요구사항으로 사용했다.
- [이관 요약](MIGRATION_SUMMARY.md), [비교 하네스](COMPARISON_TESTS.md), [이미지 설계](IMAGE_API_DESIGN.md), [사용자 API](USER_API_DESIGN.md)를 현재 구현의 기준으로 삼는다.

| 항목 | 기존 근거 | 이번 문서에서의 취급 |
| --- | --- | --- |
| CMS 형태 | 2026-11-04 범용 core + LOCO 확장 확정 | 재논의하지 않음. C-1은 물리적 분리 방법만 결정 |
| 인증 | 노션 ADR 6: 앱 JWT/관리자 세션 확정. 관리자 계정 분리는 ADR 7에서 제안 | C-4에 기존 세션 방향을 표시하고 계정 분리·초기 생성 구체화. 토큰은 비교 대안 |
| 메뉴 | ADR 9: 고정 ID + parent_id 확정 | C-2 A가 기존 결정. 경로/클로저는 비용 비교용이며 변경은 별도 명시적 승인 필요 |
| 이력·파일 | JSONB, WebP, MinIO→S3 방향 확정 | 수집·실패 경계·이전 범위를 C-6/C-8에서 결정 |
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
| C-8 파일 저장소·LOCO 이전 | A: CMS만 객체 저장 포트 + 로컬 MinIO, LOCO /uploads 유지 | 기존 앱 파일·URL 계약 유지 |
| C-9-1 실행·HTTP 경계 | A: 한 프로세스, /admin/** 별도 SecurityFilterChain | v2 호환 전역 필터는 /admin에 적용하지 않는다. 관리자 HTTP 경로를 만드는 2단계에서 적용·회귀 검사 |
| C-9-2 트리 동시 변경 | A: 트리 메타 행 비관적 락 + revision | 이미지 3장 제한은 한 행 조건이라 조건부 UPDATE, 메뉴 순환 방지는 여러 행 조건이라 비관적 락 |
| C-9-3 CMS 파일 초기 정책 | A 승인 | 비공개, JPEG/PNG/WebP/AVIF→WebP만 보관, 일반 PDF/TXT/CSV 원본 attachment, 각각10MiB |
| C-9-4 이력 보존·수집 | A 승인 | 최소 성공 변경 정보, 초기 자동 삭제 없음. 운영 전 보존 기간 재검토 |
| C-9-5 첫 LOCO 확장 | A 승인 | 조회 메뉴부터, 운영 조치는 별도 설계 |

의사결정과 구현 완료는 다르다. 인증·트리·권한·이력·파일·프런트는 이번 1단계에 구현하지 않는다. 기존 선택지 중 Nuxt 권장안은 C-5 A 변형으로 대체했다. [노션 결정 변경 기록](https://app.notion.com/p/40c140eabf0e81dca76de00565fe725b)에도 동일 결정을 반영한다.

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

**JPA만 사용한다는 것은 객체 저장까지 JPA로 한다는 뜻이 아니다.** DB 업무 접근에 적용하며 S3/MinIO 통신은 저장 포트를 통한다. 테스트의 JDBC 스냅샷·실제 DB 경합 관측은 기존 검증 도구로 유지한다.

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

core에는 `ObjectStorage`의 put/read/delete와 `ImageTransformer` 계약만 둔다. 업로드는 권한·CSRF·크기/실제 형식 확인 → 이미지 변환(일반 파일은 원본) → 서버 생성 불변 object key 저장 → 메타데이터/업무 연결/이력 DB 커밋 순서다. 객체 저장은 DB 트랜잭션과 원자적이지 않다. DB 실패 시 **이 요청이 만든 key만** 보상 삭제하며, 보상 실패/프로세스 종료 고아는 식별 가능한 상태와 재정리 대상으로 남긴다.

삭제는 DB 연결 해제와 DELETE_PENDING 표식을 먼저 커밋하고 실제 객체 삭제를 재시도할 수 있게 한다. 존재하지 않는 객체의 삭제는 성공으로 처리한다. 업로드 후 미연결 파일의 정리 유예시간, 재시도 횟수/주기는 구현 전에 수치 검증한다. 같은 객체를 여러 업무에 연결하려면 참조 집계 정책을 별도로 정해야 하며 초기에는 소유 업무 연결 한 곳을 제안한다.

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
| 파일 업로드·다운로드·삭제 | POST `/files`, GET `/files/{id}`, GET `/files/{id}/content`, DELETE `/files/{id}` | 비공개 객체, 업무/소유 연결 권한, 일반 파일 attachment, 경로조작/파일명 정제 |

파일 GET은 “로그인한 관리자면 모두 다운로드”가 아니다. core 파일 권한과 소유 영역 검사를 통과해야 하며 LOCO 업무별 접근 확인은 LOCO가 수행한 뒤 core 서비스를 호출한다. 사용자가 보낸 resourceType만으로 권한을 인정하지 않는다.

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
| 저장소 | MinIO 컨테이너 대역, WebP signature/치수·일반 파일 SHA-256, MIME 위장/크기/경로·다른 관리자 접근 거절, 저장 실패/DB 실패/보상 삭제 실패/재정리, 재시도 중복 삭제 안전성 |
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

A는 core가 S3 SDK 타입을 노출하지 않는 계약, 조립 인프라에서 S3 호환 구현과 libvips 어댑터 제공을 뜻한다. MinIO와 S3의 endpoint·자격 증명뿐 아니라 path style/서명/오류/권한 차이도 계약 테스트 대상으로 둔다. 저장소 SDK·이미지 버전·컨테이너 리소스는 구현 전 확정하고 비밀은 외부 설정, 예제는 키만 둔다. S3 비용이나 라이선스 검토가 완료됐다는 뜻은 아니다.

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

## 9. 승인된 구현 순서 — 이번에는 2단계에서 중단

1. C-1/C-7 경계·core 단독 기동·migration 테스트. 현재 V1/기존 회귀 유지 확인.
2. 관리자 계정·초기 생성·세션/CSRF·앱 JWT와의 교차 인증 차단. 이력 기반도 함께 준비.
3. 역할/권한·메뉴 트리, 이동/정렬/순환·마지막 관리자 경합·권한 설명 화면.
4. 이력 조회와 실패 롤백, 비밀값 배제 검증. 성공 변경 누락 경로 점검.
5. 파일 포트·MinIO·변환 어댑터·보상 정리, UI 통합. 실제 외부 키 사용 없는 테스트.
6. 선택된 LOCO 전용 관리 메뉴 연결, 기존 앱 전체 비교와 CMS 활성 상태 통합 회귀.

각 구현 단계는 별도 커밋·검증 수치·시간 기록을 남기는 방식으로 제안한다. **이번 요청은 1번까지만 구현·검증·커밋·push하고 중단한다.** 새 업무 결정이 생기면 C-번호와 항목 이름으로 답을 기다린다.

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
- `AdminBootstrap`: 비웹 일회성 Java 실행 경로다. 비밀번호를 요청 DTO의 자동 toString이나 CLI 로그에 포함하지 않는다. 성공 시 최초 로그인 비밀번호 변경이 필수다.

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
# apps/server-v3에서 실행. DB 환경변수는 이미 별도로 주입되어 있어야 한다.
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
