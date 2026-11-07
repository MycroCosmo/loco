# 8-A 즐겨찾기·피드백·신고

기준은 48e5df6에 포함된 v2 favorites/feedback/reports 코드와 실제 HTTP다. 새 의존성·스키마·정책 선택 없이 기존 LAZY 관계(cascade 없음), TEXT UUID, JPA Auditing/Clock, native projection, TransactionTemplate, v2 동작 유지 원칙으로 구현한다. 카카오 검색은 별도 설계이며 운영 구현하지 않는다.

## 1. 입력·권한과 응답 관측

모든 경로는 JwtAuthGuard로 보호된다. 인증과 현재 회원 상태 확인 뒤 DTO ValidationPipe를 거친다. 알 수 없는 필드는 400 message 문자열 배열이며 각 오류의 순서도 fixture에 고정했다. class-transformer가 제거하는 __proto__/constructor와 배열 body의 숫자 키도 기존 입력 처리 방식으로 맞춘다.

| API | 입력과 권한 | 응답·DB |
| --- | --- | --- |
| GET /api/favorites | 로그인 회원의 즐겨찾기만. userId/limit 쿼리 무시. 지도방 공개 여부·회원 여부를 재검사하지 않음 | 생성 시각 DESC 배열. favorite id/userId/mapId/createdAt 및 map 전체 필드, owner {id,nickname}, _count {places,favorites,members}. owner.profileImageUrl 없음. 동률 추가 정렬 없음 |
| POST /api/favorites | mapId는 문자열만 요구. 빈 문자열/공백/비UUID도 DTO 통과 후 없는 방 404. 자기 방 403. 비공개 비회원도 추가 가능 | 201 favorite 4필드. (userId,mapId) 중복 409. DTO에 userId를 넣으면 미정의 필드 400 |
| DELETE /api/favorites/:favoriteId | 없는 favorite 404, 다른 회원 소유 403. 지도방 권한은 확인하지 않음 | 200 {message:"즐겨찾기가 삭제되었습니다."}; 해당 행만 삭제 |
| POST /api/feedback | content 문자열 10~1000 코드포인트. trim 없음. 공백 10개 허용, 이모지 1000개 허용/1001개 거절. userId body 불가 | 201 {message:"피드백이 제출되었습니다.",id}; 현재 회원 ID로 새 행. AI 연동 없음 |
| POST /api/reports | targetType MAP/PLACE/USER, targetId 문자열(빈 값도 허용), reason 문자열 5~200 코드포인트, detail 선택 문자열 최대 1000. detail null은 허용. trim 없음 | 201 {message:"신고가 접수되었습니다."}. 대상의 존재·공개 여부·본인 여부를 검사하지 않음. UUID가 아닌 targetId도 TEXT 그대로 저장 |

서비스 오류 메시지: 없는 방 `지도방을 찾을 수 없습니다.`, 본인 방 `내가 만든 지도방은 즐겨찾기할 수 없습니다.`, 중복 `이미 즐겨찾기한 지도방입니다.`, 없는 즐겨찾기 `즐겨찾기를 찾을 수 없습니다.`, 남의 삭제 `삭제 권한이 없습니다.`. 예상하지 못한 DB 오류는 기존 기본 500(상태·message만)이다.

피드백 검증 순서는 maxLength → minLength → isString이다. 신고는 targetType → targetId → reason(max/min/string) → detail(max/string) 순서이며 optional null 검사를 보존한다. NUL 문자 10개는 DTO 길이는 통과하지만 PostgreSQL 저장 오류로 500이다.

관측 fixture: src/test/resources/submissions/favorites-v2.json 38, feedback-v2.json 22, reports-v2.json 37 = 97개 순차 HTTP 응답·3개 테이블 전체 결과. ID/시각은 형식을 검사한 뒤 정규화하며 다른 값은 제외하지 않는다.

## 2. 유니크·중복과 실제 동시 요청

DatabaseRequestRace의 외부 행 잠금 뒤 실제 요청 두 개가 DB 잠금 대기에 도달한 것을 확인하고 해제했다. 애플리케이션 로직에 지연을 넣지 않았다.

| 조건 | v2 상태 | DB 결과 |
| --- | --- | --- |
| 같은 회원·방의 동시 즐겨찾기 추가 | 201,409 | 새 favorite 1행 |
| 같은 favorite 동시 삭제 | 200,500 | 대상 0행, 다른 favorite 보존 |
| 같은 content 동시 피드백 | 201,201 | 동일 내용 2행 |
| 같은 대상·사유 동시 신고 | 201,201 | 동일 내용 2행 |

즐겨찾기 추가의 P2002는 409로 매핑한다. 삭제는 사전 조회에서 둘 다 행을 본 뒤 한 요청이 먼저 삭제하면 후속 삭제가 실패해 500이다. 순차 재삭제의 없는 행 404와 구별한다. 이번에는 새 개선 정책 없이 이 결과를 유지한다. 중복 피드백/신고를 임의로 UPSERT하거나 방지하지 않는다.

## 3. 구현 경계

- Favorite(User/MapRoom), Feedback(User nullable), Report(reporter User)를 기존 LAZY ManyToOne으로 매핑한다. cascade 없음. TEXT UUID는 @UuidGenerator, createdAt은 기존 Clock 기반 JPA Auditing을 재사용한다. ReportTargetType은 V1의 PostgreSQL enum 그대로다.
- favorite 조회는 기존 지도방 native projection 패턴으로 전체 응답과 세 집계를 한 쿼리로 읽는다. owner 응답은 이 API의 두 필드만 반환한다.
- favorite 추가는 TransactionTemplate 커밋까지 완료한 뒤 응답한다. DB 유니크 위반은 롤백 후 409로 바꾼다. 삭제는 관리 엔티티 조회·소유 검사·삭제를 하고 경쟁에서 stale delete가 발생하면 기존 500을 유지한다.
- feedback/report는 각 한 트랜잭션에 한 행을 저장한다. 오류 시 전체 롤백. 반환 ID와 생성 시간, 실제 회원/내용을 DB와 대조한다.
- 하네스의 reports.targetId는 FK/UUID라는 가정을 제거해야 한다. 실제 생성 PK를 참조하면 기존 별칭을 재사용하되, 임의 문자열·없는 UUID는 원문을 비교한다. 동일 제출 2행은 내용과 행 수를 비교하며 무의미한 UUID 순서로 정렬하지 않는다.
- 기존 가짜 feedback 컨트롤러 비교는 실제 /api/feedback으로 전환한다. 하네스 자체 시나리오 검증은 유지한다.

## 4. 이관 후 개선 후보

1. 비공개방 비회원이 즐겨찾기로 지도방 전체 필드와 inviteCode를 읽을 수 있다. POST 가입 검사 없음과 GET 방 권한 검사 없음이 근거다. 이번에는 권한 정책을 임의 변경하지 않는다.
2. 즐겨찾기 동시 삭제의 500: 위 실제 경합 결과가 근거. 멱등 삭제/404로 정리하는 정책은 후속 결정이다.
3. 신고 대상 검증·자기 신고 차단·중복 정책, 피드백/신고 공백 내용 허용은 현재 v2 동작이다. 이관 중 추가 검증을 넣지 않는다.

## 5. 순서와 검증 기록

1. v2 관측·설계를 커밋/push.
2. 즐겨찾기·피드백·신고와 실제 API 비교를 구현, 응답·DB 변경·롤백·동시 요청·SQL 수 검증 후 커밋/push.
3. 인증·429와 전체 회귀/build, 8-A 완료 기록.
4. 8-B 가짜 외부 서버 관측 fixture와 KAKAO_SEARCH_DESIGN.md의 선택지만 커밋/push하고 멈춤.

최초 관측 생성: JUnit 7건 성공·실패/skip/알려진 차이 0, 29.209초(v2 이미지 캐시), 재시작 0·대기 0ms. 순차 97응답 + 동시 4조건×2요청 = 105개 HTTP다. SQL 수와 전체 회귀 결과는 구현 검증 후 추가한다.

### 8-A 구현 검증

`comparisonTest --tests '*Submission*Test' --tests '*HarnessInfrastructureTest'`: 총 133, 성공 133, 실패/skip/알려진 차이 0, 122.406초(v2 이미지 캐시), 재시작 2회·대기 0ms.

- 관측 fixture 97개를 두 앱에 재요청하고 HTTP 전체 응답·3개 변경 테이블 전체 컬럼 및 전체 DB 상태를 비교한다. 동시 요청 4조건도 같은 결과다.
- 5개 API별 6개 인증 상태(토큰 없음/만료/서명/형식/탈퇴/없는 회원)는 모두 DTO와 쓰기보다 먼저 401이다. 현재 회원 확인이 필요한 경우만 SQL 1개, 그 외 0개다.
- favorite 추가/삭제, feedback/report 저장은 BEFORE trigger 오류와 DEFERRABLE constraint trigger의 커밋 오류에서 500이며 전체 DB가 원래대로다(8건).
- 각 핸들러의 120개 요청은 401, 121번째만 429이며 잔여 횟수·Retry-After도 비교한다. 429를 알려진 차이로 두지 않는다.
- 비공개 비회원 즐겨찾기 추가→목록에서 inviteCode/집계 확인→삭제→목록·DB 0행을 이어서 검증한다.
- 하네스의 가짜 feedback 경로를 제거하고 실제 API로 쓰기·연속 요청 검증을 전환했다. report targetId의 임의 문자열·없는 UUID는 값 차이를 탐지하고 실제 생성 PK 참조만 별칭으로 맞춘다.
- 동시 동일 제출 두 행은 내용·행 수·UUID/시간 형식으로 별도 비교한다. 나머지 모든 테이블은 전후 원문 비교한다. 임의 필터 쿼리로 행을 빼는 방식은 하네스가 계속 거부한다.

SQL 수는 인증 조회를 포함해 고정했다. GET favorites 2, POST favorites 성공/중복 3·없는 방/본인 방 2·입력 오류 1, DELETE 성공 3·404/403 2, feedback/report 성공·DB 오류 2·입력 오류 1이다. 증가는 테스트 실패다.

### 전체 회귀·완료

`scripts/measure-tests.ps1 -Label submissions-final -RestartMode quota`: 전체 1,145건, 성공 1,144·실패 0·기존 HEIC 공통 제약 skip 1. 빠른 테스트 230(229 성공), v2 비교 915 전부 성공. 기존 알려진 차이 8 유지, 8-A 신규 차이 0. Spotless/build 통과, v2 이미지 캐시 기준 전체 **729.391초**다.

이전 1,019개 테스트 식별자 누락 0·추가 126. v2 빌드 호출/최초 기동 각 1, 재시작 14. 인증 대기 0ms와 시나리오 내부 대기 61,005ms는 다른 지표다. 이번 시간은 두 작업·준비·build를 포함한 Stopwatch wall time이며 JUnit 개별 시간 합산이 아니다. 원본은 build/performance/submissions-final/에 있다.

운영 JAR에 세 기능 Controller가 포함되고 테스트 대역/Kakao 클래스는 없음을 확인했다. v2·Flutter·infra·V1 변경은 없다. 8-A 구현은 완료했고 8-B는 설계·가짜 서버 관측까지만 진행해 선택을 기다린다.
