# 5번 묶음: 리뷰 API 설계

상태: v2 HTTP 69개 조합과 동시 작성 2개 상황 관측 완료. 모든 항목은 기존 결정으로 정해져 추가 승인 없이 구현한다.
v2 기준은 현재 feature 브랜치의 `apps/server/src/places/places.service.ts`, 리뷰 DTO/컨트롤러, Prisma V1이다. v2·Flutter·V1은 수정하지 않는다.

## 1. API와 입력

| 경로 | v2 코드 기준 응답/처리 |
| --- | --- |
| GET /api/places/:placeId/reviews | averageRating, reviewCount, reviews 배열. 리뷰에 user {id,nickname,profileImageUrl} 포함. updatedAt DESC만 정렬 |
| POST /api/places/:placeId/reviews | 201, 사용자별 upsert 후 GET과 같은 응답. 기존 리뷰 ID/createdAt 유지 |
| DELETE /api/places/:placeId/reviews/me | 200, 본인 리뷰 deleteMany 후 GET과 같은 응답. 리뷰가 없어도 성공 |
| GET /api/places/reviews/me | 본인의 리뷰 배열과 place 전체 필드/createdBy/map {id,title}. updatedAt DESC,id DESC |

rating은 필수 정수 1~5, content는 필수 문자열 최대 1000자다. DTO 검증 뒤 ECMAScript trim을 적용하고 빈 내용이면 400이다. 길이는 trim 전에 검사한다. 미정의 필드는 forbidNonWhitelisted 400이다. 실제 타입·null·배열·빈 값·Unicode 길이·공백 오류와 검증 순서를 관측 fixture로 고정한다.

## 2. 권한과 오류 순서

모두 인증 필요 경로다. 토큰과 현재 회원 상태 확인이 입력 검증보다 먼저이며 기본 핸들러별 120회/분 제한을 유지한다.

- GET/DELETE: 장소 존재 확인 → 지도방 읽기 권한 확인. 공개방은 비회원도 가능하며, 비공개방은 방장/멤버만 가능하다.
- POST: DTO → trim/빈 내용 → 장소 존재 → 비공개 접근 → 공개방의 방장 또는 멤버인지 확인. 장소 작성자라는 사실만으로 리뷰 작성 권한을 얻지 않는다.
- DELETE는 타인의 리뷰를 삭제할 수 없다. 공개방을 나간 회원은 자기 리뷰를 지울 수 있지만 비공개방을 나간 회원은 거부된다.
- 내 리뷰 목록은 지도방 권한을 재검사하지 않는다. 나간 비공개방의 장소 정보를 포함할 수 있으며 v2 그대로 유지하고 개선 후보에 남긴다.

## 3. 기존 결정 적용

새 의존성/스키마/설정 변경 없이 기존 `places` 패키지에서 PlaceReview를 Place/User LAZY ManyToOne, cascade 없음으로 매핑한다(3-1·4-1). ID는 TEXT UUID 문자열이며 앱에서 생성한다. native 쓰기의 시각은 Clock에서 명시적으로 전달한다.

읽기는 기존 native projection 패턴을 사용한다. v2의 통계와 목록은 별도 SELECT이므로 하나의 강한 일관성 트랜잭션으로 합치지 않는다. 내 리뷰 조회는 JOIN으로 조립해 N+1을 피한다. 리뷰 컬렉션을 Place 엔티티에 추가하지 않는다.

기존 `(placeId,userId)` 유니크 제약을 그대로 사용한다. 신규/기존 리뷰의 동시 작성은 v2에서 모두 [201,201], 최종 한 행이었다. 기존 지도방 가입에서 사용한 native ON CONFLICT UPDATE 패턴을 적용하고 ID/createdAt은 갱신하지 않는다. 저장/삭제만 TransactionTemplate으로 커밋하고 응답 조회는 그 뒤 수행한다. 조회 실패가 이미 저장한 리뷰를 롤백하지 않게 한다.

## 4. 집계 영향과 경쟁 관측

장소 목록/상세/내 장소의 averageRating·reviewCount는 place_reviews에서 즉시 읽는다. 리뷰 작성/수정/삭제 시 places.updatedAt과 place_marker_cells를 갱신하는 코드는 v2에 없다. reviews 정렬은 평균이 아니라 개수다. 리뷰로 인한 평점·개수·정렬 변화와 다른 DB 상태 보존을 실제 HTTP/전체 DB로 검증한다.

DatabaseRequestRace로 두 요청이 실제 DB 잠금 대기에 도달한 후 해제한다. 신규 리뷰 경쟁은 부모 Place 행/FK INSERT, 기존 리뷰 경쟁은 리뷰 행 UPDATE를 잠근다. 두 HTTP 상태·최종 한 행·ID/createdAt·최종 내용·집계 결과를 관측한다. 경쟁에서 특정 요청의 승리나 갱신 시각 순서는 보장하지 않는다.

## 5. 검증과 구현 순서

1. ReviewObservationTest와 리뷰 전용 보조 시드로 입력/권한/집계/경쟁을 v2에서 관측하고 설계를 확정해 커밋·push한다.
2. 엔티티·읽기 2개 API를 구현하고 실제 응답·DB 불변·SQL 수를 검증해 커밋·push한다.
3. POST/DELETE, unique·경쟁·부분 실패·집계 영향·인증/429를 검증해 커밋·push한다.
4. 전체 test/comparisonTest/build를 실제 재실행하고 결과·시간·기존 테스트 누락 여부를 기록해 커밋·push한다. 5번 묶음에서 멈춘다.

새 결정이 필요하면 구현 전에 `5-번호 항목 이름`과 선택지·장단점을 제시하고 멈춘다. 기존 패턴으로 결정되는 항목은 재승인 없이 진행한다.

## 6. 이관 후 개선 후보

- 내 리뷰 조회의 비공개 지도방 접근 재검사: v2 findMyReviews는 userId만 필터하고 place/map 정보를 반환한다. 나간 회원의 정보 접근 정책을 별도 정해야 한다.
- 통계/목록 및 쓰기/응답 조회의 일관성: v2는 두 SELECT를 Promise.all로 실행하며 쓰기와 응답 조회도 별도다. 동시 변경 시 수치·목록 차이나 쓰기 성공 후 오류가 가능하다. 이번에는 경계를 유지한다.
- 같은 updatedAt 리뷰의 정렬: GET 리뷰에는 보조 정렬 키가 없다. 저장소가 보장하지 않는 동률 순서를 API 보장으로 만들지 않는다. 결정적 정렬은 후속 정책 변경이다.

## 7. 관측 근거와 재실행

`src/test/resources/reviews/http-v2.json`은 69개 HTTP 입력/권한 조합의 상태·본문·DB 평점/개수를, `race-v2.json`은 잠금으로 겹친 두 요청의 결과를 고정한다. `ReviewObservationTest`는 기본적으로 기대값과 대조만 한다. 의도적으로 새 v2 기준을 조사할 때에만 `-PreviewsObserve=true`를 주며 결과는 build 디렉터리에만 쓴다.

- rating 1/5 성공, 0/6·소수·문자·boolean·배열·객체/null 거부. 오류 순서는 max → min → integer다.
- 내용 1000 Unicode code point까지 허용하고 1001은 거부한다. 이모지도 한 글자로 계산한다. trim 전 길이를 검사하며 NBSP/BOM을 포함한 공백만 있으면 서비스 400이다.
- 없는 장소 + 공백 내용은 404보다 빈 내용 400이 먼저다. public 비회원은 읽기/본인 삭제 가능하지만 작성은 403이다.
- 기존 리뷰 수정은 201이며 ID를 유지한다. 없는 본인 리뷰 삭제도 200이다. 내 리뷰에는 나간 비공개방의 장소가 그대로 포함된다.
- 두 동시 요청은 신규/수정 모두 201이며 유니크 한 행을 유지한다. 기존 리뷰 ID도 보존한다. 경쟁에서 500을 재현하거나 알려진 차이를 추가할 필요가 없다.

재실행: `./gradlew.bat comparisonTest --tests '*ReviewObservationTest'` (Linux는 `./gradlew`).
