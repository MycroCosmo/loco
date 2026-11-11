# 성능 2단계 병합 전 복원·검증

2026-11-11. P3 후보를 제외하고 P1/P2를 병합하기 위한 기능 회귀 검증이다. 성능을 다시 측정하거나 이전 판정을 변경하지 않는다.

## 복원과 보존

- `8cc0a3fff9df312408934d469b0c5c4a37ac4dab`: 파일 단위 복원. V10·공간 predicate 2줄·P3 전용 테스트 2개 제거. 운영 `src/main`은 P2 B `8f2c07cef0fcb4dfc276d560deaedc76ba1a2fa2`와 동일하다.
- `124c21e`: `origin/main`의 포트폴리오 README를 merge commit으로 반영. 충돌 없음. main 기준은 `b12942c`.
- Health 검사는 실제 Flyway 최대 V9와 공간 확장 없음으로 복원·보강했다. MarkerBoundedRead/MarkerSql 검사는 공간 bbox 바인딩만 제거했다.
- CompatibilityTestBase의 후속 migration 적용, HarnessInfrastructureTest의 전체 history 보존, DatabaseSnapshot의 공간 메타데이터 제외 방어, P2 SQL fixture는 유지했다. 전체 검증에서 V10 없이 통과했다.
- P3 설계·결과·`perf/stage2/*`·`compose.p3-network.yml`·Release는 보존했다. P3 재현 태그 `perf-stage2-p3-e33-20261111`은 `6637fff63805c7a56c4c5dab65d8fc4d65e9ffd0`이며 결과 문서는 `a8fe4b9f53784457a89f72f9d11b79ef6100415f`에 있다.

기존 측정 DB에 적용된 V10을 역마이그레이션하거나 Flyway 이력을 수정하지 않았다. V9 앱 검증은 새로운 격리 DB에서 수행했다. 기존 이미지·볼륨·측정 원본을 삭제하지 않았다.

## 실행 환경과 결과

Java 21·Windows·Docker, 기존 v2 이미지 캐시 사용. 명령은 `apps/server-v3`에서 `gradlew.bat spotlessApply allTests build --console=plain`이다. **BUILD SUCCESSFUL, 1,245.052초(20분45초)**. 2026-11-11 14:08:19 KST 종료. JUnit XML과 비교 보고서에서 집계했다.

| 작업 | 전체 | 성공 | 실패 | 건너뜀 | 실행 상태 |
| --- | ---: | ---: | ---: | ---: | --- |
| test | 451 | 450 | 0 | 1 | 실행, 기존 HEIC 공통 제약 1건 |
| comparisonTest | 1,158 | 1,158 | 0 | 0 | 실행 |
| cms-core:test | 125 | 125 | 0 | 0 | 변경 없음, UP-TO-DATE 재사용 |
| 합계 | **1,734** | **1,733** | **0** | **1** | core 재사용 포함 |

알려진 차이 **16건(개선10·의도6·보류0)**의 case/kind 전체가 기존 E-22 검증 목록과 일치했다. v2 이미지 build 요청1·시작1·재시작17, 인증 요청 대기0ms다. [기계 판독 결과·차이 전체 목록·JAR SHA256](../../perf/stage2/results/stage2-merge/verification.json)에 기록했다. 이 벽시계 시간은 기능 회귀 실행 시간이며 성능 실험의 지연 수치가 아니다.

Docker 기본 주소 풀이 소진되어 `LOCO_TEST_NETWORK_SUBNET=10.253.30.0/24`를 지정했다. SharedHarness에만 선택적으로 적용하며 미지정 시 기존 자동 할당이다. 운영 설정은 바꾸지 않았다. 배경 프로젝트는 pause/stop하지 않는다.

## 운영 코드·배포 산출물 확인

- `git diff 8f2c07c -- apps/server-v3/src/main` 결과 없음.
- HealthIntegrationTest에서 실제 DB의 성공한 Flyway 최대 version=9, `plpgsql` 외 extension 수=0 검증.
- 호스트·core 운영 소스와 빌드 파일에 PostGIS SQL·타입·공간 의존성 참조 없음. 새 일반 실행 JAR(`loco-server-v3-0.0.1-SNAPSHOT.jar`)의 호스트 migration은 V1/V6/V7/V8/V9, core JAR의 V2~V5를 포함해 최신 V9다. 공간 JDBC/Hibernate/JTS 라이브러리 없음. 보존된 이전 측정용 JAR와 구분한다.
- PostgreSQL 17 개발 이미지 계열(`postgis/postgis:17-3.6`) 선택은 유지한다. 이미지에 도구가 포함된 것과 DB 확장 설치·운영 의존은 다르다.

브라우저 E2E·실제 외부 API·성능 부하는 이번 복원 검증에서 실행하지 않았다. 프런트와 외부 연동 코드는 변경하지 않았다. 보존한 P3 도구의 공간 SQL oracle은 현재 V9 운영 소스 대신 P3 태그에서 재현한다.
