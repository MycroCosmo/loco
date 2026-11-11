# P3 마감 — E-33 새 버퍼 선별 기준 미달

2026-11-11. **이 데이터·경로에서 공간 인덱스 이점 작음, 기존 B-tree로 충분.** E-33에 따라 HTTP p95 선별·정식 부하·쓰기 비용 측정을 생략하고 P3를 마감한다. 성능 향상이 전혀 없다는 뜻이나 다른 데이터/경로에도 GiST가 불필요하다는 주장이 아니다.

## 새 실행과 판정

결정·실행기 커밋 `6637fff63805c7a56c4c5dab65d8fc4d65e9ffd0`을 실행 전에 push했다. 새 `screen-retest2` A/B, 동일 M seed(장소100,000·지도방1,000·편향), hot 지도방·viewport10%·precision3. 실행 순서는 A1/B1/A2/B2/A3/B3. 앱 종료 없이 수행하고 CPU 간섭에 민감한 SQL 실행시간은 판정에 쓰지 않았다. 준비·복구 포함 **318.780초(5분19초)**, 한국시간13:06:52~13:12:11로 사전 예상5~8분 범위다.

| 루트 Shared Hit+Read 블록 | 1회 | 2회 | 3회 | 중앙값 |
| --- | ---: | ---: | ---: | ---: |
| A: P2 B 원래 JAR, B-tree | 3,081 | 3,081 | 3,081 | 3,081 |
| B: 셀 bbox GiST 후보 | 2,623 | 2,623 | 2,623 | 2,623 |

**B/A=0.8513469653, 감소14.8653%**. 부하 진행 조건은 ≤0.80(≥20% 감소)이므로 미달이다. E-32의 버퍼 AND p95 기준을 완화하지 않았다. 이번에는 E-33으로 버퍼 조건만 먼저 판단하며, p95는 측정하지 않아 개선/회귀 결론을 내리지 않는다. 오전 미완료 세션8225ac1의0.852·이전 A HTTP·readiness 결과는 새 판정에 섞지 않았다.

## 유효성·계획 확인

- 새 양쪽 JAR hash·힙389,283,840바이트·shared_buffers1GB·work_mem4MB·Hikari10 확인. SQL seed hash와 public 업무 fingerprint 일치, 양쪽 좌표/셀 합계 검증 통과.
- SQL 오류0, EXPLAIN6회 모두 업무 DB불변, 루트 반환 행700(표시699+overflow1). 하위 노드 버퍼를 중복 합산하지 않았다.
- A 세 회 모두 기존 `mapId/precision/minLat…`와 `mapId/precision/maxLat` B-tree를 사용했다. B 세 회 모두 `place_marker_cells_bbox_gist_idx`와 maxLat B-tree가 나타난다. 따라서 GiST가 선택되지 않아 효과가 없었다고 설명하지 않는다. 공간 후보 인덱스를 사용했어도 전체 셀 SQL의 버퍼 감소가20%에 도달하지 못했다.
- 계획 원본·Hit/Read 세부값·노드·파라미터를 모두 보존했다. 이번 판정은 페이지 접근량 기준이며 CPU 병목 원인이나 HTTP 지연 개선을 측정한 것으로 해석하지 않는다.
- 배경7개 pause/unpause 후 직접running7·paused0 확인. 자체 A/B 앱·DB 모두 정지. 기존 이미지·볼륨·네트워크·오전 원본 삭제 없음.

## 근거와 범위

[manifest·계획 요약](results/p3-e33/manifest.json), [압축 목록·SHA256](results/p3-e33/artifacts.json), [원본 Release](https://github.com/MycroCosmo/loco/releases/tag/perf-stage2-p3-e33-20261111). Release에는 전체 EXPLAIN6개와 세션·결과·비식별 복구 기록, 총10파일(30,144바이트)을 담았다. SHA256 `af3eaa9aa8132af453c42b0bf78451cef8de50800ce51e18e575624d0cd4ad81`. 비밀번호·토큰·호스트 프로세스 목록은 포함하지 않았다.

도구 검증은 Node63건 성공·실패0(0.521초), 실제 이번 EXPLAIN6회 성공·오류0이다. 운영 Java는 변경하지 않아 전체 Java 회귀를 반복하지 않았다. P3 후보 코드/V10과 실험 자산을 임의로 제거하거나 main에 반영하지 않았다. 이번 결과는 후보 채택 근거가 되지 않는다. 전체 viewport 집계 병목·사전 집계/캐시, READ_COMMITTED SHOW 제거, P4/P5는 별도 후속 결정 범위이며 자동 진행하지 않는다.
