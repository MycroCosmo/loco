# Flutter → 로컬 v3 수동 확인

이 문서는 **실행 절차와 아직 수행하지 않은 체크리스트**다. 서버 자동 비교 테스트를 Flutter 화면 검증으로 대신 표시하지 않는다. 현재 이관 기준과 차이는 [MIGRATION_SUMMARY.md](MIGRATION_SUMMARY.md)를 참고한다. 실제 키·서비스 계정·비밀번호·토큰 값은 문서, 캡처, 커밋에 남기지 않는다.

## 1. 준비와 외부 호출 범위

필수: Docker Desktop(Linux 컨테이너), Flutter SDK 및 웹 지원, PowerShell. 아래는 debug 앱을 기준으로 한다. [ApiConstants](../mobile/lib/core/constants/api_constants.dart)는 release에서 HTTPS/비로컬 주소를 요구하므로 로컬 HTTP 확인에 release를 쓰지 않는다.

| 범위 | 외부 서비스 키 없이 확인 가능한 내용 | 제한 |
| --- | --- | --- |
| 이메일 인증 | 회원가입·로그인·재발급·로그아웃·탈퇴 회원 차단 | 서버 로컬 JWT_SECRET은 필요. 외부 API 키가 아님 |
| 비밀번호 복구 | Mailpit 수신→코드 입력→재설정→기존 토큰/비밀번호 확인 | 실제 SMTP를 쓰지 않도록 아래 설정 확인 |
| 사용자·지도방·즐겨찾기·리뷰·신고·피드백·이미지 | 로컬 DB/파일/API 흐름 | 지도를 렌더링하는 화면은 아래 지도 SDK 제약 별도. 테스트 회원/지도/장소 데이터 필요 |
| 지도 배경·화면상의 마커 | 키 없는 화면 성공을 보장하지 않음 | 현재 Flutter의 KakaoMapWidget은 기존 코드의 JavaScript 키로 외부 SDK를 로드한다. 서버 키가 비어 있어도 SDK 요청이 나간다 |
| 새 장소의 앱 검색→등록 | 실제 확인에는 카카오 검색 설정 필요 | PlaceAddScreen은 검색 결과 선택을 요구한다. 키 없이 수동 좌표만 입력하는 화면은 없음. 로컬 API로 장소를 미리 만들면 후속 읽기/리뷰/이미지 검증 가능 |
| OAuth | 로컬 자동 계약 테스트로 서버 동작만 확인 가능 | 실제 로그인에는 제공자 앱 설정·등록 redirect URI·해당 client 설정 필요. 현재 앱 OAuth는 웹만 지원 |
| FCM | 설정 없음/잘못된 설정의 비활성 로그, 로컬 자동 대역 테스트 | 실제 단말 수신에는 Firebase 프로젝트·플랫폼 설정·서버 서비스 계정 필요. 현재 웹 앱은 FirebaseOptions 없이 FCM을 명시적으로 건너뜀 |

**외부 호출을 하지 않는 수동 확인**에서는 서버 외부 설정을 비우는 것에 더해, 지도 SDK 화면을 열기 전에 브라우저 개발자 도구의 요청 차단으로 `*://dapi.kakao.com/*`, `*://*.kakao.com/*`, `*://*.kakaocdn.net/*`를 차단하고 Network에서 확인한다. OAuth 버튼은 누르지 않는다. 기존 앱의 내장 키를 새 키처럼 복사하거나 문서에 옮기지 않는다. 지도 관련 화면의 SDK 의존 동작은 “미확인/차단”으로 기록한다. 패키지·이미지 다운로드는 업무 외부 API 호출과 구별한다.

네이티브 앱은 Firebase 설정이 이미 설치되어 있으면 시작할 때 토큰 획득을 시도할 수 있다. 서버 FIREBASE_SERVICE_ACCOUNT를 비우는 것만으로 앱의 Google 접속까지 막을 수 없다. 외부 호출을 금지한 실행에서는 웹을 우선 사용한다.

## 2. v3와 Mailpit 기동

저장소 루트에서 진행한다. 기존 `.env`를 덮어쓰지 않는다.

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

루트 `.env`에서 `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`과 CMS 저장소용 `CMS_STORAGE_ACCESS_KEY`, `CMS_STORAGE_SECRET_KEY`를 로컬용으로 채운다. CMS 키는 로컬 SeaweedFS 접속용이며 실제 AWS 키를 쓰지 않는다. JWT_SECRET은 최소 32 UTF-8 바이트다. 파일은 Git에 넣지 않는다. 외부 키 없는 실행에서는 `FIREBASE_SERVICE_ACCOUNT`, `KAKAO_REST_API_KEY`, `KAKAO_CLIENT_SECRET`, `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET`이 비어 있는지 **값을 출력하지 않고** 확인한다. 셸 환경변수가 `.env`를 덮어쓸 수 있으므로 기존 외부 설정도 확인한다.

SMTP 항목은 아래 로컬 값으로 맞춘다. 기존 `.env`의 실제 SMTP 설정이 Compose 기본값보다 우선한다.

```dotenv
SMTP_HOST=mailpit
SMTP_PORT=1025
SMTP_USER=noreply@loco.app
SMTP_PASS=
SMTP_SECURE=false
```

별도 Compose 프로젝트 이름을 써서 기존 개발 DB/업로드 볼륨과 분리한다. **포트는 분리되지 않으므로** 이미 8081/8025/1025를 쓰는 스택이 있으면 먼저 식별하고 그 실행을 종료한 뒤 시작한다. 기존 데이터 삭제나 `down -v`는 하지 않는다.

```powershell
docker compose -p loco-v3-smoke config --quiet
docker compose -p loco-v3-smoke up -d --build db mailpit v3
docker compose -p loco-v3-smoke ps
Invoke-RestMethod http://localhost:8081/api/health
```

기대 결과: db/mailpit/storage/v3 healthy, health JSON `{"status":"ok"}`. Flyway가 비어 있는 smoke DB에 V1과 현재 CMS 마이그레이션(V2~V5)을 적용한다. SeaweedFS는 v3의 의존 서비스로 함께 시작하며 LOCO /uploads는 기존 볼륨을 유지한다. v2 서비스는 이 명령으로 시작하지 않는다. 기존 개발 스택에서 v2가 실행 중이라면 앱이 그쪽 3000 포트로 연결되지 않는지 특히 확인한다.

Mailpit UI: [http://localhost:8025](http://localhost:8025). v3는 [Compose](../../compose.yaml)의 CPU 2/1.5GB·UID/GID 1000:1000으로 실행하고 업로드 볼륨을 사용한다. Mailpit CPU 0.5/256MB, DB CPU 2/4GB·shared_buffers 1GB다. DB는 호스트에 직접 공개되지 않는다.

문제 확인은 `docker compose -p loco-v3-smoke logs --tail 80 v3`로 하되 로그를 공유하기 전에 개인정보·토큰을 가린다. Firebase 설정이 비어 있을 때 발송 비활성 로그는 정상이다. 기존 DB를 사용하려고 baselineOnMigrate=true를 임의로 추가하지 않는다.

## 3. 웹 앱: CORS로 직접 연결 또는 같은 출처 프록시

`API_BASE_URL`은 `/api`를 포함하고 `SERVER_URL`은 포함하지 않는다. 두 값 모두 `--dart-define`이 Flutter `.env`보다 우선한다. 둘 중 하나라도 기존 3000으로 남으면 API와 이미지가 서로 다른 서버로 갈 수 있다.

PR 전 보완으로 [GlobalHttpFilter](src/main/java/com/loco/web/GlobalHttpFilter.java)에 v2 호환 CORS를 구현했다. 직접 연결하려면 루트 `.env`에 `ALLOWED_ORIGINS=http://localhost:5173`을 명시하고 `docker compose -p loco-v3-smoke up -d --build v3`로 적용한다. 기본 허용값은 8080/3000이라 5173은 명시해야 한다. HOST는 Compose에서 기본 0.0.0.0을 유지한다.

```powershell
cd apps/mobile
if (-not (Test-Path .env)) { New-Item -ItemType File .env | Out-Null }
flutter pub get
flutter run -d chrome --web-port 5173 `
  --dart-define=API_BASE_URL=http://localhost:8081/api `
  --dart-define=SERVER_URL=http://localhost:8081
```

- [ ] 앱 origin은 localhost:5173, API·이미지는 localhost:8081이며 3000으로 요청하지 않는다.
- [ ] 일반 로그인/401/Validation400/404 응답을 앱이 읽을 수 있다. 미허용 Origin은 서버가403을 내는 것이 아니라 브라우저가 응답 읽기를 막는다.
- [ ] 깨진 JSON·10MiB 초과·미지원 charset의 조기400/413/415는 **허용 Origin이어도 CORS 헤더가 없다**. 앱에서 실제 상태 대신 CORS 오류로 보이는 것은 현재 유지한 v2 동작이며 개선 후보다.

### 선택: 동일 출처 프록시

직접 연결 대신 아래 임시 nginx로 웹·API·업로드를 localhost:18081 한 출처로 전달할 수 있다. 이 프록시는 수동 확인용이며 3단계 운영 nginx 구현이 아니다. 같은 출처 요청은 위 CORS 읽기 제한과 별개이므로 어느 경로로 검증했는지 기록한다.

### 터미널 A: Flutter 개발 서버

```powershell
cd apps/mobile
# main.dart가 항상 .env를 읽으므로 파일이 없을 때만 빈 파일을 만든다.
if (-not (Test-Path .env)) { New-Item -ItemType File .env | Out-Null }
flutter pub get
flutter run -d web-server --web-hostname 0.0.0.0 --web-port 5173 `
  --dart-define=API_BASE_URL=http://localhost:18081/api `
  --dart-define=SERVER_URL=http://localhost:18081
```

`0.0.0.0`은 Docker Desktop 프록시가 개발 서버에 접근하기 위한 바인딩이다. 신뢰하는 로컬 환경에서만 실행하고 끝나면 종료한다. 브라우저는 5173이 아니라 아래 18081로 연다. `web-server`는 Chrome 직접 디버그 모드보다 디버깅 기능이 제한된다. [Flutter 공식 웹 실행 문서](https://docs.flutter.dev/platform-integration/web/setup).

### 터미널 B: 임시 프록시

저장소 루트에서 실행한다. 설정은 운영 파일 대신 OS 임시 폴더에 만든다. `nginx:alpine`은 이 수동 절차용 도구 이미지이며 앱 런타임 의존성으로 추가하지 않는다. 재현 기록에는 실제 사용한 이미지 ID도 남긴다.

```powershell
$smokeProxyConfig = Join-Path $env:TEMP 'loco-v3-smoke-nginx.conf'
@'
server {
    listen 8080;
    client_max_body_size 32m;
    location /api/ {
        proxy_pass http://v3:8081;
        proxy_set_header Host $http_host;
    }
    location /uploads/ {
        proxy_pass http://v3:8081;
        proxy_set_header Host $http_host;
    }
    location / {
        proxy_pass http://host.docker.internal:5173;
        proxy_http_version 1.1;
        proxy_set_header Host $http_host;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
'@ | Set-Content -LiteralPath $smokeProxyConfig -Encoding ascii
docker run --rm --name loco-v3-smoke-proxy `
  --network loco-v3-smoke_default -p 127.0.0.1:18081:8080 `
  --mount "type=bind,source=$smokeProxyConfig,target=/etc/nginx/conf.d/default.conf,readonly" `
  nginx:alpine
```

Docker Desktop의 `host.docker.internal`로 호스트의 Flutter 개발 서버에 연결한다. 브라우저에서 [http://localhost:18081](http://localhost:18081)을 연다.

프록시의 32MiB는 앱의 10MiB 파일 검사보다 먼저 nginx가 일반 업로드를 거절하지 않게 하는 로컬 설정이다. 무제한 입력 검증이 아니라 10MiB 전후 확인에 사용한다. API가 보는 IP는 프록시의 것이므로 이 구성으로 `getRemoteAddr`의 운영 동작을 검증했다고 기록하지 않는다.

- [ ] Network에서 API가 `localhost:18081/api/...`, 이미지가 `localhost:18081/uploads/...`로 향한다.
- [ ] `http://localhost:18081/api/health`도 200이다. 3000으로 가는 요청이 없다.
- [ ] 외부 키 없는 실행에서는 카카오 도메인 차단을 켜고, 실제 SDK/검색 호출 성공으로 기록하지 않는다.
- [ ] 기존 브라우저 로그인 정보와 섞이지 않도록 테스트용 프로필을 사용한다. 두 계정 동시 확인에는 서로 다른 프로필을 사용한다.

### Android 등 다른 연결 방법

웹 절차가 기준이다. Android emulator의 호스트 API는 `http://10.0.2.2:8081/api`, 이미지 기본 주소는 `http://10.0.2.2:8081`이다. USB 실기기는 `adb reverse tcp:8081 tcp:8081` 후 두 주소를 `http://localhost:8081/api`, `http://localhost:8081`로 사용할 수 있다. `flutter devices`에서 고른 장치에 두 dart-define을 함께 전달한다.

다만 현재 Android manifest에는 명시적인 cleartext HTTP 허용이 없다. 대상 SDK/빌드의 네트워크 정책에 따라 `CLEARTEXT communication not permitted`로 막힐 수 있다. 이를 서버 실패로 기록하거나 앱 코드를 임의 변경하지 말고, 웹 동일 출처 절차로 확인하고 네이티브 개발 HTTPS/네트워크 설정은 별도 작업으로 남긴다. Compose가 127.0.0.1에만 공개되므로 실기기에서 PC의 LAN IP만 넣는 방법도 그대로 작동한다고 가정하지 않는다. iOS는 Mac/Xcode 및 프로젝트 설정을 별도 확인해야 하며 이번 Windows 문서 작업에서 빌드/실행을 검증하지 않았다.

## 4. 화면 흐름별 체크리스트

테스트용 서로 다른 회원 A(방장)·B(멤버)를 사용한다. 각 항목에 실제 확인 환경·결과를 기록한다. UI에서 불가능한 입력/동시성/시간 조작은 기존 자동 비교 테스트 영역이다.

| 화면·흐름 | 확인 항목 | 기대 결과·경계 |
| --- | --- | --- |
| 가입·로그인 | [ ] A/B 회원가입, 중복 이메일, 잘못된 입력·비밀번호, 정상 로그인 | v2와 같은 오류 표시, 정상 세션으로 지도방 목록 이동. 오류 시 회원 중복 생성 없음 |
| 세션·로그아웃 | [ ] 앱 재시작 후 유지, 로그아웃 후 보호 화면, 재로그인 | 인증 없는 요청 401. access 만료 후 refresh는 실제 만료를 기다리거나 별도 자동 테스트 결과로 구분. 토큰 값을 캡처하지 않음 |
| 비밀번호 찾기 | [ ] 요청→Mailpit 편지→코드/새 비밀번호 입력→재로그인 | 새 비밀번호 성공·기존 비밀번호 실패, 같은 코드 재사용 거절. 요청 제한을 넘으면 429를 앱이 표시하며 몰래 재시도하지 않음 |
| 내 프로필 | [ ] 닉네임 수정·재조회, JPEG/PNG/WebP·AVIF 프로필 업로드 | 상대 이미지 URL을 SERVER_URL과 결합해 표시, 페이지 갱신 후 유지. HEIC 실패는 공통 제약, 성공으로 표시하지 않음 |
| 지도방 목록·생성 | [ ] A가 공개/비공개방 생성, 내 소유/가입 목록, 수정·태그·커버 | 목록/상세/검색/페이지 이동 데이터 일치, 커버 새로고침 후 표시 |
| 지도방 가입·권한 | [ ] B 공개 가입, 비공개 코드 가입, 멤버 목록, 코드 재생성 후 이전 코드, 나가기·내보내기 | 새 코드만 유효, 비회원의 비공개 읽기 거절, 방장/회원 권한 구분. 두 계정의 창을 혼동하지 않음 |
| 장소 목록·상세·수정 | [ ] 준비한 장소의 목록/내 장소, 태그·검색·다음 페이지, 작성자 수정·삭제, 방장 삭제 | 평점·리뷰 수·이미지 표시 일치, 비작성자 수정 거절. 비공개방을 나간 작성자의 쓰기는 현재 v2 호환 예외로 별도 기록 |
| 리뷰 | [ ] A/B 리뷰 작성, 자기 리뷰 재작성·삭제, 내 리뷰 목록 | 같은 장소/회원은 리뷰 1개, 변경 후 장소 목록 평점·개수 갱신 |
| 장소 이미지 | [ ] 순차 업로드 최대3장, 추가 거절, 삭제 후 재업로드, 상세 새로고침 | URL·파일 표시 일치. UI는 순차 업로드/중복 삭제 방지; 서버 경쟁 보장은 자동 테스트로 별도 검증 |
| 즐겨찾기·피드백·신고 | [ ] 추가/목록/삭제·재추가, 피드백, 대상 신고 | 화면 성공·오류 문구, 목록 반영. UI에 없는 경계는 자동 테스트 참조. 비공개 정보 노출의 기존 한계는 요약 백로그에 기록 |
| 회원 탈퇴 | [ ] 테스트 회원 탈퇴→보관된 세션의 보호 화면 재요청 | 해당 회원 401, 새 로그인 차단. 토큰 정리·원자적 롤백은 자동 DB 검사 근거와 구분 |
| 지도방 삭제 | [ ] A가 타인 장소 있는 방 삭제의 확인/강제 삭제 흐름 | 관련 목록·가입 정보·즐겨찾기·장소/리뷰가 현재 계약대로 정리됨. 삭제한 테스트 데이터 외 자료는 건드리지 않음 |

**키 없는 장소 준비:** 앱의 검색 기반 신규 등록을 우회해 성공했다고 표시하지 않는다. A로 로그인한 로컬 테스트 세션에서 실제 `POST /api/maps/{mapId}/places`를 호출해 `name`, `latitude`, `longitude` 등 [장소 DTO](PLACE_API_DESIGN.md)의 필수 값을 제공한다. 예시 좌표 37.4444/127.8888은 외부 검색 결과가 아닌 수동 fixture임을 기록한다. 토큰은 실행기 메모리에서만 사용하고 저장·공유하지 않는다. DB에 직접 삽입하지 않아 마커 셀도 같은 API 경로로 생성되게 한다. 이후 앱을 새로고침해 목록·리뷰·이미지를 확인한다.

UI에서 동작이 막혀 API만 확인한 항목은 “API 확인, 화면 미확인”으로 남긴다. 수동 smoke는 모든 SQL 예산·동시 요청·롤백·429 경계를 다시 증명하는 검증이 아니다.

## 5. 실제 외부 설정을 승인받은 환경에서만 확인할 항목

이 절은 실행 권한이 자동으로 생기는 절차가 아니다. 이번 문서 작성과 자동 회귀에서는 실제 Kakao/FCM/Google OAuth를 호출하지 않았다. 해당 환경의 소유자가 실제 호출을 허용하고 로컬 설정을 준비했을 때 수행한다.

| 항목 | 준비할 설정 이름·위치 | 수동 확인 |
| --- | --- | --- |
| 카카오 지도 SDK | 현재 Flutter `KakaoMapWidget`의 JS 키 관리와 허용 도메인 확인. 서버 REST 키와 별개이며 현재 코드에 임의 dart-define으로 JS 키를 교체하는 기능은 없음 | [ ] 지도 배경·줌·범위 이동·그룹/클러스터 펼치기. 로컬 origin 허용 여부와 실제 요청 대상 기록(키 값 제외) |
| 카카오 장소 검색 | 서버 루트 `.env`의 KAKAO_REST_API_KEY. 변경 후 v3 재생성 | [ ] 앱 검색→결과 선택→장소 추가→마커 반영. 오류는 고정500, timeout은 응답 무활동5초. 전체 body 시간 제한 없음 |
| 웹 OAuth | Flutter KAKAO_REST_API_KEY/NAVER_CLIENT_ID 및 제공자 redirect 등록. 서버 KAKAO_CLIENT_SECRET/NAVER_CLIENT_SECRET 등은 서버에만 둠 | [ ] 현재 origin의 `/oauth/{provider}/callback`으로 복귀, 신규/기존 연결 계정. 동일 이메일의 미연결 계정을 자동 연결하지 않음. 임시프록시 사용 시 origin은 localhost:18081 |
| 실제 FCM | 서버 FIREBASE_SERVICE_ACCOUNT, 네이티브 Firebase 프로젝트·앱 설정·알림 권한·실제 FCM 토큰 등록 | [ ] 로그인 회원의 fcm-token 저장 확인, 다른 회원 가입/장소 추가→수신자 단말 도착. foreground 로그와 OS 알림 배너를 구분. 현재 웹 FCM은 미지원이라 체크하지 않음 |

서버 비밀 값을 Flutter `.env` asset이나 dart-define에 넣지 않는다. 네이티브 초기 FCM 토큰 등록이 로그인 전에 발생했을 수 있으므로, 실제 알림 점검 전 로그인 회원에 토큰이 저장됐는지 별도로 확인한다. 앱 foreground 수신이 곧 자체 로컬 알림 UI 표시를 의미하지 않는다.

## 6. 종료와 기록

프록시 터미널에서 Ctrl+C(컨테이너 `--rm`), Flutter 터미널에서 종료한 뒤 저장소 루트에서:

```powershell
docker compose -p loco-v3-smoke stop
```

DB/업로드 볼륨은 보존한다. 임시 프록시 설정은 해당 경로만 확인 후 삭제할 수 있으며 저장소 파일·기존 볼륨을 삭제하지 않는다. 앱의 테스트 계정 데이터와 Mailpit 메일에도 개인정보를 넣지 않는다.

기록 양식:

| 기록 항목 | 입력 |
| --- | --- |
| 커밋 / 날짜 / 실행자 | 미실행 |
| Flutter·브라우저/기기·Docker·임시 nginx 이미지 ID | 미실행 |
| API_BASE_URL / SERVER_URL (비밀 제외) | 미실행 |
| 외부 호출 차단 여부 / 승인된 외부 확인 범위 | 미실행 |
| 화면 통과 / 실패 / API만 확인 / 미확인 | 미실행 |
| 재현 순서·상태 코드·민감값 제거한 증거 | 미실행 |

문서 작성 시에는 소스·Compose 설정을 대조했으며 Flutter 실행, 임시 프록시 기동, 실제 단말 수신은 수행하지 않았다.
