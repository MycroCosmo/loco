# PR 초안: 독립 CMS core와 LOCO 운영 조회·신고 처리 추가

대상: `feature/cms-core` → `main`. 이 파일은 검토용 본문이며 PR 생성·머지는 하지 않았다.

## 문제와 결과

앱 기능과 분리된 운영 관리 기능을 추가한다. 관리자는 서버 세션으로 로그인하고 역할의 READ/MANAGE 권한에 따라 메뉴·파일·LOCO 조회·신고 처리를 사용한다. 메뉴를 다른 부모로 옮겨도 자신과 자손의 UUID 및 권한·이력 참조가 유지된다. 화면은 서버의 접근 설명과 CMS 오류 code를 사용해 거절 이유와 충돌 후 할 일을 안내한다.

범용 `modules/cms-core`는 관리자 계정·역할·메뉴·이력·파일 포트를 소유하며 LOCO 타입을 참조하지 않는다. 호스트가 LOCO 업무 조회/처리, S3 호환 저장소와 libvips 어댑터를 조립한다. 기존 앱 `/api/**`와 `/uploads`의 v2 호환 계약은 유지한다.

## 포함 범위

- 관리자 Argon2id 계정, 일회성 bootstrap CLI, 첫 비밀번호 변경, 서버 세션(Idle30분/절대8시간), CSRF·세션 고정 방지·로그인 제한. 앱 JWT와 관리자 세션은 교차 인증하지 않는다.
- 역할별 READ/MANAGE(MANAGE가 READ 포함), 코드 템플릿 생성 시 복사. 계정·역할 보안 관리는 SYSTEM_ADMIN만 허용하며 메뉴 트리와 업무 권한을 분리한다.
- 메뉴 parent_id+독립UUID, 이동·정렬·순환 방지용 트리 메타 행 잠금+revision, 서버 navigation/권한 설명 API.
- 같은 업무 트랜잭션의 JSONB 이력과 명시적 필드·타입 검증. core4종은 SYSTEM_ADMIN만, `loco.report`는 신고 READ에 한정해 목록 LIMIT 전 SQL과 상세에서 제한한다. 호스트는 flat typed descriptor를 등록하며 core 예약 리소스를 덮어쓸 수 없다.
- CMS 파일 포트·SeaweedFS S3 게이트웨이·AWS SDK v2 호스트 어댑터, 서버 경유 다운로드, WebP 변환, 파일 상태 조건부 UPDATE·실패 보상·10분 주기 대조. LOCO 로컬 uploads는 유지한다.
- Vue3/Vite/TypeScript SPA와 Spring 정적 제공. 로그인/계정/역할/메뉴/접근 근거/이력/파일/LOCO4종 조회/신고 처리 화면. E2E는 Gradle allTests와 별도 명령이다.
- 회원·지도방·장소·신고 운영 조회, 서버 개인정보 마스킹·비공개 포함 READ·keyset 페이지. 신고는 접수→검토중→완료/기각만 허용하고 현재 상태 조건부 UPDATE로 경쟁을 처리한다. 상태 변경과 이력은 원자적으로 커밋한다.

## 데이터와 배포

- 기존 V1과 public 테이블은 변경하지 않는다. 같은 DB의 `cms` 스키마에 V2~V5(core), V6(호스트 소유 신고 처리)을 추가하며 기존 public Flyway history를 사용한다.
- V6 `report_id`는 논리 참조이며 FK가 없다. 행이 없는 기존 신고는 접수로 조회하고 첫 처리에서 INSERT…ON CONFLICT 후 상태 전이를 수행한다. 원본 삭제 시 처리 메타데이터·이력을 지우지 않는다.
- migration은 배포된 앱 기동 시 적용한다. bootstrap CLI는 Flyway를 끄고 필요한 스키마가 없으면 실패한다. 비밀번호는 명령줄 인자 대신 환경변수/표준 입력으로 받는다.
- 운영 HTTPS에서 CMS 세션 쿠키 Secure 기본값을 유지한다. 로컬 HTTP만 `CMS_COOKIE_SECURE=false`. 저장소 접속 값은 `.env`로 주입하며 코드/문서에 실제 키를 넣지 않는다.
- 신고 MANAGE는 SYSTEM_ADMIN이 역할에 명시 배정한다. 기존 역할·템플릿에 변경을 자동 전파하지 않는다. 상세 실행·초기 생성 방법은 [서버 README](README.md)와 [관리자 README](../admin/README.md)를 따른다.

## 검증

최종 `allTests build`·Spotless 통과, **1,642건 중 성공1,641·실패0·기존 HEIC skip1, 1,054.991초(17분35초)**. root365·v2비교1,152건은 재실행했고 core125건은 직전 단계 실제 통과 후 UP-TO-DATE 재사용했다. v2 이미지 캐시 기준 build1/start1/restart17·인증 대기0ms. **기존 알려진 차이11건(개선6·의도5·보류0)**과 case ID가 유지된다.

서버 검증 후 Vitest **26건 성공**, runner0.206초/명령0.990초. 별도 Chromium **9건 성공**, 브라우저14.4초/호스트 준비·종료 포함38.724초. 실패·flaky0이다. 단계별 재현 명령·계측 조건은 [신고 처리 검증 기록](REPORT_PROCESSING_DESIGN.md#11-단계별-구현-기록)을 참고한다.

- 실제 PostgreSQL에서 메뉴 무보호 교차이동 순환5/5 → 잠금+revision 후0/5, 성공5/충돌5. 신고 초기행/종결 경쟁 각각20회 모두 성공1/충돌1·추가이력0. 선행 이력 실패 후 대기 요청 승자 전환도 초기/기존 행2회 검증했다.
- 1만 mixed 이력 중 신고 READ는 허용5,000건을50페이지로만 읽는다. core 상세404·금지필터403·권한 회수·원본 삭제 후 이력 보존을 검사한다.
- core 성공 쓰기 경로 목록과 이력/민감필드 검사, migration·경계·롤백·파일 복구·실제 로컬 객체·UI 접근성/세션/CSRF/409/다운로드 바이트 검증을 포함한다.
- 신고1만/10만20시나리오 SQL/EXPLAIN, 업무 SELECT1/전체 SQL3을 고정했다. scan/sort와 응답 측정 기준을 보존하며 이번 PR에서 조회 인덱스를 추가하지 않는다.
- 실제 AWS·Kakao·Firebase·Google OAuth를 호출하지 않는다. 로컬 DB/SeaweedFS 및 fail-closed 외부 대역을 사용한다.

## 한계와 이연

회원 정지·강제 비공개·앱 알림은 없다. 신고 완료는 처리 기록이며 앱 제재를 뜻하지 않는다. 종결 후 메모 편집·재개가 없고 이전 검토 메모 원문은 복원할 수 없다. 최신 메모는 처리 행, 이력은 hasNote만 저장한다. 재개/편집을 추가한다면 revision 및 보존 정책을 다시 결정해야 한다.

역할에는 소유 범위·상속·deny가 없다. 비공개 데이터까지 포함한 운영 READ 범위를 역할 설명에 명시한다. 파일 업무 연결, 리뷰·피드백 조회, 파일 응답 유실의 멱등 키, 다중 서버 세션/스케줄러, 조회 인덱스와 락·가상 스레드 성능 실험은 별도 범위다. 브라우저 자동 검증은 Chromium 핵심 흐름에 한정한다.

설계 근거: [CMS core](CMS_CORE_DESIGN.md), [관리자 UI](ADMIN_UI_DESIGN.md), [LOCO 조회와 보존된 이력 원안](LOCO_ADMIN_DESIGN.md), [신고 처리 C-43~49 및 실측](REPORT_PROCESSING_DESIGN.md).
