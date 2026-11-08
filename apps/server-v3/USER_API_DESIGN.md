# 2번 묶음: 사용자 API 설계와 호환 기준

> 저장소 계획 갱신: 아래 MinIO 선택지는 이관 당시의 검토 이력이다. CMS C-20 C에서 현재 로컬 객체 저장소는 SeaweedFS(S3 호환)로 변경했다([CMS 설계19절](CMS_CORE_DESIGN.md)). LOCO /uploads는 기존 로컬 방식을 유지하며, CMS 변경이 LOCO 파일 이전을 뜻하지 않는다.

대상은 GET/PUT/DELETE `/api/users/me`, POST `/api/users/me/profile-image`, PUT `/api/users/me/fcm-token`이다.
v2 기준은 원격 main `3d7ae28`이다. 현재 feature 브랜치의 users·upload 소스가 이 기준과 같음을 확인했다.
Flutter·v2 코드는 변경하지 않는다. 선택 결과는 아래 7절에 정리했다. GET/PUT me, FCM, 탈퇴, 이미지 업로드를 구현했으며 [B 변환기 검증](CODEC_COMPATIBILITY.md)을 적용했다.

## 1. 이미지 저장과 Flutter 호환

v2는 `uploads/profiles/<UUID>.webp`에 저장하고 DB·응답에는 `/uploads/profiles/<UUID>.webp`를 넣는다.
정적 GET 주소는 `/uploads/...`이며 `/api/uploads/...`가 아니다. 외부 OAuth 프로필 URL은 절대 URL일 수도 있다.

Flutter 확인 결과:

- `core/constants/api_constants.dart`: `imageUrl(path)`는 `SERVER_URL + path`다. API 요청용 `API_BASE_URL`과 독립적이다.
- `profile_edit_screen.dart`, `my_page_screen.dart`, 지도 리뷰 작성자 표시: `/uploads/`로 시작하면 SERVER_URL을 붙이고, 그 외는 원래 URL을 사용한다.
- `map_detail_screen.dart`의 지도방 멤버 목록: 프로필 URL에 항상 SERVER_URL을 붙인다. 절대 MinIO URL을 반환하면 이 화면에서 주소가 잘못된다. 기존 OAuth 절대 URL에도 같은 제약이 있지만 이번 작업에서 Flutter는 수정하지 않는다.
- 업로드는 multipart의 `file` 필드다. 프로필 편집 화면은 닉네임 PUT 후 이미지 POST를 별도로 보낸다. 두 요청 전체를 한 트랜잭션으로 만들지 않는다.

| 선택 | 앱 호환성 | 비교 테스트 | 다중 서버·운영 영향 |
| --- | --- | --- | --- |
| 1A. 로컬 디스크 유지, MinIO는 이관 후 — 권장 | 상대 URL·정적 주소 유지. 앱 수정 불필요 | 양쪽 독립 디렉터리의 파일 목록·이미지·HTTP 제공 결과 비교 | 같은 호스트에서는 공유 볼륨 필요. 여러 호스트에서는 공유 스토리지나 별도 이미지 서버 없이는 같은 URL을 제공할 수 없음 |
| 1B. 지금 MinIO | DB·응답은 상대 URL 유지, `/uploads/...`를 객체 읽기로 연결해야 함. MinIO 절대 URL 직접 반환은 제외 | v2 디스크와 v3 객체 목록을 동일한 논리 파일 목록으로 비교. MinIO 장애·업로드·삭제 실패도 추가 | 여러 앱 인스턴스가 같은 저장소 사용 가능. SDK·서비스·접속 비밀 값·기존 파일 이관·호환 제공 경로가 추가됨 |

1B에서는 기존 로컬 파일과 신규 객체를 함께 읽는 전환 절차가 필요하다. 특히 v2가 계속 로컬에 쓰므로 초기 일괄 복사만으로 공존이 해결되지 않는다. 쓰기 주체 전환 또는 동기화 설계도 선행해야 한다.
자격 증명은 `.env`로만 받으며 `.env.example`에는 키만 남긴다. MinIO Java SDK의 역할은 [공식 저장소](https://github.com/minio/minio-java)를 참고한다. 아직 버전·의존성을 선택하지 않았다.

1A의 개발 Compose 제안: 기존 `v2-uploads` 볼륨을 두 앱에 연결한다. 같은 DB를 쓰면서 파일 볼륨을 분리하면 상대 URL은 같아도 다른 앱에서 파일을 못 읽는다.
v2 node와 v3를 UID/GID 1000:1000으로 통일했다. 새 업로드 디렉터리도 같은 소유권으로 준비하며 기존 볼륨 데이터는 지우지 않는다.
비교 테스트에서는 반대로 DB·파일 저장소를 양쪽 모두 격리해 한쪽의 쓰기가 다른 쪽 결과를 오염시키지 않게 한다.

## 2. API와 데이터 처리

기존 `com.loco.users` 패키지와 User 엔티티·저장소를 확장하는 안이다. 새 테이블·마이그레이션은 필요 없다.
엔티티를 직접 반환하지 않고 API별 명시적인 응답을 만든다. 이미 결정한 TEXT UUID, Clock 기반 JPA Auditing을 유지한다.
5개 API는 모두 기존 `@AuthenticatedApi` 방식으로 인증한다. 인증이 필요한 경로에서만 현재 회원 상태를 조회한다.
정적 파일 요청에는 회원 조회를 추가하지 않는다. 세션·CSRF·요청 제한은 기존 설정을 따른다.

| API | v2 성공 응답 | 처리와 주의점 |
| --- | --- | --- |
| GET me | 200: id, email, nickname, profileImageUrl, provider, createdAt, updatedAt | password·fcmToken·deletedAt은 노출하지 않음 |
| PUT me | 200: id, email, nickname, profileImageUrl, updatedAt | provider·createdAt을 임의로 추가하지 않음. 필드 생략과 명시적 null 구분 |
| DELETE me | 200: `{"message":"회원 탈퇴가 완료되었습니다."}` | 아래의 DB 변경을 하나의 트랜잭션으로 처리. 이미지 파일은 삭제하지 않음 |
| POST profile-image | 201: id, email, nickname, profileImageUrl, provider, updatedAt | createdAt 없음. 파일·DB 처리 순서는 다음 절 참고 |
| PUT fcm-token | 200: `{"message":"FCM 토큰이 업데이트되었습니다."}` | 새 토큰 저장, null 또는 누락이면 해제 |

PUT me의 nickname은 선택 문자열·최대 30자이고 최소 길이 제한은 없다. 회원가입의 최소 길이 검증을 재사용하면 안 된다.
profileImageUrl은 선택 URL이며 null로 해제할 수 있다. 업로드 API가 상대 URL을 반환하더라도 이 DTO의 IsUrl 검증과는 별개다.
정의되지 않은 필드는 ValidationPipe의 400 배열을 맞춘다. nickname:null은 IsOptional을 통과해 DB 제약에서 실패할 수 있으므로 일괄 400으로 바꾸지 않고 v2 HTTP로 먼저 고정한다.
빈 객체·동일 값 수정의 updatedAt 변화도 비교한다. Hibernate가 변경 없다고 UPDATE를 생략하는 동작과 Prisma의 갱신 동작을 구분해야 한다.

FCM API는 DTO가 아니라 `@Body('fcmToken')`을 읽는다. PUT me와 같은 미정의 필드 거부 정책을 자동 적용하지 않는다.
빈 문자열·누락·null·숫자·객체·미정의 필드가 섞인 입력을 v2에서 관측한 뒤 그대로 맞춘다. 새 검증 정책은 이번 이관에 끼워 넣지 않는다.

## 3. 회원 탈퇴와 롤백

Spring 서비스 트랜잭션 하나에서 Clock으로 얻은 같은 시각을 사용한다.

1. users.deletedAt 설정, fcmToken=null, updatedAt 갱신.
2. 해당 회원의 revokedAt IS NULL인 refresh_tokens를 폐기. 이미 폐기된 값은 유지.
3. 해당 회원의 usedAt IS NULL인 password_reset_tokens를 사용 처리. 이미 사용된 값은 유지.
4. 커밋 성공 후 응답. 사용자의 프로필·지도·장소·이미지 파일은 그대로 둔다. 물리 삭제나 소유권 이전을 추가하지 않는다.

만료 여부를 추가 필터로 넣지 않는다. v2는 만료됐더라도 미폐기·미사용 토큰을 위 조건으로 처리한다.
이미 탈퇴한 회원의 재요청이나 없는 회원 토큰은 기존 Guard와 같이 401이다. 서비스의 findMe 404와 혼동하지 않는다.

롤백은 mock의 호출 여부가 아니라 실제 PostgreSQL에서 검증한다. 양쪽 DB에 회원·FCM 토큰·미폐기/기폐기·미사용/기사용 토큰과 이미지 시드를 넣는다.
refresh_tokens 또는 password_reset_tokens의 UPDATE에 테스트 전용 실패 트리거를 설치해 2번·3번 단계에서 각각 실패시킨다.
HTTP 500 기본 응답과 함께 users·두 토큰 테이블이 요청 전 값으로 복구됐는지 확인한다. 시간 제외 비교에만 의존하지 않고 deletedAt·updatedAt·revokedAt·usedAt도 원래 값과 정확히 비교한다.
기존 JWT·리프레시 토큰이 여전히 사용 가능한지도 확인하며, 파일은 변경 없어야 한다. 트리거는 finally에서 제거한다.
현재 비밀번호 재설정 비교 테스트의 실패 트리거 패턴을 재사용할 수 있다. 실제 트랜잭션 검증을 HTTP 비교 그룹에 둔다.

## 4. 이미지 검증과 변환

v2 소스에서 확인한 기준:

- 파일 필드명 `file`, 최대 파일 크기 10 * 1024 * 1024 바이트. 전체 multipart 크기와 파일 크기는 구분한다.
- MIME 허용: image/jpeg, image/png, image/webp, image/heic, image/heif, image/avif.
- MIME 검사 후 실제 바이트를 sharp로 읽는다. 검출 형식 허용: jpeg, png, webp, heif, avif. MIME과 검출 형식이 모두 허용되는 경우 둘이 다르다는 이유만으로 거부하는 규칙은 없다.
- 누락: `이미지 파일을 첨부해주세요.` / 빈 파일: `빈 파일입니다.` / 메타데이터 읽기 실패: `지원하지 않는 이미지 파일입니다.` / 금지 형식: `지원하지 않는 이미지 형식입니다.`의 400.
- `resize(512,512,{fit:'cover',withoutEnlargement:true})`, WebP quality 80. 작은 이미지를 무조건 512×512로 확대하지 않는다.
- `rotate()`를 호출하지 않는다. 임의 EXIF 자동 회전, 애니메이션 지원 확대, 신규 크기 제한을 추가하지 않는다.
- 메타데이터 검증 이후 변환·쓰기 오류는 일반 예외로 전파된다. 손상 파일의 모든 실패를 400으로 통일하면 v2와 달라질 수 있다.

현재 실행 이미지에서 sharp 0.33.5, libvips 8.15.3, libwebp 1.4.0과 HEIF 입력의 .avif 표기를 확인했다. HEIC는 MIME 허용만으로 디코딩 성공을 보장할 수 없다.
JPEG·PNG·WebP·AVIF·HEIC/HEIF 샘플과 손상·EXIF·투명·애니메이션·극단적 종횡비·작은 이미지로 v2 기준 결과를 고정했다. B 변환기는 JPEG·PNG·일반/애니메이션 WebP·AVIF 필수 통과이며 HEIC 원시 코덱 가용성과 HTTP 호환 정책은 별도로 기록한다.
512보다 작을 수 있는 출력 의미는 [sharp resize 문서](https://sharp.pixelplumbing.com/api-resize/)와도 일치한다.

| 선택 | 장점 | 제약과 확인할 내용 |
| --- | --- | --- |
| 2A. Java ImageIO + WebP 플러그인, 필요한 입력 디코더 — 우선 검증안 | Java에서 변환·검증 흐름을 구현, 별도 변환 프로세스 없음 | `org.sejda.imageio:webp-imageio`는 WebP 읽기·쓰기 후보일 뿐 HEIF/AVIF 해결책은 아님. 입력 디코더와 Windows Java 21·Linux 컨테이너의 native 로딩을 모두 검증해야 함. 품질 80만 같다고 sharp와 픽셀이 같아지지 않음 |
| 2B. Java에서 libvips 실행 파일 호출 | sharp와 같은 엔진을 활용할 수 있어 입력 형식·리사이즈 호환을 맞추기 유리 | Java 라이브러리만으로 끝나지 않음. OS별 바이너리·코덱·프로세스 제한·타임아웃·임시 파일 관리 필요. 버전·옵션을 맞춰도 결과 동일성 검증은 필요 |

A로 결정했으나 의사소통 오류로 B로 구현, 결과 검토 후 유지하기로 했다. 다른 주제(CMS 구조)의 “B” 답변을 변환기 선택으로 잘못 연결한 것이며, ImageIO의 애니메이션 WebP 실패 관측과 전환 승인을 구분한다. 현재 구현은 2B(libvips CLI 8.15.3)이며 ImageConverter 경계는 유지한다. JPEG·PNG·일반/애니메이션 WebP·AVIF는 양 플랫폼 필수 통과다. HEIC API는 v2의 500·기존 DB/파일 보존·새 0바이트 파일 잔여를 유지한다. AVIF는 알려진 차이에서 해제했다.
[WebP ImageIO 공식 저장소](https://github.com/sejda-pdf/webp-imageio)는 native 라이브러리를 포함하는 reader/writer다. [NightMonkeys 현행 문서](https://github.com/gotson/NightMonkeys)는 Java 22 이상을 요구하므로 Java 21 해결책으로 채택하지 않는다.

Spring의 기본 multipart 제한을 그대로 사용하지 않는다. 파일당 10MiB를 맞추고, 전체 요청 제한으로 multipart 헤더가 붙은 정상 파일을 조기에 거절하지 않게 한다.
v2는 Guard 후 FileInterceptor가 동작한다. Spring은 multipart를 더 일찍 파싱할 수 있으므로 지연 파싱 등으로 순서를 맞추고, 미인증+초과 크기/잘못된 파일 조합에서도 v2 우선순위를 검증한다.
추가 전체 요청·픽셀 제한을 도입할 경우에는 v2의 실제 한도와 먼저 비교하고 차이를 승인받는다.

## 5. 파일 저장과 DB 반영 순서

1. 인증·회원 확인 → 파일 입력 검증 → 새 UUID 파일 변환·저장.
2. DB의 profileImageUrl 변경을 커밋하고 응답용 필드를 읽음.
3. 커밋 실패 시 새 파일 삭제를 시도하고 원래 DB 오류를 반환. 기존 파일·기존 DB URL은 유지.
4. 커밋 성공 시 기존 URL이 `/uploads/profiles/`일 때만 기존 파일 삭제. 외부 URL은 건드리지 않음.
5. v2 deleteFile은 파일 없음·삭제 실패를 삼킨다. 따라서 기존 파일 삭제 실패로 성공한 DB 변경을 롤백하거나 API를 실패시키지 않는다.

이미지 처리 전체에 단순히 @Transactional을 붙여 메서드 안에서 catch하면, 반환 뒤 발생하는 커밋 실패를 잡지 못할 수 있다.
DB 변경만 TransactionTemplate으로 감싸 반환 시점에 커밋 완료를 보장하는 안(4A), 또는 별도 @Transactional 빈 호출이 반환된 뒤 파일 정리를 하는 안(4B)을 선택한다. 같은 빈 내부 호출은 트랜잭션 경계로 사용하지 않는다.

v2는 변환·쓰기 자체의 실패에 대한 공통 보상 삭제가 없고, 삭제 실패도 무시한다. 부분 파일·고아 파일이 반드시 0이라는 보장은 없다. 실제 실패 위치별 결과를 비교하고 별도 정리 작업은 승인 없이 추가하지 않는다.
파일과 DB는 단일 원자적 트랜잭션이 아니므로 프로세스 강제 종료까지 이 설계로 복구되는 것은 아니다.

정적 파일은 인증 없이 `/uploads/...`에서 제공한다. 파일 경로는 저장 루트 안으로 제한하고 사용자 파일명을 저장 경로로 쓰지 않는다.
`Content-Type: image/webp`, v2의 `Access-Control-Allow-Origin: *`, `Cross-Origin-Resource-Policy: cross-origin`도 확인한다. ETag·Last-Modified 같은 가변 헤더는 비교 정책을 명시한다.

## 6. 비교 하네스와 케이스

`UserApiCases` 등 묶음별 케이스를 기존 빌더·연속 요청 모델로 추가하는 안이다. 실제 API로 옮긴 케이스의 `v3Path=/api/__test/harness/...`를 제거하고 불필요한 가짜 사용자 컨트롤러도 정리한다.
JSON·DB 비교에 multipart 요청, 이미지 GET, 파일 전후 목록 비교를 보탠다. 준비 시 양쪽 DB에 같은 시드를 넣고 격리된 파일 저장소에도 같은 기존 이미지를 배치한다.
새 URL을 통째로 무시하지 않는다. `/uploads/profiles/<UUID>.webp` 패턴, 응답↔DB↔실제 파일의 연결을 검증하고, 서버마다 다른 파일 UUID만 `$file1` 같은 별칭으로 대응시킨다.
시간 필드는 기존 비교 정책을 적용하되 갱신·불변·롤백이 핵심인 경우에는 별도 검증한다.

이미지 비교 선택: 3A는 파일 바이트 동일성을 요구하지 않고 형식·치수·크롭·확대 금지·투명도·HTTP 제공을 검증하며, fixture별 디코딩 픽셀 차이를 명시적인 허용 범위로 비교한다. 허용치는 차이가 나면 무조건 늘리는 방식이 아니라 기준 이미지 측정 후 검토한다.
3B는 파일 바이트까지 일치시킨다. 가장 엄격하지만 libvips/libwebp 버전·리사이즈·메타데이터 옵션까지 맞춰야 하므로 Java 변환기 선택을 크게 제약한다.

| API/상황 | 비교 케이스 | DB·파일 확인 |
| --- | --- | --- |
| 공통 인증 | 미인증, 만료/서명 오류, 탈퇴/없는 회원 → 401. 정상·잘못된 입력과 조합. 기존 120/분 제한의 429도 유지 | 거절 요청은 업무 DB·영구 업로드 파일 불변 |
| GET me | LOCAL/OAuth 회원, 이미지 null/상대/절대 URL, 응답 키·타입·날짜 형식, 비밀 필드 미노출 | DB·파일 불변 |
| PUT me | 닉네임·URL 수정, 필드 생략, 빈 객체, 동일 값, 빈 문자열, 30/31자, Unicode 길이, null, 잘못된 URL·숫자·미정의 필드 | 지정 필드와 updatedAt만 기준대로 변경. URL 수정 자체는 파일을 생성·삭제하지 않음. DB 실패 시 불변 |
| DELETE me | 정상, 토큰 없는 회원, 만료된 미처리 토큰, 이미 처리된 토큰, 재탈퇴 401, 탈퇴 후 기존 access/refresh/reset 거절 | 3개 테이블의 원자적 변경·같은 시각·기처리 값 보존. 다른 회원·지도·장소·이미지 불변 |
| DELETE 롤백 | refresh UPDATE 실패, reset UPDATE 실패를 각각 실제 DB 트리거로 주입 | 전체 원복, updatedAt 포함 원래 값 유지, 기존 인증 사용 가능, 파일 불변 |
| POST profile-image 정상 | 모든 실제 지원 형식, 큰/작은/가로/세로 이미지, 투명·EXIF·애니메이션 fixture, MIME와 실제 형식 불일치, 기존 로컬/외부/null URL 교체 | 새 파일 1개와 DB URL 연결, 기존 로컬만 삭제, 무관 파일 유지, 정적 GET 확인 |
| POST profile-image 입력 오류 | 파일 누락·0바이트·다른 필드명·여러 파일·추가 폼 필드·금지 MIME·위장/손상 이미지·10MiB 경계/초과·깨진 multipart·인증 오류 조합 | v2 상태·문구·배열/문자열 구조를 먼저 관측. 업무 DB 불변과 단계별 파일 잔여물 비교 |
| POST profile-image 실패 | 쓰기 권한 오류, 변환 후 users UPDATE 실패, 커밋 실패, 기존 파일 삭제 실패 | DB 실패 시 새 파일 보상 삭제·기존 파일 유지. 기존 파일 삭제 실패 시 성공한 새 URL 유지. 잔여 파일은 v2 실제 동작 기준 |
| PUT fcm-token | 토큰 교체, null·누락 해제, 빈 문자열, 동일 값, 숫자·객체·추가 필드, DB 오류 | fcmToken·updatedAt만 기준대로 변경, 오류 시 원복, 파일 불변 |
| 연속 요청·공존 | 수정→조회→업로드→이미지 GET→탈퇴→401. Compose에서 v2 업로드를 v3로 읽고 그 반대도 확인 | v2와 v3 저장소 공존 및 탈퇴 후 파일 유지 확인 |

관측 결과는 src/test/resources/users/{profile-v2,fcm-v2,image-v2}.json에 고정했다. 정확히 10MiB부터 413이며 multipart 경계 누락·조기 종료는 관측한 400 문구를 맞춘다. FCM 비문자 값과 set 연산 객체도 실제 결과대로 처리한다.
쓰기 실패는 양쪽 임시 저장소 권한 등으로, DB UPDATE/커밋 실패는 테스트 DB의 일반/지연 실패 트리거로 재현한다. v2 프로덕션 코드는 수정하지 않는다.
DB·파일 초기화/검증은 테스트 전용이며 개발 볼륨을 삭제하거나 초기화하지 않는다.
기본 `test`에는 입력·응답·이미지 변환 단위/v3 단독 검증, `comparisonTest`에는 실제 v2 HTTP·DB·파일 비교를 둔다. `allTests`로 양쪽을 검증한다.

## 7. 확정된 결정

| 번호 | A | B | 선택 |
| --- | --- | --- | --- |
| 1 저장소 | 로컬 유지, MinIO는 이관 후. 공존은 단순하지만 다중 호스트 제한 | 지금 MinIO. 공유 저장에 유리하지만 상대 URL 호환·기존 파일·v2 공존 설계 추가 | A |
| 2 변환기 | Java ImageIO/WebP 플러그인 검증 | Java에서 libvips 실행 파일 호출 | A로 결정했으나 의사소통 오류로 B로 구현, 결과 검토 후 유지. libvips 8.15.3 양 플랫폼 검증 |
| 3 이미지 비교 | 의미·픽셀 허용 범위 비교. 다른 인코더 허용, 허용 기준 검토 필요 | 바이트 완전 일치. 엄격하지만 엔진·버전을 제약 | A |
| 4 이미지 DB 트랜잭션 경계 | TransactionTemplate. 커밋 실패와 보상 삭제를 한 흐름에서 확인 가능, 코드에 트랜잭션 제어가 드러남 | 별도 @Transactional 빈. 선언적 처리, 파일/DB 역할이 나뉘어 추적할 파일 증가 | A |
| 5 로컬 볼륨 권한(1A일 때) | 공통 그룹과 디렉터리 group-write/setgid | 두 앱 UID/GID 통일 | B. 개발 Compose v3를 node와 같은 1000:1000으로 실행. 기존 볼륨 데이터 보존 |

회원 탈퇴의 단일 트랜잭션, v2 오류 호환, Flutter 무수정은 이미 주어진 요구사항이므로 재선택하지 않는다.
구현 순서는 변환기 검증 → GET/PUT me → PUT fcm-token → DELETE me(롤백) → POST profile-image다.
B 변환기와 공유 업로드 볼륨·UID/GID 1000:1000을 적용했다. 다음 묶음은 사용자 확인 후 진행한다.
