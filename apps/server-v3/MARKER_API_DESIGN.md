# 7번 묶음: 마커 조회 API

기준: v2 main을 포함한 48f7b54의 places.controller.ts/places.service.ts. 2026-11-06 실제 HTTP 관측 199개(입력·권한 139, 분기 48, 누락 좌표 12)를 fixture로 고정한다. v2·Flutter·V1은 수정하지 않는다. 아래는 관측 후 확정한 이관 설계이며 구현/검증 결과는 마지막 절에 추가한다.

## 1. 결정과 구현 순서

새 결정은 없다. 기존 LAZY 관계·native projection·PrismaFloats·CoordinateKeys·v2 동작 유지 및 개선 후보 기록 방식을 따른다. 새 엔티티·의존성·인덱스·PostGIS 함수·캐시를 추가하지 않는다.

1. 입력/권한/셀 누락/조회 분기를 v2 HTTP로 관측하고 SQL/EXPLAIN을 기록·커밋·push한다.
2. markers, marker-group, marker-cluster를 구현하고 응답·DB 불변·SQL 수를 비교해 커밋·push한다.
3. 인증·핸들러별 429·경계 검증과 전체 회귀/build를 마친 뒤 결과를 커밋·push하고 7번에서 멈춘다.

## 2. 입력과 권한

세 경로 모두 인증 필요. JWT/현재 회원 확인 뒤 컨트롤러 입력, 지도방 존재·읽기 권한, 서비스 입력 순서다. 공개방은 비회원도 읽고, 비공개방은 방장 또는 현재 멤버만 읽는다. 없는 방 404 `지도방을 찾을 수 없습니다.`, 비공개 외부인 403 `접근 권한이 없습니다.`. 탈퇴/없는 회원·잘못된 토큰 401은 기존 인증 기반을 따른다.

| API | 입력 | v2 관측 계약 |
| --- | --- | --- |
| markers | level | 생략/빈 문자열 5. Number 변환 후 정수 1~14만 허용. 0x6/6e0/BOM+6 허용, 5.5/5x/NaN/Infinity/공백/중복은 400 `level은 숫자여야 합니다.`. 방 조회보다 먼저 검사 |
| markers | swLat/swLng/neLat/neLng | parseFloat이므로 37x→37, 0x10→0, 중복 `37,38`→37. 누락/NaN/Infinity는 지도방 권한 확인 후 400 `지도 영역 좌표가 올바르지 않습니다.`. 위도 ±90/경도 ±180 제한 없음. 각각 min/max로 정렬하므로 뒤집힌 범위 허용, 날짜변경선 처리는 없음. 경계 포함 |
| marker-group | groupKey 또는 lat/lng | truthy groupKey 우선. `:` 정확히 두 조각, trim 후 Number 유한·위도 ±90/경도 ±180. 없으면 parseFloat(lat/lng).toFixed(6). 잘못된 좌표 400 `좌표가 올바르지 않습니다.`. groupKey 배열/객체는 ValidationPipe의 문자열 변환을 거쳐 잘못된 좌표 400 |
| marker-cluster | precision | Number 변환 후 1/2/3만. 0x3/3e0 허용. 누락/빈 값/그 외 400 `precision은 1, 2, 3 중 하나여야 합니다.`. 방 조회보다 먼저 검사 |
| marker-cluster | cellLatKey/cellLngKey | trim 후 Number로 유한/범위 검증하되 **문자열 그대로** 셀 PK에 사용. `37.4440`은 `37.444`과 다르다. 배열/객체는 먼저 문자열로 바뀌어 잘못된 좌표 400 |
| marker-cluster | limit | parseInt 10진수. 생략/NaN/0은 20, 음수는 1, 최대 50. 2x→2, 1.5→1, 0x10→0→20. limit+1로 hasMore 판정. cursor 없음 |

tag/search/sort 등 선언하지 않은 쿼리는 **무시**한다. 장소 목록 API의 태그 필터를 여기에 추가하지 않는다. 잘못된 level/precision은 없는 방에서도 400인 반면 group 입력 오류는 먼저 방 404/403이 나올 수 있다. 숫자 파싱은 Java 기본 변환으로 일괄 치환하지 않는다.

## 3. 읽는 테이블과 응답

### markers

- level 1~5: places에서 mapId와 포함 범위로 최대 **301행**, createdAt DESC/id DESC. 300 이하이면 각 Prisma Float 좌표의 JS toFixed(6) 키로 입력 순서대로 그룹화한다. 한 행은 place, 여러 행은 group. 평균은 같은 행 순서로 double을 더한 뒤 나눈다.
- 301행이 잡히면 셀 조회로 전환한다. level 6 이상은 바로 셀 조회한다. level 1~8 precision 3, 9~10 precision 2, 11~14 precision 1.
- 셀은 maxLat >= minLat / minLat <= maxLat / 경도 동일 조건의 bounding box **겹침**으로 선택한다. 평균 좌표가 화면 밖이거나 장소 일부가 범위 밖이어도 셀 전체 count가 반환된다. count DESC만 있으며 동률의 순서는 SQL에서 보장하지 않는다. 이관 중 임의 tie-breaker는 추가하지 않는다.
- 처음 699셀을 표시하고 나머지는 하나의 overflow 마커로 합친다. 정확히 700셀이어도 마지막은 overflow다. count 합계와 순차 가중 평균을 사용한다.
- 응답은 배열. 공통 필드 `type,id,name,latitude,longitude,count`. place는 추가 필드 없음; group은 groupKey; cluster는 clusterKey,precision,cellLatKey,cellLngKey. overflow는 clusterKey=overflow이며 precision/cell 키 **없음**. null 필드를 추가하지 않는다.
- 셀 count=1이고 placeId/placeName이 truthy면 place. 그 외 exactKeyCount=1이고 groupKey가 truthy면 group. 나머지는 cluster. 셀을 통해 장소 테이블을 다시 조회/정정하지 않는다.

### marker-group

places만 읽는다(작성자/리뷰 요약 포함). Number(key) ± 0.0000005로 하한 포함·상한 미포함 범위를 구성한다. createdAt DESC/id DESC. 반환은 장소 목록 배열이며 createdBy, averageRating, reviewCount를 포함하고 map/페이지 래퍼는 없다. key를 기준으로 다시 toFixed 일치 검사하지 않는다. JS 반올림 그룹과 역조회 반구간의 일치가 항상 보장되는 것은 아니다.

### marker-cluster

먼저 place_marker_cells의 복합 PK로 조회한다. 없으면 실제 places가 있어도 `{count:0,items:[],hasMore:false}`. 있으면 places의 PostgreSQL `ROUND(latitude::numeric, precision)::text`와 입력 문자열을 비교해 id를 최신순으로 limit+1개 조회한다. 이어서 장소/작성자/리뷰 요약을 붙이고 id 순서로 복원한다. count는 셀 저장값 그대로이므로 실제 items 수와 달라도 보정하지 않는다. stale 셀 count=99를 그대로 반환하는 사례도 고정한다.

## 4. 실수 변환과 누락 좌표

- 모델 Float 조회/필터 값은 기존 PrismaFloats의 유효숫자 16자리 변환을 적용한다. native SQL 결과 전체에 무조건 적용하지 않고 v2 ORM 경계를 맞춘다.
- 그룹 키는 정확한 이진 double의 toFixed(6)이며 CoordinateKeys를 재사용한다. 음수 반올림 영점의 `-0.000000`과 실제 -0.0의 `0.000000`을 구별한다.
- group 범위의 ±0.0000005 계산, 그룹의 순차 평균, overflow의 순차 가중 평균은 double 연산 순서까지 유지한다. parallel stream 평균이나 BigDecimal 평균으로 교체하지 않는다.
- 실제 POST로 37.05/경도 0을 만든 뒤 조회: level 5·6·9에서 표시, **level 11에서 누락**. JS toFixed(1)=37.0으로 갱신하지만 PG ROUND(...,1)=37.1이므로 precision 1 셀이 없다. marker-cluster는 37.0/37.1 어느 쪽도 count 0이다.
- 이어 -0.00001/경도 0을 POST하면 level 5에서 표시하지만 precision 1/2/3 셀 키의 음수 영점과 PG 양수 영점이 달라 **level 6·9·11에서 이 장소가 누락**된다. 낮은 level에서도 장소가 301개 이상이면 셀 경로로 바뀌므로 누락될 수 있다.
- 테스트의 셀 일반 시드는 SQL GROUP BY로 조립한다. 위 누락 관측만은 실제 v2 POST/재집계를 통과시켜 만든다. 요청 ID는 관측 비교를 위해 고정하며 수치 값·셀 존재 여부는 변경하지 않는다.

## 5. SQL과 EXPLAIN 기준

PostgreSQL 17 / postgis/postgis:17-3.6, Flyway V1 그대로. 실험 A는 기존 places 7행·지도방 4개·작성자 4명·리뷰 3행과 SQL로 만든 셀, B는 **다른 공개 지도방에 장소 10,000행**을 추가하고 모든 셀을 재집계한다. 대상 방은 4장소 그대로다. ANALYZE 후 EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)을 실행한다. enable_seqscan/플래너/인덱스 변경 없음. 운영 분포나 부하 성능을 대표하지 않는다.

`markers/explain-v2.cjs`는 수정하지 않은 컴파일된 PlacesService와 MapPermissionsService를 실제 PrismaClient에 연결해 읽기 메서드를 호출하고 query 이벤트를 기록한다. JWT 인증 SQL은 이 캡처에 포함하지 않는다. 실제 HTTP 응답 관측과 구별한다. 이벤트는 쿼리·파라미터를 읽기만 하며 동작을 대체하지 않는다. 원본 SQL·바인딩·실행계획은 `src/test/resources/markers/explain-v2.json`, 매번 재측정본은 build/reports/markers/explain-v2.json에 둔다. 과거 EXPLAIN 숫자를 테스트의 고정 성능 기준으로 삼지 않는다.

아래는 별칭/선택 열을 줄인 핵심 SQL이며 원본은 위 JSON이 기준이다.

```sql
-- markers: places 경로
SELECT id,name,latitude,longitude FROM places
WHERE "mapId"=$1 AND latitude >= $2 AND latitude <= $3
  AND longitude >= $4 AND longitude <= $5
ORDER BY "createdAt" DESC,id DESC LIMIT 301;
-- markers: cells 경로
SELECT * FROM place_marker_cells WHERE "mapId"=$1 AND precision=$2
  AND "maxLat">=$3 AND "minLat"<=$4 AND "maxLng">=$5 AND "minLng"<=$6
ORDER BY count DESC;
-- group (이후 작성자 IN 조회와 리뷰 GROUP BY)
SELECT * FROM places WHERE "mapId"=$1
  AND latitude >= $2 AND latitude < $3 AND longitude >= $4 AND longitude < $5
ORDER BY "createdAt" DESC,id DESC;
-- cluster (이후 id IN 조회·작성자·리뷰 요약)
SELECT * FROM place_marker_cells WHERE "mapId"=$1 AND precision=$2
  AND "cellLatKey"=$3 AND "cellLngKey"=$4;
SELECT id FROM places WHERE "mapId"=$1
  AND ROUND(latitude::numeric,$2::int)::text=$3
  AND ROUND(longitude::numeric,$2::int)::text=$4
ORDER BY "createdAt" DESC,id DESC LIMIT $5;
```

| 조회 | A: 작은 시드 | B: 다른 방 1만 행 추가 |
| --- | --- | --- |
| markers places / group | Seq Scan places → Sort (markers는 Limit) | places_mapId_latitude_longitude_idx Index Scan → Sort |
| markers cells | Seq Scan cells → Sort | place_marker_cells_mapId_precision_maxLng_idx Index Scan → Sort |
| cluster 셀 PK | Seq Scan cells → Limit | place_marker_cells_pkey Index Scan → Limit |
| cluster 장소 id IN | Seq Scan places | places_pkey Index Scan |
| 방/작성자/리뷰 | 작은 테이블 Seq Scan, 리뷰 Aggregate | 같은 선택. 다른 방 장소 증가와 무관 |

cluster ROUND id 조회는 A에서 Seq Scan → Sort → Limit, B에서 places_mapId_latitude_longitude_idx Index Scan → Sort → Limit였다. B의 Index Cond는 mapId뿐이며 두 ROUND 조건은 Filter다. v3 native projection 계획은 구현 검증 후 함께 덧붙인다. 인덱스를 쓴다는 사실만으로 모든 조건이 Index Cond인 것은 아니다. ROUND 표현식은 기존 좌표 B-tree의 범위 조건과 달라 mapId로 좁힌 뒤 Filter로 처리하는지 원본 plan에서 확인한다. PostGIS 전환 시 이 SQL/분포를 기준으로 비교하되 지금은 개선하지 않는다.

## 6. 이관 후 개선 후보

1. JS toFixed와 PG ROUND/영점 표기 불일치: 생성된 장소가 셀 기반 조회에서 누락된다. 4번 묶음 기록과 실제 조회 fixture가 근거다.
2. 셀 재집계의 분리 커밋/동시 갱신: count·좌표가 실제 장소와 다를 수 있다. 조회 시 즉석 보정은 이관 범위에서 하지 않는다.
3. 그룹 역조회 반열림 구간과 JS 반올림 그룹의 불일치 가능성, count 동률 순서 미정, 날짜변경선 미지원, 태그 무시, overflow에 재조회용 정밀도 없음은 현재 계약으로 기록한다.
4. places의 ROUND 필터와 셀 bbox B-tree 조건, 현재 전체 셀 fetch 후 699개 분할은 2단계 PostGIS 실험의 기준이다. 공간 인덱스/쿼리 재작성/응답 제한 개선은 지금 수행하지 않는다.

## 7. 검증 기록

관측 중 별도 Prisma 프로세스의 DATABASE_URL에 하네스 인증값이 빠져 SQL 수집 1건이 실패했다. DB_USERNAME/DB_PASSWORD 환경값을 조합해 해결했으며 비밀값은 파일/출력에 기록하지 않았다. HTTP 관측 10건은 처음부터 통과했다. 관측 생성 후 fixture를 재대조하고 SQL 수집을 다시 실행한다.

관측 fixture 재대조 + SQL/EXPLAIN: 전체 11건 성공·실패/skip/알려진 차이 0, 21.461초(v2 이미지 캐시), 재시작 0·대기 0ms. HTTP 199개 응답을 10개 JUnit 실행으로 묶으며 SQL 관측 1개를 더한다. 관측 테스트 수와 API 요청 수를 혼동하지 않는다.

구현 비교 단계: MarkerComparisonTest 146건 성공·실패/skip/알려진 차이 0, 94.450초(v2 이미지 캐시), 재시작/대기 0. HTTP 관측 199개를 모두 양쪽 서버에서 대조하고 두 DB의 전체 스냅샷 불변을 검사한다. 인증 포함 markers 일반/셀 직접 경로 3 SQL, 301행 fallback 4, group 3, cluster 셀 없음 3·셀 있음/장소 없음 4·items 있음 5다. 비공개방 비방장 멤버는 권한 확인 1 SQL을 더한다. level/precision 입력 거절 1, 서비스 좌표 거절 2, 없는 방 2, 접근 거절 3이다.

구현 중 최초 비교의 실패 4건은 문자열 파라미터를 배열/객체로 취급한 파서 때문이었다. v2 전역 ValidationPipe는 문자열로 먼저 변환하므로 실제 fixture의 400이 맞다. 파서와 이 문서의 잘못된 500 설명을 수정하고 146건 전체를 재실행했다. 기대값을 바꿔 통과시키지 않았다.
