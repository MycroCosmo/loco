# P2 — 마커 셀 갱신 SQL·커밋 경계와 제한된 조회 메모리

상태: **E-22 B 변형 구현·allTests build 통과. 정밀도별 별도 잠금 SELECT + 결합 DML,총6문장·3커밋이다. 측정은 커밋·push 후 새 환경 확인을 받은 뒤 시작한다. 성능 채택은 아직 판정하지 않았다.** 기준 소스는 `8e1a9dd`,최종 선행 측정 기록은 `8259e05`다. [상위 설계](PERF_STAGE2_DESIGN.md),[E-14/E-15 결과](../../perf/stage2/E14_E15_RESULTS.md)를 잇는다. E-17의3문장 목표만 E-22가 대체한다. 이전 선택지·실패 수치는 이력으로 보존한다.

승인 기록(2026-11-09): E-17 정밀도별 이전/새 셀1문장·3커밋,E-18 작은 결과 Java 정렬/큰 결과 DB top699+overflow·동일 snapshot·배열 순서 유지,E-19 최대301 후보 CTE 후≤300만 PG ROUND,E-20 PG 중심·V9 재생성·알려진 차이,E-21 같은 세션 A1 대비16req/s p95≥20% 감소. **1e-10은 중심 좌표에만 적용한다. count·셀 키·표시 집합은 완전 일치한다.** E-16·힙·부하·평가 기준은 사후 변경하지 않는다.

## 1. E-16 확정 — P1을 정합성 비용으로 조건부 승인

P1의 PG ROUND·0 정규화(E-5)와 결정적 overflow(E-12)를 유지한다. **성능 기준을 완화한 승인이 아니다. P2에서 원래 P0 대비 1% 회귀를 회복해야 한다.** E-15 결과를 성공으로 수정하거나 P1의 높아진 지연을 1% 새 기준으로 삼지 않는다.

| 근거 | 실측값 | P2 의무 |
| --- | --- | --- |
| 같은 세션1%·50req/s AB/BA5쌍 | p95 7.255445→8.114647ms(+11.84%),p99 8.937767→10.208839ms(+14.22%). p99는5쌍 모두+10% 초과 | 같은 세션 **원래 P0 JAR 대 P2**에서 두 지표 모두 회귀10% 이내 |
| 반환하지 않는301 후보의 ROUND 키 | 같은 DB SQL 중앙값0.430→0.867ms | 실제 개별 마커 반환 경로(≤300)에만 ROUND6 키 계산 |
| 515셀의 결정적 2차 정렬 | SQL 중앙값1.027→1.379ms | ≤699셀일 때 DB 전체 정렬 비용 제거 검토. 응답 순서 계약 확인 필수 |
| 1% JFR | 60초 GC 정지424.372→426.551ms,Old GC0 | GC가 전체 회귀 원인이라는 가정 없이 SQL·앱 비용 각각 확인 |
| 넓은 범위 | E-15 10% p95 105.404→116.016ms,B 편차45.33%. 이전 전체 viewport heap OOM | 10% 개선과 전체 viewport OOM0,셀 수에 비례하지 않는 앱 메모리를 함께 입증 |

E-15 SQL 중앙값 차이는 HTTP p95 증가분의 정확한 분해값이 아니다. 역사적 수치는 계획용이며 새 세션의 채택 임계값을 대신하지 않는다.

## 2. What·Why — 읽기와 쓰기의 효과를 분리한다

- **쓰기:** 현재 좌표당3정밀도×(집계 SELECT+셀 DML)=6SQL,좌표 이동12SQL이다. 같은 셀을 두 번 갱신하기도 한다. 집계와 DML을 DB 문장으로 결합하고 affected-cell을 중복 제거한다.
- **읽기:** 현재 `MarkerQueryRepository`는 전체 일치 셀을 `getResultList()`로 만들고 Java에서699개와 overflow를 나눈다. 이 전송·Tuple·Map·리스트가 O(N)이라 갱신 SQL만 줄여서는 조회 OOM이나1% 회귀를 해결하지 못한다.
- **불변:** JPA native query 경로,장소 UUID/입력·권한·태그 무시 등 현행 마커 계약,E-5 좌표키,E-12 표시 멤버십,앱/DB 자원·기본 힙,그룹/클러스터 역조회 V8 인덱스. v2·Flutter·CMS 소스는 수정하지 않는다. PostGIS·Redis·새 인덱스/저장소·전역 튜닝은 범위 밖이다.

근거 코드: [MarkerCells](src/main/java/com/loco/places/MarkerCells.java), [MarkerQueryRepository](src/main/java/com/loco/places/MarkerQueryRepository.java), [PlaceMutationService](src/main/java/com/loco/places/PlaceMutationService.java), [PrismaFloats](src/main/java/com/loco/places/PrismaFloats.java). 과거 v2 순서는 [장소 설계5절](PLACE_API_DESIGN.md#5-마커-셀-재집계와-커밋-경계)에 보존한다.

## 3. 갱신 SQL과 커밋 경계 — E-6 B 구체화

### 3.1 권장 구조(E-17 A, 미확정)

1. 장소 INSERT/UPDATE/DELETE와 리뷰 cascade는 현재처럼 먼저 커밋한다. 바깥에 하나의 큰 `@Transactional`을 붙이지 않는다.
2. 정밀도1→2→3 순으로 **각각 독립 TransactionTemplate**을 실행한다. 각 문장에 이전/새 좌표 최대2개를 전달하고 PG ROUND로 셀 키를 만든 뒤 DISTINCT한다. 정밀도당 최대2셀,전체 최대6셀이다.
3. 한 문장 안에서 affected keys→집계→`count>0` UPSERT와 `count=0` DELETE를 나눈다. 동일 키가 두 DML에 걸리지 않게 한다. UPSERT는 `ON CONFLICT`를 유지하고 MERGE로 바꾸지 않는다. 집계 결과 CTE는 작게 물질화해 두 DML이 같은 집계를 쓴다.
4. E-14 V8과 같은 `ROUND(latitude::numeric,정밀도)::text`/경도 조건과 검증된1/2/3 리터럴을 검토한다. numeric→text 비교의 키 동일성·generic plan을 검증한 뒤 사용한다. 값은 바인딩한다. 왕복1회가 places 스캔1회를 뜻하지 않으므로 각 정밀도/키의 실제 loops·Buffers를 기록한다.
5. 모든 정밀도 완료 후에만 기존 알림 호출/이미지 파일 삭제를 진행한다. 장소 커밋 뒤 어느 셀이 실패하면500이며 알림0회/삭제 파일 잔존은 유지한다. 후속 작업 실패로 장소를 되돌리지 않는다.

권장안의 갱신 SQL 목표는 생성·일반/no-op 수정·삭제6→3,좌표 이동12→3이다. 같은 셀 이동은 셀당1번만 반영한다. HTTP SQL 예산은 현재 생성10/11→7/8,수정no-op9→6·일반10→7·이동16→7,삭제10→7을 **예상**하며 실제 인증/응답/알림 조회를 다시 세어 상한을 고정한다. BEGIN/COMMIT 등 wire 왕복도 SQL 카운터와 별도로 기록한다. 결과를 보기 위해 assertion을 제거하지 않는다.

### 3.2 장애 의미

| 오류 시점 | 현재 P1 | 권장 P2(E-17 A) |
| --- | --- | --- |
| 장소 쓰기 실패 | 장소/셀 불변 | 동일 |
| 정밀도2 실패,단일 좌표 | 장소·정밀도1 커밋,2·3 이전 상태 | 동일한 큰 경계,다만 집계+DML은 한 문장 |
| 이동의 정밀도2 실패 | 이전좌표1·2·3 이후 새좌표 순서에 따라 부분 반영 | 이전/새 정밀도1은 함께 커밋,정밀도2의 두 셀은 함께 롤백,3 미실행 |
| 정밀도3까지 성공 | 각 호출별시각·중복 갱신 | 같은 정밀도 affected-cell 동일 시각·중복 없음 |

이동 순서·롤백 범위·updatedAt 횟수가 바뀌므로 **E-6 B 개선 차이로 명시**한다. 이전 실패 fixture를 덮어쓰지 않는다. 시간 필드를 통째로 무시하지 말고 고정 Clock·호출 횟수/실패 후 셀 상태를 검사한다.

### 3.3 E-6 B가 보장하지 않는 것

한 문장의 집계와 DML을 묶어도 동시 writer의 최종 최신 집계를 보장하지 않는다. Read Committed의 집계 snapshot 뒤 다른 장소 커밋이 생기고 UPSERT가 락을 기다리면 오래된 값을 나중에 쓸 수 있다. `count=0` 삭제와 다른 요청의 양수 집계 경쟁도 남는다. 새 셀에는 기존 행 락 대상조차 없을 수 있다. **전체 지도방 정합성·자연 복구·즉시 일관성을 주장하지 않는다.** 이는 E-6 C가 아닌 승인된 B의 한계다. [PostgreSQL Read Committed/ON CONFLICT](https://www.postgresql.org/docs/17/transaction-iso.html#XACT-READ-COMMITTED).

성능 구간 밖에서 DB gate로 snapshot→대기→다른 커밋→역전 완료를 양방향 강제한다. 생성/이동/삭제,같은·겹친·다른 셀,N=2/4/8/16의 stale count·누락·남은 셀·deadlock·오류 수를 보고한다. 정밀도별 atomicity는 필수0위반,전체 최신 snapshot과의 차이는 별도 잔존 한계로 공개한다. 새 장애/교착이 생기면 채택을 멈추고 보고한다. 조용한 재시도·정리 배치·map 락·버전 열을 추가하지 않는다. 이를 모두 해결해야 한다면 별도 E-번호로 E-6 C 범위를 승인받는다.

## 4. 후보 ROUND를 실제 반환 경로로 미루기(E-19)

권장 A는 SQL 한 문장을 유지한다. viewport 조건·`createdAt DESC,id DESC LIMIT301`로 **ROUND 없는 후보 CTE만 최대301행 물질화**하고,그 수가≤300인 분기의 바깥 projection에서만 PG ROUND6 키를 계산한다. 301개인 분기는 키를 계산하지 않고 현행 셀 fallback으로 간다. count 확인과 후보가 같은 snapshot이며 추가 SQL/두 시점 조회를 만들지 않는다.

개념 흐름은 `rawCandidates(≤301) → cardinality → CASE/분기 projection → individual 또는 cells`다. 실제 SQL은 승인 후 작성한다. ROUND를 내부 CTE에 넣거나 불필요한 branch가 평가되면 실패다. PostgreSQL CASE의 실행 조건을 사용하되 optimizer를 가정으로 믿지 않고 EXPLAIN VERBOSE·별도 테스트용 호출 계측으로301에서 ROUND 호출0,≤300에서 필요 행만 계산되는지 확인한다. [CASE 평가](https://www.postgresql.org/docs/17/functions-conditional.html#FUNCTIONS-CASE).

Java `BigDecimal(double)`로 PG float8→numeric 변환을 흉내 내지 않는다. PG ROUND 키·0 정규화·개별 응답 순서와 그룹 내 합산 순서는 유지한다. 원본 좌표31경계와0/1/299/300/301건,동일 ROUND6 그룹·물리 행 재배치를 검사한다.

## 5. DB에서699개 선택,앱은 최대700셀 결과(E-18)

### 5.1 응답 순서가 계약인 근거

현재 [MarkerCoordinateIntegrationTest](src/test/java/com/loco/places/MarkerCoordinateIntegrationTest.java)는699/700/701셀 배열을 정확한 순서로 검사한다. Flutter [places_service](../mobile/lib/features/places/services/places_service.dart)는 응답 순서를 그대로 list로 만들고,[map_detail_screen](../mobile/lib/features/maps/screens/map_detail_screen.dart)은 그 순서를 넘긴다. [kakao_map_widget](../mobile/lib/shared/widgets/kakao_map_widget.dart)는 배열순으로 canvas를 그리고 hitBoxes를 역순 검사한다. **순서 변경은 겹친 마커의 표시·클릭 우선순위에도 영향 가능**하다. 이번에는 코드를 읽어 확인했으며 실제 Kakao 호출/화면 실험은 하지 않았다.

### 5.2 권장 A: 작은 결과는 Java 정렬,큰 결과는 DB top699

한 SQL snapshot에서 다음을 수행한다.

1. 기존 mapId·precision·셀 bounding-box 교차 조건을 그대로 적용한 `matching`을 정의한다. centroid가 viewport 안인지로 바꾸지 않는다.
2. 정렬 없는 `probe LIMIT700`만 작은 CTE로 물질화한다. probe≤699면 그것이 전체 일치 셀이다. 추가 전체 정렬·overflow 집계는 실행하지 않는다.
3. probe=700이면 전체 matching에서 **`count DESC,cellLatKey COLLATE C ASC,cellLngKey COLLATE C ASC LIMIT699`**로 visible을 선택한다. 동률 선택은 E-12 그대로다.
4. 같은 snapshot의 matching에서 visible의 복합 키를 제외한 나머지만 aggregate해 overflow1행을 만든다. 전체합−visible합 방식의 상쇄 오차를 피한다. 표시699와 잔여 count/가중 중심이 같은 셀 집합을 사용한다. `SUM(count)`는 bigint,분모0·빈 집합을 명시 처리한다.
5. DB 결과는 visible≤699+overflow≤1행. Java에서 visible만 기존 비교자 순서로 정렬하고 overflow는 마지막에 붙인다. 작은 결과의 DB 2차 정렬 비용을 **제거하는 대신 최대699행 Java 정렬로 이동**한다. ASCII 숫자/부호/점으로 구성된 정규 셀 키에서 C 순서와 Java 비교자 일치를 테스트한다. 효과는 측정으로 판단한다.

`matching` 전체의 MATERIALIZED/array_agg/json_agg/Window 전체행 반환은 피한다. 재사용 CTE는 NOT MATERIALIZED와 실제 plan을 확인하고,작은 probe/visible만 제한해서 물질화한다. DB가 전체 matching을 scan하거나 bounded top-N sort·aggregate·temp spill하는 비용은 여전히 존재한다. [CTE 물질화·중복 스캔](https://www.postgresql.org/docs/17/queries-with.html#QUERIES-WITH-CTE-MATERIALIZATION).

앱이 읽는 후보≤301+셀≤700,합계≤1,001 DB행으로 viewport 셀 수 N에 대한 상한을 고정한다. Map/list 변환의 상수 배수는 별도 계측한다. 전체 셀을 먼저 읽고 Java stream.limit를 적용하거나 DB가 거대한 JSON1행을 반환하는 것은 이 상한의 통과로 인정하지 않는다. 출력 name/필드 길이도 기존 제한을 유지한다. fallback HTTP SQL4/direct-cells3 목표는 유지한다.

## 6. DB로 계산을 옮길 때의 수치 계약(E-20, 새 결정)

단순한 SQL 합치기로 응답·DB 값이 완전히 같아진다고 가정할 수 없다.

- 현재 CRUD는 AVG/MIN/MAX를 Java로 받아 `PrismaFloats.input`(정확한 double→유효16자리 HALF_EVEN)으로 다시 바인딩한다. V7 backfill·테스트 대량 seed는 이미 PG AVG를 직접 저장한다. 직접 INSERT SELECT는 CRUD의 중간 왕복 반올림을 없앤다.
- overflow는 현재 각 셀 좌표를 PrismaFloats로 읽어 Java double 순차 곱·합산한다. PG SUM으로 바꾸면 입력 정규화와 합산 순서가 달라질 수 있다. 단순 numeric ROUND는 이 변환의 정확한 대체가 아니다.
- E-20 A 권장 제안: 원본 places 입력·viewport 입력 변환과 PG ROUND 키는 유지하고,**파생 셀 중심/overflow 중심만 PG 집계 기준**으로 통일한다. 셀 count·키·extents·ID·멤버십·배열 순서는 정확 비교한다. 중심만 기존 P1 대비 절대오차≤1e-10도라는 필드별 경계를 제안한다(약0.011mm 위도 환산,기능 정밀도 목표가 아닌 호환 허용치). 부호 혼합·극단/경계·M 전체·다중 count를 독립 고정밀 oracle로 확인한다. NaN/Infinity·범위 밖·count 불일치는 예외가 아니다. PG numeric 가중합으로 중심을 구하는 안과 float8 합은 구현 전 계약에 명시하고,권장안은 **셀 AVG(float8),overflow SUM(latitude::numeric*count)/SUM(count) 후 float8**이다.
- A이면 기존 live CRUD 셀의 혼합 표현을 없애도록 **다음 가용 Flyway(V9 예상)**에서 파생 셀만 재생성하는 계획을 포함한다. 원본 places/이미지·V1~V8은 불변,writer 일시중지·역호환 쓰기 금지·원본행 hash·전후 oracle·실패 롤백을 검사한다. 메모리 때문에 모든 셀을 Java로 옮기는 backfill은 금지한다.
- B 대안: DB 안에서 기존 PrismaFloats 및 정렬된 double 누산을 정확 재현하는 SQL 함수/aggregate. 더 엄격한 값 호환이나 binary64→16자리 HALF_EVEN·누산 순서·이식/유지 비용이 크고 P2 속도 목표를 방해할 수 있다. 함수도 migration 대상이다.

새 수치 차이는 별도 알려진 **의도된 차이**,커밋 원자성 변경은 **개선 차이**로 등록한다. 중앙값 차이를 가리려고 전역 JSON 허용 오차/응답 무시 규칙을 도입하지 않는다. extents·필터 멤버십까지 달라지거나 오차 경계를 넘으면 E-20을 임의 확장하지 않고 멈춘다. [PG float/numeric 차이](https://www.postgresql.org/docs/17/datatype-numeric.html#DATATYPE-FLOAT),[집계 자료형](https://www.postgresql.org/docs/17/functions-aggregate.html).

## 7. 필수 검증과 성공 기준

| 구분 | 필수 검사·통과 조건 |
| --- | --- |
| 갱신 SQL | 생성/일반/no-op/동일셀·다른셀 이동/삭제,작은방/hot방. E-17 선택에 따른6/12→상한 고정. PG statement·scan/loops/시간·WAL/인덱스 유지 비용도 기록 |
| 정합성 | 실제 HTTP R 경계31개·precision1/2/3·음수0·300/301·698/699/700/701,가중 count/중심·빈/역전 bounds·태그·권한. standalone oracle·P1·v2 원본을 구분,예외 외 모든 필드/순서 정확 비교 |
| 장애/경합 | 정밀도1/2/3의 집계/DML/commit 실패,이전·새 셀 중 한 쪽 실패. 장소 커밋/셀 단위 원자성/알림0회/이미지 잔존 확인. DatabaseRequestRace와 test-only DB gate로 §3.3 잔존 경합도 숫자로 보고 |
| snapshot | probe/visible/overflow 사이 외부 변경을 강제해 같은 문장 snapshot인지 검증. count합·표시/잔여 교집합0·누락0. 동시에 갱신 중인 정밀도간 최신성까지 보장한다고 주장하지 않음 |
| 메모리 | JDBC 수신행·응답 cardinality·필드 크기를 고정. 같은 동시성에서1%/10%/전체,앱 할당bytes/request·live heap·working set·GC·DB sort/temp·RSS 측정. heap 상한으로 RSS가 평평한 것만으로 합격 불가 |
| 전체 viewport | 기본 heap371.25MiB 유지. M hot50,000장소 전체,16req/s·120초 예열+300초3회 제안. **OOM0**·오류/dropped0,DB/호스트도 OOM0. 앱의 물질화 상한≤1,001행 및 메모리 비례 증가 없음. 전체 viewport의 p95 200ms는 탐색값,이번20% 판정 대상은10% |
| 10% 주 시나리오 | M편향·hot80%,같은 세션 P1+E-14 대 P2. **같은 세션 A1 대비16req/s p95≥20% 감소(E-21 A)**. 고정 자원·오류0·dropped0·p95≤200ms,반복 편차보다 개선폭이 큼 |
| 1% 회복(E-16) | M편향·hot80%·50req/s,**P0 원래 JAR 대 P2,같은 세션 AB/BA5쌍 이상**,p95·p99 각각≤1.10×P0 중앙값. P1/E14를 대조군으로 대체 금지 |
| 다른 경로 | group/cluster V8 Index Scan·generic/custom 유지. 승인된 수치 차이 외 응답 동일. 소량 individual 경로·쓰기 지연의 회귀10% 기준도 유지(이전 공통 E-10),미달이면 원인 보고·채택 보류 |
| 전체 회귀 | 호스트 코드/SQL 변경이므로 core+root+v2 allTests build·Spotless,Node 도구 및 실제 normal/perf JAR 격리. 기존13 알려진 차이 보존,추가 차이는 ID·근거·필수 v3 검사와 함께 보고 |

SQL 같은 문장 내 DELETE/UPSERT가 같은 행을 중복 수정하지 않는 것을 affected key 조합으로 검사한다. 실행 순서를 CTE 텍스트 순서로 가정하지 않는다. [PG data-modifying CTE 제약](https://www.postgresql.org/docs/17/queries-with.html#QUERIES-WITH-MODIFYING).

## 8. 측정 계약·비교군·예상 시간

### 8.1 비교군과 효과 분리

- A0: c7320e6 측정 당시 P0 원래 JAR,sha256 `c1a5293f1dc59a6ee0e648548e92695857a0357c57d10e45f06704a96952158f`. 재빌드/패치 금지.1% 회복의 대조군.
- A1: E-14 적용 P1 JAR,sha256 `da87c44168c321d1b05c5d3bbf61a459284c937fdd145c93aa6e940fff1d1a60`.10% 개선·쓰기의 권장 대조군. 역사적116.016ms×0.8=92.813ms는 예상치이며 실제 판정은 같은 세션 A1로 다시 계산한다.
- B: 최종 P2 JAR. 쓰기 결합만/W,조회만/R,통합/B를 별도 커밋으로 두고 각 부모와 짧은 진단으로 효과를 구분한다. **채택은 최종 B의 위 두 대조**로만 한다. W만으로 markers 읽기 개선을 주장하지 않는다. 진단용 W/R을 모두5회 주 행렬로 늘리지 않는다.
- 각 이미지별 새 DB/볼륨·같은 R+S+M seed와 원본 places hash. migration별 파생 셀 hash가 다르면 승인된 필드 차이와 키/count oracle로 설명한다. SQL tracing/profiler는 본 성능 실행에서 끈다. 원래 P0의 E-12 비결정적 동률만 기존 검증 예외를 유지하고 P2와 A1의 정확 멤버십은 검사한다.

### 8.2 권장 E-21 A: 지연 개선으로 먼저 판정

1% A0/B50req/s와10% A1/B16req/s 각각 AB/BA5쌍,매 측정 JVM 재기동·동일 preflight·120초 예열+300초 본 측정. P1/JAR와P0/JAR를 같은 A 이름으로 혼동하지 않게 manifest에 A0/A1/B·해시·pair를 넣는다. 각 지표는 회차 percentile의 중앙값·회차별 회귀·range/median·n을 모두 남긴다. A1의10% 편차45.33% 이력이 있으므로 새 세션에서도10% 초과 편차이면 승자를 선언하지 않는다. 임의로 낮은 회차를 버리거나 통과할 때까지 재시도하지 않는다.

지연 경로가 실패한 뒤 처리량으로 조용히 평가 방식을 바꾸지 않는다. 처리량 경로를 고르면(E-21 B) A1/B 각각의 최대 지속 처리량부터 새로 탐색한다. 기존16req/s는 최대값이 아니다. 60초 정수 도착률 탐색→후보120+300초×5회→바로 위 도착률300초,최고값 정의·오류/dropped0·p95≤200ms는 기존 E-10 그대로다. 탐색 상한·총 시간은 B 실행 전에 고정한다.10% 회차당약4,800표본에서 p99가 불안정하면 탐색값으로 표시하고 필요한 반복을 추가해 문구로 숨기지 않는다.

### 8.3 실행 전 예상 시간(승인 A 기준, 새 환경 확인 전 실행 금지)

| 항목 | 산정 | 예상 |
| --- | --- | --- |
| 두 주 비교 | 2조건×5쌍×2버전×(120+300초) | 순수 부하140분,기동·preflight 포함 약160~175분(E-15 실측161.5분 참고) |
| 전체 viewport | B3회×420초 | 21분+기동/검증 약3~6분 |
| 나머지 탐색 | S/M·균등/편향·0.01/1/10/전체·균등요청의 사전 고정 8개 대표 조합,각1회120+60초 | 24분+준비. 채택 근거로 쓰지 않음 |
| 쓰기 비용 | 각쪽 CRUD10예열+30측정×5쌍,기존 단일VU 도구·SQL trace 분리 | 기존 단위시간으로 약4~8분+seed복구. 동시1/2/4/8/16은 별도 짧은 진단·경합 검사 |
| 회귀·oracle·진단 | allTests build 약19분 기준,SQL/메모리/경합 조사 추가 | 약35~55분 |
| 합계 | 구현·문서 작성 제외 | **약250~295분**. 환경/DB준비·새 실패 원인조사는 별도 기록 |

탐색8개 제안은 (데이터/분포/요청/viewport) 기준 S/편향/hot80/1%,S/균등/균등/1%,M/편향/hot80/0.01%,M/편향/hot80/전체,M/균등/hot80/1%,M/균등/hot80/10%,M/편향/균등/1%,M/편향/균등/10%다. 주 판정/전체 스트레스와 별도 탐색이며 실행 전에 scenario ID·seed·실제행수로 고정한다. 유리한 조합만 사후 선택하지 않는다. B안 처리량 선택 시 추가 후보 수에 따라 `420×5×추가 후보/60`분과 탐색시간을 재계산한다. 전원 연결·최고 성능·WSL 할당·무거운 앱 종료/측정 중 미사용은 **새 세션 시작 때 다시 확인**하고 manifest에 남긴다. 자원·heap·Hikari·k6·PG/work_mem을 바꾸지 않는다. Windows TCP4227/4231·수집 간격·배경 컨테이너도 기록한다. 노트북 상대 비교이며 운영 절대 수치가 아니다.

## 9. 선택지 원문 — E-17~E-21 A 승인 완료

| 번호·항목 이름 | A | B | C |
| --- | --- | --- | --- |
| **E-17 셀 갱신 결합·커밋 단위** | **정밀도별 이전/새 셀 결합,3문장·3커밋(권장)**.6/12→3,중복 제거와 제한된 롤백 / 이동 순서·부분 상태 변경 | affected 최대6셀 전체1문장·1커밋. 왕복 최소·셀 묶음 atomic / 모든 정밀도 롤백으로 v2 차이 큼·SQL 복잡 | 셀별 집계+DML1문장,최대3/6커밋. 기존 old→new 순서에 가까움 / 왕복·중복 제거 효과 작음 |
| **E-18 소량 정렬·DB699개 선택** | **≤699는 Java 정렬,≥700은 DB top699+overflow,한 snapshot(권장)**.배열·클릭 순서 유지,작은 DB정렬 제거 / bounded probe·분기 SQL 복잡 | 항상 DB 결정적 정렬+LIMIT699·overflow. 구조 단순·앱정렬 없음 /1% 원인2의 비용이 남아 회복 검증 필요 | ≤699는 동률 순서 미보장. 정렬 비용 최소 /응답 계약·Flutter 겹침/클릭 우선순위 변경,별도 의도 차이 |
| **E-19 ROUND projection 지연 방식** | **최대301 후보 CTE 후≤300 분기에서만 PG ROUND(권장)**.추가 왕복 없이 같은 snapshot /plan·미실행 분기 계측 필요 | raw probe 후≤300일 때 두 번째 PG조회.흐름 단순 /작은 조회 SQL+1·중간 변경,같은 snapshot을 위한 transaction 검토 필요 | — |
| **E-20 파생 중심의 수치 계약** | **PG 집계 기준 통일·중심만≤1e-10도 예외·파생 셀 재생성(권장)**.SQL 결합·bounded DB집계 가능,V7과 같은 DB 직접 집계 /미세 응답·DB 중심 변화·migration·알려진 차이 추가 | 기존 Java/Prisma 변환·double 누산을 DB 함수로 정확 재현.엄격 값 호환 /함수·aggregate 복잡도와 비용,구현 전 추가 검증 필요 | — |
| **E-21 10% 채택 측정 경로** | **같은 세션 A1(P1+E14) 대비16req/s p95≥20% 감소 우선(권장)**.고정된20회 주 행렬로 확인 /최대 처리량 향상은 결론내리지 못함 | 같은 A1 대비 최대 지속 처리량≥20% 증가.용량 효과 확인 /기준 최대값 미확정,탐색시간 증가·사전 상한 필요 | — |

E-16의1% 원래P0 대비 회귀≤10%,전체 OOM0,오류/dropped0은 어느 선택도 면제하지 못한다. 권장 조합은 **E-17 A / E-18 A / E-19 A / E-20 A / E-21 A**다. E-20을 승인하지 않은 상태에서 부동소수 비교를 완화하지 않는다.

## 10. 승인 후 순서와 중단 기준

1. 선택 기록·알려진 차이 계약·측정 시나리오/예산 고정. 기존 테스트/원본 JAR 보존.
2. 쓰기 W: affected-cell·SQL 결합·선택한 커밋 경계·수치/backfill. 실제 오류·경합·SQL 수·기존 비교 확인 후 커밋·push.
3. 읽기 R: 후보 ROUND지연→bounded probe/DB top699/overflow→정렬. 순서·수치 oracle·JDBC행 상한·SQL EXPLAIN 확인 후 커밋·push.
4. 통합 B: allTests build·도구/JAR검사. 승인한 차이와 잔존 경합·실패 수를 모두 기록.
5. 새 사용자 환경 확인 후 주 비교/전체 viewport/쓰기·탐색·분리 진단. 각 실행 전 예상 총 시간 기록. 정합성 실패·OOM·1%회귀>10%·10%개선 미달·큰 편차는 원인 보고 후 채택 보류. 평가 기준/힙/부하/예외를 사후 변경하지 않는다.
6. 결과·manifest·압축 원본 Release·노션 WWHO에 같은 SHA 연결,성공/실패/알려진 차이/시간을 커밋 본문에 남기고 **P2 결과에서 멈춤**. P3 실행은 별도 확인 후 진행한다.

최초 설계 커밋82fbe63은 Markdown만 검증했다. 아래부터 승인 후 구현 기록이다. 성능 측정은 전체 구현·검증 push와 새 환경 확인 전에는 실행하지 않는다.

## 11. 구현 기록

**af592b4 당시 중단 기록:** W/R 집중 검증 후 교차 이동에서 새 deadlock을 재현했다. E-22 선택 전에는 전체 allTests build·정식 성능 측정을 진행하지 않았다. 실패를 알려진 차이로 승인하거나 테스트를 skip하지 않았다. 이후 확정 내용은 §12이며 E-16/E-21·힙·부하는 그대로다.

### W — 정밀도별 SQL 결합·V9

집계와 양수 UPSERT/빈 셀 DELETE를 정밀도당 한 SQL로 결합했다. 기존 호출부는 이전/새 좌표를 한 번에 전달한다. 갱신 SQL6/12→3,실제 HTTP 생성7/8·수정6/7·삭제7을 고정했다. 이동 정밀도2 실패는 정밀도1의 이전/새 상태만 커밋,2의 양쪽 셀 모두 이전 상태이며 장소 변경은 남는다. 중복 셀1회·동일 정밀도 timestamp1개를 트리거 관측으로 확인했다.

V7/V9 각각 재생성·원본 불변·실패 rollback·재실행0 검사를 통과했다. E-20의 실제 중심 차이(127.8888 대127.88879999999999)를 재현했으며 중심만≤1e-10으로 비교한다. count/키/extent 변경과 허용치를 넘는 중심을 거절하는 검사가 있다. `markers.precision-commit` 개선·`markers.pg-derived-centers` 의도 차이2건을 등록했다. 전체 알려진 차이 수는 최종 allTests에서 재확인한다.

집중 검증: migration4·쓰기 정합성6·v2 비교100으로 고유110건. 첫 실행94.913초는 옛 중심 완전 일치 검사16건 실패,중심 전용 계약 반영 후101.445초 실행은109성공/1실패였다. 남은1건은 거절 검사에서 count를 원래 값1로 넣은 테스트 오류였고2로 수정해 해당 클래스2건을 재실행·통과했다. 전체 검증은 후속 통합 단계에서 실행한다.

[DB gate 원본](../../perf/stage2/results/p2-implementation/write-race.json): N=2/4/8/16 각각 HTTP 성공N·오류0,최종 정밀도별 count `[1,N,N]`,stale셀1. 먼저 집계한 요청을 나중에 기록하도록 강제한 **E-6 B 잔존 경합**이다. SQL 결합을 동시 writer 직렬화로 포장하지 않는다. 성능 수치가 아닌 정합성 진단이다.

### R — ROUND 지연·bounded 조회·snapshot

`MarkerQueryRepository`의 후보301행 CTE는 실제 개별 응답인≤300일 때만 ROUND6 키를 계산한다. 시험용 PG 함수로 실제 HTTP SQL의 projection 호출을 세어300후보=600회,301후보=0회를 확인했다. 기본/동률/경계 배열 순서와 group·cluster의 V8 인덱스 경로를 유지한다.

셀 SQL은 probe700·visible699·overflow1로 제한한다. Java 정렬 대상도≤699다. 0/698/699/700/701/50,000셀에서 실제 HTTP SQL을 JDBC로 다시 실행해 최대700행,표시 집합·순서·count·고정밀 가중 중심을 검사했다. 조회 probe에 test-only DB gate를 걸고 다른 연결에서 count를 변경해 진행 중 응답은 변경 전 snapshot,다음 문장은 변경 후 값임을 확인했다.

초기 잔여 제외 `NOT EXISTS`는 통계가 오래된 테스트 DB에서 Nested Loop Anti(50,000×visible)로34,705,350행을 비교했다. 양쪽 셀 키의 NOT NULL을 근거로 tuple `NOT IN`으로 바꿔699키 hashed subplan을 사용한다. [실제 SQL·EXPLAIN](../../perf/stage2/results/p2-implementation/bounded-50000.json)은 JDBC700행·잔여49,301행·temporary block0이다. 2,944.665ms→66.220ms는 서로 다른 테스트 실행의 단회 진단으로,같은 세션 성능 채택 수치가 아니다.

집중 회귀: root48·v2 비교147,총195성공/실패0/skip0,120.609초. 비교 부분의 알려진 차이는 기존 좌표·동률2건이며,E-20 중심 허용은 중심 필드에만 적용하고 다른 모든 값·순서는 정확 비교한다. 첫 조회 검사에서 중심 완전 일치4건이 실패한 기록과 수정 후 실행은 build 로그에 보존했다. 전체 allTests·기본 힙 부하·E-16/E-21 채택 검증은 통합 B 이후다.

### 교차 이동 — 신규 deadlock으로 중단

`MarkerCrossMoveIntegrationTest`는 실제 HTTP로 두 장소를 셀A(37.0)↔셀B(38.0)로 교차 이동시킨다. test-only BEFORE trigger/advisory gate는 각 요청이 첫 셀의 행 잠금을 가진 시점에만 멈춘다. 업무 값이나 운영 SQL은 바꾸지 않는다. v2는 이전 셀을 먼저 개별 커밋하므로 첫 DELETE/UPDATE에서, P2는 결합 SQL의 첫 UPSERT에서 멈춘다. 서로 다른 커밋 구조의 가능한 실행 순서를 고정한 검사이며 자연 발생 확률 측정이 아니다.

| 실행 | HTTP 결과 | 테스트 결과·시간 | 최종 정밀도1 |
| --- | --- | --- | --- |
| P2 최초 v3 단독 | 500/200, SQLState40P01 | 1건 실패,17.426초 | A1/B1(후속 성공 요청으로 맞음) |
| v2 대조 | 200/200 | 아래 비교 실행 중1건 성공 | A1/B1 |
| P2 재현 | 200/500, SQLState40P01 | 비교2건 중1성공/1실패,20.354초 | A셀 누락/Bcount2(실제 장소는 양쪽1개) |

[v2 응답·셀](../../perf/stage2/results/p2-implementation/p2-cross-move-v2.json),[P2 응답·셀](../../perf/stage2/results/p2-implementation/p2-cross-move-v3.json). 두 P2 실행 모두 교착1건이며 PostgreSQL이 중단하는 요청은 달랐다. 실패한 정밀도 트랜잭션은 롤백되지만 이미 커밋된 장소까지 되돌아가지는 않는다. 성공 요청의 오래된 snapshot이 남는 경우도 함께 보였다. 기존 N=2/4/8/16 stale-count 한계와 달리 **새 HTTP500은 승인되지 않았다**.

원인: 요청1은 UPSERT B 후 DELETE A, 요청2는 UPSERT A 후 UPSERT B를 진행해 서로의 행 잠금을 기다린다. UPSERT의 `ORDER BY`는 별도 DELETE CTE까지 잠금 순서를 통일하지 않는다. DML CTE의 텍스트 순서를 실행 순서로 보장할 수 없다는 [PG17 WITH 문서](https://www.postgresql.org/docs/17/queries-with.html#QUERIES-WITH-MODIFYING)와 부합한다. [교착 방지 지침](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-DEADLOCKS)은 전체 대상의 일관된 잠금 순서를 요구한다.

### E-22 셀 갱신 교착 방지 — 선택 전 제안 이력

E-17은 SQL·커밋 수를 승인했지만 새 잠금 규약/범위는 정하지 않았다. §3.3의 “새 장애/교착이면 중단”에 따라 아래를 제안만 하며 구현하지 않는다.

| 선택 | 방법 | 장점 | 단점·한계 |
| --- | --- | --- | --- |
| **A (권장)** | 정밀도별 이전/새 **셀 키별 transaction advisory lock**을 DML 전에 같은 전역 순서로 획득. 없는 셀 키도 포함. 결합 SQL 안에 잠금 완료 의존성을 명시 | E-17의3문장/3커밋 유지 목표. 겹치지 않는 셀은 병렬 처리 | 최대2잠금/정밀도·정렬/해시/CTE 평가 순서 검증 필요. 잠금 ID를 먼저 중복 제거·전역 정렬해 해시 충돌도 순서를 뒤집지 않게 설계. 다른 writer가 규약을 우회하면 보장 불가 |
| B | **지도방+정밀도별 transaction advisory lock** 하나를 결합 SQL 앞단에서 획득 | 구조가 단순하며 새 셀도 보호.3문장/3커밋 유지 목표 | 같은 지도방·정밀도 쓰기가 모두 직렬화되어 hot방 처리량 저하 가능 |
| C | E-17을 변경해 **셀별 집계+DML1문장·셀별 커밋**으로 분리 | 다중 셀을 동시에 잠그는 원인을 제거하고 v2 부분 커밋에 가까움 | 이동 최대6문장/6커밋으로3/3 목표와 이전·새 셀 원자성 철회 필요 |

A/B의 advisory lock은 명시적 규약이다. v2 또는 우회 SQL과 이미지 쓰기처럼 섞어 실행해서는 이 보장을 주장할 수 없다. transaction 종료 시 해제되는 잠금을 쓰며 session 잠금·자동 재시도·새 테이블은 도입하지 않는다. **같은 문장 snapshot은 잠금을 기다린 뒤에도 새로 생기지 않으므로 오래된 집계 문제까지 해결한다고 주장하지 않는다.** 최신 snapshot 보장까지 확대하려면 잠금 후 별도 문장/커밋 범위의 새 선택이 필요하다. [transaction advisory lock·평가 순서 주의](https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS).

선택 후: gate를 새 잠금의 앞/뒤에 맞춰 대기·해제 순서까지 검증하고 HTTP2성공·deadlock0으로 고정한다. 현재 실패를 없애려 gate 대기를 timeout/skip으로 바꾸지 않는다. 없는 셀·겹친/다른 셀·생성/이동/삭제·N=2/4/8/16·장애/commit 실패·stale 한계를 재확인하고 allTests build를 실행한다. 그 뒤 커밋·push하고 새 환경 확인을 요청한 후에만 고정된 E-16/E-21 측정을 시작한다.

## 12. E-22 B 변형 확정 — 별도 잠금 문장과 최신 집계

사용자 결정(2026-11-09): **지도방+정밀도별 transaction advisory lock을 DML과 별도 문장으로 먼저 획득한다.** `MarkerCells` 전용 TransactionTemplate의 격리 수준을 READ_COMMITTED로 지정하고 공유 template의 설정은 바꾸지 않는다.

각 정밀도에서 (1) `SELECT 1 FROM pg_advisory_xact_lock(:mapKey,:precision)` 완료 (2) 이전/새 셀 결합 DML (3) commit 순서다. SELECT의1은 JPA가 PostgreSQL void 반환 타입을 매핑하지 않아도 되도록 한 것이며 잠금 호출은 동일하다. **6SQL·3커밋**으로,이동 갱신은 원래12→6,단일 좌표는 원래6과 동일하다. round trip 최소화보다 교착 방지와 새 snapshot을 우선한 선택이다. 장소 쓰기는 여전히 앞서 별도 커밋된다.

지도방 키는 `UUID.fromString(mapId).hashCode()`의 결정적인32비트 값,두 번째 키는 정밀도1/2/3이다. 해시 충돌은 같은 정밀도의 관계없는 방을 과직렬화할 수 있지만 DML의 실제 UUID 조건은 유지하므로 데이터를 섞지 않는다. 트랜잭션당 advisory lock1개이며 commit/rollback에 해제된다. low-frequency 사람의 장소 쓰기에는 셀별 정렬 잠금·해시 충돌 순서·CTE 평가 제어의 복잡도를 정당화할 처리량 요구가 없다는 것이 선택 근거다.

READ COMMITTED는 문장마다 snapshot을 정하므로 잠금 대기가 끝난 **다음 DML**이 이전 갱신 트랜잭션과 그 전 장소 커밋을 본다. 모든 참여 writer가 성공적으로 끝난 뒤 각 정밀도의 마지막 집계가 최신 장소를 반영하는지를 검사한다. 잠금 획득 후에도 장소 커밋은 진행될 수 있으므로 진행 중 순간의 전체 지도방 일관성·정밀도간 원자성을 주장하지 않는다. 중간 실패로 셀 갱신까지 끝내지 못한 장소의 부분 커밋 역시 남는다. [PG17 READ COMMITTED](https://www.postgresql.org/docs/17/transaction-iso.html#XACT-READ-COMMITTED).

**전환 조건:** v2 writer·구버전 v3·직접 SQL은 이 잠금 규약을 따르지 않는다. 같은 DB의 장소/셀 쓰기에 섞으면 교착 방지·최종 집계 보장이 깨진다. 기존 writer 중지→V9 파생 셀 재생성→새 v3만 쓰기 재개 순서를 따른다. 스키마·V1~V8·외부 API·Flutter는 변경하지 않는다.

### 검증과 측정 범위

- 기존 cross-move 실패 테스트는 v2 200/200을 보존하고 v3의 두 번째 요청이 **잠금 SELECT에서 대기**하는지 확인한 뒤 gate를 해제한다. HTTP200/200,양쪽 셀 count1을 요구한다. 실패 assertion을 반전하거나 timeout으로 통과시키지 않는다.
- 같은 셀/없는 셀 동시 생성 N=2/4/8/16: 첫 집계 snapshot 뒤 나머지 장소 커밋·잠금 대기를 강제한다. 예전 `[1,N,N]` 대신 `[N,N,N]`,stale0을 요구한다. N16에서는 풀10의 제약 때문에 최소8대기 확인 후 gate를 열고 모든16요청 완료 후 집계를 검사한다.
- 이동·삭제·생성/삭제 혼합 N=2/4/8/16은 실제 동시 HTTP 완료 후 places에서 새로 만든 PG 집계와 셀 키·count를 전수 대조한다. 누락/고아/stale0을 요구한다. 이 기능 테스트의 시간은 성능 채택 수치가 아니다.
- 정밀도1/2/3의 deferred constraint trigger로 commit 실패를 강제한다. 장소는 남고 이전 정밀도만 커밋,실패 정밀도의 이전/새 셀은 함께 롤백,별도 연결에서 잠금 재획득 가능을 확인한다. 기존 DML 실패·알림0·이미지 파일 유지 검사도 전체 회귀에 포함한다.
- HTTP SQL 상한: 생성10/11(공개/회원 확인 필요),수정9/10(no-op/변경·이동),삭제10. 삭제의 정밀도p 실패는4+2p. 조회는 변하지 않는다.
- 알려진 개선 차이 `markers.refresh-serialization`: 실제 v2 동시 생성 `[1,2,2]`,v3 `[2,2,2]`를 보고한다. 기존13차이+E17/E20+E22=16건 예상이며 전체 회귀 결과로 확정한다.

집중 실행: root21·비교92,113성공/실패0(94.494초). 별도 실제 v2/v3 stale 대조1성공(20.961초).

첫 전체 실행은1,045.089초에 종료했다. root449건(448성공·HEIC1건skip),비교1,158건(1,156성공·2실패),알려진 차이16건이다. 실패2건은 overflow701과level6 경계의 옛 중심 완전 일치 검사였다. 운영 계산을 바꾸거나 허용치를 늘리지 않고,승인된 E-20의 중심 전용≤1e-10 계약을 해당 검사에 적용했다. 공통 비교기는 count·키·표시 집합·순서·그 밖의 필드는 완전 일치를 유지한다. 허용치 초과·NaN·Infinity·다른 타입·순서 변경 거절과 V9의 실제 중심 재생성을 포함한 집중19건(root6·비교13)이34초에 통과했다. 수정 후 전체 실행 결과는 아래에 기록한다.

최종 `allTests build perfBootJar`: **1,032.436초(17분12초),성공**. root451건=450성공/실패0/기존 HEIC skip1,비교1,158건=전부성공,변경 없는 core125건은 UP-TO-DATE다. 전체 결과 집계1,734건=1,733성공/실패0/skip1이며,이번에 새로 실행한 범위는1,609건이다. 알려진 차이는 **16건(개선10·의도6·보류0)**,v2 이미지 빌드1회·기동1회·재시작17회·인증 대기0ms다. Node25건은0.130초에 통과했고,실제 JAR의 잘못된 profile 거절·일반 JAR의 perf 코드 제외4검사는3.467초에 통과했다. B 이미지 준비 후 기존 A0/A1 이미지 ID가 그대로임도 확인했다. `k6 inspect`의 N1/2/4/8/16·대표 arrival-rate·별도 단일VU 메모리 진단 설정 검사는 부하를 보내지 않는다.

[E-22 검증 원본](../../perf/stage2/results/p2-implementation/e22-verification.json)은 테스트 건수·시간·알려진 차이 목록과18개 HTTP/DB 관측을 모은다. 교차 이동 v2/v3 각각200/200,새 deadlock0; N2/4/8/16 생성은각 `[N,N,N]`·stale0이며 이동/삭제/혼합12조합도 stale/누락/고아셀0이다. 정밀도별 commit 실패3건에서 양쪽 셀 rollback·이전 정밀도 commit·잠금 해제를 확인했다. **정식 A/B·쓰기 직렬화 비용·넓은 viewport OOM/메모리 측정은 아직 실행하지 않았다.** 이 기록을 성능 채택 근거로 사용하지 않는다.

쓰기 비용은 A1/B 동일 조건에서 단일VU의 기존5쌍 AB/BA를 유지하고,동시2/4/8/16은 각1쌍의 별도 짧은 탐색으로 추가한다(순서AB/BA 교대). 다중VU 단회 결과를 채택 근거로 쓰지 않는다. 같은 hot 지도방의 **서로 다른 셀**을 VU별로 배정하여 A1의 알려진 stale 버그와 지도방 전체 직렬화 비용을 분리한다. 각VU10회 예열+30회 CRUD,총8,400 HTTP요청이다. 겹치는 셀의 정합성은 앞의 필수 race 테스트가 담당한다. 지연p50/p95/p99·오류·CPU/메모리와 별도 SQL 추적의 잠금 SELECT 시간(대기 포함,순수 wait time으로 오표기하지 않음)·SQL 수·WAL을 남긴다. 추적은 대표 측정에서 끈다. 1VU 쓰기 회귀 기준10%는 유지하고 나머지 동시성은 직렬화 비용 진단이다.

예상 시간 갱신(실행 전): 주 읽기140분 순수부하/기동 포함160~175분,전체 viewport21분+준비,탐색24분+준비,동시성 쓰기·SQL진단10~20분,회귀·추가 메모리진단35~55분. **총260~320분(구현 제외)**. E-16 1%50req/s A0/B p95·p99≤10%회귀,E-21 10%16req/s A1/B p95≥20%감소,오류0/dropped0·200ms budget·기본힙·큰편차 중단 규칙은 변경하지 않는다.

## 13. 정식 측정 세션의 호스트 관측·무효 표시

사용자 환경 확인: 전원 연결·Windows 최고 성능·절전 해제·Windows 업데이트 일시 중지,무거운 앱 없음/측정 완료까지 미사용. 구현 JAR는91d653a에서 검증한 그대로 사용하고 아래 변경은 수집기만 대상으로 한다. 최초 호스트 확인에서 별도 프로젝트 Docker7개가 실행 중이어서 부하를 시작하지 않고 일시 정지/종료 후 복구 여부를 사용자에게 확인한다. 기존 프로젝트 데이터·볼륨은 삭제하지 않는다.

`host-sample.ps1`은 매5초 CPU 구간 차이·메모리와상위10개 CPU/메모리 프로세스(이름·PID·working set)를 기록한다. 명령줄·경로·환경변수는 기록하지 않는다. Docker 자원은 기존1초 수집이며,각 manifest에도 측정 전 프로세스 목록을 넣는다. sandbox가 호스트 프로세스를 숨기는 경우(<10개)는 기동 전 거절한다. 실제 호스트 self-check에서644개·수집기0.116 CPU core·배경0.639 core·가용메모리약9.88GiB를 관측했다(준비 중 단회 진단,성능 수치 아님).

`interference.mjs`는 **본 측정 전에 고정한** 다음 관측을 `validity.json`의 무효 사유로 남긴다. VM/Docker backend/해당 수집기 PID 외 CPU합계>2 logical-core equivalent가10초 이상,가용물리메모리<2GiB가10초 이상,AC/최고성능 조건 변경,호스트 표본 간격>10초,자원 수집 오류,별도 프로젝트 컨테이너 실행이다. VM/backend 부하는 측정 자체일 수 있어 CPU 간섭 판정에서는 제외하지만 원본에는 남기며 별도 컨테이너 검사는 유지한다. P/E 코어가 혼합된 노트북에서 이 기준은 간섭의 보수적 징후이며 감지되지 않은 간섭이 없음을 증명하지 않는다. 짧게 종료된 프로세스·OS 내부 부하 등 관측 한계를 함께 기록한다.

무효 회차와 warmup은 삭제·유리한 회차로 대체하지 않는다. 사전5쌍 수치표에 모두 남기고 한 회차라도 무효이면 해당 조건의 채택은 불가다. 환경 변경으로 실행이 중단되면 남은 회차/미검증을 명시한다. 지연·처리량·힙·SQL·응답 계약 기준은 변경하지 않는다. Node29건과 실제 Windows 수집 검사를 수행했으며,이번 변경은 Java/Gradle/운영 설정을 건드리지 않아 직전 전체 회귀 결과를 재사용한다.

### 사용자 확정: 다른 프로젝트7개는 pause/unpause만

추가 승인에 따라 다른 프로젝트7개는 `docker pause`로만 일시 정지하고 성공·실패·중단 모두 `docker unpause`로 복구한다. 이 컨테이너에 stop/rm/볼륨 변경은 하지 않는다. `background-lease.mjs`는 사전running 확인→pause→작업→finally unpause→7개 모두running/paused=false 검증을 수행한다. 일부 pause만 성공한 경우도 이미 정지된 대상만 복구한다. 실제 ID 목록은 gitignore된 private에만 두고 manifest에는 ID 기반 비식별 별칭과 상태만 쓴다.

`p2-session.mjs`는 이 복구 경계 안에서 사전 읽기20회·전체viewport3회·쓰기18회·SQL진단·고정동시성JFR·탐색8회를 순서대로 실행한다. 중단 신호는 본인 측정 workload를 끝내고 복구하며,별도 세션 수명의 watchdog도 부모 비정상 종료를 감시해 복구한다. 일반 종료 후 watchdog은 자동 종료한다. 수동 복구 명령은 `node perf/stage2/p2-session.mjs restore`다. 외부 서비스는pause/unpause만 사용하며 내부의 기존 perf 프로젝트 기동/정지는 별도다.

paused 컨테이너는 CPU 실행 간섭으로 분류하지 않되 매manifest/원본 자원표에 상태를 남긴다. 메모리는 해제되지 않으므로 호스트 가용메모리와 함께 해석하고,A0/A1/B에 같은 조건을 유지한다. 다시running이 된 배경 컨테이너는 무효 사유다. 복구 테스트는 정상 종료·작업 실패·부분pause 실패3건을 포함해 Node32건 성공(0.131초)이다. 측정 기준과JAR는 변경하지 않았다. 예상 총 시간은 앞서 고정한260~320분 범위로 기록하며 실제 단계별 시간을 원본에 남긴다.

### 전체 viewport 실행 전 실행기 오류 정정

읽기 세 번째 묶음 진행 중 §7과 실행기를 재대조해,전체 viewport 실행기에1%의50req/s가 잘못 들어간 것을 발견했다. **승인된 전체 viewport는16req/s·3회**이며 이 단계의 요청은 아직 한 건도 보내지 않았다. `fullViewportSchedule()`로 승인값을 한곳에 고정하고33번째 도구 검사로 검증했다. 이는 결과를 본 뒤 부하를 낮추는 변경이 아니다.

이미 실행 중인 읽기 driver는 시작할 때 불러온4bf7edc의 그대로20회를 완료한다. 읽기의 JAR·run.mjs·1%50/10%16·예열120/본300·관측 규칙·원본은 바꾸지 않는다. 새 코드는 미실행 전체범위 순서와 읽기 종료 후 handoff에만 영향을 준다. per-run Git HEAD에는 이 제어 코드 정정 커밋이 기록될 수 있으며 실제 읽기 실행 코드/JAR가 바뀐 뜻은 아니다.

기존 부모 프로세스에는 잘못된50이 이미 로드되어 있어,`read-handoff.json`이 있으면20회 평가 결과를 먼저 저장한 뒤 종료코드78로 부모의finally 복구를 실행한다. 첫 세션의 이 종료는 측정 응답 실패가 아닌 의도한 단계 분리로 기록한다.7개running 확인 후 `p2-session.mjs remaining`이 별도background-remaining-manifest에 다시pause/복구 이력을 남기며 미실행 단계만 계속한다. 이미 완료한 읽기 회차는 재실행·삭제하지 않는다. Node33성공(0.165초),Java/운영코드 불변이다.
