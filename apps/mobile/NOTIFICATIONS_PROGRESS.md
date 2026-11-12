# 앱 알림 구현 진행

## 1. 토큰 등록·해제 생명주기

### 문제

앱 시작 시 인증 전에 FCM 초기화를 실행하고, 성공 여부와 무관하게 초기화 완료로 표시했다. 첫 등록이 401이거나 Firebase 초기화가 실패하면 로그인 후에도 재등록하지 않았다. 회원가입·소셜 로그인에는 등록 호출이 없었다.

저장한 `notif_enabled=false`를 초기화에서 읽지 않았고, 로그아웃 뒤 토큰 갱신 구독도 남았다. 단말 토큰 삭제와 서버 해제를 하나의 try 블록에서 실행해 앞의 실패가 뒤의 정리를 막았다.

### 수정

- 인증 성공의 공통 토큰 저장 지점과 저장된 로그인 복원 후 등록한다. 알림 초기화 실패가 로그인 성공을 실패로 바꾸지 않는다.
- 로그인과 기존 알림 설정을 확인한 뒤 권한 요청·토큰 등록을 진행한다. Firebase 초기화·등록 실패는 다음 초기화 호출에서 다시 시도할 수 있다. 주기 재시도는 추가하지 않았다.
- 갱신·수신 구독을 한 번만 유지하고, 알림 해제·로그아웃·세션 만료·탈퇴 때 정리한다.
- 등록/해제 작업을 순서대로 실행한다. 해제 시 세대 번호를 바꿔 늦게 도착한 SDK 토큰과 대기 중인 갱신을 무효화하고, 이미 전송 중인 등록 뒤에는 해제 요청을 보낸다.
- 등록 요청의 401 처리에서 정리 큐를 기다리지 않아 자기 요청의 완료를 기다리는 교착을 피한다.
- 서버 토큰 해제와 단말 토큰 삭제는 각각 시도한다. 만료·탈퇴 뒤에는 인증 없는 서버 변경 요청을 보내지 않는다.
- 웹 FCM은 기존처럼 미지원이다. 웹 로그아웃으로 같은 계정의 휴대폰 토큰을 지우지 않는다.

새 라이브러리·서버 코드·API 계약 변경은 없다. `NotificationService`의 Firebase 연결 함수와 `AuthService`의 알림 서비스만 주입 가능하게 해 실제 Firebase 없이 경계를 검증한다.

### 검증

Flutter 3.44.8 / Dart 3.12.2, Windows. 실제 Firebase·FCM·Google OAuth·서비스 계정 값 사용 없음. 테스트의 토큰·메일 주소는 합성 값이다.

| 범위 | 결과 | 명령 전체 시간 |
|---|---|---|
| Flutter 전체 테스트 | 34 성공·0 실패·0 skip, 새 알림 테스트 17 포함 | 5.556초 |
| flutter analyze | 오류 0·경고 0·기존 info 11, 종료 코드 1 | 62.755초(분석 59.7초) |
| profile JS 웹 빌드 | 성공 | 51.194초(컴파일 49.5초) |

새 테스트는 로그인 전/후, 저장된 알림 끄기, 초기화·등록 실패 후 재시도, 권한 거절 후 허용, 중복 구독, 늦은 SDK 토큰, 전송 중 등록과 해제 순서, 서버/단말 삭제 실패 격리, 세션 만료와 401 큐 경합, 회원가입·일반/소셜 로그인·로그인 복원·로그아웃을 검사한다.

기존 info는 `dart:html`, `withOpacity`, splash const 관련이다. 웹 빌드의 기존 secure-storage Wasm dry-run 경고는 남으며 Wasm 성공을 뜻하지 않는다. 이번에는 서버 변경이 없어 Gradle 전체 회귀는 재실행하지 않았다.

```powershell
cd apps/mobile
flutter test --no-pub --reporter expanded
flutter analyze --no-pub
flutter build web --profile --no-pub --no-web-resources-cdn --dart-define=API_BASE_URL=http://localhost:8081/api --dart-define=SERVER_URL=http://localhost:8081
```

원본 로컬 로그는 `build/notification-all-tests.log`, `build/notification-analyze.log`, `build/notification-web-build.log`이며 Git에는 포함하지 않는다.

## 남은 작업과 한계

- 앱 실행 중 수신은 아직 로그만 남긴다. 사용자에게 보이는 알림 표시 방식은 다음 결정 대상이다.
- 알림 클릭 후 지도방/장소 이동, 삭제된 대상·접근 권한 없음·로그인 필요 처리는 다음 단계다.
- 실제 Firebase 설정·Android/iOS 단말 수신, 앱 종료/백그라운드 상태, iOS APNs 준비 시점은 미검증이다. SDK 대역 테스트와 웹 빌드로 이를 검증했다고 주장하지 않는다.
- 기존 마이페이지 스위치는 앱의 저장된 선호이며 OS 권한 상태 표시가 아니다. OS 거절 안내/설정 이동은 후속 UI 범위다.
- 해제는 최선 노력이다. 서버와 SDK 양쪽이 실패하면 원격 토큰 잔존 가능성이 있다. 영속 재시도·다중 단말 토큰 저장은 이번 범위가 아니다. 서버의 회원당 토큰 한 개 계약을 유지한다.
- 알림 작업 직렬화 때문에 진행 중인 SDK 호출이 지연되면 해제도 기다린다. 별도 SDK timeout 정책은 추가하지 않았다.

근거: [Firebase Flutter 클라이언트 설정](https://firebase.google.com/docs/cloud-messaging/flutter/get-started), [메시지 수신](https://firebase.google.com/docs/cloud-messaging/flutter/receive-messages). 서버 구현·대역 검증 범위는 [NOTIFICATIONS_DESIGN.md](../server-v3/NOTIFICATIONS_DESIGN.md)를 따른다.
