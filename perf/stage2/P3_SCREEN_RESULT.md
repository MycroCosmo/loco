# P3 E-32 선별 — 실행 도구 오류로 판정 불가

2026-11-11 확인. 실행 source `7fde566`, 한국시간 11월10일23:52:06부터 11월11일00:08:40까지 **993.654초(16분34초)**. 앱 종료 사전 검사와 배경7개 pause를 통과했다. [집계 원본](results/p3-screen/incomplete.json).

| 항목 | 관측 | 해석 |
| --- | --- | --- |
| 셀 SQL EXPLAIN | A/B 각3회 완료, 간섭 검사6회 모두 통과. root Shared Hit+Read A=[3081,3081,3081], B=[2626,2626,2626] | 중앙값 B/A=0.8523, 감소14.77%. 버퍼 조건≤0.80에는 못 미쳤지만 전체 선별 완료 판정으로 대체하지 않는다 |
| A HTTP | 예열120초·본300초 완료. 본측정4,801건 모두200, 오류0·dropped0·DB불변. p95=20.421ms, p99=22.108ms, 수집기 간섭 검사 통과 | 단회 대조군 관측이며 개선 근거가 아님 |
| B HTTP | 측정 전 diagnostic EXPLAIN 오류, k6 예열·본측정0회 | 지연 B/A 미산출. A-ext도 미실행 |
| 복구 | 종료 시7개 unpause·running 확인. 결과 확인 때도running7·paused0 | 자동 복구 완료, 측정 재시작 없음 |

원인은 운영 SQL이나 사용자 앱 미종료가 아니라 **측정 도구의 SQL 재생 버그**다. `sql-replay.mjs`의 토큰 정규식이 PostGIS `&&`를 문자 두 개로 분리하고 `join(' ')`이 `& &`로 바꾼다. 최소 재현은 `bindTemplate('SELECT a && b','SELECT a && b',{})` → `SELECT a & & b`. PostgreSQL은 `operator does not exist: & geometry`로 거절했고 실행기가 중단했다. 별도 셀 EXPLAIN6회는 원래 연산자를 유지하는 다른 바인딩 경로여서 완료됐다.

기존 도구 테스트는 파라미터 순서·SRID만 검사해 실제 연산자 보존을 놓쳤다. 따라서 Java 전체 회귀 통과와 Node59건 성공이 이 측정 경로까지 입증한 것은 아니다. 후속 수정은 연산자 보존 및 **재생한 SQL을 실제 PostGIS에서 실행하는 검사**를 포함해야 한다. 이 결과 확인에서는 코드를 수정하거나 재측정하지 않았다.

**결론: 실행 도구 오류로 선별 미완료·P3 채택 판정 불가.** 부분 버퍼 수치만으로 E-32의 유효한 미달 결론인 “B-tree로 충분”을 확정하지 않는다. 오류 원본을 보존하며 기준·힙·부하 변경이나 B만 대체 실행은 하지 않는다. E-31의 중단 원칙에 따라 다음 실행은 별도 확인이 필요하다. 이번 실패는 앱 간섭 재발로 확인된 것이 아니므로 VM 전환으로 이 버그가 해결된다고 주장하지 않는다.

로컬 원본은 `apps/server-v3/build/perf-stage2-p3-screen`, `perf-stage2-p3-a`, `perf-stage2-p3-b`에 남아 있다. 앞선 측정0회 사전 검사 실패도 `perf-stage2-p3-screen-preflight-failed-20261110-220102`에 별도 보존했다. 이번 커밋은 공개 집계·원인 문서만 포함하며 전체 원본 Release 업로드는 수행하지 않았다.

## 2026-11-11 실행 도구 수정·검증 (측정 재실행 아님)

사용자 승인으로 토큰 분리 정규식에 `&&`를 추가했다. 연산자를 공백으로 나눈 관측 SQL이나 단일 `&`로 바뀐 SQL은 거절한다. 운영 앱·JAR·migration·판정 기준은 변경하지 않았다.

- Node 도구 테스트 **60성공·0실패**, runner **0.517초**.
- `sql-replay.integration.mjs` 실제 PostgreSQL17/PostGIS3.6 검사 **5성공·0실패**, **10.816초**(임시 DB 시작·정리 포함). 접촉/비접촉 bbox의 참·거짓, 2개/701개 데이터 각각의 CANDIDATES/BOUNDED_CELLS를 검증했다.
- 실제 DB의 `pg_stat_statements`가 정규화한 SQL을 가져와 실패 경로와 같은 `bindTemplate`으로 바인딩한 뒤 `EXPLAIN(ANALYZE,BUFFERS)`을 실행했다. 원본 SQL과 행 전체가 일치하며, 701개 셀에서 표시699개+overflow1개 및 overflow count2를 확인했다. [검증 기록](results/p3-screen/replay-fix-verification.json).
- UUID 이름·네트워크 없는 tmpfs 전용 DB를 사용하고 그 컨테이너만 정리했다. 기존 측정 DB·원본·이미지·볼륨과 배경7개는 변경하지 않았다.

검증 범위는 **오류가 난 SQL 진단 경로의 실제 DB 실행**이다. HTTP 앱 전체 실행기·k6·정식 측정은 재실행하지 않았으며 기존 선별 실패 판정은 그대로다. 도구만 변경해 Java allTests/build를 반복하지 않았다. 다음 측정은 별도 환경 확인 후 A/B 전체 새 세션으로 실행해야 하며, 기존 `launch.json`이나 원본을 삭제해 재시도하지 않는다.
