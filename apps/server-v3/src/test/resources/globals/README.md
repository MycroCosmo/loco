# 전역 HTTP 관측·비교 — 10-1 A 구현

2026-11-07, 원본 v2 `dist/main.js`에 HTTP 79사례를 보냈다. 운영 v2 소스나 Helmet/CORS/body-parser를 대체하지 않는다. [observe-v2.cjs](observe-v2.cjs)가 요청 조건·생성 규칙을 만들고 [http-v2.json](http-v2.json)이 응답 전체 헤더·본문·상태를 고정한다. 요청의 `$valid`는 테스트에서 직접 서명하는 JWT 자리이며 실제 토큰은 저장하지 않는다.

`GlobalHttpObservationTest` 1건 안의 HTTP 79사례이며 JUnit 79건으로 세지 않는다. 기본 실행은 fixture를 다시 관측해 같아야 통과한다. 크기 큰 본문 자체를 저장하지 않고 UTF-8 바이트 수·압축·chunked 여부를 기록한다. 응답 body가 없는 HEAD/204/304는 빈 문자열이다.

## 재현

`apps/server-v3`에서 Java 21·Node/npm·Docker로 실행:

```powershell
.\gradlew.bat comparisonTest --tests '*GlobalHttpObservationTest' --rerun --console=plain
```

원본 관측을 갱신할 때만 `-PglobalsObserve=true`를 지정한다. 결과는 `build/reports/globals/http-v2.json`이며 원본을 직접 덮어쓰지 않는다. 변경 이유·응답 차이를 검토한 뒤에만 fixture를 교체한다.

500은 정상 DTO의 피드백 INSERT에 테스트 DB 임시 트리거로 오류를 주입한다. 원본 요청/오류 처리 경로를 거치며 트리거·함수는 finally에서 제거한다. 관측용 정적 파일도 생성 후 제거한다. ALLOWED_ORIGINS 설정/공백 기본값 확인은 같은 v2 이미지 안에서 원본 bootstrap의 별도 로컬 프로세스를 잠시 실행한다. 외부 Firebase·카카오·OAuth API를 호출하지 않으며 서비스 계정/외부 키를 사용하지 않는다.

## 전체 헤더와 가변 값

헤더 이름을 소문자로 정렬한다. **헤더 존재 자체는 유지**하고 아래 값만 `$variable`로 치환한다. 나머지 헤더는 허용 목록으로 추려내지 않고 전부 fixture에 남긴다.

| 값 비교 제외 | 이유 |
| --- | --- |
| date | 서버 응답 시각 |
| etag | 직렬화된 표현·파일 metadata로 만든 validator |
| last-modified | 파일 수정 시각. 조건부 요청 자체는 고정 시각으로 별도 관측 |
| x-ratelimit-remaining, x-ratelimit-reset | 실행 순서·시각에 따라 달라지는 잔여량·만료. 기존 429 필수 검증은 유지 |
| x-harness-handler, x-harness-route, x-harness-image-nanos | 계측 전용 헤더. 운영 계약이 아니며 이미지 시간도 매번 다름 |

fixture의 connection=close는 Node 관측 클라이언트의 `agent:false`에 따른 값이다. v3 비교의 `ResponseHeaders`는 전체 헤더 집합을 비교하며 아래 규칙을 적용한다.

- connection/keep-alive/transfer-encoding은 서버·클라이언트별 전송 방식이므로 값/존재 비교에서 제외한다. content-length는 JSON 직렬화·파서 오류 문구에 따른 바이트 차이가 있어 교차 비교에서는 제외하지만, 각 v3 응답의 실제 바이트 수와 일치하는지 검사한다. HEAD의 길이는 fixture의 파일 크기와 비교한다.
- date/last-modified는 RFC1123 시각, etag는 HTTP validator 형식, 요청 제한 잔여/만료는 음이 아닌 숫자를 검사한 뒤 값만 제외한다. 이 헤더들의 존재 유무는 그대로 비교한다. 자기 ETag로 조건부 GET을 보내는 304도 별도 검사한다.
- Content-Type의 대소문자·세미콜론 공백, Cache-Control/Vary 토큰 순서만 정규화한다. 보안·CORS 헤더를 선택해서 비교하거나 미지의 헤더를 무시하지 않는다. 하네스 전용 헤더 3개만 운영 비교에서 제외한다.
- **X-Powered-By는 가변 헤더가 아니다.** 승인된 10-1 A에 따라 v2 조기 오류의 Express 값과 v3의 부재를 별도로 검사하고 `http.runtime-header` 의도된 차이 1건을 보고한다.
- 깨진 JSON/null/숫자의 `/message`만 기존 런타임 문구 예외로 지정한다. 상태·구조·다른 본문 필드·조기 헤더 부재는 필수 비교다.

## 관측 결과

- 기본 허용 Origin은 localhost:8080/3000. 미허용 Origin 실제 요청은 같은 업무 상태/본문을 반환하며 Allow-Origin만 없다. 사전 요청도 403이 아니라 204, 고정 methods/allowed-headers를 반환한다. Origin 없음·문자열 null·요청한 추가 헤더도 관측했다.
- 정상·Validation 400·401·404·업무 500에는 Helmet 헤더가 붙는다. `/uploads`의 실제 파일만 Allow-Origin `*`·CORP `cross-origin`으로 덮어쓰며 없는 파일은 일반 404 헤더다. HEAD·Range206·조건부304도 고정했다.
- JSON/form은 **10MiB=10,485,760바이트까지 파싱을 통과**, +1바이트에서 413 `{"statusCode":413,"message":"request entity too large"}`. 아래/같음의 404는 없는 경로까지 도달했다는 뜻이다. UTF-8 다중 바이트, chunked, gzip 해제 후 초과도 확인했다.
- form 1,001개 파라미터는 413 `too many parameters`. JSON charset ISO-8859-1은 415. 빈 JSON·배열은 파서 통과, null/숫자·깨진 JSON은 400. deflate JSON도 파싱된다.
- 본문 파싱 오류는 Helmet/CORS보다 먼저 발생한다. 그래서 **보안/CORS 헤더가 없고 `X-Powered-By: Express`가 남는다**. 깨진 JSON 400·크기 초과413·charset415 모두 해당한다.

생성 검증: JUnit 1/1 성공, 22.568초. 고정 fixture 재검증: **1/1 성공·실패/skip 0, 24.251초**(v2 이미지 캐시, 재시작/대기 0). 응답 분포는 200×11, 204×19, 206×1, 304×1, 400×8, 401×5, 404×21, 413×7, 415×1, 500×5다. 전체 회귀/build는 아직 실행하지 않았다.

## 10-1 런타임 표시 헤더 — A 확정

| 선택지 | 장점 | 단점 |
| --- | --- | --- |
| A. v3에서는 X-Powered-By만 생략하고 의도된 차이로 등록 (권장) | Spring 응답에 Express라는 잘못된 런타임 표시를 넣지 않음. 비교 보고서에서 차이를 명시적으로 검사 | 해당 오류 경로의 전체 헤더는 v2와 달라짐 |
| B. v2와 같이 오류 경로에 X-Powered-By: Express까지 재현 | 헤더까지 v2 관측 그대로 유지 | 실제 v3 런타임과 다른 표시를 호환 목적으로 의도적으로 노출 |

사용자가 **A를 선택**했다. 파서 오류에도 Helmet/CORS를 먼저 적용한 것이 아니다. Spring의 기본 보안 헤더를 중복 적용하지 않고 GlobalHttpFilter에서 v2와 같은 값을 적용한다. 정상 정적 파일만 ACAO `*`·CORP `cross-origin`이며 없는 파일의 404에는 일반 API 헤더가 적용된다. API ETag는 본문 표현으로 생성하고 정적 파일은 크기·수정 시각 validator와 스트리밍을 사용한다.

이전 점검표의 “JSON 크기 제한 없음”은 정정한다. 기존 JsonBodyFilter에 이미 10MiB와 인증 전 JSON 선검사가 있었고, 이번에는 이를 이동·확장해 form·압축·문자셋 및 응답 헤더를 보완했다. 라이브러리는 추가하지 않았다. form 호환은 고정된 원본 body-parser 1.20.4/qs 6.14.2와 배열·객체·반복·잘못된 percent/대괄호·깊이 32·파라미터 1,000/1,001 경계를 실제 로컬 HTTP로 교차 검사한다.

검증: 설정 3건+전역 비교/재관측/form 91건, **94/94 성공·실패/skip 0, 알려진 차이 1, 68.198초**. 이후 불완전한 대괄호 등 9사례를 추가한 form 교차 검사 **1/1 성공(HTTP 38사례), 22.970초**. 전체 회귀는 후속 단계에서 별도로 기록한다.

```powershell
.\gradlew.bat test --tests '*GlobalHttpSettingsTest' --rerun comparisonTest --tests '*GlobalHttp*Test' --tests '*ExtendedFormComparisonTest' --rerun --console=plain
```

개선 후보: 조기 파서 400/413/415에는 CORS 헤더가 없어 Flutter 웹에서는 실제 상태/본문 대신 CORS 오류로 보인다. v2 계약이므로 이번에는 유지한다. 이후 순서를 바꿀 때는 미인증 요청·크기 초과·OPTIONS와 함께 다시 비교해야 한다.

공식 파서 근거: [body-parser 1.20.4 URL-encoded](https://github.com/expressjs/body-parser/blob/1.20.4/lib/types/urlencoded.js), [qs 6.14.2 parse](https://github.com/ljharb/qs/blob/v6.14.2/lib/parse.js). 기대 응답은 문서 추정이 아니라 원본 HTTP fixture와 실제 라이브러리 실행에서 얻는다.
