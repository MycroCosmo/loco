# LOCO · CMS UX/UI와 문구 진단

2026-11-11 · 진단만 수행 · 제안 미적용

기준: main `4d241baab51ef799e33fd0c98c00c62a74810081`에서 생성한 `feature/ux-copy`.
앱·서버·테스트·의존성·API 계약·업무 데이터는 변경하지 않았다.

## 1. 요약

핵심은 새 디자인을 덧씌우는 것이 아니라, **다음 행동과 현재 상태를 이해하기 쉽게 만드는 것**이다.

| 우선 | 대상 | 진단 | 개선 방향 — 아직 구현하지 않음 |
|---|---|---|---|
| P1 | Flutter | 예외 원문이 오류 안내로 노출되고 복구 행동이 약하다 | 실패 원인별 사용자 문구, 입력 보존, 가능한 재시도 경로 |
| P1 | Flutter | 회색 안내문 대비와 글자 확대·아이콘 이름에 문제가 있다 | Material 3 색상/문자 역할, 글자 확대, 접근 가능한 이름 |
| P1 | CMS | 첫 조회의 select가 빈칸이라 적용 범위를 알기 어렵다 | 실제 기본 범위를 명시하고 적용 조건 표시 |
| P1 | CMS | 되돌릴 수 없는 신고 종결도 버튼은 ‘상태 변경 적용’이다 | 선택한 결과를 구체적으로 말하는 버튼·최종 안내 |
| P1 | CMS | 조회 실패와 빈 결과/이전 결과의 표시 조건이 분리되지 않는다 | 실패·정상 빈 결과·이전 결과를 구별하는 상태 안내 |
| P2 | 공통 | 지도/지도방, enum·권한 코드·내부 구현 용어가 사용자 언어와 섞인다 | 용어 통일, 한국어 주표시, 필요한 코드·제약은 보조 설명 |
| P2 | CMS | 긴 필터가 결과보다 먼저 화면을 차지한다 | 빈도가 높은 조건과 추가 조건 분리, 결과와 상세의 위치 개선 |

P0(확인된 전면 차단)는 발견하지 못했다. 제한된 관찰에서 없었다는 뜻이며 제품 전체에 없음을 보증하지 않는다. P1은 중요한 이용 어려움, P2는 우회 가능한 불편, P3는 마무리 개선이다.

## 2. Impeccable 설치·실행 기록

### 전역 설치

- 공식 저장소: [pbakaus/impeccable](https://github.com/pbakaus/impeccable).
- 고정 소스: [`c1e0e5492fbb524f5df84d635ecfae7dc438ec45`](https://github.com/pbakaus/impeccable/tree/c1e0e5492fbb524f5df84d635ecfae7dc438ec45), 스킬 버전 **4.8.2**, 엔진 **0.1.22**.
- 라이선스: [Apache-2.0](https://github.com/pbakaus/impeccable/blob/c1e0e5492fbb524f5df84d635ecfae7dc438ec45/LICENSE). LICENSE와 NOTICE.md도 전역 설치 폴더에 보존했다.
- [공식 README의 설치 안내](https://github.com/pbakaus/impeccable/blob/c1e0e5492fbb524f5df84d635ecfae7dc438ec45/README.md#installation)에 맞춰 `.agents/skills/impeccable`을 Codex 전역 `C:\Users\Ian\.agents\skills\impeccable`에 설치했다. 저장소 안에 스킬은 없다.
- 설치에는 Codex `skill-installer`의 `install-skill-from-github.py`를 사용했다. `--repo pbakaus/impeccable --ref <위 SHA> --path .agents/skills/impeccable --dest C:/Users/Ian/.agents/skills`. npm 전역 설치나 프로젝트 hook 설치기를 실행하지 않았다.
- 소스 검토용 clone은 저장소 밖 `C:\Users\Ian\Documents\Codex\impeccable-install-source-20261111`에 있다.
- 새 전역 스킬은 다음 턴부터 검색 대상으로 사용할 수 있다. 이번에는 설치된 SKILL.md와 해당 reference를 직접 읽고 적용했다.

### 다운로드 실행 파일과 telemetry

| 항목 | 실제 경로/확인 결과 |
|---|---|
| Windows launcher | `C:\Users\Ian\.agents\skills\impeccable\scripts\impeccable.cmd` |
| 내려받은 네이티브 실행 파일 | `C:\Users\Ian\.impeccable\bin\0.1.22\impeccable.exe` |
| 실행 파일 크기 | 21,696,392 bytes |
| SHA-256 | `4bacf8c756ec3fda3b9fcb69bf1869d4d700ff471be1e31cbff669222dcd8ff2` |
| 검증 기준 | 설치된 `scripts/engine-checksums.txt`의 `0.1.22 impeccable-windows-x64.exe` 값과 일치 |
| 사용자 설정 | `C:\Users\Ian\.impeccable\config.json`에 `"telemetry": false` |

[공식 telemetry 설명](https://github.com/pbakaus/impeccable/blob/c1e0e5492fbb524f5df84d635ecfae7dc438ec45/README.md)에 따라 첫 실행 전에 프로세스 환경 `DO_NOT_TRACK=1`을 설정했다. 그 상태에서 launcher의 `telemetry off`를 실행했다. 이어 해당 환경변수를 제거한 별도 확인에서 `telemetry status`가 **사용자 config에 의해 off**라고 응답하는 것을 검증했다. 따라서 현재 셸에만 의존하는 차단이 아니다.

```powershell
$env:DO_NOT_TRACK = '1'
$env:IMPECCABLE_LIVE_COPY_AGENT = 'off'
& "$env:USERPROFILE/.agents/skills/impeccable/scripts/impeccable.cmd" telemetry off
Remove-Item Env:DO_NOT_TRACK
& "$env:USERPROFILE/.agents/skills/impeccable/scripts/impeccable.cmd" telemetry status
```

telemetry off는 모든 네트워크를 차단한다는 뜻은 아니다. 이번에는 설치용 GitHub 접근 외에 방향 카탈로그 요청을 발생시키는 새 디자인 생성 명령을 사용하지 않았다. `IMPECCABLE_LIVE_COPY_AGENT=off`는 실행 프로세스의 추가 방어 설정이며 영구 설정으로 주장하지 않는다. **Live·Apply·overlay 주입은 실행하지 않았다.**

프로젝트 hook은 설치·승인하지 않았다. `context --target apps/admin/src`의 hook 없음 경고는 사용자 지시와 일치하는 상태다. 이를 고치기 위한 hooks on/update/doctor --fix를 실행하지 않았다. PRODUCT.md·DESIGN.md가 없어도 기존 화면의 scoped 진단은 가능하다는 도구 안내에 따라 기존 설계 문서와 소스를 사용했다.

### 도구의 실제 실행 한계

`critique-storage slug/write/trend`는 정상 실행됐고, 첫 기록은 **22/40**이다. 과거 기록이 없어 개선 추세는 계산하지 않는다. 스킬 reference가 안내한 `audit-storage write`는 설치된 엔진에서 `Unknown command: "audit-storage"`로 실패했다. 소스 README의 버전 4.8.2와 고정 엔진 0.1.22 사이 명령 지원 차이로 기록하며, 실패를 감추거나 임의 엔진 업데이트로 우회하지 않았다. audit 자체는 playbook에 따른 독립 수동 평가와 detector 실행으로 수행했고, 저장소 밖 원본과 이 진단서에 결과를 보존했다. 브라우저 overlay도 사용하지 않았으므로 시각적 오버레이가 있다고 주장하지 않는다.

## 3. 진단 범위와 근거 수준

- **Flutter:** Impeccable critique·clarify 원칙만 사용. CSS/HTML detector를 Dart에 적용하거나 웹 검사 결과를 네이티브 통과로 해석하지 않았다. 구현 검토의 기준은 Material 3이며, [Flutter 접근성 체크리스트](https://docs.flutter.dev/ui/accessibility)의 터치 영역·글자 확대·라벨·대비도 참고했다.
- **CMS:** 사용자 승인 후 독립 Assessment A(디자인·흐름·문구)와 B(detector·기술 audit)를 분리했다. A가 끝나기 전에 부모가 B detector 결과를 평가 근거로 사용하지 않았다. critique·audit·clarify를 함께 종합했다.
- 두 제품은 홍보 페이지가 아닌 **작업 수행 화면(Operate)**이다. 무조건 장식을 늘리거나 새 UI 라이브러리·상태 관리 도구를 도입하는 것을 개선으로 보지 않는다.

| 대상 | 실제 확인 | 소스로만 확인/미검증 |
|---|---|---|
| Flutter | 임시 웹 복사본의 지도방 목록·지도방 생성 양식·내 정보, 접근성 트리 | 로그인·가입·비밀번호 재설정·장소/사진·탈퇴·피드백의 주요 문구와 소스. Android/iOS 실기기, 키보드 표시, TalkBack/VoiceOver, 큰 글씨, 네이티브 제스처는 미검증 |
| CMS | 기존 로컬 세션의 로그인 화면·관리자 계정·역할·메뉴·접근 근거·이력·파일·LOCO 조회. 1280×720 및 390×844 viewport emulation | 실제 신고 종결·계정 변경·삭제·업로드·세션 만료·CSRF 실패·revision 충돌은 유도하지 않음. 빈 메뉴/파일/신고의 편집 및 큰 데이터 상태, 실제 터치·200% 확대·스크린리더 음성은 미검증 |

Flutter 화면은 앞서 승인된 임시 경로 `C:\Users\Ian\Documents\Codex\loco-preview-20261111`의 Flutter 3.44.8 웹 실행본이다. 원본 lib 파일 **59개**와 해시를 대조했을 때 `app.dart`의 기존 승인된 `CardTheme → CardThemeData` 호환 수정만 달랐다. 생성된 웹 진입 파일과 로컬 설정도 임시 복사본에만 있다. 브라우저 관찰은 네이티브 렌더링의 증거가 아니며, 기존 미리보기의 로그인 화면 관찰과 이번 소스 검토도 구분한다.

샘플 데이터만 있는 로컬 환경이다. 실제 사용자 이메일·계정 비밀번호·키·토큰은 진단서에 기록하지 않았다. 카카오 장소 검색·OAuth·Firebase 발송을 호출하지 않았다.

## 4. A — Flutter 진단

### 유지할 점

1. Material 3 테마·FilledButton·SegmentedButton·표준 입력·대화상자를 이미 사용한다. 새 컴포넌트 체계보다 기존 위젯과 색상/문자 역할의 일관성이 먼저다. [app.dart](apps/mobile/lib/app.dart#L15)
2. 지도방 목록은 검색·공개/비공개 구분·정렬·생성의 기본 흐름이 있다. 첫 사용 빈 상태와 검색 결과 없음도 구분한다. [map_list_screen.dart](apps/mobile/lib/features/maps/screens/map_list_screen.dart#L350)
3. 장소/지도방 저장은 됐지만 사진만 실패한 상태를 이미 구분한다. 개선할 때 이를 일반적인 ‘저장 실패’로 합치면 중복 생성 위험을 키운다. [place_add_screen.dart](apps/mobile/lib/features/places/screens/place_add_screen.dart#L232), [map_create_screen.dart](apps/mobile/lib/features/maps/screens/map_create_screen.dart#L125)

### 우선 문제

| ID | 수준·근거 | 문제와 영향 | 개선 제안/나중에 확인할 조건 |
|---|---|---|---|
| F1 | P1 · 소스 확인 | `로그인 실패: $e`, `검색 실패: $e`, `추가 실패: $e`처럼 예외가 사용자 안내다. 원인에 맞는 다음 행동을 고르기 어렵다. | 화면용 오류 변환을 공통으로 정리하고 인증 실패·통신 실패·권한·부분 성공을 구분. 재시도 때 중복 생성하지 않게 실제 결과/목록 확인. 서버 `/api/**` 응답 자체는 바꾸지 않기 |
| F2 | P1 · 소스 확인+일부 시각 관찰 | 로그인 안내의 `Colors.grey`(#9e9e9e)/흰 배경 대비가 계산상 **2.68:1**이다. OAuth 버튼은 `TextScaler.noScaling`; 로그인 전체는 스크롤 없는 Column이다. 큰 글씨·키보드에서 읽기/진입 위험이 있다. | Material 색상 역할과 문자 스타일, 확대 가능한 라벨, 필요 시 스크롤 가능한 로그인. 48dp 조작 영역과 글자 200%·키보드 조건을 실기기/위젯 검증으로 확인. 이 수치는 코드 색상쌍 계산이며 화면 전체 대비 측정이 아님 |
| F3 | P2 · 실제 화면+소스 | 목록은 ‘지도방’, 생성은 ‘지도 만들기/지도 이름’, 즐겨찾기는 ‘지도’, 커버는 ‘표지 이미지/썸네일’이다. 같은 대상을 다른 개념으로 이해할 수 있다. | 기본 용어 ‘지도방’, 이미지 ‘표지 사진’ 등 한 가지를 선택해 전체 흐름에 적용. DB/API 식별자 변경 불필요 |
| F4 | P2 · 실제 화면+소스 | 생성 기본값은 공개인데 공개 범위 도움말은 없고, 비공개일 때만 초대 코드 설명이 나온다. 탈퇴는 되돌릴 수 없다고만 하며 로그인·남는 기록의 영향은 설명하지 않는다. | 공개/비공개 양쪽에 실제 서버 계약에 맞는 짧은 설명. 탈퇴는 계정 이용 불가와 데이터 보존 정책을 구분. ‘모든 데이터 삭제’ 같은 사실과 다른 약속 금지 |
| F5 | P2 · 소스+접근성 트리 | 목록의 즐겨찾기·내 정보 아이콘, 생성 FAB, 비밀번호 보기 버튼에 tooltip/명시 이름이 빠져 있다. 생성 화면 뒤로 가기는 AX에서 영어 `Back`으로 관찰됐다. | 보이는 기능과 같은 한국어 의미 라벨·tooltip. 기존 MaterialLocalizations/한국어 locale 구성 검토. 버튼 크기만으로 스크린리더 접근성을 통과했다고 하지 않기 |
| F6 | P2 · 소스 확인 | 비밀번호 찾기는 실제 발송 여부와 무관하게 ‘전송했습니다’를 확정하고, 코드 입력 단계의 유효시간 안내가 없다. 메일이 오지 않는 사람은 계속 기다리기 쉽다. | 계정 존재를 공개하지 않는 중립 안내, 받은편지함/스팸함·가입 방식 확인, 유효시간 안내. 서버는 현재 15분. 재전송 동작 추가는 문구 교체와 별도 검토 |

근거: F1 [login_screen.dart:58](apps/mobile/lib/features/auth/screens/login_screen.dart#L58), [place_add_screen.dart:87](apps/mobile/lib/features/places/screens/place_add_screen.dart#L87). F2 [login_screen.dart:65](apps/mobile/lib/features/auth/screens/login_screen.dart#L65), [login_screen.dart:208](apps/mobile/lib/features/auth/screens/login_screen.dart#L208). F3/F4 [map_create_screen.dart:169](apps/mobile/lib/features/maps/screens/map_create_screen.dart#L169), [map_create_screen.dart:305](apps/mobile/lib/features/maps/screens/map_create_screen.dart#L305), [my_page_screen.dart:212](apps/mobile/lib/features/profile/screens/my_page_screen.dart#L212). F5 [map_list_screen.dart:329](apps/mobile/lib/features/maps/screens/map_list_screen.dart#L329), [app.dart](apps/mobile/lib/app.dart). F6 [forgot_password_screen.dart:47](apps/mobile/lib/features/auth/screens/forgot_password_screen.dart#L47), [PasswordRecoveryService.java:37](apps/server-v3/src/main/java/com/loco/auth/PasswordRecoveryService.java#L37).

신규 이용자는 ‘무엇을 만들 수 있는 앱인가 → 지도방 만들기 → 장소 추가’의 의미를, 기존 이용자는 검색·오류 복구를 먼저 알아야 한다. 공개 상태에서 위축되지 않도록 공개 범위 설명을 두되 보안을 약화하거나 새로운 비공개 정책을 만들지 않는다. 네이티브 검증이 없으므로 Flutter 전체 품질 점수는 매기지 않았다.

## 5. B — CMS 진단

### 유지할 점

- 권한 설명 API와 READ/MANAGE 구분, 비공개 조회 범위, 신고 처리와 앱 제재의 분리를 숨기지 않는다.
- 영구 label·본문 바로가기·focus-visible·main 포커스·표 caption·alert/status·취소와 포커스 복귀가 있는 네이티브 dialog를 갖췄다. 이를 전체 접근성 통과로 확대 해석하지 않는다.
- 세션·CSRF·권한·충돌을 구분하는 안내와, 응답 유실 때 중복 재업로드를 경고하는 기존 회복 계약을 유지한다.

### 독립 디자인 평가의 우선 문제

| ID | 수준·근거 | 진단 | 제안과 계약 경계 |
|---|---|---|---|
| CUX1 | P1 · 실제 DOM+소스 | 최초 select 일부가 `selectedIndex=-1`, 빈칸이다. 이미 조회는 됐는데 적용 범위가 보이지 않는다. 회원 상태에는 ‘전체’와 `ALL`이 중복된다. | 초기 선택을 명시하고 적용 조건을 표시. 서버 확인상 회원 상태 미지정/ALL 모두 탈퇴 조건을 추가하지 않지만 cursor scope는 필터에 연결되므로 페이지 중 필터 값을 조용히 바꾸지 않기 |
| CUX2 | P1 · 소스, 실제 종결 미검증 | 신고 검토 시작/완료/기각의 버튼이 모두 ‘상태 변경 적용’. 종결의 되돌릴 수 없음이 마지막 행동에서 충분히 특정되지 않는다. | ‘검토 시작 / 검토 완료로 종결 / 기각으로 종결’. 대상·결과·재개 불가를 최종 행동 가까이에 배치. 확인 단계 추가는 별도 구현 선택이며 재개 기능은 추가하지 않음 |
| CUX3 | P2 · 실제 화면 | 1280×720에서도 세로 필터와 설명이 결과를 밀어낸다. 자주 쓰는 검색과 UUID·기간·개수가 같은 비중이다. | 검색·상태와 추가 조건을 나누고 결과를 앞당김. 초기화·적용 조건 표시, 선택한 상세로의 위치/초점 연결을 검토 |
| CUX4 | P2 · 실제 화면+소스 | 상단 10개 탐색 링크와 READ/MANAGE·deny·GROUP/PAGE·revision·ACTIVE 등 내부 용어가 과도하다. | 운영 조회/관리자 설정/접근·이력 등 업무군으로 묶기. 한국어를 먼저 쓰고 필요한 코드는 병기/도움말. ‘비공개 포함’과 ‘앱 제재 없음’은 잘 보이는 곳에 유지 |
| CUX5 | P2 · 실제 화면+소스 | UUID를 읽고 다른 화면에 입력하는 비용이 크다. 이력의 원시 JSON과 ID가 행위자·변경 내용보다 앞선다. | 권한 내 이미 받은 이름/제목을 주표시, UUID는 보조. 대상별 접근 근거/이력 연결과 변경 필드 요약. 새 개인정보 조회·권한 확대는 제안하지 않음 |
| CUX6 | P2 · 실제 화면+소스 | 로그인된 세션에서도 `/admin/login`에 로그인 폼과 관리 메뉴가 동시에 보인다. | 로그인 상태에 맞는 이동/안내를 검토. 이는 보안 우회의 증거가 아니라 상태 표현 혼란 |

근거: [LocoReadView.vue:12](apps/admin/src/views/LocoReadView.vue#L12), [LocoReadView.vue:65](apps/admin/src/views/LocoReadView.vue#L65), [locoModel.ts:21](apps/admin/src/views/locoModel.ts#L21), [LocoReadRepository.java:29](apps/server-v3/src/main/java/com/loco/cmsadmin/LocoReadRepository.java#L29), [ReportProcessingForm.vue:27](apps/admin/src/components/ReportProcessingForm.vue#L27), [HistoryView.vue](apps/admin/src/views/HistoryView.vue), [screens.ts](apps/admin/src/screens.ts), [AccessView.vue](apps/admin/src/views/AccessView.vue), [router.ts:29](apps/admin/src/router.ts#L29).

### Nielsen 평가 — Assessment A

| 원칙 | 잠정 점수 / 4 |
|---|---:|
| 시스템 상태 가시성 | 3 |
| 현실 세계와 일치 | 2 |
| 사용자 통제와 자유 | 2 |
| 일관성과 표준 | 3 |
| 오류 예방 | 2 |
| 기억보다 인식 | 2 |
| 유연성과 효율 | 2 |
| 미학과 최소성 | 2 |
| 오류 인지·복구 | 2 |
| 도움말 | 2 |
| 합계 | **22/40** |

이는 읽기 중심 관찰과 소스의 주관적 진단이며 성공률·WCAG 인증·전수 테스트 수치가 아니다. 재개 불가처럼 승인된 업무 제약 자체를 감점하지 않았다. 신규 운영자는 코드 용어와 필터 공백에서, 숙련 운영자는 긴 필터와 ID 대조에서 어려움을 겪을 가능성이 있다. 실제 사용자 인터뷰나 작업 소요시간 측정은 하지 않았다.

### 기술 audit와 자동 검사 — Assessment B

`detect --json apps/admin/src`의 종료 코드는 **0**, 출력은 **`[]`**였다. 자동 탐지 항목과 오탐 항목은 모두 0건이다. 소스 디렉터리에 파일 30개가 있으나 도구가 실제 검사 파일 수를 주지 않아 ‘30개 전수 규칙 통과’라고 쓰지 않는다. 원본 JSON은 저장소 밖 `C:\Users\Ian\Documents\Codex\ux-copy-review-20261111\cms-detector.json`에 보존했다.

| ID | 수준·근거 | 추가 진단과 검증 방향 |
|---|---|---|
| CUX7 | P1 · 소스 확정, 장애 재현 미실시 | `useAction`은 오류 후 busy=false로 끝나고 빈 결과 조건은 오류 여부를 보지 않는다. 초기 실패가 ‘결과 없음’과 동시 표시될 수 있고, 재조회 실패 때 이전 결과가 남을 수 있다. 정상 빈 결과·실패·이전 결과를 구분할 것. [action.ts:9](apps/admin/src/composables/action.ts#L9), [LocoReadView.vue:74](apps/admin/src/views/LocoReadView.vue#L74) |
| CUX8 | P2 · 실제 화면/DOM | 390×844에서 회원 상세 클릭 후 상세 시작점이 약 836px로 대부분 화면 아래에 나타났다. 초점은 body에 있었고 상세에 완료 안내용 live region은 없다. 상세 제목으로 초점 이동 또는 ‘상세로 이동’ 안내를 검토하되 긴 본문 전체를 자동 낭독하지 않기. [LocoReadView.vue:43](apps/admin/src/views/LocoReadView.vue#L43) |
| CUX9 | P2 · 소스·computed style 확인 | 상세의 `preserve-text` 클래스가 CSS에 정의돼 있지 않다. dd는 white-space/overflow-wrap이 normal이다. 신고·장소의 긴 본문 줄바꿈 소실/넘침 위험은 장문 데이터로 후속 재현 필요. [LocoReadView.vue:78](apps/admin/src/views/LocoReadView.vue#L78), [style.css](apps/admin/src/style.css) |
| CUX10 | P2 · 키보드 초점 관찰+색상 계산 | 헤더 #173b46 위 초점색 #ba6515 대비는 약 **2.83:1**. 초점선은 존재한다. 배경별 초점색/이중 outline을 검토하고 실제 확대·고대비 환경에서 확인. 이 계산 하나로 WCAG 위반을 확정하지 않음. [style.css:8](apps/admin/src/style.css#L8) |

그 외 오류 원문의 사용자 노출과 모호한 ‘확인’ 버튼은 CUX2/CUX4와 함께 개선한다. 공통 CSS literal 색상을 의미 토큰으로 묶는 것은 P3 유지보수 후보이며, 새 디자인 시스템이나 다크모드 도입을 지금 요구하지 않는다.

기술 평가 잠정 점수: 접근성 **2/4**, 성능 **3/4**, 테마 구조 **1/4**, 반응형 **3/4**, 구현 일관성 **3/4**, 합계 **12/20**. 테마 점수는 유지보수 구조에 대한 평가이며, 요구되지 않은 다크모드 부재를 사용자 결함으로 보지 않는다. 성능 프로파일링은 하지 않았으므로 성능 3점은 소스 구조 기반의 잠정 판단이다. A의 22/40과 측정 축이 달라 합산하지 않는다.

오탐 방지 근거: 390px에서 관찰한 회원·빈 신고 화면에 페이지 전체 가로 넘침은 없었다. 회원 표만 컨테이너 폭 327px 안에서 638px 내용을 가로 스크롤했다. 44px 권장치에 못 미치는 버튼이 있다는 이유만으로 WCAG AA 실패라고 하지 않는다. 기본 본문/링크/오류 색 대비는 충분한 코드 색상쌍이었으며, 장식이나 애니메이션이 적다는 것을 결함으로 보지 않는다. 실제 터치, 키보드 전체 업무 완주, 스크린리더 음성, 다량/장문 데이터는 별도 검증 대상이다.

## 6. 문구 개선 초안 — 적용 금지

| 화면/상황 | 현재 | 제안 | 유지할 사실 |
|---|---|---|---|
| 앱 생성 | 지도 만들기 / 지도 이름 | 지도방 만들기 / 지도방 이름 | 장소 자체 생성과 구분 |
| 앱 커버 | 썸네일 / 표지 이미지 | 표지 사진 | 이미지 지원 형식·크기 정책은 별도 |
| 앱 목록 아이콘 | 아이콘만 | 즐겨찾기 / 내 정보 / 지도방 만들기 — tooltip·접근성 이름 | 시각 라벨 추가 여부는 공간에 맞춰 결정 |
| 앱 인증 오류 | 로그인 실패: 예외 원문 | 로그인 정보를 확인해 주세요. / 연결하지 못했어요. 잠시 후 다시 시도해 주세요. | 실제 원인별로 선택, 모든 오류를 비밀번호 오류로 돌리지 않음 |
| 앱 부분 성공 | 장소는 생성됐지만 일부 사진 업로드에 실패했습니다: 예외 | 장소는 저장됐어요. 일부 사진을 올리지 못했어요. 저장된 사진을 확인한 뒤 다시 추가해 주세요. | 이미 성공한 장소를 재생성하지 않음 |
| 앱 재설정 코드 | 이메일로 인증 코드를 전송했습니다. | 재설정 코드 발송을 요청했어요. 받은편지함과 스팸함을 확인해 주세요. | 계정 유무 비노출·도착 보장 금지. 유효시간 15분은 현재 서버 계약에 맞춰 안내 |
| CMS 로그인 | 초기 계정은 관리자 생성 CLI로 준비합니다. | 관리자 계정으로 로그인하세요. LOCO 회원 계정으로는 로그인할 수 없습니다. | CLI 준비법은 개발/운영 문서로 이동 |
| CMS 권한 | MANAGE는 READ를 포함…상속·deny는 없음 | 관리(MANAGE)에는 조회(READ)가 포함됩니다. 여러 역할의 허용 권한을 합산하며, 상속이나 거부 설정은 없습니다. | 권한 합집합·상속/deny 없음 유지 |
| CMS 메뉴 | GROUP / PAGE / 루트 | 메뉴 묶음 / 화면 연결 / 최상위 | 전송 enum·메뉴 ID·권한 불변 |
| CMS 파일 | ACTIVE 파일 목록 | 사용 가능한 파일 | ACTIVE만 노출하는 서버 상태 계약 유지 |
| CMS 파일 삭제 | 물리 삭제는 재정리 작업에서 완료될 수 있습니다. | 목록에서 삭제했습니다. 저장소의 파일 삭제가 지연되면 자동으로 다시 처리합니다. | 즉시 물리 삭제·완료 시각을 보장하지 않음 |
| CMS 오류 | 보안 토큰이 만료되었습니다. 갱신 후… | 보안 확인 정보를 새로 받았습니다. 입력 내용을 확인한 뒤 다시 시도하세요. | CSRF 갱신 성공 시에만 이 표현. 변경 요청 자동 재전송 금지 |
| CMS 신고 상태 | RESOLVED | 검토 완료 | 회원 제재·문제 해결 완료라는 의미로 바꾸지 않음 |
| CMS 종결 | 상태 변경 적용 | 검토 시작 / 검토 완료로 종결 / 기각으로 종결 | 단방향 상태·종결 후 메모 수정/재개 불가 유지 |
| CMS 공통 확인 | 확인 | 파일 삭제 / 계정 비활성화 / 역할 배정 저장 / 메뉴 이동 | 현재 작업·대상을 구체화. 승인 기능 자체를 없애지 않음 |

앱은 짧고 편안한 해요체, CMS는 간결한 업무형 합니다체를 후보로 둔다. 위 표현은 의미 검토용이며 어투 최종 적용은 아직 하지 않았다. 메뉴 숨김은 보안 경계가 아니고 서버 권한 검사를 대체하지 않는다. 민감한 계정 존재 여부, 비공개 범위, 종결/삭제 효과를 ‘쉬운 문구’를 이유로 바꾸지 않는다.

## 7. 다음 구현 범위를 정할 때

1. 문구·기본 상태·접근 가능한 이름부터: CUX1, F1, F3, F5, 내부 용어의 한국어 주표시.
2. 위험 행동/복구: 신고 종결·계정 변경·탈퇴·부분 업로드 실패. 사실관계를 서버 계약에 대조하고 클라이언트 표시만 먼저 개선.
3. 레이아웃·확대: CMS 조회 결과 우선 배치, Flutter 로그인 글자 확대/키보드. UI 라이브러리 교체 없이 기존 Vue 공통 컴포넌트와 Material 3로 검토.
4. 이후 회귀 검증: CMS Vitest·핵심 Chromium E2E, Flutter 위젯/실기기 접근성 확인. 서버 응답 계약을 바꾸지 않는 UI 작업에 무조건 서버 전체 회귀를 붙이지 말고 실제 변경 범위에 맞춰 정한다.

이번에는 런타임과 코드를 고치지 않았으므로 allTests·build·E2E를 재실행하지 않았다. 관찰한 UI와 소스가 근거이며, 저장·삭제 성공/실패 시나리오를 통과했다고 주장하지 않는다. 추가 구현 선택 질문은 진단 검토 이후로 미뤘다(Questions skipped: 진단만 요청받았으며 지금 구현을 결정하지 않음).

## 8. 생성 파일과 작업 종료 상태

커밋 전에 확인할 저장소 내 생성 파일은 다음 **3개**다. 스킬·실행 파일·hook 설정은 포함되지 않는다.

| 파일 | 용도 | Git 상태 |
|---|---|---|
| `UX_COPY_DIAGNOSIS.md` | 설치 이력·Flutter/CMS 종합 진단·문구 제안·한계 | 사용자 후속 승인에 따라 커밋·push 대상 |
| `.impeccable/critique/.gitignore` | critique 저장 도구가 만든 로컬 보고서 제외 규칙 | 자기 자신을 포함해 ignore됨 |
| `.impeccable/critique/2026-11-11T06-33-02Z__apps-admin-src.md` | 독립 디자인 Assessment A의 공식 snapshot | 위 규칙으로 ignore됨 |

독립 근거 원본은 저장소 밖 `C:\Users\Ian\Documents\Codex\ux-copy-review-20261111`의 `cms-assessment-a.md`, `cms-assessment-b.md`, `cms-detector.json`에 있다. 재현 가능한 핵심 근거·제안은 이 문서에 모아 두었으므로 이 외부 경로가 없어도 진단을 이해할 수 있다.

최초 진단은 코드 적용·커밋·push·PR 생성 없이 마쳤다. 이후 사용자 승인으로 이 종합 진단서만 커밋·push 대상으로 확정했다. 로컬 Impeccable snapshot은 공유 대상에서 제외하며 PR은 생성하지 않는다. 진단용 탭은 닫고 CMS viewport override는 해제했다. 사용자가 보던 기존 탭과 로컬 미리보기 서버는 유지했다. 다음 구현은 이 진단 검토 후 별도 범위로 진행한다.
