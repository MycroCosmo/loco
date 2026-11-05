# 4번 묶음: 장소 기본 API 설계안

상태: 4번 묶음 구현·전체 회귀 검증 완료. 4-1~4-6은 모두 A로 확정했다. 아래 대안 표는 선택 당시 비교 기록이다.
v2 기준은 작업 브랜치 `0d5ed03`의 places·map-permissions·notifications·upload와 Prisma 스키마다. v2 소스, Flutter, Flyway V1은 변경하지 않는다.

설계 근거는 [컨트롤러](../server/src/places/places.controller.ts), [서비스](../server/src/places/places.service.ts), [입력 DTO](../server/src/places/dto), [스키마](../server/prisma/schema.prisma)다. 임시 PostgreSQL 17 DB에 V1 SQL을 적용하고 캐시된 v2 이미지로 **32건의 HTTP 요청**과 관련 DB·파일 결과를 관측했다. 이는 아래 경계 사례의 설계용 관측이며, v3 비교 테스트 32건을 구현하거나 통과했다는 뜻은 아니다. 임시 DB·컨테이너는 정리했고 개발 DB는 사용하지 않았다.

## 1. 이번 범위와 응답

모든 경로 앞에 `/api`가 붙는다. PUBLIC 지도방도 익명 API는 아니다. 기존 JWT·현재 회원 상태 확인·오류 응답·핸들러별 기본 120회/분 제한을 적용한다.

`장소 필드`는 id, mapId, createdById, name, address, roadAddress, latitude, longitude, phone, category, memo, tags, imageUrls, createdAt, updatedAt이다. `작성자`는 createdBy {id, nickname, profileImageUrl}, `리뷰 집계`는 averageRating·reviewCount다. 리뷰가 없으면 둘 다 0이다. API별 DTO를 사용해 없는 필드와 null을 구별한다.

| API | 성공 응답 | 접근 조건 |
| --- | --- | --- |
| GET `/maps/:mapId/places` | 200 `{items: (장소 필드 + 작성자 + 리뷰 집계)[], nextCursor, hasMore}` | 공개방 또는 방장 또는 비공개방 멤버 |
| POST `/maps/:mapId/places` | 201 장소 필드 + 작성자. map·리뷰 집계 없음 | 방장 또는 가입 멤버. 공개방 비회원도 생성 불가 |
| GET `/places/:placeId` | 200 장소 필드 + 작성자 + **전체 map 필드** + 리뷰 집계 | 해당 지도방 읽기 권한 |
| PUT `/places/:placeId` | 200 장소 필드 + 작성자. map·리뷰 집계 없음 | 작성자만. 방장도 다른 사람의 장소 수정 불가 |
| DELETE `/places/:placeId` | 200 `{"message":"장소가 삭제되었습니다."}` | 작성자 또는 방장 |
| GET `/places/me` | 200 배열. 장소 필드 + 작성자 + map {id,title} + 리뷰 집계 | 현재 회원이 작성한 장소. 페이지네이션 없음 |

리뷰 쓰기 API는 후속 묶음이지만 기존 리뷰의 평점·개수 조회와 장소 삭제 시 DB cascade는 이번 범위다. 마커 읽기·관리자 재집계 API는 7번 묶음으로 남긴다. 이미지 추가·개별 삭제 API는 6번 묶음으로 남기되 **장소 삭제에 따른 기존 이미지 정리**는 이번 범위다.

## 2. v2에서 확인한 권한·입력 경계

- 없는 지도방은 404, 없는 장소는 404 `장소를 찾을 수 없습니다.`다. 비공개방 읽기 거절은 403 `접근 권한이 없습니다.`다.
- 생성 시 비공개방 외부인은 403 `비공개 지도방에는 장소를 추가할 수 없습니다.`, 공개방 비회원은 403 `지도방에 가입해야 장소를 추가할 수 있습니다.`다. 방장은 멤버 행이 없어도 허용한다.
- 수정·삭제에는 지도방 읽기 권한 재검사가 없다. 실제로 비공개방 멤버가 아닌 작성자는 상세 조회 403이지만 수정 200·내 장소 목록 200·삭제 200이었다. 내 장소 목록도 현재 지도방 가입 여부로 필터링하지 않는다. 이관하면서 추가 권한 조건을 넣지 않는다.
- 수정 권한 실패는 `수정 권한이 없습니다.`, 삭제 권한 실패는 `삭제 권한이 없습니다.`다. 회원 탈퇴·없는 회원은 기존 인증 단계에서 401이다.
- 생성 필수 값은 name·address 문자열, latitude(-90~90)·longitude(-180~180) 숫자다. name 최대 100자, memo 최대 500자. roadAddress·phone·category·memo는 선택 문자열, tags는 선택 문자열 배열이다. 수정은 이 필드들이 모두 선택이다. imageUrls·mapId·createdById는 입력 DTO에 없다.
- 빈 name/address는 실제 201이었다. tags:null 생성은 빈 배열이 된다. 수정의 name:null·tags:null은 실제 500 `{"statusCode":500,"message":"Internal server error"}`였다. Java DTO에서 일괄 NotBlank/NotNull을 붙여 400으로 바꾸지 않는다. 선택 nullable 필드의 null과 필드 누락도 구분한다.
- 알 수 없는 필드는 기존 forbidNonWhitelisted 규칙을 적용한다. 잘못된 타입·범위·복수 오류의 message 배열과 순서는 구현 전에 실제 v2 관측 fixture로 고정한다. 빈 수정 객체·동일 값 지정의 updatedAt, 반복 쿼리 파라미터, 문자열 숫자 등은 아직 이번 HTTP 관측으로 확정하지 않았다.

## 3. 목록·cursor·태그·검색

정렬은 latest(기본): createdAt DESC, id DESC / name: name ASC, id DESC / reviews: 리뷰 개수 DESC, createdAt DESC, id DESC다. reviews는 평균 평점순이 아니다. 실제 관측에서 리뷰 2건(평균 3.5)인 Beta가 1건(평균 4)인 Gamma보다 앞섰다.

limit는 컨트롤러의 JavaScript parseInt 이후 서비스에서 처리한다. 기본 20, 상한 50, 음수는 1, 0·NaN은 기본 20이다. 실제 `limit=2x`는 2건, `limit=0`은 기본 동작이었다. 잘못된 sort는 400 `sort는 latest, name, reviews만 가능합니다.`다. Spring의 기본 숫자 바인딩으로 이 응답을 임의 변경하지 않는다.

Prisma는 `take=limit+1`, cursor의 id, `skip=1`을 함께 사용한다. 마지막 페이지 nextCursor는 null이고, 더 있으면 반환한 마지막 장소 ID다. 커서는 새 인코딩을 도입하지 않고 기존 문자열 ID를 유지한다.

| 실제 관측 | 결과 |
| --- | --- |
| 최신순 Alpha(1/3)·Beta(1/2)·Gamma(1/1), limit=2 | Alpha·Beta, hasMore=true, nextCursor=Beta |
| 다음 요청 cursor=Beta | Gamma, hasMore=false, nextCursor=null |
| 없는 cursor | 200·빈 목록. 커서 오류 400/404 아님 |
| 다른 지도방의 1/2 12:00 장소를 cursor로 지정 | 이 데이터에서는 Gamma만 반환. 단순히 해당 지도방 cursor 존재를 검사하거나 정렬 키보다 작은 모든 행을 반환하면 달라짐 |
| search로 제외되는 Gamma를 cursor로 지정 | 이 데이터에서는 200·빈 목록 |
| tags=[foo,bar], search=`foo bar` | Alpha 일치. 태그를 공백으로 합친 문자열도 검색 |
| tag=foo / tag=FOO | 각각 Alpha만 / 빈 목록. tag는 대소문자 구분 |
| search=`%` | 이 데이터의 세 장소 모두 일치. 와일드카드를 임의 이스케이프하지 않음 |

search·tag는 trim한다. tag는 배열의 정확한 원소 일치이며 search 조건과 AND로 결합한다. search는 name/address/roadAddress의 대소문자 무시 부분 일치 또는 `array_to_string(tags,' ') ILIKE`다. SQL 값은 바인딩하고 sort는 허용 목록으로만 고른다.

일반적인 `id < cursor`나 전역 OFFSET 페이지네이션으로 대체하지 않는다. 선택한 조회 구현에서 정렬별 커서 기준 행·동률·필터 적용·Prisma skip 효과를 재현한다. 특히 다른 지도방/검색 제외/삭제된 cursor, 같은 시각·이름·리뷰 개수는 Prisma SQL과 실제 결과를 먼저 고정한다. 내 장소 목록은 createdAt DESC, id DESC이며 위 페이지네이션과 분리한다.

## 4. 엔티티·조회 설계

Place는 기존 TEXT UUID 문자열·`@UuidGenerator`·Clock 기반 Auditing을 재사용한다. 좌표는 기존 double precision, tags/imageUrls는 기존 PostgreSQL TEXT[]에 매핑한다. 스키마·인덱스·외래 키·V1은 변경하지 않는다. 같은 값을 명시한 수정과 빈 수정은 Prisma 관측 결과에 맞게 갱신 시각을 처리한다.

Place의 map/createdBy 관계는 4-1 A인 LAZY ManyToOne으로 구현했다. 리뷰 컬렉션을 응답 직렬화하거나 행별로 순회하지 않는다. 목록의 작성자·리뷰 집계는 일괄 조회하며, 4-2 A인 native projection을 사용한다. 리뷰 읽기를 위해 리뷰 쓰기 기능 전체를 앞당기지 않는다.

place_marker_cells의 키는 `(mapId, precision, cellLatKey, cellLngKey)`다. placeId는 nullable 집계 값이며 Place 외래 키가 아니다. 4-3 A인 JPA native 명령을 사용한다. native DML의 updatedAt에는 기존 Clock을 명시적으로 전달한다(JPA Auditing이 native SQL을 자동 처리하지 않음).

## 5. 마커 셀 재집계와 커밋 경계

다음 순서는 고정된 호환 요구다. 한 개의 큰 `@Transactional`로 묶지 않는다.

| 작업 | v2 순서 |
| --- | --- |
| 생성 | 장소 INSERT 커밋 → 새 좌표 정밀도 1·2·3 재집계 → 작성자 닉네임 조회·지도방 알림 호출 → 응답 |
| 수정 | 장소 UPDATE 커밋 → 이전 좌표 1·2·3 재집계 → 좌표가 바뀌었으면 새 좌표 1·2·3 재집계 → 응답 |
| 삭제 | 장소 DELETE 및 DB 리뷰 cascade 커밋 → 이전 좌표 1·2·3 재집계 → 이미지 파일 삭제 → 응답 |

이름만 바꿔도 이전 좌표를 재집계한다. 좌표가 달라졌지만 같은 셀에 속하더라도 이전/새 좌표 집계 호출을 합치지 않는다. v2의 중복 제거는 별도 backfill용 refreshUniqueCells에 있으며 CRUD에는 적용되지 않는다.

각 셀은 다음처럼 처리한다.

1. JavaScript `latitude.toFixed(precision)`·longitude로 키를 만든다(정밀도 1, 2, 3 순서).
2. mapId와 `ROUND(좌표::numeric, precision)::text = 셀 키` 조건으로 places를 SELECT한다. AVG 좌표, MIN/MAX 경계, COUNT, 좌표를 소수 6자리로 반올림한 고유 키 수를 계산한다.
3. 개수가 0이면 해당 셀 DELETE. 아니면 복합 키 UPSERT. groupKey는 고유 좌표 키가 하나일 때만, placeId/placeName은 장소가 하나일 때만 채운다. 나머지는 null이다. updatedAt도 갱신한다.

집계 SELECT와 셀 쓰기도 같은 트랜잭션이 아니다. 이전 단계의 커밋을 뒤의 실패로 되돌리지 않는다. 4-4의 두 구현 방식 모두 이 경계를 지킨다. 한 SQL에 집계와 UPSERT를 합치거나 전체 잠금을 추가하면 경쟁 상황 의미가 바뀌므로 이번 설계에서 하지 않는다. 동시 저장 중 오래된 집계가 덮어써질 가능성도 남는다. 동시성 개선이 필요하면 관측 결과와 함께 별도 결정을 받는다.

실제 PostgreSQL 트리거로 정밀도 2의 쓰기만 실패시킨 관측:

| 요청 | HTTP | 요청 후 상태 |
| --- | --- | --- |
| 생성 | 500 | 장소 1건과 정밀도 1 셀이 남음. 2·3은 미생성 |
| 이름 수정 | 500 | 새 이름이 장소와 정밀도 1에 남음. 2·3은 이전 이름 |
| 삭제 | 500 | 장소·리뷰는 삭제됨. 정밀도 1 셀 삭제, 2·3 셀 잔존. 이미지 파일은 남음. targetId만 가진 신고 행도 남음 |

**반올림 호환성도 필요하다.** 실제 좌표 37.05는 정밀도 1 셀이 생기지 않고 2·3만 생겼다. -0.00001은 해당 좌표의 1·2·3 셀이 모두 생기지 않았다. JS 이진 부동소수점 toFixed와 PostgreSQL numeric ROUND의 경계/음수 0 표현 차이 때문이다. PostgreSQL 쿼리를 고치거나 Java의 일반 반올림으로 통일하면 v2와 DB가 달라진다. 4-5 A인 Java 키 생성 함수의 경계 값·음수·±0·좌표 범위 끝을 Node 결과와 대조한다. 이는 기존 제약을 보존하는 것이며 새 PostGIS 로직은 넣지 않는다.

## 6. 알림과 파일

3-6 결정대로 실제 Firebase 전송은 미구현 상태를 유지한다. 4-6 A대로 기존 NotificationSender에 sendToMap을 추가했다.

장소 생성과 모든 셀 갱신이 성공한 뒤에만 아래 계약을 호출한다. 장소 INSERT 직후 AFTER_COMMIT 이벤트에 연결하면 집계 실패에도 알림이 나가므로 순서가 틀린다. HTTP 응답은 외부 전송 완료를 기다리지 않는 v2 경계를 따른다.

- 대상: mapId, 제외 회원: 장소 작성자 userId. 실제 전송 구현 시 지도방 멤버 중 작성자 제외이며 방장을 별도로 중복 추가하지 않는다.
- title: `새 장소 추가`
- body: `${nickname ?? '누군가'}님이 "${map.title}"에 "${name}"을 추가했습니다.`
- data: `{type:'place_added', mapId, placeId}`
- mock 계약: 성공 시 한 번, 권한/입력/DB/어느 정밀도 집계 실패 시 0번. 값·호출 순서를 검증한다. Firebase 설정 환경에서도 실제 푸시가 나가지 않는 알려진 차이는 보고서에 계속 표시한다.

장소 삭제는 저장된 imageUrls를 대상으로 기존 LocalImageFiles 정리를 재사용한다. DB 삭제·모든 셀 갱신 이후 실행하며, 파일 실패로 이미 커밋한 장소를 복원하지 않는다. 정상 삭제에서 실제 파일이 없어지는 것도 관측했다. 없는 파일·외부 URL·경로 탈출·디렉터리·삭제 실패 처리는 기존 정책과 v2 결과를 비교한다. 리뷰는 DB cascade, FK가 없는 신고는 잔존한다.

6번 묶음에서 이미지 업로드·개별 삭제·개수 제한·변환을 이관한다. 이번에는 기존 이미지 파일과 imageUrls를 시드로 넣어 장소 삭제에 따른 정리를 검증한다. 장소 삭제에서 libvips 변환을 실행할 이유는 없다.

## 7. 비교 테스트 계획

기존 이름 기반 ComparisonCase·연속 요청 시나리오·분리 DB·Flyway V1·직접 서명 JWT를 재사용한다. HTTP·전체 DB·파일 변경을 비교하며 새 UUID 별칭과 참조 관계, 시간의 타입/null도 유지한다. 마커 키·집계 수·평점·이미지 URL을 동적 필드라는 이유로 제외하지 않는다.

| 대상 | 정상·입력 | 권한·DB·파일 |
| --- | --- | --- |
| 지도방 장소 목록 | 빈/여러 페이지, 정렬 3종과 동률, limit 경계, 없는/다른 방/검색 제외 cursor, trim·대소문자·태그·와일드카드 | 공개/비공개·방장·멤버·외부인·없는 방, 리뷰 없는/여러 건 집계, 읽기 후 DB 불변 |
| 생성 | 최소/전체 필드, 빈 문자열, null·누락·타입·길이·범위·미정의 필드 | 공개 비회원·비공개 외부인·방장 멤버 행 누락, places+세 정밀도 셀, 동일/다른 좌표, 알림 계약, INSERT/각 셀 실패 |
| 상세 | 전체 map와 작성자/리뷰 응답, 없는 장소 | 방 접근 조건, 작성자가 나간 비공개방, DB 불변 |
| 수정 | 부분/전체/빈 객체/동일 값/null/잘못된 입력, 좌표 유지·이동·겹치는 셀 | 작성자/다른 멤버/방장/나간 작성자, 이전·새 셀과 이름/시각, 장소 UPDATE·집계 실패 후 부분 커밋 |
| 삭제 | 성공·반복 삭제·없는 장소 | 작성자/방장/타인/나간 작성자, 리뷰 cascade·신고 잔존, 마지막/여러 장소 셀, DB/집계/파일 실패, 로컬 파일·외부 URL·없는 파일 |
| 내 장소 | 빈/다수·같은 시각·리뷰·여러 지도방, map 최소 필드 | 본인만, 나간 비공개방 포함, 다른 회원 장소 제외, DB 불변 |

모든 API에 토큰 없음·만료/서명 오류·탈퇴/없는 회원 401, 해당 핸들러의 429 경계와 핸들러 독립성을 적용한다. 429를 피하려고 케이스를 줄이거나 응답을 재시도하지 않는다. DB 오류 주입은 테스트 전용 트리거로 두 DB에 동일하게 적용하고 제거한다. 실패 후 남은 셀도 비교 대상이다.

SQL 수는 인증 조회를 포함해 각 대표 API/분기에서 실제 측정 후 상한을 고정한다. 특히 목록 크기에 따라 쿼리가 증가하는지 확인하고, 이동은 이전·새 좌표의 집계 비용을 따로 기록한다. 구현 후 실측한 SQL 수는 11장에 기록하고 테스트로 고정했다. 테스트는 빠른 단위/v3 단독과 v2 비교로 기존 Gradle 작업에 나눠 넣는다.

## 8. 결정할 항목

모든 항목은 사용자 선택 A로 확정했다. 좌표 키는 new BigDecimal(double)의 정확한 이진 값에 HALF_UP을 적용하고, 음수의 반올림 결과 0에는 부호를 유지하되 입력 -0.0은 Node toFixed처럼 양의 0으로 처리한다. 실제 Node 결과로 검증한다.

| 번호·항목 이름 | A | B |
| --- | --- | --- |
| **4-1 장소 엔티티 관계** | Place.map·createdBy를 LAZY ManyToOne, cascade 없음. 지도방 묶음과 일관되고 관계가 명확함. 조회 시 N+1·프록시 관리 필요 | mapId·createdById 문자열만 매핑. 암묵적 로딩이 없고 쿼리가 명시적. 관계 조립·무결성 확인 코드가 늘어남 |
| **4-2 목록·리뷰 집계 조회** | Spring Data JPA native SQL projection. 배열 검색·리뷰 개수 정렬·Prisma 커서 경계를 한 조회 설계에서 제어. PostgreSQL 의존 SQL과 행 매핑 관리 필요 | JPA/JPQL 중심 + 배열 검색 native 보완. 엔티티 재사용에 유리. 리뷰 읽기 매핑·집계 정렬·특수 커서 때문에 조회 조합이 복잡함 |
| **4-3 마커 셀 저장** | JPA EntityManager native SELECT와 별도 UPSERT/DELETE. v2 집계 SQL·호출 수를 가깝게 유지. 컬럼 매핑과 updatedAt을 명시적으로 관리 | EmbeddedId 셀 엔티티 + native 집계 후 JPA 저장. 키·필드를 타입으로 관리. 존재 확인/신규 판별·flush·동시 UPSERT 충돌 처리가 추가됨 |
| **4-4 부분 커밋 표현** | 비트랜잭션 조정 서비스 + TransactionTemplate으로 장소 쓰기와 각 셀 쓰기의 커밋을 명시. 기존 코드와 같은 방식이고 순서가 보임. 경계를 코드에서 직접 관리 | 별도 Spring 빈의 트랜잭션 메서드들 호출. 선언으로 경계를 분리. 빈/메서드가 늘고 자기 호출·외부 트랜잭션 전파 실수를 주의해야 함 |
| **4-5 JavaScript 좌표 키 호환** | Java 호환 함수 + 테스트에서 Node toFixed 결과 대조. 운영 Node 의존 없음. 이진 실수·반올림·음수 0 규칙을 정확히 검증해야 함 | 운영에서 고정 Node 실행 파일로 키 생성. JS 계산 자체를 사용. 배포 의존·프로세스 비용·장애 처리가 추가됨 |
| **4-6 지도방 알림 인터페이스** | 기존 NotificationSender에 sendToMap(mapId, excludeUserId, message) 추가. 전송 경계 하나와 기존 deferred 구현 재사용. 사용자/지도방 두 대상이 한 인터페이스에 있음 | 별도 MapNotificationSender. 지도방 계약이 독립적. deferred/mock 빈과 향후 Firebase 연결이 두 경계로 나뉨 |

LocalImageFiles 재사용, Firebase 실제 전송 보류, 기존 스키마와 API 형식, 비원자적 커밋 유지, 마커 갱신의 이번 이관은 이미 정한 요구이므로 다시 선택받지 않는다.

## 9. 승인 후 구현 순서

1. 입력·커서 경계 관측을 재현 가능한 fixture로 고정하고 Place 매핑·응답·조회 구현. 목록/상세/내 장소와 SQL 수 검증.
2. 선택한 좌표 키 호환·셀 집계와 생성 구현. 실제 DB 부분 실패·알림 mock 계약 비교.
3. 수정 구현. 이전/새 좌표, 겹치는 셀, null/동일 값/updatedAt, 실패 후 상태 비교.
4. 삭제 구현. DB cascade·셀 갱신·파일 정리 순서와 실패 잔여 상태 비교.
5. 포맷·빠른 테스트·전체 비교 회귀. 단계별 한국어 커밋 본문에 성공/실패/알려진 차이·실행 시간 기록.

단계별 검증·커밋·push 후 다음 단계로 진행하며 4번 묶음 완료 시 멈춘다.

## 10. 이관 후 개선 후보

다음 세 문제는 사용자 지시에 따라 이번 이관에서는 v2와 동일하게 유지한다. 개선 완료나 v3만의 알려진 차이로 표시하지 않는다.

| 문제 | 근거 | 이관 후 검토할 개선 |
| --- | --- | --- |
| toFixed와 PostgreSQL ROUND 차이로 마커 셀 누락 | v2 refreshMarkerCellsForCoordinate의 JS 키와 refreshMarkerCell의 numeric ROUND 조건이 다름. 실제 37.05는 정밀도 1 셀 누락, -0.00001은 세 셀 누락 | 키 생성·셀 소속 판정의 반올림 규칙을 통일하고 기존 집계 재생성. 좌표 경계 회귀 검증 필요 |
| 동시 저장 시 오래된 집계 잔존 가능 | 장소 쓰기·집계 SELECT·셀 UPSERT가 별도 커밋. 두 SELECT/쓰기의 교차 실행에서 이전 집계가 덮어쓸 수 있음. 정밀도 2 오류 주입으로 앞선 커밋 잔존은 관측했으나 이 설계 시점에 경쟁 발생 빈도는 측정하지 않음 | 저장/집계 원자성 또는 셀 단위 직렬화·재시도 방식을 실제 경쟁 테스트로 비교 |
| 비공개방을 나간 작성자의 수정·삭제 허용 | v2 update는 작성자만, remove는 작성자/방장만 검사하고 map 읽기 권한을 재검사하지 않음. 실제 상세 403·수정/삭제 200 관측 | 읽기/쓰기 접근 정책을 명시하고 회원 상태·멤버십 재검사 도입. 이전 동작 변경으로 별도 승인 필요 |

## 11. 구현 검증

조회 단계는 `PlaceObservationTest`로 실제 v2의 67개 입력/커서/권한 응답을 `src/test/resources/places/read-v2.json`에 고정했다. `PlaceReadIntegrationTest`가 같은 요청의 v3 상태·JSON·DB 불변과 SQL 수를 검증한다. 반복 search/tag는 쉼표 문자열, 중첩 객체는 `[object Object]`로 변환하며 없는 커서는 빈 페이지다. JSON 정수/실수 표현도 Node 직렬화와 맞춘다.

조회 SQL 수(회원 인증 조회 포함): 내 장소/공개 상세 2회, 비공개 상세 3회, 지도방 목록은 공개 3회/비공개 멤버 4회. 잘못된 sort는 인증 1회 후 400이다. 목록 크기와 무관하게 고정한다.

재검증: `./gradlew test --tests '*PlaceReadIntegrationTest' comparisonTest --tests '*PlaceObservationTest'`. 명시적 v2 재관측은 `./gradlew comparisonTest --tests '*PlaceObservationTest' -PplacesObserve=true`; 결과는 build/reports/places에만 생성하고 커밋된 기대값을 자동 교체하지 않는다.

생성 단계는 `PlaceInputs`의 누락/null 구분과 v2 ValidationPipe 오류 순서를 44개 실제 관측으로 검증한다. `PlaceMutationService`는 장소 커밋 → `MarkerCells`의 정밀도별 SELECT/쓰기 커밋 → 비동기 알림 호출 순서다. 알림은 `NotificationSender.sendToMap` 계약까지만 연결하며 실제 Firebase 전송 보류는 기존 알려진 차이다.

`CoordinateKeys`는 정확한 이진 BigDecimal + HALF_UP을 사용한다. 실제 Node로 경계의 인접 double, ±0, 음수 반올림 0과 임의 좌표를 교차 검증한다. PostgreSQL ROUND와 다른 셀 누락은 그대로 유지한다.

추가로 실제 v2 Prisma 5의 float 입력 왕복에서 `127.88879999999999 → 127.8888`이 관측됐다. PostgreSQL AVG 결과를 JS/Prisma로 다시 넘기는 경계 때문에 발생하며, 허용 오차로 비교를 완화하지 않는다. `PrismaFloats`가 정확한 double을 유효숫자 16자리 HALF_EVEN으로 변환하고 고정 Prisma의 실제 DB 왕복과 대조한다. 원인은 Prisma 5가 사용하는 bigdecimal 0.3의 f64 변환(소수 15자리 지수 표기 = 유효숫자 16자리)이다. 근거: [bigdecimal 0.3의 TryFrom<f64> 구현](https://github.com/akubera/bigdecimal-rs/blob/v0.3.0/src/lib.rs#L1652)과 하네스 `CoordinateKeysComparisonTest`. 운영에서 Node를 호출하지 않는다.

수정 단계의 `update-v2.json`은 31종 실제 v2 결과를 고정한다(200 20종, 400 3종, 403/404 각 1종, 500 6종). 빈 객체는 updatedAt을 유지하면서 셀을 갱신한다. 같은 값/null 가능한 필드의 명시는 updatedAt을 변경하며, 필수 DB 컬럼의 null·tags:null·빈 배열 본문은 500이다. 비공개방을 나간 작성자도 수정 가능하다.

`PlaceUpdateFailureComparisonTest`는 장소 UPDATE와 이전/새 좌표의 정밀도 1·2·3, 총 7개 오류 지점을 검증한다. 단독 장소 이동 시 이전 셀 DELETE와 새 셀 INSERT 각각의 부분 커밋 상태를 양 DB에서 비교한다. 인증 포함 SQL 수는 빈 수정 9회, 값 명시 10회, 좌표 변경 16회다. 이전/새 셀이 겹쳐도 호출을 합치지 않는다.

삭제 단계는 장소·리뷰 cascade 커밋 → 세 정밀도 셀 갱신 → LocalImageFiles 정리 순서를 유지한다. `PlaceDeletionComparisonTest` 12개 모드가 마지막/공유 셀, 작성자/방장/외부인/나간 작성자, 없는/반복 삭제, 장소 DELETE/지연 커밋/정밀도별 오류, 신고 잔존과 파일 상태를 실제 v2와 비교한다. 외부 URL·없는 파일·디렉터리·경로 탈출은 정상 완료를 취소하지 않는다. 집계 실패 시 파일이 남는다. SQL은 정상 삭제 10회, 권한 거부 3회, 없는 장소 2회다. 이미지 업로드·개별 삭제·libvips 변환은 6번 묶음 범위다.

최종 경계 검증은 `PlaceProtectionComparisonTest`(6개 경로의 토큰 없음/만료/오서명/형식 오류/탈퇴/없는 회원과 DB 불변), `PlaceRateLimitComparisonTest`(각 핸들러 120회 후 429, 서로 독립), `PlaceBoundaryComparisonTest`(순환 소수 평점·큰 페이지·방장 멤버 행 없음·고정밀 좌표·JS 공백/배열 쿼리)로 나눴다. 알려진 차이를 피하려고 비교 케이스를 생략하지 않는다.

장소 묶음만 반복할 때는 앱 디렉터리에서 `./gradlew test --tests 'com.loco.places.*' comparisonTest --tests 'com.loco.places.*'`를 실행한다. 전체 회귀·빌드 실측은 `pwsh -File scripts/measure-tests.ps1 -Label places-final -RestartMode quota`다. v2·Flutter·V1·개발 Compose는 이 묶음에서 수정하지 않는다.

### 최종 회귀 (2026-11-06)

`test --rerun comparisonTest --rerun build`를 실제 실행했다. v2 이미지 빌드 캐시를 사용했으며 전체 350.515초다. 빠른 191건은 성공 190/실패 0/HEIC 공통 제약 skip 1, 비교 442건은 전부 성공했다. 합계 633건, 성공 632/실패 0/skip 1. 기존 성능 개선 시점의 447개 테스트 식별자 누락 0, 신규 186개다.

알려진 차이는 기존 지도방 4건(동시 가입 개선 2, 초대 코드 생성 1, Firebase 보류 1)과 장소 Firebase 보류 1, 총 5건이다. HEIC skip과 별도로 센다. 사용자 지시로 보존한 v2의 세 문제는 v2/v3 차이가 아니라 공통 제약이다.

v2 기동 1회, 재시작 8회, 시나리오 내 대기 0ms. 인증 관련 6회와 장소의 필수 429 검증 예약 1회·PUT 한도 소진 1회다. 시간 분해와 원본 보고서 경로는 TEST_PERFORMANCE.md를 참고한다. 이 묶음에서 실제 Firebase 전송과 동시 집계 경쟁 발생 빈도는 검증하지 않았다. 4번 묶음 종료 후 사용자 확인을 기다린다.
