# 6번 묶음: 장소 이미지 API 설계

상태: **6-1 B / 6-2 B / 6-3 B / 6-4 B(범위 조정) 확정. POST/DELETE 구현·전체 회귀·build 완료.** 조건부 원자적 UPDATE와 락 밖 변환을 적용한다. 이번에는 정합성 시나리오·SQL 수 고정까지만 진행하며, 방식별 성능 비교는 2단계 실험 백로그다.

대상은 POST/DELETE `/api/places/:placeId/images`다. 기준은 Git `d31c750`의 v2 `places.controller.ts`, `places.service.ts:addImage/removeImage`, `upload.service.ts`, `image-upload.options.ts`다. 관측 과정에서도 v2·Flutter·V1·운영 Java 코드는 수정하지 않았다.

## 1. 기존 결정과 응답

- Java 21, Spring Data JPA, PostgreSQL 17, TEXT UUID, 기존 Place LAZY 관계·native projection·Clock·TransactionTemplate을 사용한다. 새 라이브러리는 제안하지 않는다.
- 저장 위치는 기존 공유 로컬 볼륨의 `uploads/places/`, 공개 상대 URL은 `/uploads/places/<UUID>.webp`다. 개발 v3 UID/GID 1000:1000을 유지한다.
- 변환기는 승인된 B(libvips) 인터페이스를 재사용한다. 장소 이미지는 **1200×1200 안에 비율 유지(fit inside), 확대 없음, WebP quality 80**이다. 프로필/커버의 중앙 크롭과 구별해야 한다. 관측에서 2400×1600→1200×800, 64×48→64×48이었다. 기존 LocalImageStorage.Purpose에 PLACE 용도와 inside 처리를 추가했다.
- POST 201, DELETE 200. 둘 다 장소 전체 필드와 `createdBy {id,nickname,profileImageUrl}`을 반환한다. 평점/개수/map 추가 필드는 없다. 기존 PlaceResponses.Core를 재사용할 수 있다.
- 두 경로 모두 인증·현재 회원 조회·핸들러별 기본 120회/분 대상이다. 이미지 변경은 places.updatedAt을 갱신하되 장소 마커 셀·리뷰·알림을 갱신하지 않는다.

## 2. 실제 v2 입력 경계

`src/test/resources/place-images/input-v2.json`은 파일 입력 28종, `delete-v2.json`은 삭제 14종의 상태·전체 본문·DB URL·파일 크기/해시를 담는다. 아래는 그 요약이다.

| 입력 | v2 결과 |
| --- | --- |
| 파일 없음, 빈 filename, JSON body에 file 문자열 | 400 `이미지 파일을 첨부해주세요.` |
| 0바이트 파일 | 400 `빈 파일입니다.` |
| file 외 파일 필드 / file 두 개 | 400 `Unexpected field` |
| 허용하지 않는 MIME, image/png로 가장한 실제 GIF | 400 `지원하지 않는 이미지 형식입니다.` |
| 손상된 파일 | 400 `지원하지 않는 이미지 파일입니다.` |
| multipart boundary 없음 / 끝나지 않은 form | 400 `Multipart: Boundary not found` / `Multipart: Unexpected end of form` |
| 10MiB−1(10,485,759) bytes | 201, 유효 PNG 뒤 padding을 붙인 파일도 처리 |
| 정확히 10MiB(10,485,760) / 그보다 큼 | 413 `File too large` |
| JPEG/PNG/WebP/AVIF | 201, WebP 저장 |
| HEIC | 500 `Internal server error`, DB 유지, 새 0바이트 파일 1개 잔여 |
| 허용 MIME끼리 실제 형식과 불일치 | 201 (PNG를 image/jpeg로 보내도 성공) |
| 추가 일반 form 필드 | 무시하고 201 |
| 투명 PNG, EXIF JPEG, 애니메이션 WebP | 201, 관측 이미지 크기 64×48. 애니메이션은 첫 프레임, EXIF 자동 회전은 v2 코드에 없음 |

허용 MIME은 image/jpeg, png, webp, heic, heif, avif다. MIME 검사와 실제 디코딩은 별도다. HEIC/AVIF 결과는 현재 고정한 v2 sharp/컨테이너의 결과이며 모든 네이티브 빌드의 지원 여부를 일반화하지 않는다.

DELETE는 DTO가 없고 `@Body('imageUrl')`을 직접 받는다.

| imageUrl/본문 | v2 결과 |
| --- | --- |
| 키 누락, null, `""`, false, 0 / 최상위 빈 배열 | 400 `삭제할 이미지가 필요합니다.` |
| 1, true, 빈 배열, 빈 객체, 공백 문자열 | 200, 배열 포함 검사에 걸리지 않아 DB/파일 변경 없음 |
| 존재하지 않는 URL | 200, 변경 없음. updatedAt도 유지 |
| 존재하는 URL + 미정의 필드 | 미정의 필드 무시, 해당 URL 삭제 후 200 |
| 같은 URL 재삭제 | 200, 추가 변경 없음 |
| DB 배열에 같은 URL이 중복 | 같은 URL을 모두 제거하고 파일 삭제 한 번 |

문자열 변환으로 `1`을 `"1"`로 바꾸거나 임의의 DTO로 400 처리하지 않는다. 정상/오류 JSON 전체 모양은 fixture를 기준으로 하며, 401/500에는 `error` 키가 없는 기존 형식을 유지한다. 입력 강화는 별도 개선 후보다.

## 3. 권한과 검증 순서

`permissions-v2.json` 20종으로 다음을 확인했다.

- 인증 없는/탈퇴한/존재하지 않는 회원은 401이다. 인증은 multipart MIME 검사보다 앞선다.
- **장소 작성자만** 업로드/삭제할 수 있다. 다른 작성자의 장소라면 방장도 업로드 403 `권한이 없습니다.`, 삭제 403 `이미지 삭제 권한이 없습니다.`다.
- 비공개 지도방을 나간 장소 작성자도 업로드 201·삭제 200이다. 지도방 접근 권한/멤버십은 재검사하지 않는다. 4번 묶음의 장소 수정/삭제와 같은 개선 후보이며 이번 동시성 선택과 별개로 유지한다.
- POST: 인증 → multipart/MIME/크기 → 파일 유무 → 장소 존재 → 작성자 → 현재 3장 여부 → 내용 디코딩/변환 → 저장 → DB 추가.
- 따라서 파일 없는 요청은 없는 장소에도 400, 다른 작성자+금지 MIME은 403보다 MIME 400, 3장+손상 파일은 디코딩 오류보다 `최대 3장까지 업로드 가능합니다.` 400이다. 3장+파일 없음은 파일 없음 400이다.
- DELETE: 인증 → imageUrl truthiness → 장소 존재 → 작성자 → 배열 포함 검사 → DB 갱신 → 파일 삭제. 없는 장소+imageUrl 누락은 404보다 400이다.

## 4. 저장·삭제·실패 경계

| 단계/실패 | 실제 v2 DB·파일 결과 |
| --- | --- |
| POST 정상 | 변환한 UUID.webp를 먼저 저장한 후 Prisma `imageUrls: {push: url}`. 응답은 갱신된 장소 |
| POST UPDATE 또는 지연 커밋 예외 | 500, DB 전체 롤백. catch가 새 파일을 삭제해 기존 파일만 남음 |
| POST 저장 디렉터리 장애 | 500, DB·기존 파일 유지 |
| POST 변환 중 실패(HEIC) | saveAsWebP가 URL을 반환하기 전 예외. addImage의 DB try/catch 밖이므로 0바이트 파일이 남음 |
| DELETE 정상 | 읽었던 배열에서 URL을 뺀 **배열 전체 set** → DB 커밋/응답 데이터 생성 → unlink → 200 |
| DELETE UPDATE 또는 지연 커밋 예외 | 500, DB 롤백, 파일 삭제 안 함 |
| DELETE 실제 파일이 이미 없음 | DB URL 제거 후 200 |
| DELETE URL 경로가 디렉터리 | DB URL 제거 후 unlink 실패를 삼켜 200, 디렉터리는 남음 |
| DELETE 외부 URL | DB URL 제거 후 경로 검사로 외부 파일 작업을 하지 않고 200 |

`failure-v2.json` 9종에 기록했다. DB 예외는 실제 DB 트리거, 지연 커밋은 DEFERRABLE CONSTRAINT TRIGGER, 저장 장애는 격리 컨테이너의 테스트용 places 디렉터리를 잠시 일반 파일로 대체해 재현했다. 테스트 종료 시 모두 복원한다. 파일은 존재 여부뿐 아니라 SHA-256도 기록한다.

외부 URL 테스트의 old-a 파일은 요청 **전부터** DB 참조가 없는 시드다. beforeState/후 상태를 함께 기록하며 이를 DELETE가 새로 만든 고아 파일이라고 세지 않는다. 고아 파일은 대상 장소만이 아니라 전체 places.imageUrls 참조를 확인해 판정한다.

DB 트랜잭션은 파일시스템을 원자적으로 커밋하지 못한다. 아래 동시성 수정안을 택해도 변환 실패 잔여, 삭제 실패, 프로세스 종료 시 파일 보상 문제까지 자동 해결되는 것은 아니다. 6-1의 수정 범위는 정상 파일 I/O에서 재현된 세 경쟁 문제다. HEIC 공통 제약과 기존 삭제 실패 무시 정책을 조용히 바꾸지 않는다.

## 5. 실제 동시 요청 관측

### 재현 방법

`PlaceImageRaceObservationTest`는 실제 HTTP를 보내며 서비스/Prisma/sharp를 mock하지 않는다. 별도 JDBC 연결이 대상 places 행을 FOR UPDATE로 잠근다. v2의 평범한 SELECT는 진행되고 UPDATE만 대기한다. 각 요청의 UPDATE가 `pg_stat_activity.wait_event_type='Lock'`에 도달한 것을 확인한 후 다음 요청을 보낸다. 모두 대기하면 잠금을 해제하고 HTTP 응답 및 DB·파일을 읽는다. v2 코드는 지연/분기 주입 없이 그대로다.

이 방법은 동시에 진행 중인 요청이 같은 오래된 상태를 읽는 일정을 강제한다. 자연 트래픽에서 발생할 확률을 측정한 실험은 아니다. 6개 조건을 각각 **3회** 반복했다. 총 경쟁 HTTP 60회, 깨진/고아 URL의 HEAD 확인 6회다.

A=`/uploads/places/old-a.webp`, B=`/uploads/places/old-b.webp`, U=새 UUID URL. 처음에는 항상 DB [A,B], 실제 파일 A/B 2개이며 두 파일 모두 유효 WebP다.

| 동시 조건 | 요청 응답(각 반복) | 최종 DB 장수(3회) | 최종 URL/파일 | 깨진 URL / 고아 파일 |
| --- | --- | --- | --- | --- |
| 업로드 N=2 | 201×2 | 4,4,4 | [A,B,U1,U2], 파일 4개 | 0 / 0 |
| 업로드 N=4 | 201×4 | 6,6,6 | [A,B,U1…U4], 파일 6개 | 0 / 0 |
| 업로드 N=8 | 201×8 | 10,10,10 | [A,B,U1…U8], 파일 10개 | 0 / 0 |
| A 삭제 → B 삭제의 UPDATE 대기 순서 | 200,200 | 1,1,1 | DB [A], 실제 A/B 파일 모두 없음. A HEAD=404 | 1 / 0 |
| 업로드 → A 삭제의 UPDATE 대기 순서 | 201,200 | 1,1,1 | DB [B], 파일 B/U. 성공한 업로드 U가 DB에서 사라짐. U HEAD=200 | 0 / 1 |
| A 삭제 → 업로드의 UPDATE 대기 순서 | 200,201 | 2,2,2 | DB [B,U], 파일 B/U | 0 / 0 |

각 실패 현상은 해당 조건에서 **3/3** 재현했다. 업로드 제한은 모든 요청이 처음 2장이라고 읽어 통과한 뒤 push하기 때문에 초과한다. 삭제 두 건은 오래된 배열의 set이 상대방 삭제를 되살리지만 두 unlink는 모두 실행된다. 혼합 경쟁은 삭제 set이 먼저 추가된 U를 덮어쓰는 경우에만 업로드 URL/파일 대응이 깨진다. 최종 URL과 파일 이름/해시는 `race-*.json`에 함께 저장했다.

잠금 해제 전 파일 수는 N개 업로드에서 2+N, 두 삭제에서 2, 혼합에서 3이다. 즉 업로드는 DB 반영 전에 파일이 존재하고, 삭제는 DB 커밋 전에는 파일을 지우지 않는 순서도 관측했다.

### 이번 진단 시간의 의미

HTTP 시작→응답 완료 시간과 잠금 해제 직전 blocked UPDATE의 `clock_timestamp()-query_start`를 원본 `timing-*.json`에 기록한다. 후자는 **쿼리 시작 후 경과 시간이며 순수 lock wait가 아니다**. 전자에는 의도적인 DB gate·후속 요청 준비·상태 조회 시간이 포함된다. 자원 제한을 걸어 A/B 성능을 비교한 실행이 아니므로 이 수치로 6-2 방식의 속도 순위를 정하지 않는다. 확정 재실행 수치와 스냅샷은 아래 검증 절에 남긴다.

2026-11-06 고정 fixture 재검증 실행의 진단 범위(각 조건 3회, 단위 ms):

| 조건 | 요청 시간 min~max | 해제 직전 blocked 쿼리 경과 min~max |
| --- | ---: | ---: |
| 업로드 N=2 | 90.8~146.9 | 80.8~137.6 |
| 업로드 N=4 | 109.3~230.7 | 98.2~221.0 |
| 업로드 N=8 | 112.2~333.8 | 99.3~324.8 |
| 서로 다른 두 삭제 | 112.5~165.5 | 103.6~157.7 |
| 업로드→삭제 | 124.4~158.3 | 114.6~147.9 |
| 삭제→업로드 | 113.6~153.1 | 104.3~145.8 |

이 실행의 표본별 원본은 `src/test/resources/place-images/observed-timing-v2.json`에 보관한다. 다음 실행에서 시간은 달라져도 정합성 fixture 결과는 같아야 한다.

## 6. 결정 이력 — B/B/B/B(범위 조정) 확정

### 6-1 v2 동시성 버그 처리

**B 선택 근거:** Flutter `place_add_screen.dart:193,224`는 for 루프 안에서 uploadPlaceImage를 await해 순차 업로드한다. `map_detail_screen.dart:411`은 `_deletingPlaceImage` 플래그로 중복 삭제를 막고, 추가/수정 화면도 `_submitting`으로 작업 중 UI를 제한한다. 하지만 이 상태는 한 화면/클라이언트에만 유효하다. 여러 기기·화면·직접 HTTP 요청은 겹칠 수 있으므로 **서버는 클라이언트 동작에 의존하지 않는다**. 앱을 수정하지 않고 서버에서 정원과 배열 변경을 원자화한다.

| 선택지 | 장점 | 단점/결과 |
| --- | --- | --- |
| A. v2 재현 유지 | 순차·경쟁 모두 v2 비교 일치. 이관으로 인한 알려진 차이 없음 | 3장 초과·깨진 URL·고아 파일을 그대로 서비스에 남김. 6-2/6-3 수정안은 적용하지 않음 |
| B. 이관 시 수정 (권장) | 장수 제한과 DB/파일 대응을 정상 I/O 경쟁에서 보장 | 경쟁 시 v2와 HTTP·DB·파일 결과가 달라짐. 초과 업로드는 일부 400. 아래 3개를 개선된 알려진 차이로 명시해야 함 |

B 선택 시 `images.concurrent-limit`, `images.concurrent-delete`, `images.concurrent-upload-delete`를 보고서에 등록한다. v2 재현 테스트는 유지하고 v3의 올바른 결과를 별도 필수 검사한다. known difference라고 skip하지 않는다. 정상 순차·권한·입력·의도된 실패 경계는 계속 엄격 비교한다.

### 6-2 수정 방식 (6-1 B일 때)

| 선택지 | 방법 | 장점 | 단점 |
| --- | --- | --- | --- |
| A. 비관적 락 | TransactionTemplate에서 SELECT FOR UPDATE 후 최신 배열 검사·수정, 커밋 후 파일 삭제 | 흐름이 명확하고 여러 필드 조건도 한 번에 검증. 스키마 변경 없음 | 행 잠금 보유 구간 관리 필요. 같은 장소 작업이 직렬 대기하며 변환을 안에 두면 길어짐 |
| B. 조건부 원자적 UPDATE (권장) | 업로드 cardinality<3와 array_append를 한 UPDATE, 삭제 array_remove를 한 UPDATE | 기존 native JPA 패턴, 스키마 변경 없음, 오래된 배열을 덮어쓰지 않음 | 영향 행 0건의 이유(없는 장소/작성자/정원/없는 URL)를 구분해야 함. native 쓰기 Clock·영속성 컨텍스트 관리 필요. UPDATE의 행 잠금 대기는 여전히 있음 |
| C. 낙관적 락 | version 컬럼과 @Version 또는 WHERE version 조건, 충돌 시 최신 상태 재검사/재시도 | 긴 명시적 잠금 없이 충돌 감지, 다른 변경까지 버전 관리 가능 | **스키마 변경 승인과 V2 이후 migration 필요**. 충돌 재시도/파일 보상 복잡. v2와 기존 native 쓰기가 버전을 갱신하지 않으면 누락되므로 모든 작성 경로를 정비해야 함 |

B의 SQL 모양(설계 예시, 아직 구현하지 않음):

```sql
UPDATE places
SET "imageUrls" = array_append("imageUrls", :newUrl), "updatedAt" = :now
WHERE id=:id AND "createdById"=:userId AND cardinality("imageUrls") < 3
RETURNING *;

UPDATE places
SET "imageUrls" = array_remove("imageUrls", :url), "updatedAt" = :now
WHERE id=:id AND "createdById"=:userId AND :url = ANY("imageUrls")
RETURNING *;
```

입력/존재/작성자 사전 검사를 유지하되 갱신 시 조건을 다시 검사한다. DELETE 대상 URL이 없으면 updatedAt을 바꾸지 않고 200으로 읽어 반환한다. 삭제한 URL 파일은 성공적인 DB 커밋 뒤에만 정리한다. array_remove는 중복 URL도 모두 제거한다. PostgreSQL Read Committed의 UPDATE는 경쟁 트랜잭션 종료 후 갱신된 행에 WHERE 조건을 다시 검사한다. [PostgreSQL 17 격리 수준](https://www.postgresql.org/docs/17/transaction-iso.html), [배열 함수](https://www.postgresql.org/docs/17/functions-array.html).

어느 안도 v3만 바꿔 놓고 **동일 DB에 v2 이미지 쓰기를 계속 섞으면 전체 보장을 할 수 없다**. v2의 미리 만든 set이 v3 커밋 뒤 덮어쓸 수 있다. 개발 Compose의 두 앱은 DB를 공유하므로 수정안 검증은 기존 격리 DB 하네스로 하고, 실제 전환에서는 해당 이미지 쓰기의 담당 서버를 하나로 제한해야 한다. v2 소스 수정으로 해결하지 않는다. A/B도 잠금/조건을 따르지 않는 다른 place.imageUrls 쓰기(이후 이미지/장소 삭제 등)를 함께 점검한다.

### 6-3 이미지 변환과 락의 순서 (6-1 B일 때)

| 선택지 | 장점 | 단점 |
| --- | --- | --- |
| A. 락 안에서 검사→변환→추가 | 정원이 가득 찬 대기 요청은 변환하지 않음. 코드 순서를 설명하기 쉬움 | libvips/파일 I/O 동안 DB 연결·행 잠금을 보유. 같은 장소 지연과 장애 영향이 커질 수 있음. 사실상 6-2 A와 조합하며 B에 추가 SELECT 락을 넣으면 B 단독 비교가 아님 |
| B. 락 밖 변환→짧은 락/원자적 UPDATE에서 재검사·추가 (권장) | 변환 동안 DB 행 잠금을 잡지 않음. A/B 모두 적용 가능 | 동시에 만든 파일 중 최종 정원 검사에서 거절된 파일은 보상 삭제 필요. 변환 CPU/임시 파일 작업 일부가 낭비될 수 있음 |

B에서도 v2의 오류 우선순서를 위해 파일 유무·장소·작성자·관측 시점 정원은 변환 전에 확인한다. 변환 후 최신 상태 재검사에서 실패하거나 DB 커밋이 실패하면 **이 요청이 만든 완성 파일만** 삭제한다. 이미 공유 중인 기존 파일은 삭제하지 않는다. 변환 자체 실패/HEIC 잔여는 4절의 기존 정책과 구분한다. 프로세스 강제 종료까지 다루려면 별도 복구/청소 설계가 필요하며 이번 선택의 보장으로 주장하지 않는다.

### 6-4 측정 방법

| 선택지 | 장점 | 단점 |
| --- | --- | --- |
| A. 정합성 재현과 성능 실험을 분리하고 비관적/조건부 UPDATE를 교대 비교 (권장) | 같은 요청·자원에서 정합성과 대기 비용을 따로 판단. 행 잠금 안/밖 변환 비용도 비교 가능 | 선택한 운영 구현 외 테스트 전용 대안 구현·계측이 필요. 설계 승인 후 진행할 실험이지 이번 관측에서 완료한 결과가 아님 |
| B. 채택한 방식만 정합성 회귀+v2 대비 응답 시간 측정 | 구현/측정 범위가 작고 회귀를 빨리 고정 | Java/libvips와 Node/sharp 런타임 차이가 섞여 락 방식 자체의 우열을 결론낼 수 없음. 다른 방식 비교는 후속 실험 |

6-2 C도 비교하려면 별도 실험 DB와 스키마 승인 후 A 계획에 추가한다. 기본 계획에 version 컬럼을 미리 만들지 않는다. 6-1 A라면 버그 재현을 유지하고 위 수정안 비교 실험은 보류한다.

## 7. 승인 후 비교 실험 계획

### 정합성: 성능과 다른 테스트

- 기존 v2 gate 방식과 별도로, 비관적 락 구현이 처음 SELECT에서 대기하는 경우를 허용하는 시작 barrier/관측 지점을 사용한다. 현재처럼 모든 요청이 UPDATE까지 도달해야 한다고 강제하면 비관적 락에서는 테스트가 잘못 타임아웃된다.
- 같은 Place의 초기 0/1/2/3장, 동시 N=2/4/8/16, 두 URL 삭제, 같은 URL 중복 삭제, 업로드/삭제의 두 순서를 비교한다. 정상 I/O 기준 초기 2장에서 업로드만 N개면 최종 3장·201 1개/정원 400 N−1개, 깨진 URL 0·고아 파일 0을 요구한다.
- 삭제 두 건은 DB []·실제 파일 없음. 혼합은 [B,U]·파일 B/U, 성공한 새 URL 유실 0을 요구한다. 요청 완료와 보상 삭제 완료 이후 검사한다. 파일 URL 별칭은 요청 응답·DB·파일에 공통 적용하며 다른 이미지 바이트/해시도 사용한다.
- 사전 권한 거절, DB UPDATE/커밋 오류, 저장 장애, 삭제 실패, 변환 실패, 기존 3장+손상 파일을 별도 검증한다. 의도된 HEIC 잔여 등은 정합성 성공 시나리오와 합쳐 평균내지 않는다.
- 각 API의 인증 포함 SQL 수를 고정한다. 알려진 차이 3개는 보고서에 v2/v3 상태·URL·파일 수를 나란히 표시한다.

### 2단계 실험 백로그 — 성능: 강제 DB gate 없이 실제 동시 HTTP

아래 전체는 보존만 한다. 이번 6번 묶음에서 대안 락 방식·성능 계측·벤치마크를 구현하거나 실행하지 않는다. 현재 수행 범위는 위 정합성 시나리오 전체와 SQL 수 고정이다.

- 기존 개발 기준 앱 CPU 2/메모리 1.5GB, PostgreSQL CPU 2/메모리 4GB/shared_buffers 1GB로 고정한다. 라이브러리/Java/Node/DB 버전, 풀 크기, CPU 부하, 이미지 캐시·파일 시스템/볼륨 조건을 함께 기록한다. 현재 관측 테스트에는 이 자원 제한을 적용하지 않았다.
- 같은 v3 런타임·libvips·입력·응답·DB 시드를 사용해 방식만 바꾼다. v2는 호환 기준이지 Java 락 방식의 성능 대조군으로 섞지 않는다.
- 초기 2장 업로드 경쟁(거절 포함)과 초기 0장/서로 다른 장소 요청(성공 위주)을 분리한다. 이미지 종류/크기(작은 PNG, 2400×1600 JPEG/PNG, 큰 유효 입력)와 동시성 1/2/4/8/16을 동일하게 적용한다.
- 준비/시드/컨테이너 시작/워밍업/요청 제한 리셋은 측정 구간 밖에 둔다. 묶음 전에 handler별 요청 슬롯을 예약한다. 예상하지 않은 429·timeout·5xx는 결과 실패로 표시하며 빠른 성공에 섞지 않는다. rate limit 동작 자체는 별도 필수 429 테스트로 검증한다.
- 워밍업 뒤 최소 5회 교대로 실행(A→B, B→A 순서 교대). 단일 배치 p95를 과신하지 않고 각 조건의 표본 수·성공/정원 거절/오류별 p50/p95/max, 처리량, 반복별 median/range를 기록한다. 시드 준비를 포함한 전체 실험 시간은 별도 기재한다.
- 앱에서 단조 시계로 변환/파일 쓰기, 락 획득 호출 또는 UPDATE 시작→반환, 트랜잭션 구간, 보상/삭제 시간을 기록한다. UPDATE 시간은 실행+대기 합계이므로 lock wait라고 부르지 않는다. 별도 진단 실행에서 pg_stat_activity wait_event/pg_locks를 짧은 주기로 관측해 대기 구간을 추정하고 표본 간격 오차를 명시한다. 상세 로그/프로파일링 비용이 있는 진단 실행은 성능 대표값에서 제외한다.
- 잠금 보유 시간과 대기 시간, CPU/변환·풀 대기를 함께 봐야 한다. CPU/변환이 병목이면 짧은 DB 구간만으로 전체 개선을 주장하지 않는다. 방식 간 차이가 반복 변동보다 작으면 우열 미확정으로 보고한다.

## 8. 관측 파일과 검증

- `PlaceImageObservationSupport`: 기존 multipart 하네스 재사용, 격리된 시드·파일 해시·전체 DB 참조 기반 고아 판정·URL 별칭.
- `PlaceImageObservationTest`: 입력 28, 권한/순서 20, 삭제 14, 실패 9 = **71개 응답/상태 기록**. 재삭제 준비 요청 1회를 더해 HTTP 72회.
- `PlaceImageRaceObservationTest`: 6조건×3회 = 18번 경쟁, HTTP 60회와 HEAD 6회. 경합을 만드는 DB gate는 테스트에만 있다.
- 기존 ImageUploadRequests.Part의 생성자를 하위 패키지 테스트에서 쓸 수 있도록 공개했다. 운영 multipart 처리는 변경하지 않았다.
- `src/test/resources/place-images/`의 입력/경쟁 fixture는 기본 테스트에서 대조만 한다. `-PplaceImagesObserve=true`일 때 새 기대값을 **build**에 생성하며 원본 fixture를 자동 덮어쓰지 않는다. 시간 원본은 매번 build/reports/place-images/timing-*.json에 저장하고 시간 값 자체로 성공/실패를 판정하지 않는다.

```powershell
# apps/server-v3에서, 기대값과 다시 비교
./gradlew.bat spotlessCheck comparisonTest --tests '*PlaceImage*ObservationTest'
# 의도적으로 v2 기준을 다시 조사할 때만
./gradlew.bat comparisonTest --tests '*PlaceImage*ObservationTest' -PplaceImagesObserve=true
```

이 절은 구현 전 관측 단계의 기록이다. 당시 성공은 **v2 관측 재현 성공**이며 v3 호환성 성공을 의미하지 않는다. 당시 전체 733건 회귀는 실행하지 않았고, 이후 구현 검증 결과는 10절에서 별도로 기록한다.

2026-11-06 검증 결과(v2 이미지 캐시):

| 실행 | 전체 | 성공 | 실패/skip | wall time |
| --- | ---: | ---: | --- | ---: |
| 새 장소 이미지 관측 4 + 경쟁 조건 6, Spotless | 10 | 10 | 0 / 0 | 48.607초 |
| 기존 ProfileImageObservationTest + MapCoverObservationTest | 2 | 2 | 0 / 0 | 26.253초 |

두 실행 모두 v2 빌드 호출 1·기동 1·재시작 0·시나리오 대기 0ms다. 첫 실행은 업무 HTTP 132회(순차 72+경쟁 60)와 정적 HEAD 6회를 포함한다. 두 번째 실행은 공유 multipart 도우미의 기존 입력 54종을 확인한다. 알려진 차이 보고는 각 실행 0이지만 v2-only 관측이므로 아직 v3 차이를 판정한 것이 아니다. 기존 프로젝트의 알려진 차이 5건을 해제한 것도 아니다.

초기 관측 실행은 재사용한 protected nested record의 생성자 접근 오류로 컴파일에 실패했다(2.535초). 테스트 전용 Part 생성자만 보완한 뒤 관측 생성과 위 기대값 대조를 모두 다시 실행했다. 운영 동작을 고치는 변경은 없다.

원본 로그는 `build/place-images-check.log`, `build/place-images-shared-check.log`다. 관측 fixture/시간 스냅샷/설계 및 테스트 도우미만 커밋하고 push한다.

## 9. 확정한 구현 순서

1. 6-1~6-4를 번호·항목 이름과 함께 확정한다. 6-2 C라면 스키마 변경을 먼저 별도 승인한다.
2. 장소용 inside 변환·POST를 구현하고 입력/권한/DB·파일 실패를 비교한다.
3. DELETE를 구현하고 no-op·정리 순서·실패를 비교한다.
4. 선택한 동시성 처리·차이 3개 보고·7절 정합성 전체·SQL 수·429를 고정한다. 성능 측정 구현은 하지 않는다.
5. 전체 회귀·build 후 단계별 커밋/시간을 남기고 6번 묶음에서 멈춘다.

사용자는 **6-1 B / 6-2 B / 6-3 B / 6-4 B(범위 조정)**를 선택했다. v2 재현을 유지하고 v3 정합성은 별도 필수 검사한다. 7절 성능 계획 전체는 2단계 실험 백로그로 보존하며 이번에 비관적 락 대안·성능 계측·벤치마크를 구현하지 않는다. 성능 우위도 주장하지 않는다.

## 10. 구현 검증

POST 단계: 입력/권한/극단 비율 42건과 저장·UPDATE·커밋 실패 3건, 기존 프로필/커버 회귀 89건을 함께 실행했다. 전체 134건 모두 성공, 실패/skip/알려진 차이 0, 127.531초(v2 이미지 캐시), 재시작/대기 0이다. 인증 포함 정상 POST SQL 4회, 서비스의 장소/권한/정원/디코딩 거절 2회, multipart/파일 누락 거절 1회다. 변환은 TransactionTemplate 밖이고 cardinality 조건과 array_append는 한 UPDATE다. 영속 엔티티를 읽지 않는 native 경로라 오래된 엔티티 flush로 배열을 덮어쓰지 않는다.

PlaceImageController는 HTTP/인증 경로, PlaceImageService는 사전/최종 검사와 새 파일 보상, PlaceImageRepository는 원자적 배열 DML을 맡는다. PlaceImageTestSupport는 두 DB/파일 시드와 변동 URL 대응을 공유한다. 장소 inside 축소에는 최소 1픽셀을 유지해 1×3000/3000×1 입력도 v2와 맞춘다. 선행 문서 커밋의 EOF 빈 줄 경고를 정리했고 현재 git diff --check는 통과했다.

DELETE 단계: 관측 입력 14·권한 8·DB/파일 실패 6건, 총 28건 성공·실패/skip/알려진 차이 0, 39.608초(v2 이미지 캐시), 재시작/대기 0이다. 이미지 URL 미존재는 UPDATE 없이 200, updatedAt을 보존한다. 성공 삭제는 array_remove 후 TransactionTemplate 커밋이 끝나야 파일을 삭제한다. 인증 포함 정상 SQL 4회, 미존재/비문자 no-op 3회, 입력 400은 1회, 권한/장소 거절은 2회다. 같은 URL 경쟁에서 UPDATE 0건이면 재조회하여 삭제·권한 상태를 확인한다.

정합성 단계: v3 단독 22건(초기 0~3장 × 동시 2/4/8/16, 삭제/혼합 4조건, 최종 장소 삭제·작성자 변경 2조건)과 비교 12건(개선 차이 3, 인증 2, 429 1, 기존 v2 경쟁 재현 6), 총 34건 모두 성공했다. 실패/skip 0, 알려진 차이 3건, Gradle 실행 시간 62초(v2 이미지 캐시), v2 재시작 1회·대기 0ms다. 이 단계의 시간은 Gradle 출력 기준이며 전체 회귀에서는 별도 wall clock을 기록한다.

ImageRequestRaces는 테스트 전용 시작 barrier와 DB gate로 실제 HTTP 경합을 재현한다. 성능 계측은 추가하지 않는다. v3 정상 I/O 정합성 행렬에서 깨진 URL·고아 파일·성공 업로드 URL 유실은 모두 0이다. 서로 다른 삭제/혼합 두 요청의 인증 포함 SQL 합계는 8, 같은 URL 중복 삭제는 실패한 UPDATE의 재조회 때문에 9다. 초기 3장 거절은 요청당 2, 최종 정원/장소/작성자 거절은 4로 고정했다. N=16은 기본 연결 풀을 유지하며 동시에 출발하고, 최대 4개 UPDATE의 실제 경합을 확인한 뒤 gate를 해제한다.

개선 차이 보고는 v2/v3를 모두 실행하고 결과를 검사한 뒤 등록한다. 초기 2장에서 업로드 2건은 v2 4장/201 두 건 → v3 3장/201·400, 다른 URL 삭제 2건은 v2 깨진 URL 1 → v3 0, 업로드 후 삭제 경합은 v2 고아 파일·업로드 URL 유실 각 1 → v3 각 0이다. v2 재현 6조건도 그대로 유지한다.

최종 전체 검증(2026-11-06): 빠른 테스트 229건 중 성공 228·실패 0·기존 HEIC 공통 제약 skip 1, v2 비교 615건 전부 성공. 합계 844건 중 성공 843·실패 0·skip 1, 알려진 차이 8건(기존 5+이미지 개선 3)이다. 캐시된 v2 이미지 기준 전체 wall time **492.740초**, v2 빌드 호출/기동 각 1·재시작 11·시나리오 대기 0ms다. Spotless/build와 git diff --check를 통과했다. 이전 전체 733개 테스트 식별자의 누락은 0이고 111개가 추가됐다. 이 수치는 테스트 실행 시간이며 조건부 UPDATE와 비관적 락의 성능 비교가 아니다.

v2·Flutter·infra·V1 변경 없음, 실행 JAR에 PlaceImageController/Service/Repository 포함 및 테스트 대역 미포함을 확인했다. 원본 결과·로그·테스트 목록·알려진 차이는 build/performance/place-images-final/에 보존한다. 동시 요청 때문에 기존 원격 이미지 시간 합산에는 중복 구간이 있으므로 구간별 categoriesSeconds를 정확한 wall time 분해로 해석하지 않는다. 원격 구간 합집합 계측과 대안 락 비교는 2단계 실험 백로그다.

재확인 명령(apps/server-v3에서 Java 21·Docker 준비):

```powershell
# 이번 묶음 정합성만 (v2 서버 없이)
./gradlew.bat test --tests '*PlaceImageConcurrencyTest' --rerun
# 장소 이미지 전체 비교·원래 v2 관측 재현
./gradlew.bat comparisonTest --tests '*PlaceImage*' --rerun
# 전체 회귀·build 및 wall time 기록
./scripts/measure-tests.ps1 -Label place-images-recheck -RestartMode quota
```

이번 묶음은 여기서 완료한다. 마커 조회 등 다음 묶음과 2단계 성능 실험은 시작하지 않는다.
