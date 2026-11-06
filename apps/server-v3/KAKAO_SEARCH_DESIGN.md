# 8-B 카카오 장소 검색 — 관측·설계만

대상은 GET /api/places/search. v3 Controller/Service/설정은 아직 구현하지 않았다. 아래 8-1~8-4 선택을 받은 뒤 구현한다. 실제 카카오 요청 0건, 실제/더미 API 키 사용 0건이다.

## 1. 근거와 관측 격리

- v2 원본: `apps/server/src/places/places.controller.ts`, `dto/search-place.dto.ts`, `kakao/kakao-place-search.service.ts`.
- `KakaoObservationTest`는 기존 v2 테스트 컨테이너 안에서 별도 Node 프로세스를 실행한다. 원본 compiled AppModule, Guard, ValidationPipe, Controller, Service를 그대로 올리고 실제 HTTP 요청을 보낸다. v2 소스 파일은 수정하지 않는다.
- `src/test/resources/kakao/observe-fake.cjs`는 AppModule을 불러오기 **전** 환경의 KAKAO_REST_API_KEY를 삭제한다(읽거나 출력하지 않음). 테스트 JWT만 기존 로컬 테스트 secret으로 발급한다.
- global fetch는 원본의 정확한 Kakao origin/path만 허용하고, 이를 127.0.0.1의 가짜 HTTP 서버로 바꾼다. Authorization은 전달하지 않는다. 다른 fetch URL은 오류로 차단하고 원래 주소로 fallback하지 않는다. redirect도 error여서 외부 redirect를 따라가지 않는다.
- native fetch가 받는 주소는 루프백뿐이다. 요청한 Kakao 주소 문자열을 파싱하는 것은 네트워크 호출이 아니다. 가짜 서버가 받은 path/query와 Authorization 부재를 fixture로 검증한다.
- 이 교체는 외부 통신을 바꾸는 **테스트 대역**이다. 동작을 보존해야 하는 `timing-v2.cjs` 계측과 다르다. 이번 관측용 사용이 향후 하네스 방식(8-1)의 사용자 선택을 대신하지 않는다.
- observer의 3초 watchdog은 테스트가 멈추지 않게 하는 장치이며 v2 timeout 설정이 아니다. 250ms 지연은 실제 경과 200ms 이상과 정상 완료를 검사하고 fixture에는 지연 조건만 저장한다.

관측 fixture는 `src/test/resources/kakao/http-v2.json`이다. 총 56 HTTP 사례, 가짜 upstream 도달 40회, 차단된 예상 밖 fetch 0회. 일반 테스트에서는 fixture와 정확히 비교하고 자동 갱신하지 않는다. HTTP 상태·본문·upstream 쿼리를 모두 기록했다.

## 2. 인증·입력 변환

PlacesController 전체에 JwtAuthGuard가 있다. 공개 검색이 아니다. 토큰 없음은 401 `{"message":"Unauthorized","statusCode":401}`이며 upstream 호출은 0회다. 인증된 회원만 DTO 검증에 도달한다. 현재 회원 DB 확인도 기존 Guard를 따른다. 검색 자체는 업무 DB를 쓰지 않는다. 기본 요청 제한은 다른 일반 API처럼 핸들러별 120/분이다.

| 입력 | 실제 처리 |
| --- | --- |
| query 누락/빈 문자열 | IsString/IsNotEmpty에 따른 400 message 배열 |
| query 공백만 또는 앞뒤 공백 | trim 없이 그대로 upstream 전달 |
| query 중복 배열/객체, unknown 키 | 400, upstream 없음 |
| x/y/radius/page/size 누락 | IsOptional 통과, controller는 undefined |
| 선택 파라미터를 명시적으로 빈 문자열로 전달 | IsNumberString 400. controller의 truthy 기본 처리까지 도달하지 않음 |
| x/y | 검증 뒤 parseFloat. 0/-0/+0은 service truthy 검사에서 생략. 음수·소수·.5 허용, .5는 0.5로 전달 |
| 숫자 `1.`, `1e2`, `0x10`, NaN, Infinity, 공백, `1x`, 중복 배열 | IsNumberString에서 400. Java 숫자 파싱만 사용하면 허용 범위가 달라짐 |
| radius/page | parseInt(기수 인자 없음) 후 truthy 검사. 1.5→1, -0.5→-0 생략, 0 생략. -1/100000은 그대로 전달 |
| size | parseInt 후 `size ?? 15`. 누락만 15, 0/-0.5는 문자열 "0", 음수와 100000도 전달 |

범위 제한이나 clamp는 없다. x=127.25,y=37.5,radius=1.9,page=2.9,size=3.9는 radius=1,page=2,size=3이 된다. query의 공백도 유지된다. y는 x와 같은 DTO/코드 경로다. 현재 fixture는 x의 상세 경계와 양 좌표를 함께 주는 사례를 포함하며 y의 모든 경계를 별도 관측했다고 주장하지 않는다.

## 3. 정상 응답 매핑과 느슨한 타입

응답은 `{meta:{totalCount,isEnd},documents:[...]}`이다. meta.total_count→totalCount, is_end→isEnd이고 pageable_count 및 다른 필드는 버린다.

| document 원본 | 응답 |
| --- | --- |
| id, place_name, address_name, road_address_name | id, placeName, addressName, roadAddressName |
| phone, category_name, place_url | phone, categoryName, placeUrl |
| y, x | parseFloat로 latitude, longitude |

문자열 필드는 `?? ''`여서 null/누락만 빈 문자열로 바뀐다. 숫자 id=7, boolean place_name=false도 그대로 반환한다. y="1.25x"는 1.25, 잘못된 x/Infinity는 JSON 직렬화 시 null이다. placeUrl URL 유효성 검사는 없다.

meta={}면 응답 meta={}이고 undefined 키는 생략된다. meta 필드의 "two"/"yes" 같은 잘못된 타입도 그대로 전달한다. meta 또는 documents 자체가 없으면 접근/map 오류로 기본 500이다. TypeScript 타입 단언은 런타임 검증이 아니다.

## 4. 가짜 외부 응답별 실제 v2 결과

| 외부 조건 | v2 HTTP·본문 |
| --- | --- |
| 정상 200 | 위 매핑한 200 |
| 400/401/429/500/503 | 모두 500. `{message:"카카오 API 오류: 상태 - 외부 본문",error:"Internal Server Error",statusCode:500}`. 개행도 노출 |
| 연결 중 소켓 종료(fetch reject) | 500 `{message:"카카오 검색 API 호출 실패",error:"Internal Server Error",statusCode:500}` |
| 250ms 지연 후 정상 | 기다린 뒤 정상 200 |
| 잘못된 JSON / 204 / meta 누락 / documents 누락 | 500 `{"statusCode":500,"message":"Internal server error"}`. error 키 없음 |
| 503 헤더를 받은 뒤 body 읽기 도중 연결 종료 | 위 기본 500. res.text가 fetch try/catch 밖에 있음 |
| 빈 meta / 잘못된 필드 타입 | 200, 3절의 느슨한 매핑 그대로 |

v2에 애플리케이션이 명시한 연결/응답 timeout, retry, circuit breaker는 없다. Node fetch 런타임 자체 제한까지 없다고 해석하지 않는다. 이번의 연결 실패는 실제 소켓 중단이며 DNS 오류/TCP 거절 각각의 별도 관측은 아니다.

## 5. 결정할 항목 — 아직 미선택

### 8-1 v2 카카오 대역·v3 base URL

- **A 권장: 테스트 프로세스 preload에서 fail-closed fetch 교체 + 루프백 가짜 서버.** v2 파일 변경 없이 실제 Guard/DTO/service 경로를 관측한다. 가짜 응답·지연·단절을 제어하기 쉽다. 대신 테스트에서만 전역 fetch 동작을 바꾸므로 허용 URL, 헤더 제거, redirect/fallback 차단을 검사해야 한다. v3는 환경/설정으로 base URL을 주입하고 테스트는 루프백으로 고정한다. 운영 기본 주소는 현재 Kakao endpoint와 같게 한다. 키는 외부 환경에서만 주입하며 테스트는 사용하지 않는다.
- **B: 테스트 네트워크의 HTTPS 프록시/DNS·인증서 대역 + v3 base URL 설정.** 원본 fetch 호출을 더 그대로 둘 수 있지만 TLS 신뢰·호스트·프록시 설정과 외부 연결 차단 관리가 복잡하다. Node fetch의 프록시 지원 경로도 검증해야 한다. 여기서 구현하지 않았다.

이번 관측은 별도 Node 프로세스 내 AppModule import 전 교체다. A 선택 시 공용 v2 하네스 시작 preload로 확장할지 독립 관측 앱을 유지할지는 격리 비용과 필요한 비교 케이스를 기준으로 정리한다. 운영 Compose에는 주입하지 않는다.

### 8-2 외부 호출 타임아웃

- **A: v2처럼 애플리케이션 timeout 미지정.** 이관 차이가 적다. 다만 지연 요청이 오래 점유할 수 있고 Node와 Java HTTP 런타임의 기본 제한은 별개다.
- **B 권장: v3 연결·응답 timeout을 설정으로 추가하고 알려진 차이 등록.** 장애 시 대기를 제한할 수 있다. v2보다 먼저 실패하므로 지연 비교에서 의도된 차이를 검증해야 한다. 제안값 연결 2초/응답 5초는 아직 적용하지 않았으며 이 선택에서 함께 확정한다. 자동 재시도는 추가하지 않는다.

### 8-3 카카오 오류 본문 노출

- **A: v2처럼 상태·원문 본문을 message에 포함.** 기존 응답과 정확히 맞고 디버깅이 쉽지만 외부 서비스 세부 내용이 클라이언트에 노출된다.
- **B 권장: 고정 message, 서버 로그에서만 원인 확인.** 노출과 응답 변동을 줄인다. 알려진 차이가 생기며 로그도 키·민감 값 제거와 길이 제한이 필요하다. 제안 응답은 기존 연결 실패 형태의 500 `{message:"카카오 검색 API 호출 실패",error:"Internal Server Error",statusCode:500}`. 적용 여부는 사용자 선택 후 확정한다.

### 8-4 정상 외부 응답의 잘못된 타입 처리

- **A 권장(이관): v2의 느슨한 매핑 유지.** 숫자/boolean 문자열 필드, 좌표 null, 빈 meta를 fixture 그대로 재현한다. 기존 패턴에 맞지만 DTO 타입만 믿으면 구현을 잘못 만들기 쉽다.
- **B: 외부 응답 스키마 검증 후 잘못된 타입은 고정 500.** v3 반환 계약을 예측하기 쉽다. v2 200과 달라지므로 알려진 차이와 필드별 허용 정책을 추가해야 한다.

추천 조합은 **8-1 A / 8-2 B(2초·5초) / 8-3 B / 8-4 A**다. 추천은 결정 기록이 아니며 어떠한 v3 검색 코드에도 반영하지 않았다.

## 6. 재검증과 후속 구현 범위

```powershell
cd apps/server-v3
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
.\gradlew.bat comparisonTest --tests '*KakaoObservationTest' --rerun --console=plain
```

일반 실행은 1 JUnit 안에서 56 HTTP fixture를 재검증한다. 생성 모드 `-PkakaoObserve=true`는 build/reports/kakao에만 출력하며 원본 fixture는 바꾸지 않는다. 최초 생성 검증은 1/1 성공, 실패·skip·알려진 차이 0, 19.700초(v2 이미지 캐시)였다.

고정 fixture 재검증도 1/1 성공, 실패·skip·알려진 차이 0, 19.333초(v2 이미지 캐시), 재시작 0·대기 0ms였다. 56개 HTTP 응답과 외부 쿼리를 빠짐없이 대조했다.

선택 후에는 SearchInputs(JS parsing), 주입 가능한 외부 호출 어댑터, 응답 매핑, 고정/호환 오류를 분리한다. 인증·현재 회원 확인·120/분은 기존 패턴을 재사용한다. JWT 오류/탈퇴/없는 회원과 제한 초과에서는 외부 호출 0회를 검증하고, 정상/실패 모두 업무 DB가 변하지 않는지 검사한다. 가짜 서버로만 v2/v3 비교하며 실제 API·키를 테스트하지 않는다. 이번 작업은 이 설계에서 멈춘다.
