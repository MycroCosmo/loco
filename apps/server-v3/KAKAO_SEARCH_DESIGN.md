# 8-B 카카오 장소 검색 — 결정과 구현 기록

대상은 GET /api/places/search. 8-1 A / 8-2 B / 8-3 B / 8-4 A / 8-5 A를 확정하고 v3 검색을 구현했다. 아래 검증 기록을 따른다. 실제 카카오 요청 0건, 실제/더미 API 키 사용 0건이다.

## 1. 근거와 관측 격리

- v2 원본: `apps/server/src/places/places.controller.ts`, `dto/search-place.dto.ts`, `kakao/kakao-place-search.service.ts`.
- `KakaoObservationTest`는 기존 v2 테스트 컨테이너 안에서 별도 Node 프로세스를 실행한다. 원본 compiled AppModule, Guard, ValidationPipe, Controller, Service를 그대로 올리고 실제 HTTP 요청을 보낸다. v2 소스 파일은 수정하지 않는다.
- `src/test/resources/kakao/observe-fake.cjs`는 AppModule을 불러오기 **전** 환경의 KAKAO_REST_API_KEY를 삭제한다(읽거나 출력하지 않음). 테스트 JWT만 기존 로컬 테스트 secret으로 발급한다.
- global fetch는 원본의 정확한 Kakao origin/path만 허용하고, 이를 127.0.0.1의 가짜 HTTP 서버로 바꾼다. Authorization은 전달하지 않는다. 다른 fetch URL은 오류로 차단하고 원래 주소로 fallback하지 않는다. redirect도 error여서 외부 redirect를 따라가지 않는다.
- native fetch가 받는 주소는 루프백뿐이다. 요청한 Kakao 주소 문자열을 파싱하는 것은 네트워크 호출이 아니다. 가짜 서버가 받은 path/query와 Authorization 부재를 fixture로 검증한다.
- 이 교체는 외부 통신을 바꾸는 **테스트 대역**이다. 동작을 보존해야 하는 `timing-v2.cjs` 계측과 다르다. 이후 사용자 승인으로 공용 v2 시작 preload에도 적용했으며, 독립 관측 fixture는 유지했다.
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

## 5. 확정 결정과 근거

| 결정 | 확정 내용 |
| --- | --- |
| 8-1 A | 테스트 preload의 fail-closed fetch 교체·루프백 가짜 서버. v3 base URL 설정 주입, 운영 기본값은 v2 Kakao endpoint |
| 8-2 B | 연결 2초·응답 **소켓 무활동 5초**, 설정값. 재시도 없음. 전체 수신 시간 제한은 구현하지 않음 |
| 8-3 B | 모든 외부 호출/파싱 실패를 500 `카카오 검색 API 호출 실패`로 고정. 원인은 서버 로그에만 기록 |
| 8-4 A | 정상 응답의 느슨한 매핑 유지. 런타임 스키마 검증은 이관 후 개선 후보 |
| 8-5 A | Apache HttpClient 5, Spring Boot BOM 버전, 카카오 전용 인스턴스에서 자동 재시도 비활성화 |

**새 의존성 추가 예외: JDK HttpClient의 연결 재시도가 JVM 전역 속성으로만 제어되기 때문.** JDK 유지+전역 재시도 금지와 비교한 뒤 Apache 전용 설정을 선택했다. 실제 해석 버전은 Spring Boot 3.5.16 BOM의 HttpClient **5.5.2**이며 버전을 직접 덮어쓰지 않는다. [JDK 공식 속성](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/module-summary.html).

Flutter `apps/mobile/lib/core/network/dio_client.dart`의 connectTimeout/receiveTimeout은 각 10초(25~26행, 재발급용 124~125행)다. 서버가 먼저 오류 메시지를 반환하도록 연결 2초·소켓 무활동 5초를 선택했다. 다만 [Dio receiveTimeout](https://pub.dev/packages/dio)은 전체 다운로드 시간이 아니라 첫 응답·데이터 이벤트 사이의 대기 제한이다.

현재 v3는 외부 본문을 모두 읽고 JSON을 매핑한 뒤 앱에 응답한다. 따라서 외부가 조금씩 계속 보내면 서버의 5초 무활동 제한은 발동하지 않고, 앱은 서버의 첫 응답을 기다리다 약 10초에서 실패할 수 있다. **이 10초는 앱의 대기 상한이지 서버 작업의 전체 시간 상한이 아니다.** 앱 연결 종료가 진행 중인 외부 호출을 자동 취소하지도 않는다. 전체 deadline·클라이언트 취소 전파는 이번에 구현하지 않는다.

## 6. 구현 구조·설정

| 파일 | 역할 |
| --- | --- |
| KakaoSearchController | /api/places/search와 기존 @AuthenticatedApi 인증·회원 조회·120/분 |
| KakaoSearchInputs | DTO 오류 순서, IsNumberString 범위, parseInt/parseFloat와 JS 문자열화 |
| KakaoSearchResponse | nullish 기본값·잘못된 필드 타입·parseFloat·NaN/Infinity→null, -0→JSON 0 |
| KakaoSearchClient | 카카오 전용 Apache 클라이언트, 연결/TLS·응답 무활동 timeout, 오류의 고정 응답·안전한 로그 |
| kakao/preload-v2.cjs | 원본 v2 fetch를 가짜 서버로만 교체, 시나리오·수신 요청 수 제어 |

`spring.http.client.factory=jdk`를 명시해 새 Apache 의존성으로 기존 OAuth/Boot HTTP 팩터리가 자동 변경되지 않게 했다. 카카오 인스턴스만 Apache를 직접 구성한다. 클라이언트 종료 시 풀도 닫는다. 쿠키·redirect·자동 retry를 비활성화했다. TCP 연결과 TLS handshake에 연결 제한을 적용하며 풀에서 연결을 기다리는 시간도 같은 값으로 제한한다. 정상 body 읽기는 소켓 무활동 제한만 적용한다. [Apache socket timeout 설명](https://hc.apache.org/components/httpcomponents-client-5.3.x/5.3.1/httpclient5/apidocs/org/apache/hc/client5/http/config/ConnectionConfig.Builder.html).

| 환경 키 | 기본값·의미 |
| --- | --- |
| KAKAO_SEARCH_BASE_URL | https://dapi.kakao.com/v2/local/search/keyword.json (전체 endpoint) |
| KAKAO_REST_API_KEY | 외부 환경의 키. 코드·fixture에 넣지 않으며 테스트는 빈 값 |
| KAKAO_CONNECT_TIMEOUT_MS | 2000, 양의 정수. 연결/TLS/풀 대기 |
| KAKAO_RESPONSE_TIMEOUT_MS | 5000, 양의 정수. 응답 소켓 무활동 |

Compose v3에도 설정을 전달한다. .env.example에는 키만 둔다. 로컬 .env에서 기본값을 사용하려면 선택 설정 키를 삭제하거나 주석 처리한다(명시적 빈 문자열은 timeout 숫자가 아니다). 테스트는 base URL을 가짜 서버로 덮어쓰며 빠른 테스트에는 루프백의 닫힌 endpoint를 지정해 실제 Kakao로 나가지 않는다. v2/Flutter 원본은 수정하지 않는다.

오류 응답은 `{statusCode:500,message:"카카오 검색 API 호출 실패",error:"Internal Server Error"}`다. 로그는 상태 코드 또는 예외 **종류**만 기록한다. 오류 body는 진단용으로 최대 1,024바이트를 읽고 **그 길이만** 남긴다. 원문 body·URL·Authorization·키·예외 message/stack은 남기지 않으며 Apache wire/header 로그도 OFF다. 원문을 잘라 남기는 방식보다 노출을 줄인 대신 로그에서 원문 오류 설명을 볼 수 없는 한계가 있다.

## 7. 필수 비교와 알려진 차이

- 고정 56개 HTTP 사례에서 상태·JSON 구조/값·외부에 전달된 쿼리를 대조한다. 업무 DB는 양쪽 모두 전체 스냅샷 전후 불변이다. 인증된 요청의 SQL은 회원 확인 1개로 고정한다.
- 추가 숫자 경계는 x/y/radius/page/size를 모두 대조한다. 1,010개 double은 Node String 결과와 대조해 지수 임계값·최소/최대·영점·큰 정수를 검사한다.
- 정상 외부 응답의 배열/객체/boolean/null, 숫자 좌표의 JS 문자열화, 0/-0, 뒤에 문자가 붙은 parseFloat도 실제 HTTP로 비교한다.
- 토큰 없음/만료/다른 서명/잘못된 형식/탈퇴/없는 회원은 401, 외부 호출 0회, DB 불변이다. 120개 정상 호출 뒤 121번째 429는 외부 요청 수가 120 그대로이며 DB 불변이다.
- 연결 종료·오류 body 종료·503·헤더 지연·본문 무활동에서 외부 **HTTP 요청 1회**를 검사한다. redirect도 추가 요청 없이 실패한다. 진짜 TLS 연결 실패/handshake 무활동은 로컬 가짜 TCP 서버가 **연결 1회**를 관측한다. TLS가 실패하면 HTTP는 성립하지 않으므로 이를 "HTTP 요청 1회"라고 기록하지 않는다.
- 6.2초 헤더 지연 또는 body 무활동은 v2 200, v3 약 5초 후 고정 500이다. 0.5초마다 body가 도착하며 전체 6.2초가 걸리는 경우는 두 앱 모두 200이다. 이 검사로 소켓 무활동과 전체 시간 제한을 구별한다.
- 오류 로그에 반사된 Authorization/키/검색어/body가 없는지, 진단 길이가 1024인지 검증한다. 실제 API 키는 사용하지 않는다.

보고서의 알려진 차이는 두 **정책**으로 등록한다. `kakao.failure-message`는 외부 4xx/5xx 원문과 JSON/응답 오류를 고정 500으로 바꾼 차이, `kakao.socket-timeout`은 5초 무활동 실패 차이다. 각각의 실제 v2/v3 결과를 필수 테스트로 확인하며 skip/abort하지 않는다. 기존 8개와 합치면 10개다.

v2 컨테이너 재시작 때 임의 게시 포트가 바뀌던 테스트 문제는 가짜 서버 포트의 명시적 바인딩으로 해결했다. 이미지/컨테이너는 추가하지 않았고 timing-v2.cjs도 변경하지 않았다. 키 삭제와 fetch 교체는 start-v2.cjs의 preload에서만 실행해 기존 child Node 숫자/fixture 관측은 유지한다.

## 8. 검증 기록·재실행

- 최초 독립 v2 관측: 1/1 성공, 56 HTTP, 19.700초. 고정 fixture 재검증 19.333초.
- 공용 preload 도입 f421a14: 57/57 성공, 36.316초(v2 이미지 캐시), 실패/skip/알려진 차이 0.
- 첫 v3 API 비교: 56/56 성공, 고정 오류 알려진 차이 1. 후속 테스트에서 JSON -0 직렬화와 재시작 포트 문제를 찾아 수정했다.
- 구현 집중 검증: 160/160 성공(test 22 + comparisonTest 138), 실패/skip 0, 알려진 차이 2, 146.172초(v2 이미지 캐시). 기존 OAuth 계약 18건과 Boot HTTP 팩터리 유지 검사도 포함한다.
- 최종 전체 회귀·Spotless/build: **1,286건=성공 1,285·실패 0·HEIC 공통 제약 skip 1**, 알려진 차이 10, **838.209초(13분 58초, v2 이미지 캐시)**. test 234건(233 성공/1 skip), comparisonTest 1,052건 전부 성공. 기존 1,145개 식별자 누락 0·추가 141개.
- v2 빌드 호출/최초 기동 각 1·재시작 17. authWaitMillis=0, withinCaseWaitMillis=61,006으로 기존 마커 관측의 창 대기는 남아 있다. 원본 결과·로그·manifest·quota는 `build/performance/kakao-final/`에 보존한다.
- Compose 문법 검사 통과(컨테이너 추가 기동 없음), 실행 JAR의 HttpClient 5.5.2 포함·테스트 대역 제외를 확인했다. v2/Flutter 원본·V1·v2 Dockerfile·timing-v2.cjs 변경 없음. 실제 Kakao API·키 사용은 없으며 운영 외부 연결은 이번 검증 범위가 아니다.

```powershell
cd apps/server-v3
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
.\gradlew.bat test --tests '*KakaoTransportTest' --tests '*OAuthContractTest' comparisonTest --tests '*Kakao*Test' --console=plain
# 전체 회귀·Spotless·build와 벽시계 시간
.\scripts\measure-tests.ps1 -Label kakao-final -RestartMode quota
```

## 9. 이관 후 개선 후보

외부 응답 런타임 스키마 검증, 전체 deadline/취소 전파, 느린 body가 연결 풀·서버 스레드를 오래 점유하는 상황은 후속 검토 대상이다. 현재의 느슨한 200 매핑과 전체 시간 제한 부재가 근거다. 이번에는 해당 기능이나 실제 Kakao 호출을 추가하지 않는다.
