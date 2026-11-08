# P0 기준선 결과

상태: 측정 기반·전체 회귀 검증 완료, 대표 측정 진행 전. 이 문서의 pilot은 채택 근거가 아니다. **노트북 상대 비교이며 운영 절대 수치 아님.** P1~P3 구현과 E-10 지연 budget 확정은 이번 P0에 포함하지 않는다.

## What / Why

같은 v3에서 이후 누락 정합성·SQL·PostGIS 대안을 비교할 수 있도록 자원·seed·코드·요청·오류·업무 결과가 설명되는 A 기준선을 만든다. v2의 속도와 Java의 속도를 기술 선택의 효과로 비교하지 않는다.

## How — 검증한 기반

| 검사 | 결과 |
|---|---|
| core test | 125/125 성공 |
| root 빠른 test | 총377: 성공376·실패0·기존 HEIC skip1 |
| v2 comparisonTest | 1,152/1,152 성공, 알려진 차이11 유지(개선6·의도5·보류0) |
| allTests build + perfBootJar + Spotless | 총1,654: 성공1,653·실패0·skip1, **1,111.503초**. 이번에는 core도 실제 재실행. v2 이미지 캐시·빌드1·기동1·재시작17·인증 대기0 |
| 실제 JAR 격리 검사 | 4건 성공. 일반 JAR에서 perf 거절·측정 JAR의 production/혼합 profile 거절, 모두 datasource/Flyway 초기화 전. 일반 JAR에 perf 설정/진단 클래스/표식 없음 |
| Node 도구 자체 검사 | 7건 성공, 0.108초(Node24.22.2). 결정적 seed·viewport·응답 변조 탐지·EXPLAIN 리터럴 복원/누락 거절 |
| 경계 R | 실제 POST→level5/6/9/11 GET→DELETE. 37.05/-0.00001의 기존 셀 누락 재현. 수정하지 않음. 300/301·699/700/701 등 기존 경계 회귀도 전체 테스트 유지 |
| M 대량 seed | 장소100,000·지도방1,000. JS/PG 좌표 키600,000회 비교 불일치0. 정밀도1/2/3의 셀 count 합이 각각100,000과 일치 |

실행 절차·파일 역할은 [README](README.md), 승인 결정은 [설계 §0](../../apps/server-v3/PERF_STAGE2_DESIGN.md#0-확정-결정과-p0-실행-범위)를 따른다. 일반 앱·비교 계약은 기존대로이며 perf 전용 profile에서만 한도를 높인다. v2·Flutter·CMS core 수정 없음.

## How — pilot (비교 판정 제외)

각 도착률60초, 총11,466건 성공·HTTP/업무 오류0·dropped0. 50req/s를 본 측정 부하로 고정한다. 100req/s에서 발생기 CPU p95 약1.055코어였고 50req/s에서는 약0.503코어였다(한도2코어). 이 pilot으로 최대 지속 처리량을 선언하지 않는다.

| 도착률(req/s) | 완료 | p50(ms) | p95(ms) | p99(ms, 표본 부족 탐색) |
|---:|---:|---:|---:|---:|
| 1 | 61 | 9.474 | 16.636 | 22.411 |
| 5 | 301 | 7.346 | 11.233 | 12.156 |
| 10 | 601 | 7.188 | 11.226 | 12.551 |
| 25 | 1,501 | 6.119 | 8.768 | 10.311 |
| 50 | 3,001 | 5.092 | 7.620 | 9.182 |
| 100 | 6,001 | 4.815 | 9.408 | 16.203 |

pilot은 준비 중 작업 트리의 JAR이고 초기 환경/JAR SHA를 `pilot-environment.json`에 보존한다. 대표 A는 검증 후 커밋을 고정한 JAR로 별도 실행한다. pilot에서의 JIT 예열·도착률 차이를 개선율로 계산하지 않는다.

## How — 준비 중 발견·수정한 문제

- 전용 BootJar에는 Java 타깃을 명시해야 했고, 제한된 단일 테스트 필터는 core 모듈까지 적용되므로 집중 검증에서는 core를 제외했다. 이후 전체 회귀에서 core125를 모두 실행했다.
- 일반 Docker ignore가 빌드 JAR도 제외하므로 전용 Dockerfile ignore에 측정 JAR만 허용했다. 내부 네트워크는 호스트 포트가 열리지 않아 준비용 HTTP를 docker exec/curl로 변경했다. 대표 HTTP는 k6→앱 직접 경로다.
- PostgreSQL 시작 인수의 `pg_stat_statements.track=none`이 ALTER SYSTEM보다 우선했다. 초기 SQL0건 진단은 **무효**로 원본을 보존하고, 시작 인수에서 제외한 뒤 진단 시 all/대표 시 none의 실제 적용을 assert한다.
- marker-cluster의 준비 요청을 실제 `cellLatKey/cellLngKey` 계약으로 바로잡았다. PostgreSQL이 `coalesce(...,0)`의 0도 파라미터로 정규화하므로 실제 관측 SQL에 맞춰 EXPLAIN 복원을 수정하고 회귀 검사를 추가했다.
- pg_catalog 메타데이터 조회는 기존 ORM SQL 카운터에 잡히지 않는다. `sqlCount`(업무), `driverMetadataQueries`, `postgresSelectCount`(전체 SELECT)를 함께 기록하고 BEGIN/COMMIT도 원본에서 보존한다. 높은 전체 DB 호출을 숨겨 기존 예산과 같다고 보고하지 않는다.

## Opinion

대표 A5회·탐색6회·CMS1회와 별도 SQL/JFR 진단 후 판정한다. P0는 대안의 채택 결과가 아니며, E-10 지연 budget은 사용자와 후속 결정한다. 현재 기본 힙/Serial GC/Hikari 설정을 미튜닝 기준으로 기록하고, 설정이 병목이면 통제한 대조군을 먼저 만들어야 한다.
