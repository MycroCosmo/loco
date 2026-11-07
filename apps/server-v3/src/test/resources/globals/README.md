# 전역 HTTP 관측 fixture — 구현 결정 대기

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

현재 fixture의 connection=close는 Node 관측 클라이언트의 `agent:false`에 따른 값이다. v2/v3 비교에서 hop-by-hop 전송 헤더와 본문 길이/validator를 어떻게 검증하는지는 후속 비교 구현에 명시해야 한다. **X-Powered-By는 가변 값으로 숨기지 않는다.** 현재 테스트는 v2 재관측 검사이며 v3 헤더 호환을 통과했다는 뜻이 아니다.

## 관측 결과

- 기본 허용 Origin은 localhost:8080/3000. 미허용 Origin 실제 요청은 같은 업무 상태/본문을 반환하며 Allow-Origin만 없다. 사전 요청도 403이 아니라 204, 고정 methods/allowed-headers를 반환한다. Origin 없음·문자열 null·요청한 추가 헤더도 관측했다.
- 정상·Validation 400·401·404·업무 500에는 Helmet 헤더가 붙는다. `/uploads`의 실제 파일만 Allow-Origin `*`·CORP `cross-origin`으로 덮어쓰며 없는 파일은 일반 404 헤더다. HEAD·Range206·조건부304도 고정했다.
- JSON/form은 **10MiB=10,485,760바이트까지 파싱을 통과**, +1바이트에서 413 `{"statusCode":413,"message":"request entity too large"}`. 아래/같음의 404는 없는 경로까지 도달했다는 뜻이다. UTF-8 다중 바이트, chunked, gzip 해제 후 초과도 확인했다.
- form 1,001개 파라미터는 413 `too many parameters`. JSON charset ISO-8859-1은 415. 빈 JSON·배열은 파서 통과, null/숫자·깨진 JSON은 400. deflate JSON도 파싱된다.
- 본문 파싱 오류는 Helmet/CORS보다 먼저 발생한다. 그래서 **보안/CORS 헤더가 없고 `X-Powered-By: Express`가 남는다**. 깨진 JSON 400·크기 초과413·charset415 모두 해당한다.

생성 검증: JUnit 1/1 성공, 22.568초. 고정 fixture 재검증: **1/1 성공·실패/skip 0, 24.251초**(v2 이미지 캐시, 재시작/대기 0). 응답 분포는 200×11, 204×19, 206×1, 304×1, 400×8, 401×5, 404×21, 413×7, 415×1, 500×5다. 전체 회귀/build는 아직 실행하지 않았다.

## 10-1 런타임 표시 헤더 — 사용자 결정 필요

| 선택지 | 장점 | 단점 |
| --- | --- | --- |
| A. v3에서는 X-Powered-By만 생략하고 의도된 차이로 등록 (권장) | Spring 응답에 Express라는 잘못된 런타임 표시를 넣지 않음. 비교 보고서에서 차이를 명시적으로 검사 | 해당 오류 경로의 전체 헤더는 v2와 달라짐 |
| B. v2와 같이 오류 경로에 X-Powered-By: Express까지 재현 | 헤더까지 v2 관측 그대로 유지 | 실제 v3 런타임과 다른 표시를 호환 목적으로 의도적으로 노출 |

어느 쪽이든 나머지 Helmet 헤더·CORS·파서 실행 순서는 기존 v2 유지 원칙을 따른다. A는 파서 오류에도 Helmet을 먼저 적용하자는 제안이 아니다. 사용자 요청의 “새 결정이 있으면 중단” 조건에 따라 v3 전역 필터·헤더 비교 구현 및 전체 회귀·커밋·push는 답을 받은 뒤 진행한다.
