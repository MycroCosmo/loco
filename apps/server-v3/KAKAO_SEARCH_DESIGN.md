# 8-B 카카오 장소 검색 — 결정과 구현 기록

대상은 GET /api/places/search. 8-1 A / 8-2 B / 8-3 B / 8-4 A를 사용자 확인으로 확정했다. 공용 v2 preload를 구현·검증했으며, v3 외부 호출 구현은 추가 HTTP 클라이언트 선택(8-5) 답변을 기다린다. 실제 카카오 요청 0건, 실제/더미 API 키 사용 0건이다.

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

## 5. 검토했던 대안 (아래 확정 기록 우선)

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

사용자가 **8-1 A / 8-2 B(연결 2초·응답 5초) / 8-3 B / 8-4 A**를 확정했다. 아래 구현 기록을 따른다. 위의 미선택·제안 표현은 최초 설계 당시의 대안을 보존한 것이다.

## 6. 재검증과 후속 구현 범위

```powershell
cd apps/server-v3
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
.\gradlew.bat comparisonTest --tests '*KakaoObservationTest' --rerun --console=plain
```

일반 실행은 1 JUnit 안에서 56 HTTP fixture를 재검증한다. 생성 모드 `-PkakaoObserve=true`는 build/reports/kakao에만 출력하며 원본 fixture는 바꾸지 않는다. 최초 생성 검증은 1/1 성공, 실패·skip·알려진 차이 0, 19.700초(v2 이미지 캐시)였다.

고정 fixture 재검증도 1/1 성공, 실패·skip·알려진 차이 0, 19.333초(v2 이미지 캐시), 재시작 0·대기 0ms였다. 56개 HTTP 응답과 외부 쿼리를 빠짐없이 대조했다.

선택 후에는 SearchInputs(JS parsing), 주입 가능한 외부 호출 어댑터, 응답 매핑, 고정/호환 오류를 분리한다. 인증·현재 회원 확인·120/분은 기존 패턴을 재사용한다. JWT 오류/탈퇴/없는 회원과 제한 초과에서는 외부 호출 0회를 검증하고, 정상/실패 모두 업무 DB가 변하지 않는지 검사한다. 가짜 서버로만 v2/v3 비교하며 실제 API·키를 테스트하지 않는다. 이번 작업은 이 설계에서 멈춘다.

## 7. 확정 결정·진행 기록

- 8-1 A: 공용 테스트 v2 시작 프로세스에 `preload-v2.cjs`를 주입한다. v2 원본 파일 변경 없이 고정 Kakao endpoint만 루프백 가짜 HTTP 서버로 전달하며 다른 fetch와 redirect/fallback을 차단한다. 기존 `timing-v2.cjs`는 변경하지 않는다. v3는 base URL을 설정으로 주입하고 운영 기본 endpoint는 v2와 같다.
- 8-2 B: 연결 2초·응답 5초를 설정값으로 관리한다. 재시도 없음. Flutter `apps/mobile/lib/core/network/dio_client.dart`의 connectTimeout/receiveTimeout은 각각 10초(25~26행, 재발급 클라이언트 124~125행)다. 앱보다 서버가 먼저 오류 응답을 반환하려는 결정이다. 알려진 차이에 등록한다.
- 8-3 B: 500 `카카오 검색 API 호출 실패`로 고정하며 원인은 서버 로그에만 남긴다. 키·Authorization 미기록, 본문 길이 제한. 알려진 차이에 등록한다.
- 8-4 A: 느슨한 정상 응답 매핑을 유지한다. 스키마 검증은 이관 후 개선 후보로 남긴다.

첫 구현 단계: `SharedHarness`가 기존 v2 컨테이너에 preload 파일과 가짜 서버 제어 포트만 추가한다. 추가 이미지 빌드/컨테이너는 없다. child `node -e`에는 preload 서버를 중복 실행하지 않으므로 기존 독립 관측도 유지한다. 재시작 뒤 게시된 포트를 다시 조회한다. 가짜 서버의 제어 경로는 테스트에서만 사용하고 운영 Compose에는 주입하지 않는다.

`comparisonTest --tests '*KakaoPreloadTest' --tests '*KakaoObservationTest'`: 총 57건, 성공 57·실패 0·skip 0·알려진 차이 0, **36.316초**(v2 이미지 캐시), v2 빌드 호출/최초 기동 각 1·재시작 0·인증 대기 0ms. 공용 v2에 56개 기존 fixture를 재요청해 HTTP 상태·본문·가짜 서버에 도착한 path/query·Authorization 부재·전체 업무 DB 불변을 확인했다. 기존 독립 관측 1건도 통과했다. 아직 v3 검색 구현 및 전체 회귀/build 검증은 하지 않았다.

### 8-5 HTTP 클라이언트 — 추가 선택 대기

Java 21 기본 HttpClient는 연결 실패를 자동 재시도할 수 있고 `jdk.httpclient.disableRetryConnect`/`jdk.httpclient.redirects.retrylimit`은 JVM 공통 시스템 속성이다. 단순히 애플리케이션 retry 코드를 쓰지 않는 것으로는 "재시도 없음"을 보장할 수 없다. [Java 21 공식 네트워크 속성](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/module-summary.html).

- **A 권장: Apache HttpClient 5 의존성 추가.** Spring Boot BOM 버전을 따라 카카오 전용 클라이언트에서 자동 재시도를 비활성화한다. 다른 OAuth/HTTP 호출의 설정을 바꾸지 않는다. 단점은 런타임 의존성 추가다.
- **B: JDK HttpClient 유지·JVM 전체 자동 재시도 금지.** 새 의존성은 없지만 JDK 기반 다른 외부 호출에도 공통 설정이 적용되며, 테스트/bootRun/JAR/Compose에서 설정 전달을 맞춰야 한다.

어느 쪽이든 헤더 수신 이후 body가 느리게 오는 경우까지 응답 5초 한도를 검증하고, 연결 종료/5xx/timeout에서 가짜 서버 요청 수로 재시도 없음을 확인한다. 이 선택 전에는 의존성이나 JVM 재시도 설정을 변경하지 않는다.
