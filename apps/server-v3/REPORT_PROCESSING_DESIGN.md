# CMS 마지막 묶음 — 신고 처리 설계

상태: **설계만 작성, C-43~C-49 선택 대기. 구현·migration·테스트 실행 없음.** 기준 `feature/cms-core`의 CMS6 완료 `93f5642`. 이 문서의 권장은 아직 승인된 결정이 아니다. 선택할 때 **번호와 항목 이름**을 함께 적는다.

목적은 운영자가 신고를 검토하고 결론을 기록하는 것이다. 회원 정지·강제 비공개·앱 알림·신고자 통지·앱 조회 응답 변경은 제외한다. 이들은 v2의 앱 인증/응답/부작용을 바꾸므로 별도 결정 후 진행한다. 리뷰·피드백 조회와 CMS 파일 업무 연결도 이연 상태를 유지한다. 이번 쓰기 묶음 완료 후 CMS를 main에 병합하고 성능 실험으로 넘어가는 순서이며, **이번 설계 작업에서 PR 생성·병합은 하지 않는다.**

## 1. 현재 코드와 경계

| 근거 | 현재 동작 / 설계에 미치는 영향 |
|---|---|
| [v2 ReportsService](../server/src/reports/reports.service.ts), [v3 ReportService](src/main/java/com/loco/reports/ReportService.java), [Report](src/main/java/com/loco/reports/Report.java) | 앱은 신고를 INSERT하고 접수 메시지를 반환한다. 처리 상태·담당자·메모 필드와 앱 처리 API는 없다 |
| [Prisma Report](../server/prisma/schema.prisma), [V1](src/main/resources/db/migration/V1__baseline.sql) | id/reporterId/targetType/targetId/reason/detail/createdAt. targetId는 다형 참조로 대상 삭제 후 남을 수 있다. 신고 대상 삭제와 신고 행 자체 삭제는 다르다 |
| [CMS 조회](src/main/java/com/loco/cmsadmin/LocoReadRepository.java), [권한 기여](src/main/java/com/loco/cmsinfra/AdminScreens.java) | 4종 READ만 등록. 신고 상세의 대상 존재 확인은 해당 대상 업무 READ가 있어야 한다. 새 처리 권한이 이를 우회하면 안 된다 |
| [HistoryPayload](modules/cms-core/src/main/java/com/cms/core/history/HistoryPayload.java), [HistoryWriter](modules/cms-core/src/main/java/com/cms/core/history/HistoryWriter.java) | core 4개 리소스 고정 검사, 명시 snapshot 복사, MANDATORY+saveAndFlush. 업무와 같은 트랜잭션에 이력을 넣는 기반은 이미 있다 |
| [ChangeHistory](modules/cms-core/src/main/java/com/cms/core/history/ChangeHistory.java), [HistoryQueryService](modules/cms-core/src/main/java/com/cms/core/history/HistoryQueryService.java) | payload_version은 현재1 고정, 읽기는 저장 JSON을 그대로 View에 전달. 목록·상세 모두 SYSTEM_ADMIN. 호스트 계약의 버전별 읽기 검사와 권한 조건은 아직 없다 |
| [V3 CMS 이력 스키마](modules/cms-core/src/main/resources/db/cms/V3__admin_accounts_and_history.sql) | resource/actor는 문자열이며 업무 대상 FK가 없다. 기존 time/resource 인덱스를 먼저 측정한다 |
| [Flyway baseline 테스트](src/test/java/com/loco/migration/FlywayBaselineIntegrationTest.java), [앱 DB snapshot](src/test/java/com/loco/compat/DatabaseSnapshot.java) | baseline은 target1만 비교한다. 앱 snapshot은 public 테이블 전체를 읽는다. baseline 통과만으로 최신 앱 스키마 불변을 입증할 수 없다 |
| [CMS6 서비스 검사](src/test/java/com/loco/cmsadmin/LocoReadIntegrationTest.java), [core 이력 검사](modules/cms-core/src/test/java/com/cms/core/HistoryIntegrationTest.java) | 새 CMS 쓰기 메서드는 기존 읽기 목록 검사에서 실패한다. 검사를 없애지 않고 쓰기 시나리오로 명시 분류해야 한다 |

호스트 `com.loco.cmsadmin`이 신고 처리 엔티티·JPA repository·TransactionTemplate 서비스·HTTP·LOCO 계약 등록을 소유한다. core에는 범용 계약/권한 검사만 추가하며 LOCO 엔티티·테이블·권한 키 상수를 넣지 않는다. S3·파일·외부 통신은 이 흐름에 없다.

## 2. 저장 모델 (C-43 B 권장, C-49 A와 조합)

`cms.loco_report_processing`을 **호스트 migration**으로 추가하는 안이다. 현재 마지막 버전은 V5이므로 V6가 다음 후보이며, 구현 직전 가용 버전을 다시 확인한다. 위치는 호스트 `classpath:db/migration`, 기존 public Flyway history를 사용한다. core의 `db/cms`에 LOCO DDL을 넣지 않는다. V1~V5 checksum·public.reports 정의·CLI migration 금지 원칙을 유지한다.

| 컬럼 | 제안 계약 |
|---|---|
| report_id | TEXT UUID PK. public.reports.id와 같은 값, 별도 처리 ID 생성 없음 |
| status | TEXT + CHECK: RECEIVED / IN_REVIEW / RESOLVED / DISMISSED |
| note | nullable TEXT. 마지막 성공 상태 전이의 메모만 보존(C-46 A) |
| handled_by | nullable TEXT 관리자 UUID. 소유/담당자 배정 권한이 아니라 마지막 성공 행위자 |
| handled_at | nullable timestamp(3), 서버 Clock의 UTC. 마지막 상태 전이 시각 |

RECEIVED는 note/handled_by/handled_at=null, 이후 상태는 handled_by/handled_at 필수, 종결 상태는 유효 메모 필수라는 CHECK/서비스 검사를 맞춘다. revision은 C-45 B를 선택할 때만 추가한다. 처리 행 자체의 별도 createdAt이나 자동 배정은 만들지 않는다. 접수 시각은 public.reports.createdAt, 검토 시작/종결 행위자는 이력으로 추적한다.

- **행 없음=접수(RECEIVED)**. 기존 신고와 v2가 새로 만든 신고 모두 backfill 없이 조회한다. GET은 LEFT JOIN/COALESCE로 읽으며 절대로 처리 행을 INSERT하지 않는다.
- 첫 쓰기의 트랜잭션 안에서 원본 신고 존재를 확인하고 RECEIVED 행을 `INSERT … SELECT … FROM public.reports … ON CONFLICT DO NOTHING`으로 준비한다. 기존 행은 덮어쓰지 않는다. 실패하면 이 준비 행도 롤백한다.
- 신고 목록에 상태 필터와 마지막 처리 시각을 추가하고, 상세에서 현재 메모/행위자/시각을 반환한다. 원문 신고 reason/detail은 처리 메모와 별개로 유지한다. 목록에 메모 본문을 넣지 않는다. 기존 createdAt/id keyset·최대100·COUNT 없음은 유지한다.
- 앱이 신고를 생성해도 CMS 이력은 생기지 않는다. 최초 CMS 상태 변경부터 `loco.report` 이력이 생긴다. public 데이터는 모든 CMS 처리 전후에 불변이어야 한다.
- metadata에 원본 신고 내용/이메일/FCM/이미지 URL을 복사하지 않는다. public 원본이 사라져도 이력은 resourceId/actorId만으로 보존할 수 있다. 링크/상세 처리 원칙은 C-49에서 선택한다.

public.reports에 컬럼을 추가하는 대안은 V1을 그대로 두더라도 최신 public 스키마가 달라진다. v2 DB snapshot의 전체 컬럼 비교와 향후 Prisma 관리에 영향을 준다. 이를 테스트에서 조용히 제외하는 방식을 기본안으로 삼지 않는다. CMS 테이블 방식도 별도 최신 스키마 비교·CMS 상태/이력 검증이 필요하며, 기존 앱 비교가 CMS 테이블을 검사한다고 주장하지 않는다.

## 3. 상태 전이와 동시 처리 (C-44 A / C-45 A 권장)

| 현재 → 다음 | 의미·입력 |
|---|---|
| RECEIVED → IN_REVIEW | 검토 시작. 메모 선택 사항 |
| IN_REVIEW → RESOLVED | 검토 완료 기록. 메모 필수. **회원/지도방/장소에 제재가 적용됐다는 뜻이 아님** |
| IN_REVIEW → DISMISSED | 기각 기록. 사유 메모 필수 |
| 그 외·동일 상태 | 409 INVALID_REPORT_TRANSITION. 직접 종결·되돌리기·재개·메모만 수정 없음 |

IN_REVIEW는 독점 배정/잠금 상태가 아니다. `loco.reports MANAGE`가 있는 다른 관리자도 최신 상태를 읽고 종결할 수 있다. 소유 범위를 추가하지 않는 C-3을 유지하며 마지막 행위자를 표시한다. UI에는 “검토 완료(앱 조치 없음)”로 의미를 설명한다.

요청은 `expectedStatus`, `nextStatus`, `note`만 받는다. 처리자·처리 시각·이력 버전은 클라이언트가 정하지 못한다. 서버는 권한/입력 검사 후 하나의 TransactionTemplate에서 다음을 수행한다.

1. 원본 신고 확인·필요한 초기 행 준비, 현재 snapshot 읽기.
2. 요청 expectedStatus와 최신 상태 및 허용 전이를 확인. 이미 바뀌었으면409 STALE_REPORT_STATE.
3. `UPDATE cms.loco_report_processing SET status=:next,note=:note,handled_by=:actor,handled_at=:now WHERE report_id=:id AND status=:expected` 실행. 변경1행만 승자, 0행은409+전체 롤백. 이 조건은 반드시 DB UPDATE에 남긴다.
4. 승자의 전후 안전 snapshot을 HistoryWriter로 기록하고 함께 커밋한다. 이력 실패→처리 변경/초기 행까지 롤백. 실패 요청의 성공 이력0건.

최초 동시 INSERT는 PK/ON CONFLICT로 중복을 막고 이후 상태 조건으로 한 승자만 정한다. 기존 행 경쟁은 실제 PostgreSQL READ COMMITTED에서 선행 UPDATE의 행 잠금 대기 후 조건 재평가를 검증한다. **명시적 SELECT FOR UPDATE를 쓰지 않는다는 뜻이며 PostgreSQL 내부 행 잠금까지 없다는 뜻은 아니다.**

DB 동작의 근거는 PostgreSQL17 공식 [Read Committed의 UPDATE 조건 재평가](https://www.postgresql.org/docs/17/transaction-iso.html#XACT-READ-COMMITTED)와 [미커밋 중복 키의 UNIQUE 검사 대기](https://www.postgresql.org/docs/17/index-unique-checks.html)다. 이 문서를 바탕으로 제안한 흐름이며, 우리 실제 HTTP/DB 경합 결과는 구현 단계에서 따로 측정한다.

이 안은 상태가 되돌아오지 않고 동일 상태 메모 변경이 없어서 상태만으로 오래된 화면을 식별한다. 재개/메모 편집을 허용하면 상태가 같아도 내용이 달라지는 경우(ABA 포함)가 생기므로 **revision 비교를 조건부 UPDATE에 더하는 C-45 B가 필요**하다. revision도 한 행 CAS이며 “한 행 조건=조건부 UPDATE / 여러 행 조건=비관적 락”과 모순되지 않는다. 이번 신고 처리는 메뉴 트리처럼 여러 행의 순환 조건을 검사하지 않는다.

응답 유실 후 동일 요청 재전송은409이며 자동 재시도하지 않는다. 사용자가 최신 상세/이력을 확인하게 한다. 별도 멱등 키·작업 테이블·배정·벌크 처리는 이번 범위에 추가하지 않는다. 중간 검토 메모를 이후 전이에서 바꾸면 C-46 A에서는 이전 원문을 복원할 수 없다는 한계를 명시한다.

## 4. 메모와 개인정보 (C-46 A 권장)

- 메모는 trim 후 최대1,000 Unicode code point, 빈 값은 null. 검토 시작은 선택, 종결은 1~1,000 필수. 알 수 없는 필드/비문자/과대 값/잘못된 enum은400. 서버·브라우저 양쪽에서 경계를 검사한다.
- 최신 메모 원문은 CMS 처리 행의 상세에서 `loco.reports READ`에게 표시한다. HTML/Markdown 렌더링·첨부·URL 미리보기 없이 Vue 텍스트로 표시한다. 입력 안내에 개인정보·계정 비밀 입력 금지를 적는다. 자유 본문 속 비밀을 자동으로 완벽히 검출한다고 주장하지 않는다.
- 권장 A의 이력에는 `hasNote` boolean만 넣고 본문·본문 해시·일부 문자열은 넣지 않는다. 본문 해시도 추측 가능한 문장의 대조 근거가 되므로 이번에는 불필요하다. 처리 action/상태/행위자/시각은 감사 가능하지만 과거 사유의 원문 감사는 불가능하다.
- 로그·오류·URL·브라우저 저장소·추적 자료에 요청 본문을 기록하지 않는다. 실패 응답에 note를 반사하지 않는다. 권한 회수/세션 만료/화면 이탈 때 폼의 메모를 폐기한다. 409에서는 기존 메모를 자동 덮어쓰지 않고 서버 상태를 다시 확인하도록 안내한다.
- 현재 정책에 새 자동 보존기간/정리 작업은 도입하지 않는다. C-46 A도 최신 메모와 백업에 개인정보가 남을 수 있으며, 원문 보존기간/삭제 정책은 운영 확대 전에 별도로 결정해야 한다. C-46 B(원문 이력 보존)는 이 부담이 더 크다.

## 5. C-33 A의 첫 사용처에 맞춘 최소 계약 (C-48 A 권장)

[LOCO_ADMIN_DESIGN 2절](LOCO_ADMIN_DESIGN.md#2-호스트-등록형-이력-계약-c-33-a-제안)은 원안 그대로 보존한다. 다음은 **이번에 구현할 하위 범위 제안**이며 원안 전체 프레임워크를 미리 만들지 않는다.

```text
HistoryContractContribution.contracts() -> immutable collection
HistoryContract(resourceType, payloadVersion, current, visibility, actions, fields)
HistoryContractRegistry.require(resourceType, payloadVersion)
HistoryWriter.record(actor, action, resourceType, resourceId, before, after)
```

호스트 등록 `loco.report`, 현재 payloadVersion=1, action=`TRANSITION`. before/after는 모두 non-null이며 아래 **4필드가 반드시 존재**한다. reportId는 resourceId, actor/occurredAt은 기존 이력 최상위 필드에 있으므로 별도 원본 신고 snapshot은 필요 없다.

| snapshot 필드 | 타입·검사 |
|---|---|
| status | 허용4개 값의 문자열 enum |
| hasNote | boolean, 문자열 `"true"` 거절 |
| handledBy | canonical UUID 문자열 또는 null |
| handledAt | UTC ISO 시각 문자열(밀리초 정밀도) 또는 null |

RECEIVED의 null 규칙·후속 필수 값·허용 상태 전이 등 업무 의미는 호스트 서비스/계약 테스트가 검증한다. core descriptor는 필수/nullable/추가 필드 금지·문자열 길이/enum/UUID/시각·boolean만 표현하며, core가 신고 상태 상수를 알지 않는다. C-45 B를 선택할 때만 snapshot에 revision과 0 이상 정수 검사를 더한다. 첫 리소스의 문자열 최대 길이와 before+after 합계64KiB를 제한한다. 범용 decimal/array/key-map/재귀 schema·깊이8 엔진·JSON Schema 의존성·버전 변환기는 이번에 만들지 않는다. 호스트 v1의 중첩 object/array/임의 POJO/byte[]/숫자 NaN 등은 지원하지 않고 거절한다.

기존 core4종은 기존 중첩 validator를 내부 계약으로 감싸 보존한다. 특히 role Action이 쓰기에서는 enum, 저장 JSON 읽기에서는 문자열이므로 쓰기 검사 함수를 그대로 읽기에 호출하지 않는다. 구버전 JSON의 정수/enum/null/선택 필드 규칙을 별도 읽기 검사로 고정하고 기존 role/menu 쓰기 허용 범위에 새 호스트 한도를 소급하지 않는다. core를 전부 새 schema DSL로 다시 작성하지 않는다.

반드시 유지할 안전장치:

- 기동 시 불변 registry 조립. core4개 이름·cms/cms.* 예약, host namespace 필수, 리소스/버전 중복(내용이 같아도)·current 누락/복수·권한 READ 미지원은 기동 실패. 현재는 v1만 등록하고, 향후 v2 추가 시 구버전을 삭제하지 않는 구조만 둔다. 런타임 REST 등록/동적 DB schema 없음.
- 등록 필드명에서 비밀번호/해시/세션/CSRF/JWT/FCM·providerId·파일본문/서명URL 금지 변형을 검사한다. core 기존 `mustChangePassword`/`sha256` 예외를 호스트 일반 예외로 풀지 않는다. 허용된 일반 문자열에 들어간 비밀까지 이름 검사로 검출할 수는 없다.
- 미등록 리소스는 전후 null이어도 기록 거절. version은 writer가 등록 계약에서 선택하고 기존 payload_version 컬럼에 저장한다. before/after를 명시 추출·검증·복사한 뒤 저장하며 수정 가능한 입력과 분리한다.
- 읽기는 해당 버전 계약으로 검사한다. raw JSON fallback 없음. 허용 리소스의 미등록 버전/손상 payload는 본문 없는 진단 로그와503 HISTORY_CONTRACT_UNAVAILABLE. SYS 조회의 미등록 리소스도 실패. 일반 관리자에게 권한 없는 리소스는 SQL에서 먼저 제외하므로 그 내용/오류도 노출하지 않는다.
- 배포 전 실제 distinct(resource_type,payload_version)와 등록 목록을 대조한다. v1 실제 JSONB round-trip 및 테스트용 이전 버전 descriptor 보존을 검사하고, 자동 JSONB 변환은 만들지 않는다.
- core는 `sample.documents` 대역으로 독립 검증한다. 호스트만 실제 `loco.report` 문자열/권한/스냅샷 생성과 전이 의미를 안다. 신규 라이브러리는 필요하지 않다.

## 6. C-34 재검토와 HTTP·화면 (C-47 A 권장)

권장안은 **core 보안 이력4종=SYSTEM_ADMIN**, **loco.report=현재 loco.reports READ**다. MANAGE⊃READ는 기존 C-3 그대로다. 관리자 역할 관리·배정은 여전히 SYSTEM_ADMIN만 한다. 신규 신고 MANAGE는 자동 부여하지 않으며 기존 READ 역할과 코드 템플릿 복사 규칙을 유지한다.

| API/화면 초안 | 계약 |
|---|---|
| 기존 GET `/admin/api/loco/reports`, `/{id}` | 처리 상태/메타 정보·상세 메모 추가, 상태 필터. 기존 타 업무 READ 경계 유지 |
| PUT `/admin/api/loco/reports/{id}/processing` | loco.reports MANAGE, CMS 세션+CSRF. 상태 변경200(최신 처리 DTO), 존재 없음404, 입력400, 오래된 상태/금지 전이409 |
| 기존 GET `/admin/api/core/history`, `/{historyId}` | C-47 A일 때 업무 권한으로 범위 제한. 일반 READ에게 core 이력은 열지 않음 |
| GET `/admin/api/core/history/resources` | 서버가 현재 열람 가능한 리소스 목록만 반환. core는 등록 계약과 권한 키만 사용 |
| `/admin/loco/reports` 상세 | 상태/메모/마지막 처리자UUID·시각, MANAGE의 다음 상태 버튼, 성공 후 상세/목록 갱신. 처리자 관리자 계정 상세/이메일 자동 JOIN 없음 |
| 기존 `/admin/history` 및 신고 상세의 이력 링크 | server resources로 메뉴/필터 구성. 신고 링크는 resourceType=loco.report/resourceId 고정 필터. 화면 숨김은 보안 경계가 아님 |

현재 HistoryView/router/App는 SYSTEM_ADMIN 전용으로 고정돼 있으므로 C-47 A면 함께 바꾼다. 허용 resourceType 집합을 **SQL WHERE에 먼저 적용한 뒤** 검색·정렬·size+1한다. 메모리 후처리·권한 없는 총수/빈 페이지 유출 금지. 명시적 권한 없는 리소스 필터는403, 상세의 타 업무 이력/비존재는404. 허용 집합 없음403, 허용 기록 없음200. cursor가 있어도 매 요청 현재 권한을 다시 검사한다. 일반 관리자는 actorId만 보고 계정 조회 권한을 얻지 않는다.

401→로그인·메모 폐기, 403 CSRF_INVALID→토큰 갱신 안내 후 사용자가 재시도(자동 쓰기 재전송 없음), PERMISSION_DENIED→상세/폼 제거,409 STALE_REPORT_STATE→최신 상태 재조회·명시 확인 후 재작성. 409 INVALID_REPORT_TRANSITION은 허용 다음 상태를 다시 안내한다. 500은 고정 메시지로 실패하며 메모 원문을 반사하지 않는다. `/api/**`의 에러/인증/CSRF 계약은 변경하지 않는다.

## 7. 사용자 결정 목록

| 번호·항목 이름 | 선택지와 장점 | 단점·권장 근거 |
|---|---|---|
| **C-43 처리 상태 저장 위치** | A: public.reports 컬럼 추가 — 단일 행 조회. **B 권장: cms 전용 처리 테이블** — public/v2 분리. C: 이력 이벤트만으로 상태 계산 — 중복 상태 없음 | A는 최신 앱 스키마·비교 기준 영향. B는 LEFT JOIN/초기 행 경쟁 필요. C는 최신 상태·동시성·조회 비용이 커짐. B의 DDL/엔티티는 호스트 소유 |
| **C-44 상태·메모 변경 범위** | **A 권장: 접수→검토중→완료/기각**, 같은 상태 편집/재개 없음. B: 메모만 수정·종결 재개도 허용 | A는 최소 검토 기록, 잘못된 종결을 화면에서 고칠 수 없는 한계. B는 정정 가능하지만 허용 전이·정정 이력·revision 필요. 두 안 모두 제재/알림/독점 배정 없음 |
| **C-45 상태 전이 경합 처리** | **A 권장(C-44 A일 때): 현재 상태 조건부 UPDATE**. B: expectedRevision 조건+증가(필요시 상태 조건도). C: SELECT FOR UPDATE 후 검사 | A는 단방향 상태로 승자 판별, 컬럼 최소. B도 한 행 CAS이며 메모 수정/재개에 적합하나 revision 계약 추가. C는 명시 락 대기·데드락 관리가 늘고, 락만으로 오래된 화면을 감지하지 못해 expected 값 검사도 필요 |
| **C-46 처리 메모·이력 개인정보** | **A 권장: 최신 메모는 처리 행만, 이력은 hasNote**. B: 전후 원문도 JSONB 이력 보존. C: 정형 사유만 받고 자유 메모 없음 | A는 원문 확산 최소/과거 사유 복원 불가. B는 감사 가능/영속 민감정보 노출·삭제 정책 부담. C는 노출 최소/현장 설명 제약. A/B의 최신 메모는 READ 상세,1,000문자·종결 필수 |
| **C-47 업무 이력 열람(C-34 재검토)** | **A 권장: loco.reports READ에 loco.report 이력만 개방**. B: 모든 이력 SYSTEM_ADMIN 유지 | A는 담당자가 처리 근거 확인/SQL 권한 필터·UI 변경 필요. B는 기존 구현 유지/최고관리자에게 조사 집중. A여도 core4종은 SYS 유지 |
| **C-48 호스트 이력 계약 최소 범위(C-33)** | **A 권장: 호스트 flat typed descriptor+기존 core validator 어댑터**, 버전·예약명·민감값·쓰기 검사 유지. B: 원안의 범용 중첩 DSL까지 지금 구현 | A는 첫 실제 4필드에 맞게 작게 검증/차후 복합 payload 시 확장 필요. B는 표현력/현재 미사용 구조·기존 core 전환 검증 증가. 원안2절은 두 안 모두 보존 |
| **C-49 원본 신고 삭제와 참조 수명** | **A 권장: reportId 논리 참조(FK 없음)**, 원본 없으면 처리404, 메타/이력 유지. B: FK RESTRICT. C: FK CASCADE, 이력은 유지 | A는 앱 물리 삭제를 막지 않음/DB의 영구 참조 정합성 미보장·잔존 데이터. B는 강한 참조/향후 앱 삭제 차단. C는 잔존 메타 없음/최신 메모 소실·앱 삭제가 CMS 상태까지 변경. A는 쓰기 SQL 시점 존재 확인이며 그 직후 외부 물리 삭제까지 막는 보장은 아님 |

**권장 조합: C-43 B, C-44 A, C-45 A, C-46 A, C-47 A, C-48 A, C-49 A.** C-44 B라면 C-45 B로 함께 바꾼다. 이 조합은 승인 전 제안이다. C-49 A에서는 원본 삭제와 경합해 완료된 처리 메타가 남을 수 있으나 앱 쓰기를 막지 않고 이력은 보존한다. 현재 신고 자체 삭제 API는 없으므로 자동 정리 작업을 미리 추가하지 않는다.

## 8. 선택 후 검증·구현 순서

1. **최소 이력 등록 계약**: core 대역으로 예약/중복/미등록·currentVersion·잘못된 READ키·필수/null/타입/추가키·중첩 거절·변경 후 입력 분리·민감 키/값 표식 검사. 기존4종 JSONB round-trip·읽기/쓰기·과거 버전·실패 rollback 유지. core만 바뀌면 core test+root 빠른 test.
2. **호스트 처리 저장·전이·이력**: 선택한 새 migration과 JPA/TransactionTemplate 구현. 빈 DB/V5 업그레이드 및 public schema/data/V1~V5 checksum 불변, core 단독 classpath에 LOCO DDL·타입 없음, bootstrap CLI migration 미실행. 기존 앱 snapshot/fixture 제외로 통과시키지 않는다.
3. **동시 처리 및 실패**: 두 관리자 동일 RECEIVED→IN_REVIEW, IN_REVIEW→서로 다른 종결, 늦은 중복 요청. 첫 행 없는 경쟁은 테스트 gate 트랜잭션이 동일 PK의 초기 행을 미커밋 INSERT하고 두 HTTP의 INSERT 잠금 대기를 관측한 뒤 **gate를 rollback**해 두 요청을 경쟁시킨다. FK 없는 INSERT SELECT는 원본 행의 FOR UPDATE만으로 대기하지 않으므로 이를 gate로 오해하지 않는다. 기존 처리 행은 DB gate로 UPDATE 대기를 확인한다. 두 관리자 payload가 다르도록 실제 HTTP 요청을 사용한다. DatabaseRequestRace의 DB 대기 관측 방식을 재사용하되 현 도우미가 public 테이블/id·동일 Callable에 묶여 있으므로 검증된 cms schema/report_id 및 요청2개를 받는 최소 테스트 확장만 한다(임의 SQL 입력 도구로 만들지 않음).
4. **기대 수치와 롤백**: 각 경쟁20회, 매 회200×1/409×1, 처리 행1개, 성공 이력1개, 패자 메모/행위자 반영0. 최초 INSERT 및 기존 UPDATE 이후 이력 INSERT 실패를 강제해 상태/메모/행위자/시각과 이력 모두 원복 확인. 승자 커밋 전에 실패하면 다른 요청이 정상 승자가 될 수 있는 순서도 검증. 기록 수치와 실행 시간은 구현 후 실측하며 이번에 측정했다고 쓰지 않는다.
5. **권한·이력·비밀값**: READ 변경403/MANAGE 성공/SYS 성공, CSRF/세션/비활성/권한 회수/앱JWT 차단. public11테이블 전후 불변. 컨트롤러가 repository 직접 쓰기로 우회하지 못하도록 호스트 경계 검사. core/호스트 서비스 전체 시그니처를 읽기·실행 가능한 쓰기·근거 있는 예외로 분류하고 새 미등록 메서드는 실패시킨다. 승인안 A의 메모 표식이 처리 행에는 있고 모든 history JSON/로그/에러에는 없음을 검사하며 비밀번호/해시/세션/CSRF/FCM/파일 표식 검사도 유지한다.
6. **조회·화면**: mixed core/업무 이력1만 건에서 권한 조건이 LIMIT 전에 적용됨을 목록·상세·필터·다음 페이지·권한 회수·삭제 대상/원본으로 검사. 처리 행 없음/있음·상태 필터·신고 상세 SQL/EXPLAIN 기준 기록, 기존 인덱스 우선이며 자동 최적화 없음. CMS6의 SQL 수/서비스 분류 테스트는 실제 요구 변화 근거와 새 기준을 적어 갱신하고 무작정 느슨하게 하지 않는다. 권한 설명·역할 MANAGE 선택·신고 처리 폼·이력 링크를 같은 단계에서 연결한다.
7. **최종 gate와 종료**: 호스트·core·migration·화면이 바뀌므로 allTests build/Spotless, Vitest, 별도 Chromium E2E(READ와MANAGE·종결·두 브라우저409·이력 범위) 실행. LOCO 알려진 차이11건 불변, 문서/커밋 본문에 범위·성공/실패/skip·시간 기록, 단계별 commit/push. 마지막에 사용자 확인을 기다린 뒤 CMS→main 병합 및 성능 실험 단계로 넘어간다. 이번 요청은 설계이므로 이 절은 아직 수행하지 않는다.

## 9. 이번 설계 검증

소스·기존 결정·migration 버전·비교 하네스/서비스 검사 및 PostgreSQL17 공식 문서와 대조했다. 변경 문서3개의 상대 파일 링크50개는 누락0, `git diff --check` 통과(검사0.490초). 운영 코드·migration·테스트·프런트·v2·Flutter 변경 없음. 실행 테스트/경합 수치/성능 수치는 이번에 생성하지 않았으며 CMS6 결과를 새 구현 결과로 재사용하지 않는다.
