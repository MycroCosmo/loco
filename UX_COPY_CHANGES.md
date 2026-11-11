# UX·문구 개선 기록

승인 범위는 [진단 6·7절](UX_COPY_DIAGNOSIS.md)과 F1~F6, CUX1~4·7이다. 앱은 해요체, CMS는 간결한 합니다체를 사용한다. 서버 코드, API 요청·응답, 데이터 식별자는 바꾸지 않는다.

## Flutter

| 위치 | 이전 | 이후 |
|---|---|---|
| 오류 | `로그인 실패: $e` 등 예외 원문 | 인증·권한·연결·입력·요청 제한을 구분한 원인과 다음 행동. 서버 본문·OAuth state를 표시하지 않음 |
| 사진 부분 성공 | 생성 실패와 혼동 가능한 예외, 수정 후 사진 실패는 일반 실패 | “장소는 저장됐어요. 일부 사진을 올리지 못했어요. 저장된 사진을 확인한 뒤 다시 추가해 주세요.” 지도방은 생성/수정 성공 후 표지 사진만 실패했음을 별도 표시. 프로필도 닉네임 저장 후 사진 실패를 별도 안내 |
| 지도방 생성 | 지도 만들기 / 지도 이름 | 지도방 만들기 / 지도방 이름 |
| 표지 | 썸네일 / 표지 이미지 | 표지 사진 |
| 공개 설정 | 비공개 설명만 제공 | 공개: 로그인한 누구나 조회. 비공개: 참여자만 조회하며 초대 코드를 받은 사람이 참여 가능 |
| 탈퇴 | 되돌릴 수 없음만 안내 | 재로그인 불가·되돌리기 불가·작성한 지도방/장소/리뷰는 남음 |
| 비밀번호 찾기 | 이메일로 코드를 전송했습니다 | “재설정 코드 발송을 요청했어요. 받은편지함과 스팸함을 확인해 주세요.” |
| 코드 입력 | 6자리 입력 | “코드는 15분 동안 유효해요” 추가. 계정 존재·도착 여부를 단정하지 않음 |
| 아이콘 | 이름 없는 목록·사진·닫기 버튼 | 한국어 tooltip/접근성 이름. SDK 한국어 localization으로 기본 뒤로 가기 등도 한국어 제공 |
| 로그인 | 고정 중앙 열, 회색 문구, 간편 로그인 글씨 확대 차단 | 스크롤·최대 폭 480·줄바꿈, ThemeData 색/글씨 역할, 글씨 확대 허용 |

`user_message.dart`는 화면 표현만 담당한다. 네트워크 실패는 반영 여부 확인도 안내한다. 장소 저장 후 사진 전송 실패 시 요청을 자동으로 재전송하지 않는다. 지도방·장소·권한 enum과 전송 값은 유지한다.

색상은 수정 화면의 오류/보조 글씨/표면/기본 동작을 ThemeData 역할로 바꿨다. 11~12px 보조 글씨는 `textTheme.bodySmall` 역할을 사용한다. 소셜 브랜드 배경·사진 위 오버레이 등 의미 있는 색은 유지하고 네이버 버튼 글자는 어두운 색으로 대비를 높였다. 다크 모드·큰 파일 분해는 포함하지 않는다.

`web/index.html`은 원본에 없던 Flutter 웹 진입 파일이다. `CardThemeData`로 현재 Flutter SDK와 호환된다. 테스트 전용 이미지 선택기에는 앱에서 이미 사용하는 image_picker의 플랫폼 인터페이스를 dev dependency로 명시했다. 새 UI 라이브러리는 없다.

### 검증과 캡처

- Flutter 3.44.8 / Dart 3.12.2. 기존 렌더링 테스트 포함 **16 성공 / 0 실패**, **6.40초**. 오류 원문 배제, 320×568·글씨 2배·키보드 표시, 중립 재설정 안내/15분, 실제 지도방 생성→표지 사진 500과 닉네임 저장→사진 500의 부분 성공을 검증한다. API는 테스트 대역만 사용한다.
- `flutter analyze` 실행. 오류·경고 0, info 11건(분석 6.0초); 기존 `dart:html`, `withOpacity`, splash const 관련 info는 범위 밖으로 남긴다. info가 있는 기본 analyze의 종료 코드는 1이므로 이를 무경고 통과로 표기하지 않는다.
- `flutter build web --profile --no-web-resources-cdn --dart-define=API_BASE_URL=http://localhost:8081/api --dart-define=SERVER_URL=http://localhost:8081` 성공: **41.1초**. JS 웹 빌드 기준이며 기존 secure-storage의 Wasm dry-run 경고는 별도 이관 후보다.
- before: e9bce8e의 앱을 임시 복사본에서 CardThemeData·웹 진입 파일만 호환 수정한 빌드. after: 이 브랜치의 실제 앱 빌드. 390×844 / 1280×720, 같은 가짜 회원/API 응답. 실제 사용자 계정·외부 API를 쓰지 않는다.
- [before 이미지](docs/ux-copy/before) / [after 이미지](docs/ux-copy/after): 로그인, 재설정 코드, 지도방 생성, 비공개 설정, 탈퇴 확인은 양쪽 폭에서 촬영했다. 390px에서는 회원가입·지도방 목록·프로필·프로필 수정·내 지도방 2종·내 장소·내 리뷰·즐겨찾기·장소 추가도 촬영했다. Flutter 총 40장, CMS 총 12장이다. 웹 캔버스에서 한글 폰트가 빠지지 않도록 캡처 도구만 로컬 `malgun.ttf`를 제공한다. 폰트 파일은 커밋하지 않는다. `UX_CAPTURE_FONT`로 로컬 대체 경로를 지정할 수 있다.
- 재실행: `apps/admin`에서 고정 Node 환경을 준비한 뒤 `node scripts/capture-flutter.mjs ../mobile/build/web after`. 캡처의 브라우저는 실제 미리보기 브라우저와 분리되어 있다.

## CMS

| 위치 | 이전 | 이후 |
|---|---|---|
| 회원 등 필터 | 빈 선택칸, 전체/ALL 중복 | 기본값 전체를 명시. 입력 중인 조건과 마지막으로 조회에 성공한 적용 조건을 구분 |
| 목록 배치 | ID·기간·일반 검색을 같은 비중으로 세로 나열 | 검색·상태는 먼저 표시, ID·기간·개수는 추가 조건으로 분리. 파일 목록을 업로드 폼보다 먼저 표시 |
| 조회 실패 | 오류와 “결과 없음”이 동시에 표시될 수 있음 | 최초 실패 / 성공한 빈 결과 / 재조회 실패 후 이전 결과를 구분. 새 조건은 성공한 뒤에만 적용 표시 |
| 로그인 | 관리자 생성 CLI 안내 | “관리자 계정으로 로그인하세요. LOCO 회원 계정으로는 로그인할 수 없습니다.” |
| 권한 | MANAGE·READ·deny·합집합 | “관리(MANAGE)에는 조회(READ)가 포함됩니다. 여러 역할의 허용 권한을 합산하며, 상속이나 거부 설정은 없습니다.” |
| 메뉴 | GROUP / PAGE / 루트 / revision | 메뉴 묶음 / 화면 연결 / 최상위 / 변경 번호. 전송 값은 유지 |
| 파일 목록·삭제 | ACTIVE 파일 목록 / 물리 삭제·재정리 | 사용 가능한 파일 / 목록에서 삭제했고 저장소 삭제 지연 시 자동 재처리됨을 안내 |
| 신고 종결 | 상태 변경 적용 | 검토 시작 / 검토 완료로 종결 / 기각으로 종결. 바로 옆에 종결 후 메모 수정·재개 불가 표시 |
| 확인 대화상자 | 확인 | 파일 삭제 / 계정 활성화·비활성화 / 역할 배정 저장 / 역할 삭제 / 메뉴 이동·삭제 |
| CSRF | 보안 토큰 만료·갱신 요구 | 실제 새 토큰을 받은 뒤에만 “보안 확인 정보를 새로 받았습니다.” 표시. 실패 시 새로 받았다고 말하지 않음 |
| 상세 버튼 | 짧은 ID만 붙은 상세 | 화면에는 “상세 보기”, 접근 가능한 이름에는 전체 대상 ID를 포함 |

새 `queryState.ts`·`QueryStatus.vue`는 목록 조회 상태를 쓰기 작업 오류와 분리한다. 이전 응답이 새 화면의 조회 상태를 덮지 않도록 요청 세대를 검사한다. 기존 composable 방식이며 별도 상태 관리 패키지는 없다. `displayLabels.ts`는 표시만 번역하고 요청 enum·권한 키를 변경하지 않는다.

### 검증과 캡처

- 고정 Node 24.22.2 / npm 11.19.1. **Vitest 29 성공 / 0 실패**, 0.251초. 타입 검사·Vite build 성공, 단위 테스트와 빌드 명령 합계 4.40초.
- **Chromium E2E 13 성공 / 0 실패**: 핵심 흐름 11건 + 명시적 캡처 2건. 브라우저 검사 24.6초, 격리 호스트 시작·종료 포함 **53.462초**. 로그인·첫 비밀번호 변경, CSRF 재발급/자동 재전송 없음, 세션 만료, 메뉴 revision 충돌·확인 대화상자 포커스 복귀, SeaweedFS 파일 업로드·다운로드, 신고 처리 충돌/종결, 권한 회수, 필터 기본값/적용 조건/실패·빈 결과를 검증한다.
- 재실행: `apps/admin`에서 `. ./prepare-node.ps1`, `npm run test:unit`, `npm run build`, `npm run test:e2e -- core.spec.ts`. E2E 호스트에는 Java 21과 Docker가 필요하다. `UX_CAPTURE_PHASE=after`로 `npm run test:e2e`를 실행하면 1280×720·390×844의 로그인·회원 조회·신고 처리 full-page 캡처도 함께 갱신한다. 일반 실행에서는 캡처 2건을 명시적으로 skip한다.
- 실행 중 확인한 테스트 문제: 고정 시계 때문에 같은 시각의 이력 첫 행을 종결 이력으로 가정할 수 없었다. 두 행의 실제 내용을 읽고 `IN_REVIEW`/`RESOLVED` 및 hasNote·원문 비노출을 검사하도록 고쳤으며, 상세 응답 완료를 기다린다. 서버 정렬·시간을 바꾸지 않았다.
- 별도 회차에서 기존 호스트 `/reset`이 검사 시작 전 500을 반환했다(12 성공·1 실패, 61.235초). 호스트가 예외를 기록하지 않아 세부 원인은 확인하지 못했다. 자동 재시도나 검사 제외 없이 새 호스트에서 전체 13건을 다시 실행해 위 결과를 얻었다. 서버 테스트 호스트 수정은 이번 범위에 넣지 않았다.
- 서버 파일 변경은 0건이다. 서버 allTests는 실행하지 않았다. 요청한 프런트 검증과 기존 격리 CMS E2E를 수행했다.

## 화면 전후 비교 (390px)

1280px 앱 주요 화면과 CMS는 위 이미지 폴더에도 포함되어 있다. 아래 화면은 같은 가짜 데이터로 비교한다.

| 화면 | 변경 전 | 변경 후 |
|---|---|---|
| 로그인 | [before](docs/ux-copy/before/flutter-login-390.png) | [after](docs/ux-copy/after/flutter-login-390.png) |
| 재설정 코드 | [before](docs/ux-copy/before/flutter-reset-390.png) | [after](docs/ux-copy/after/flutter-reset-390.png) |
| 회원가입 | [before](docs/ux-copy/before/flutter-register-390.png) | [after](docs/ux-copy/after/flutter-register-390.png) |
| 지도방 목록 | [before](docs/ux-copy/before/flutter-map-list-390.png) | [after](docs/ux-copy/after/flutter-map-list-390.png) |
| 지도방 생성 | [before](docs/ux-copy/before/flutter-map-create-390.png) | [after](docs/ux-copy/after/flutter-map-create-390.png) |
| 비공개 설정 | [before](docs/ux-copy/before/flutter-map-private-390.png) | [after](docs/ux-copy/after/flutter-map-private-390.png) |
| 내 정보 | [before](docs/ux-copy/before/flutter-profile-390.png) | [after](docs/ux-copy/after/flutter-profile-390.png) |
| 프로필 수정 | [before](docs/ux-copy/before/flutter-profile-edit-390.png) | [after](docs/ux-copy/after/flutter-profile-edit-390.png) |
| 탈퇴 확인 | [before](docs/ux-copy/before/flutter-withdraw-390.png) | [after](docs/ux-copy/after/flutter-withdraw-390.png) |
| 만든 지도방 | [before](docs/ux-copy/before/flutter-owned-maps-390.png) | [after](docs/ux-copy/after/flutter-owned-maps-390.png) |
| 참여 지도방 | [before](docs/ux-copy/before/flutter-joined-maps-390.png) | [after](docs/ux-copy/after/flutter-joined-maps-390.png) |
| 등록한 장소 | [before](docs/ux-copy/before/flutter-my-places-390.png) | [after](docs/ux-copy/after/flutter-my-places-390.png) |
| 작성한 리뷰 | [before](docs/ux-copy/before/flutter-my-reviews-390.png) | [after](docs/ux-copy/after/flutter-my-reviews-390.png) |
| 즐겨찾기 | [before](docs/ux-copy/before/flutter-favorites-390.png) | [after](docs/ux-copy/after/flutter-favorites-390.png) |
| 장소 추가 | [before](docs/ux-copy/before/flutter-place-add-390.png) | [after](docs/ux-copy/after/flutter-place-add-390.png) |
| CMS 로그인 | [before](docs/ux-copy/before/cms-login-390.png) | [after](docs/ux-copy/after/cms-login-390.png) |
| CMS 회원 조회 | [before](docs/ux-copy/before/cms-users-390.png) | [after](docs/ux-copy/after/cms-users-390.png) |
| CMS 신고 처리 | [before](docs/ux-copy/before/cms-report-390.png) | [after](docs/ux-copy/after/cms-report-390.png) |

## 커밋 단위와 검증 범위

1. `19e06ed`: Flutter 오류·말투·용어·로그인·접근성, 첫 15건 검증과 주요 화면 캡처.
2. `99551ab`: CMS 문구·조회 상태·조건·종결 버튼, Vitest 및 Chromium 회귀와 캡처.
3. 후속 마무리: 앱의 남은 완료·빈 상태 문구, ThemeData 역할, 프로필 부분 성공, 접근성 이름 및 캡처 확대. API와 저장 순서는 유지한다.

원문 예외를 화면에 넣는 `$e`/`$error`/서버 message 직접 표시 경로를 소스로 재검색했다. 서버 코드와 `/api/**` 계약 파일은 변경하지 않았다. 캡처에는 가짜 계정만 사용하며 실제 메일·OAuth·카카오 검색·FCM을 호출하지 않는다. Flutter 지도 배경 자체의 외부 SDK 렌더링은 이번 캡처 검증 대상이 아니다. Impeccable hook·Live·Apply는 사용하지 않았다.

## 범위 밖 후속 제안

큰 화면 파일 분해, 다크 모드, 새 UI/상태 관리 라이브러리, 코드 재전송은 구현하지 않았다. Flutter 웹 전용 API의 장기 대체와 실제 모바일 기기·스크린리더 검증은 후속 작업이다.

프로필 수정 화면에 직접 주소로 진입할 때 기존 닉네임이 비어 보이는 현상은 before/after 양쪽에서 관찰했다. 비동기 사용자 정보의 초기 폼 반영은 후속 검토 후보이며 이번 문구 변경으로 해결했다고 주장하지 않는다.
