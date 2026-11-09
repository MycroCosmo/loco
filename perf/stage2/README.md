# 성능 2단계 측정 도구

최신 결정 E-16: P1의 정합성 수정 비용을 조건부 승인하고 P2에서 **원래 P0 대비1%·50req/s p95/p99 회귀≤10%**를 회복한다. [P2 설계·선택지](../../apps/server-v3/PERF_STAGE2_P2_DESIGN.md)만 작성했으며 새 구현·측정은 없다. 아래 E-15 결과는 당시 실패 기록으로 보존한다.

## E-14/E-15 같은 세션 대조

최종 결과: group/cluster Index Scan 복귀·전체 회귀 통과. 같은 세션1% p95+11.84%·p99+14.22%로 상대 회귀 기준 미달이며 SQL·JFR 진단 후 중단했다. P2/P3는 미진행이다. [회차별 결과·원본](E14_E15_RESULTS.md)을 확인한다.

[고정 실행 계약과 결과](E14_E15_RESULTS.md). E-15는 `PERF_STAGE=e15-a/e15-b`로 기존 P0/P1 출력과 DB를 보존한다. A의 기존 `loco-perf-p0:local` 이미지를 재빌드하지 않는다. B는 검증한 perf JAR로 `docker build -f perf/stage2/Dockerfile -t loco-perf-e15-b:local .`을 실행한다. 양쪽에 `run.mjs up`, `seed M skew`, `verify-seed`, `stop`을 각각 적용한 뒤 `node perf/stage2/paired.mjs`가20개 측정을 교대 실행한다. 각 실행은120초 예열+300초, 실패도 보존한다. 측정 전 현재 사용자 확인에 따라 PERF_HEAVY_APPS_STATUS를 closed-user-confirmed 또는 not-closed-user-confirmed로 설정한다. 이전 확인을 다음 세션에 재사용하지 않는다.

쓰기 비용은 B에서 `node perf/stage2/run.mjs write-cost`로 별도 수행한다. 인덱스 없는 B/있는 B5쌍의 단일 VU 실제 CRUD와 별도 SQL 추적이며 E-15 읽기 결과와 섞지 않는다. 종료 후 V8 인덱스를 복구한다. 각 stage에서 `node perf/stage2/summarize.mjs` 후 `node perf/stage2/paired-analysis.mjs`가5쌍 회귀율·실패 여부를 판정한다. Node 검증은 `node --test perf/stage2/*.test.mjs`다.

## P1 재현 추가

최초 [P1 결과](P1_RESULTS.md): 정합성·전체 회귀는 통과했지만1% 지연 회귀10% 기준은 미통과다.10%·16req/s A5는 참고 기준선이며 편차55.8%로 성능 채택·최대 처리량은 보류했다. 기본 힙의 전체 viewport OOM과 당시 group 역조회 Seq Scan도 역사적 기준으로 보존한다(group 인덱스는 E-14에서 복구). 아래 `measure`의 k6 종료0은 p95 200ms 검사를 뜻하며 **P0 대비 회귀10% 통과를 대신하지 않는다**. 회귀율과 별도 gate는 결과 summary의 evaluation을 확인한다.

P0 명령·결과는 아래에 그대로 보존한다. P1은 `$env:PERF_STAGE='p1'`을 먼저 설정한다. `run.mjs`, 결과 요약/압축, artifact 검사가 `loco-perf-p1` 프로젝트·별도 볼륨·`apps/server-v3/build/perf-stage2-p1` 출력으로 분리되며 이미지도 `loco-perf-p1:local`이어야 한다. P0 태그·볼륨·결과를 덮어쓰지 않는다. P1측정 중에도 JVM 옵션을 추가하지 않는다.

```powershell
$env:PERF_STAGE='p1'
docker build -f perf/stage2/Dockerfile -t loco-perf-p1:local .
node perf/stage2/run.mjs up
node perf/stage2/run.mjs seed S skew
node perf/stage2/run.mjs boundary
node perf/stage2/run.mjs seed M skew
node perf/stage2/run.mjs verify-seed
# 60초 탐색의 통과/실패 구간만 찾는다. 이 결과만 최대 지속 처리량이라 부르지 않는다.
node perf/stage2/p1-capacity.mjs
# NAME은 새 이름, RATE는 탐색에서 실제 확인한 정수 도착률이다.
node perf/stage2/run.mjs probe capacity-5 5 10 60
node perf/stage2/run.mjs diagnostic
# 예열120초·본 측정300초×5. 확정 RATE와 실행 이름은 P1_RESULTS.md를 따른다.
node perf/stage2/run.mjs measure primary RATE 10 hot80 5
node perf/stage2/run.mjs measure regression 50 1 hot80 5
node perf/stage2/run.mjs probe full-stress 50 100 120
node perf/stage2/run.mjs snapshot
node perf/stage2/run.mjs stop
node perf/stage2/summarize.mjs
```

`probe`는60/120/300초를 지원하고 p95≤200ms·HTTP/업무오류0·dropped0을 검사한다. 실패도 실행 코드·요약·앱 로그·DB 불변 검사와 함께 남긴다. `measure`의 예열에는 지연 budget을 적용하지 않지만 오류·dropped는 허용하지 않는다. 본 측정은 모두 budget을 적용한다. 부하 hash는 P0와 같은 배열 순서 정규화를 유지하되 표시 항목·좌표·count 전체를 검사하고, E-12의 순서 자체는 별도 실제 HTTP 경계 테스트가 엄격하게 검증한다.

`p1-capacity.mjs`는 1·2·4…req/s로 첫 실패를 찾고 정수 이분 탐색으로 바로 아래 후보를 정한다. 예상된 k6 threshold 실패만 보존 후 진행하며, HTTP200의 업무 값 불일치·DB 변경·수집 실패는 중단한다. 탐색 전120초 예열하고, HTTP오류·dropped로 포화된 경우에만 같은 설정의 앱을 재시작·재예열한다. 지연 threshold만 실패하면 실행을 보존하고 다음 도착률로 진행한다. 후보는5회 본 측정과 바로 위 도착률300초 확인을 거쳐야 한다.

`P1_RESULTS.md`는 정합성·도착률 탐색·A5·회귀·OOM 스트레스를 한곳에서 검토하기 위한 결과 문서다. 기존 P0의 넓은 범위 실패를 성공값으로 대체하지 않는다.

`profile NAME RATE VIEWPORT`는 같은 JAR·힙에 JFR만 켜는 별도 진단이다. 시작140초 후60초를 기록하고, 부하는 예열120초+진단90초다(준비/복구 포함 약5분). 이 진단에서만 k6 threshold 실패를 보존한 채 기록을 마치며, 기준선 통과로 취급하지 않는다. 종료 후 원래 앱 설정으로 복구한다. 인자를 생략하면 P0의50req/s·1% 조건이다.

운영 절대 수치가 아닌 **동일 노트북 상대 비교**를 위한 도구다. P0에는 개선안 B가 없다. 앱/DB/발생기는 각각 CPU 2, 메모리 1.5/4/2GiB, DB shared_buffers 1GiB다. 외부 통신·호스트 포트가 없는 내부 Docker 네트워크를 사용하며 기존 개발 볼륨은 건드리지 않는다. 데이터 초기화는 이름이 `loco_perf`인 이 프로젝트 DB만 허용한다.

## 파일과 실행 경계

- `compose.yml`: 버전/digest·자원·내부 네트워크를 고정한다. PostGIS 이미지의 자동 확장 초기화를 비워 P3 이전 공간 확장 도입을 막는다. pg_stat_statements는 진단 구간만 추적한다.
- `Dockerfile`, `Dockerfile.dockerignore`: 운영 Dockerfile의 codec-runtime 위에 **별도 perf JAR만** 복사한다. 일반 Docker 빌드의 build/비밀 파일 제외 규칙은 그대로다.
- `data.mjs`, `data.test.mjs`: 고정 seed·UUID·시각·좌표와 데이터 생성 SQL/응답 검사를 검증한다. 대량 seed는 SQL, 경계 R은 실제 HTTP다. S/M의 리뷰 2천 건(평점1~5)은 전체 manifest에 기록한다. CMS 데이터는 10만 회원/방/장소/신고 및 50% 신고 처리행으로 별도 준비한다.
- `load.js`: k6 constant-arrival-rate, 요청1개=iteration1개. 80% hot/20% 나머지 모든 방에 결정적으로 분산한다. 균등 요청은 모든 방을 순회한다. 성공200뿐 아니라 전체 업무 응답 hash를 검증한다. 마커 count 동률의 순서만 정규화한다. 각 경로의 n·지연·오류를 원본에 남긴다.
- `run.mjs`: 준비·HTTP 관측·SQL 수집·k6·1초 자원 샘플·데이터 불변 검증. 호스트 HTTP 프록시 없이 준비 요청만 컨테이너 안의 curl로 전송한다. k6 본 측정은 앱에 직접 요청한다. 비밀번호/JWT는 매 준비 환경에서 생성해 gitignore 대상 build/private에 보관하고 원본 묶음에서 제외한다.
- `check-artifacts.mjs`: 실제 일반/측정 JAR의 잘못된 profile 기동을 검사해 DB/Flyway 초기화 전 거절되는지 확인한다.
- `summarize.mjs`: 압축된 k6 원본에서 경로별 표본·percentile을 다시 계산하고 CPU·메모리·샘플 간격을 요약한다.
- `host-sample.ps1`: Windows 기본 성능 카운터로 호스트 전체 CPU·가용 메모리를 1초마다 읽는다. 컨테이너 CPU와 중복되므로 둘을 합산하지 않는다.
- `package-results.mjs`, `publish-results.mjs`: 자격증명 제외·검사, 배경 컨테이너 비식별 처리, 파일별 SHA256, 압축 및 GitHub Release 업로드 후 digest 확인. 기존 원본/Release를 덮어쓰지 않는다. 업로드는 결과 검토·커밋 후 명시 명령으로만 실행한다.
- `RequestLimitPolicy`, `PerfEnvironmentGuard`, `spring.factories`: 일반 JAR에서 perf 활성화를 **DB/Flyway 초기화 전에** 거절한다. 측정 JAR도 perf 단독 프로필·격리 확인·전용 DB·외부 발송 비활성 조건이 필요하다. 외부 배포를 기술적으로 완전히 막는 정책 시스템은 아니므로 측정 JAR 자체를 배포 대상으로 사용하지 않는다.
- `src/perf/resources/*`: 일반 bootJar에 없는 표식과 설정. 요청 한도600,000/분 외 기존 limiter의 method+IP·synchronized·창 계산을 그대로 통과한다. 한도를 높여 누적 hit deque 메모리가 늘어나는 비용도 포함한다.
- `PerfReadPlans`: perf JAR에만 들어가는 준비 시점 진단. 실제 CMS query builder의 SQL/EXPLAIN과 실제 JVM·Hikari·DB 설정을 기록한다. 부하 중에는 실행하지 않는다.

## 재현 명령 (저장소 루트)

Java 21, 저장소의 Node 준비 스크립트, Docker Desktop이 필요하다. Windows AC 연결·최고 성능 overlay를 매 측정 전후 확인한다. `.wslconfig`가 없으면 Docker가 보고한 실제 VM CPU/RAM을 기록한다. 다른 앱은 중단하지 않고 배경 사용량을 함께 측정한다.

아래는 개별 실행 명령이다. 새 checkout의 빈 build 출력에서 재현하고, 기존 원본은 먼저 보존한다. 각 명령의 `$LASTEXITCODE`가0인지 확인한다. 실패하면 다음 부하를 자동으로 이어 실행하지 않는다. 이번 P0의 viewport10%·전체는 예열에서 실패했으며, 전체 범위 뒤에는 로그·DB 불변 확인 후 `docker restart loco-perf-p0-perf-app-1`로 **같은 설정**의 앱을 복구했다. 실패 조건의 검증을 약화하거나 도착률을 바꾼 성공값으로 대체하지 않는다. 실제 성공/실패와 원인은 [P0 결과](P0_RESULTS.md)를 따른다.

```powershell
Set-Location apps/server-v3
. ../admin/prepare-node.ps1
./gradlew.bat spotlessApply allTests build perfBootJar --console=plain
Set-Location ../..
node --test perf/stage2/data.test.mjs
docker build --target codec-runtime -f infra/docker/server-v3.Dockerfile -t loco-perf-codec:8.15.3 .
docker build -f perf/stage2/Dockerfile -t loco-perf-p0:local .
node perf/stage2/run.mjs up
node perf/stage2/run.mjs seed S skew
node perf/stage2/run.mjs boundary
node perf/stage2/run.mjs seed M skew
node perf/stage2/run.mjs verify-seed
node perf/stage2/run.mjs pilot
# P0 pilot에서 50req/s 확정. 지연 budget/SLA나 최대 지속 처리량은 아니다.
node perf/stage2/run.mjs measure primary 50 1 hot80 5
node perf/stage2/run.mjs measure viewport-small 50 0.01 hot80 1
node perf/stage2/run.mjs measure viewport-large 50 10 hot80 1
node perf/stage2/run.mjs measure viewport-full 50 100 hot80 1
node perf/stage2/run.mjs measure uniform-requests 50 1 uniform 1
node perf/stage2/run.mjs seed S skew
node perf/stage2/run.mjs measure small-data 50 1 hot80 1
node perf/stage2/run.mjs seed M uniform
node perf/stage2/run.mjs measure uniform-data 50 1 hot80 1
node perf/stage2/run.mjs seed M skew
node perf/stage2/run.mjs diagnostic
node perf/stage2/run.mjs profile
node perf/stage2/run.mjs seed CMS-S uniform none
node perf/stage2/run.mjs cms-probe
node perf/stage2/run.mjs seed CMS-S uniform half
node perf/stage2/run.mjs cms-probe
node perf/stage2/run.mjs seed CMS uniform none
node perf/stage2/run.mjs cms-probe
node perf/stage2/run.mjs seed CMS uniform
node perf/stage2/run.mjs cms
node perf/stage2/run.mjs snapshot
node perf/stage2/run.mjs stop
```

한 실행은 예열120초·측정300초다. P0 주 조건5회35분, 탐색6회42분, CMS1회7분으로 부하84분. pilot6분+준비/진단+회귀 약18분을 포함한 **예상110~130분(구현 제외)**을 실행 전에 고정했다. 실제 실패·재실행·준비 시간은 별도 기록한다. 모든 실행 원본은 `apps/server-v3/build/perf-stage2`에 남는다. 실패 실행도 지우지 않는다.

`profile`은 대표 측정과 분리한 JFR 진단(예열120초+진단90초)이다. 환경변수/시스템 속성 이벤트를 끄고, 공개 원본에는 필요한 실행 샘플·소켓 대기·GC·잠금 이벤트만 내보낸다. 설정·프로파일러의 비용을 대표 지연에 섞지 않는다. [JFR 설정 근거](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html).

JFR와 CMS 진단의 일회성 앱은 `--use-aliases`로 같은 `perf-app` DNS를 사용한다. 종료 후 원래 앱을 다시 시작한다. JFR 이벤트 추출은 Java21의 `jfr print --json --events jdk.ExecutionSample,jdk.SocketRead,jdk.GarbageCollection,jdk.GCHeapSummary,jdk.JavaMonitorEnter,jdk.ThreadPark,jdk.CPULoad <diagnostic.jfr>`를 사용한다. `jfr summary`로 환경변수·시스템 속성 이벤트0도 확인한다.

CMS는 1만/10만 행과 처리행0%/50%의 네 데이터셋에서 SQL/EXPLAIN·HTTP 결과를 준비 구간에 수집한다. 실제 부하는 10만·처리행50% 한 조합만 1회 탐색한다. 이 4종 준비 작업을 4회 부하 측정으로 부풀리지 않는다.

## 해석

P0는 현재 배포 기본 JVM/GC/풀 그대로의 **미튜닝 기준선**이다. 기본값이 병목이면 설정을 통제한 별도 대조군을 먼저 만들고 B와 비교해야 하며, 현재 설정만을 근거로 기술의 우열을 선언하지 않는다. p99는 경로별 성공 표본1만 미만일 때 탐색값이다. E-10 지연 budget은 P0 결과를 사용자와 검토한 후 정한다. 지연 budget 없는 pilot의 높은 RPS를 최대 지속 처리량으로 발표하지 않는다.

주 조건의 실행별 p95 범위가 중앙값의10%를 넘으면 변동 원인을 먼저 조사한다. k6/배경 부하·throttling·OOM·TCP 실패·dropped·업무 응답 오류를 원본과 함께 보고한다. 평균 percentile을 전체 p99라고 부르지 않는다. 원본 압축은 private/비밀·세션·쿠키·토큰을 제외하고, 다른 프로젝트 컨테이너 이름은 비식별 별칭으로 바꾼 사본만 Release에 올린다.

기술 근거: [k6 constant arrival rate](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/), [k6 2.3.0](https://github.com/grafana/k6/releases/tag/v2.3.0), [Windows 최고 성능 overlay](https://learn.microsoft.com/en-us/windows-hardware/customize/desktop/customize-power-slider).
