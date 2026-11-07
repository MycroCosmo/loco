# 이미지 변환기: libvips CLI 8.15.3

A로 결정했으나 의사소통 오류로 B로 구현, 결과 검토 후 유지하기로 했다.
다른 주제(CMS 구조)에 대한 사용자의 “B” 답변을 변환기 선택으로 잘못 연결했다. ImageIO의 애니메이션 WebP 실패는 기술적 관측이며, 당시 B 전환을 승인받았다는 근거는 아니다.
운영 구현은 `ImageConverter` 뒤의 `VipsImageConverter`다. `webp-imageio:0.1.6`은 독립 검증용 테스트 의존성으로만 남겼다.

| 입력 | v2 읽기 | Windows Java 21 | Linux Java 21 | 업로드 API |
| --- | --- | --- | --- | --- |
| JPEG·PNG·일반 WebP | 성공 | 성공 | 성공 | 201 |
| 애니메이션 WebP | 첫 프레임 성공 | 첫 프레임 성공 | 첫 프레임 성공 | 201, 정적 WebP |
| AVIF | 성공 | 성공 | 성공 | 201, 알려진 차이 해제 |
| HEIC | 디코더 부족으로 실패 | 디코딩 실패 | 디코딩 성공 | 양 플랫폼 모두 v2와 같은 500 |

Windows 코덱 6건은 성공 5건·HEIC 제약 SKIPPED 1건, Linux는 6건 모두 성공이다.
HEIC 업로드는 디코더 가용성과 별도로 v2의 500 응답·DB와 기존 파일 보존·새 0바이트 WebP 잔여를 유지한다.
AVIF는 API 비교에서도 필수 통과 대상으로 전환했다.

고정 fixture의 SHA-256, 64×48 크기, WebP 시그니처, RGB 오차 24 이내를 확인한다.
애니메이션은 빨강 첫 프레임을 검증하며 파랑 두 번째 프레임과 구분한다. 양 플랫폼 출력 11개는 v2 sharp로도 독립 디코딩·픽셀 검증을 통과했다.
HTTP 비교는 투명 PNG, EXIF 비자동 회전, 512px 중앙 크롭, 작은 이미지 비확대, 크기 경계·multipart 오류·파일/DB 실패도 포함한다.

## 실행 파일과 설정

- Windows: `scripts/prepare-vips.ps1`이 공식 8.15.3 web 배포본을 `build/vips`에 준비한다. 다운로드 SHA-256을 고정했다.
- Linux: Dockerfile에서 공식 8.15.3 소스와 게시된 SHA-256을 검증해 빌드한다. OS별 하위 코덱 차이는 위 실제 테스트로 확인한다.
- `prepareVips`는 Windows의 테스트·`bootRun`에 연결된다. 수동 JAR 실행 또는 Linux 호스트는 CLI를 설치하고 PATH나 `.env`의 `VIPS_EXECUTABLE`을 지정한다.
- 메타데이터는 `vipsheader`, 읽기는 libvips → 임시 PNG → Java 이미지, 쓰기는 Java PNG → libvips WebP 품질 80이다. 중앙 크롭은 Java에서 수행한다.
- `ProcessBuilder`로 쉘 없이 내부 임시 파일명만 전달한다. 실행당 30초 제한, 네이티브 동시 작업 수 2, 종료 코드·경고 검사, 임시 파일 정리를 적용한다.
- CLI는 경고가 있어도 0으로 종료할 수 있으므로 디코딩 경고도 실패로 처리한다.

## 재검증

저장소 루트에서 Java 21·PowerShell 7·Docker를 준비한 뒤 실행한다.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
./apps/server-v3/scripts/verify-codec.ps1
./apps/server-v3/scripts/verify-user-images.ps1
```

코덱 스크립트는 같은 6건을 Windows와 Linux에서 실행한다. Linux는 UID/GID 1000:1000, CPU 2, 메모리 1536MB, 네트워크 차단 상태다.
Compose 스크립트는 임시 프로젝트에서 5종 변환, HEIC 오류, 공유 파일 읽기·교체·보존을 확인하고 자신이 만든 프로젝트만 정리한다. 기존 개발 볼륨은 건드리지 않는다.

- 코덱 보고서: `build/reports/codec/{windows,linux,local}/summary.json`, `summary.md`.
- 플랫폼 시간: `build/reports/codec/timing.json`. 이미지 준비 시간 제외, Windows Gradle 실행과 Linux 컨테이너 실행·종료는 포함한다.
- Compose 결과: `build/user-images-compose-result.json`.
- 기본 `gradlew test`에도 코덱 6건 포함. API 비교는 `comparisonTest`, 전체는 `allTests`.

공식 자료: [설치와 Windows 배포본](https://www.libvips.org/install.html), [애니메이션 WebP](https://www.libvips.org/API/8.17/ctor.Image.webpload.html).
