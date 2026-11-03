# 2번 묶음 1단계: 변환기 호환성 검증

필수 JPEG·PNG·WebP 읽기와 WebP 쓰기는 Windows·Linux Java 21에서 통과했다.
승인된 기준에 따라 A안을 유지하며 B(libvips 프로세스)로 전환하지 않는다. 사용자 확인 전 다음 GET/PUT me 단계로 넘어가지 않는다.

## 검증 범위와 결과

검증 후보는 `org.sejda.imageio:webp-imageio:0.1.6`이다. 현재는 **testRuntimeOnly**이며 운영 JAR에 포함되지 않는다.
검증용 `ImageConverter` 인터페이스 뒤에 `ImageIoConverter`를 두었다. HTTP·회원·업로드 저장·512px 크롭은 아직 연결하지 않았다.
실제 프로필 변환기는 POST 단계에서 이 경계를 이용해 연결하고 크롭·작은 이미지·메타데이터 등 추가 동작을 검증한다.

| 입력 | v2 sharp 0.33.5 | Windows Java 21.0.5 | Linux Java 21.0.13 | 판정 |
| --- | --- | --- | --- | --- |
| JPEG | 읽기 성공 | 읽기·WebP 쓰기 성공 | 읽기·WebP 쓰기 성공 | 필수 통과 |
| PNG | 읽기 성공 | 읽기·WebP 쓰기 성공 | 읽기·WebP 쓰기 성공 | 필수 통과 |
| WebP | 읽기 성공 | 읽기·WebP 쓰기 성공 | 읽기·WebP 쓰기 성공 | 필수 통과 |
| HEIC(HEVC) | 메타데이터 성공, 디코더 부족으로 전체 읽기 실패 | reader 없음 | reader 없음 | SHARED_LIMITATION: 공통 지원 제약 |
| AVIF(AV1) | 읽기 성공 | reader 없음 | reader 없음 | KNOWN_DIFFERENCE: 알려진 차이 |

HEIC를 v2는 지원하는데 v3만 실패하는 것으로 기록하지 않는다. v2 실제 오류와 fixture 해시는
`src/test/resources/codec/v2-observation.json`에 고정했다. 이는 코덱 관측이며 HTTP 상태·오류 문구 관측은 아니다.
독립 libheif 도구가 HEIC fixture를 디코딩했으므로 손상 파일을 미지원으로 오인한 결과가 아니다.

각 필수 형식은 원본·출력의 64×48 크기와 네 사분면 색상, 출력 RIFF/WEBP 시그니처를 확인했다.
출력 6개(3형식 × 2플랫폼)는 v2 sharp로도 형식·크기·픽셀 교차 검증을 통과했다.
두 선택 형식의 reader 부재만 알려진 제약으로 처리한다. JNI 로딩 오류·잘못된 출력·필수 형식 실패는 예외 처리하지 않고 검증을 실패시킨다.

## 실행과 보고서

Java 21, PowerShell 7, Docker를 준비한 뒤 저장소 루트에서 실행한다.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
./apps/server-v3/scripts/verify-codec.ps1
```

스크립트는 Windows의 `codecTest prepareCodecProbe` 실행 후 같은 클래스·fixture·의존성으로 Linux를 검증한다.
Linux 이미지는 Temurin 21 JRE Jammy digest로 고정하고 UID/GID 1000:1000, CPU 2, 메모리 1536MB, 네트워크 차단 상태에서 실행한다.
이 검증으로 개발 Compose나 기존 업로드 볼륨을 변경하지 않는다. Compose 계정 통일은 이미지 업로드 단계에서 적용한다.

- Windows JUnit HTML: `build/reports/tests/codecTest/index.html`.
- Windows 결과: `build/reports/codec/windows/summary.json`, `summary.md`.
- Linux 결과: `build/reports/codec/linux/summary.json`, `summary.md`.
- 명령 실행 시간: `build/reports/codec/timing.json`. Windows는 Gradle·준비 작업 포함, Linux는 컨테이너 실행·종료 포함. 이미지 pull은 제외한다.
- 기본 `gradlew test`에도 5건을 포함하며 결과는 `build/reports/codec/local/`에 남긴다. 기본 테스트에서 v2 서버를 띄우지 않는다.
- JUnit에서는 HEIC·AVIF가 사유를 가진 SKIPPED로 표시된다. 콘솔과 별도 보고서에도 공통 제약 1건·실제 차이 1건이 보인다. 성공 5건으로 집계하지 않는다.

v2 기준을 다시 관측하려면 저장소 루트에서 다음과 같이 실행한다. 보고서의 baseline을 교체하기 전 차이를 검토한다.

```powershell
docker build -t loco-v2-codec -f infra/docker/server-v2.Dockerfile .
$fixtures = (Resolve-Path apps/server-v3/src/test/resources/codec).Path
$scripts = (Resolve-Path apps/server-v3/scripts).Path
docker run --rm --network none --user 1000:1000 `
  --mount "type=bind,source=$fixtures,target=/fixtures,readonly" `
  --mount "type=bind,source=$scripts,target=/probe,readonly" `
  --entrypoint node loco-v2-codec /probe/codec-fixtures.cjs observe /fixtures
```

Java 출력의 독립 디코딩 확인은 같은 이미지에서 `verify-outputs /results`를 실행한다.

```powershell
$reports = (Resolve-Path apps/server-v3/build/reports/codec).Path
docker run --rm --network none --user 1000:1000 `
  --mount "type=bind,source=$reports,target=/results,readonly" `
  --mount "type=bind,source=$scripts,target=/probe,readonly" `
  --entrypoint node loco-v2-codec /probe/codec-fixtures.cjs verify-outputs /results
```

## 다음 단계에 적용할 결정

로컬 디스크 유지, 이미지 의미 비교, 이미지 DB 반영은 TransactionTemplate을 사용한다.
공유 볼륨을 붙일 때 개발 Compose의 v3를 v2 node와 같은 UID/GID 1000:1000으로 실행하고 기존 볼륨 데이터는 지우지 않는다.
HEIC·AVIF는 현재 승인된 지원 제약으로 유지하되, POST API 구현 시 실제 v2 HTTP 응답과의 차이도 별도 비교 보고서에 남긴다.
형식 이름만으로 손상 파일이나 서버 오류까지 알려진 차이로 처리하지 않는다.

현재 검증은 작은 코덱 시험이다. 프로필 크롭·EXIF·투명도·대용량/손상 이미지·multipart 오류·파일/DB 실패 순서는 후속 단계에서 검증한다.
라이브러리 공식 사용법: [WebP ImageIO](https://github.com/sejda-pdf/webp-imageio).
