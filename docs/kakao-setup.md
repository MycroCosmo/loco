# Kakao Map 설정 가이드

## 보유 키 정리

| 키 종류 | 용도 |
|---------|------|
| JavaScript 앱 키 | Flutter WebView 지도 렌더링 (`kakao_map_widget.dart`에 이미 설정됨) |
| REST API 키 | NestJS 서버 장소 검색 (`.env`에 이미 설정됨) |
| 네이티브 앱 키 | Android/iOS 네이티브 설정 (아래 참고) |

---

## Android 설정

`apps/mobile/android/app/src/main/AndroidManifest.xml`에 추가:

```xml
<manifest ...>
  <!-- 인터넷 권한 (WebView 필수) -->
  <uses-permission android:name="android.permission.INTERNET"/>

  <application ...>
    <!-- Kakao 네이티브 앱 키 (추후 Kakao 로그인 OAuth 연동 시 필요) -->
    <meta-data
      android:name="com.kakao.sdk.AppKey"
      android:value="6f122bd2d3741c76ab8ea0588ff3fa3d"/>

    ...
  </application>
</manifest>
```

`apps/mobile/android/app/build.gradle`에서 minSdk 확인:
```gradle
minSdk 21   // Kakao SDK 요구사항
```

---

## iOS 설정

`apps/mobile/ios/Runner/Info.plist`에 추가:

```xml
<!-- Kakao 네이티브 앱 키 (추후 Kakao 로그인 OAuth 연동 시 필요) -->
<key>KAKAO_NATIVE_APP_KEY</key>
<string>6f122bd2d3741c76ab8ea0588ff3fa3d</string>

<!-- Kakao 로그인 URL Scheme -->
<key>CFBundleURLTypes</key>
<array>
  <dict>
    <key>CFBundleURLSchemes</key>
    <array>
      <string>kakao6f122bd2d3741c76ab8ea0588ff3fa3d</string>
    </array>
  </dict>
</array>

<!-- Kakao 앱 연결 허용 -->
<key>LSApplicationQueriesSchemes</key>
<array>
  <string>kakaokompassauth</string>
  <string>storykompassauth</string>
  <string>kakaolink</string>
</array>
```

---

## Flutter Web에서 카카오 지도 동작 원리

Flutter Web에서는 `webview_flutter`가 지원되지 않으므로 `dart:html`의 `IFrameElement`를 사용합니다.
- `kakao_map_web_helper_web.dart` — 웹 전용 IFrame 구현
- `kakao_map_web_helper_stub.dart` — 모바일용 빈 stub
- 조건부 import(`if (dart.library.html)`)로 자동 분기

> **⚠️ 카카오 개발자 콘솔에 Web 플랫폼을 등록하지 않으면 JS 키가 있어도 지도가 표시되지 않습니다.**

---

## 카카오 개발자 콘솔 설정

[developers.kakao.com](https://developers.kakao.com) → 내 애플리케이션 → 앱 설정

1. **플랫폼** → Web 추가 ← **PC에서 지도를 보려면 필수!**
   - 사이트 도메인: `http://localhost:3000` (개발용)
   - Flutter Web 개발 서버 포트도 추가: `http://localhost:포트번호`
   
   > `flutter run -d chrome`으로 실행하면 기본 포트가 매번 달라질 수 있음.
   > 고정하려면 `flutter run -d chrome --web-port 8080` 사용 후 `http://localhost:8080` 등록

2. **플랫폼** → Android 추가
   - 패키지명: `com.loco.app`
   - 키 해시: `keytool -exportcert -alias androiddebugkey -keystore ~/.android/debug.keystore -storepass android -keypass android | openssl sha1 -binary | openssl base64`

3. **플랫폼** → iOS 추가
   - 번들 ID: `com.loco.app`

4. **카카오 로그인** 활성화 (OAuth 연동 시)

---

## 현재 구현 상태

- [x] Flutter WebView 카카오 지도 렌더링 (JS 키 사용)
- [x] NestJS 장소 검색 API (`GET /api/places/search?query=...`)
- [ ] Kakao 로그인 OAuth (추후 구현)
- [ ] 네이티브 SDK 연동 (WebView 대비 성능 향상 원할 때)
