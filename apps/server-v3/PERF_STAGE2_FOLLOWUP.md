# 성능 2단계 종료 후 백로그

P1 정합성·P2 읽기/메모리 개선을 병합 대상으로 삼는다. P2 쓰기는 개선 방향 확인·정량 판정 보류, P3는 E-33 선별 미달로 종료했다. 아래는 이번 PR의 구현 범위가 아니며 각각 새 가설·성공 기준·환경을 고정한 뒤 별도 실험한다.

| 순서·항목 | 근거와 목적 | 후속 검증·채택 조건 |
| --- | --- | --- |
| 1. READ_COMMITTED 명시와 JDBC SHOW 제거 검토 | 생성/이동/삭제 요청당 `SHOW TRANSACTION ISOLATION LEVEL` 3회. 현재 DB 기본값과 같은 설정의 비용을 분리한다. | pool 신규/재사용 연결의 실제 격리 수준·반납 시 복구, advisory lock 뒤 새 snapshot, 3커밋·경합 정합성 유지부터 확인. wire SQL 수와 쓰기 지연을 독립 A/B로 측정하며 단순 설정 삭제만으로 개선을 주장하지 않는다. |
| 2. 전체 viewport 사전 집계·캐시 | 앱 OOM은 해결했지만 전체 범위에서 DB 작업량이 남았다. P2 전체 범위 한 회차 p95 117.379ms·DB 약1.341코어. P3 bbox 인덱스는 모든 셀이 필요한 경로의 해결책이 아니다. | 사전 집계의 정확성·쓰기 비용 또는 캐시 키/무효화/권한/허용 신선도를 먼저 결정. 전체 범위 DB CPU·루트 버퍼·지연을 측정하고 1%/10% 회귀도 검사. Redis는 자동 도입하지 않는다. |
| 선택. AWS 재현 | 현재 결과는 i9-13900HX 노트북 상대 비교이며 운영 절대 용량이 아니다. | 채택한 P2 결과만 별도 예산 승인 후 전용 VM에서 새 A/B 전체 세션으로 재현. 로컬 A와 AWS B를 비교하지 않으며 비용·종료·원본 보존 범위를 사전 고정. |
| P4. 이미지 비관적 락 대 조건부 UPDATE | 이미지 3장 제한은 조건부 UPDATE로 정합성을 확보했지만 락 방식의 성능 우열은 미측정이다. | 동일 변환기·파일 보상·데이터·동시 요청 수에서 최종 장수/깨진 URL/고아 파일을 우선 검사. 그다음 p50/p95/p99·처리량·DB 대기·CPU·메모리·SQL 수를 비교. 기존 정합성 시나리오 초기0~3장, N=2/4/8/16 및 업로드/삭제 순서 검사를 재사용. |

고정 근거: [P2 전체 범위·쓰기 SQL 관측](https://github.com/MycroCosmo/loco/blob/8f2c07cef0fcb4dfc276d560deaedc76ba1a2fa2/perf/stage2/P2_RESULTS.md), [P2 쓰기 최종 한계](https://github.com/MycroCosmo/loco/blob/8f2c07cef0fcb4dfc276d560deaedc76ba1a2fa2/perf/stage2/P2_WRITE_E24.md), [P3 E-33 마감](https://github.com/MycroCosmo/loco/blob/a8fe4b9f53784457a89f72f9d11b79ef6100415f/perf/stage2/P3_E33_RESULT.md), [이미지 실험 계획](https://github.com/MycroCosmo/loco/blob/8f2c07cef0fcb4dfc276d560deaedc76ba1a2fa2/apps/server-v3/IMAGE_API_DESIGN.md).

P3 코드·V10·전용 테스트는 태그 `perf-stage2-p3-e33-20261111`에서 재현한다. 현재 병합 대상 운영 코드는 P2 B/V9이므로 보존된 P3 도구를 현재 운영 소스와 혼합해 재실행하지 않는다. 이전 raw·Release·설계 문서와 실패 기록은 삭제하지 않는다.
