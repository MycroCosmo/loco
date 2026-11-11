# 지도 마커 정합성·메모리 개선과 성능 2단계 마감

좌표 반올림 차이로 일부 마커 셀이 누락되고, 넓은 지도 조회에서 전체 셀을 앱 메모리에 올려 OOM이 발생하던 문제를 수정합니다. P1 정합성 수정과 P2 읽기·메모리 개선을 병합하며, 쓰기 성능은 개선 방향 확인·정량 판정 보류로 마감합니다.

## 반영 범위

- 좌표 키를 PostgreSQL numeric ROUND·0 정규화로 통일하고 누락 셀을 재생성합니다. overflow 동률 선택은 결정적으로 유지합니다.
- 필요한 경우 DB에서 표시 셀 699개와 overflow를 선택하고, 작은 결과의 정렬·ROUND 계산을 필요한 경로로 제한합니다. 앱 메모리가 전체 셀 수에 비례하는 문제를 제거합니다.
- 셀 갱신은 정밀도별 별도 advisory lock 문장 후 결합 DML을 실행하고 3커밋을 유지합니다. 교차 이동 교착과 오래된 집계 경합을 방지합니다. v2 writer·우회 SQL과 혼용하면 이 보장이 깨집니다.
- Flyway V7~V9로 좌표 키·역조회 인덱스·파생 중심을 반영합니다. 최신 버전은 **V9**, 운영 소스는 P2 B(`8f2c07c`)와 같습니다.

## P3 미채택과 보존

새 EXPLAIN 6회에서 루트 버퍼 중앙값은 3,081→2,623, **14.8653% 감소**로 사전 기준 20%에 미달했습니다. 기존 B-tree를 유지하며 HTTP 지연 개선은 판정하지 않았습니다. [E-33 결과](https://github.com/MycroCosmo/loco/blob/a8fe4b9f53784457a89f72f9d11b79ef6100415f/perf/stage2/P3_E33_RESULT.md)

V10·공간 predicate·P3 전용 테스트만 새 복원 커밋으로 제거했습니다. P3 설계·도구·원본·Release와 `perf-stage2-p3-e33-20261111` 태그를 보존하며 히스토리는 재작성하지 않았습니다. 보존 도구의 P3 재현에는 해당 태그를 사용합니다. 이미 V10이 적용된 측정 DB를 V9 앱의 배포 DB로 재사용하거나 Flyway 이력을 지우지 않습니다.

## 성능과 한계

| 항목 | 결과 |
| --- | --- |
| 지도를 넓게 볼 때 | p95 약111→21ms, 약81% 개선 |
| 좁은 범위 회귀 | P0 대비 p95 −0.205%, p99 +0.869%, 10% 기준 통과 |
| 지도방 전체 조회 | 힙 증설 없이 검사 3회 OOM·오류·dropped 0 |
| 쓰기 | 개선 방향 확인, 간섭으로 정량 판정 보류 |

[P2 읽기·메모리 근거](https://github.com/MycroCosmo/loco/blob/8f2c07cef0fcb4dfc276d560deaedc76ba1a2fa2/perf/stage2/P2_RESULTS.md) · [쓰기 최종 판정](https://github.com/MycroCosmo/loco/blob/8f2c07cef0fcb4dfc276d560deaedc76ba1a2fa2/perf/stage2/P2_WRITE_E24.md)

같은 세션 AB/BA와 결과 전 기준 고정, 간섭·오류 회차 무효 규칙을 적용했습니다. **노트북 상대 비교이며 운영 절대 수치가 아닙니다.** 실제 외부 API·AWS 용량을 검증한 결과가 아닙니다.

## 병합 전 검증

`spotlessApply allTests build` 성공, **1,245.052초(20분45초)**, v2 이미지 캐시 사용.

- 총 **1,734건: 성공1,733·실패0·기존 HEIC 공통 제약 건너뜀1**. root451·v2비교1,158은 실행, 변경 없는 core125는 UP-TO-DATE 재사용.
- 알려진 차이 **16건(개선10·의도6·보류0)**의 이름·분류 불변.
- 실제 DB에서 Flyway최신V9·공간확장0, 일반 실행 JAR에 V10·공간 라이브러리0 확인. 운영 소스는 P2 B와 동일.
- 기본 Docker 주소 풀 소진 대응으로 하네스에 선택적 `LOCO_TEST_NETWORK_SUBNET`을 추가. 미지정 동작·운영 설정 불변이며 기존 네트워크·볼륨을 삭제하지 않음.
- 이번 복원에서 브라우저 E2E·실제 외부 API·성능 부하는 실행하지 않음. 프런트·외부 연동 코드는 변경하지 않음.

[검증 상세](https://github.com/MycroCosmo/loco/blob/0d82311c1d21124bc8136681c5d044c7d8b94908/apps/server-v3/PERF_STAGE2_MERGE_VERIFICATION.md) · [결과·알려진 차이 전체 목록](https://github.com/MycroCosmo/loco/blob/0d82311c1d21124bc8136681c5d044c7d8b94908/perf/stage2/results/stage2-merge/verification.json)

## 후속 작업

[백로그](https://github.com/MycroCosmo/loco/blob/0d82311c1d21124bc8136681c5d044c7d8b94908/apps/server-v3/PERF_STAGE2_FOLLOWUP.md): READ_COMMITTED 명시로 인한 SHOW 요청당 3회 제거 검토, 전체 범위 사전 집계·캐시, 선택적 AWS 재현, 이미지 비관적 락 대 조건부 UPDATE 비교. 이번 PR에서는 구현하지 않습니다.

main의 포트폴리오 README를 merge commit으로 반영했고 충돌은 없었습니다. 이 PR은 생성만 하며 사용자가 **Create a merge commit**으로 병합합니다.
