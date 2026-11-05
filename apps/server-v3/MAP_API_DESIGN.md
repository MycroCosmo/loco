# 3번 묶음: 지도방 API 14개 설계안

상태: 14개 중 12개 이관. 목록·내 목록·상세, 생성·수정·삭제, 멤버십·가입·코드 가입·나가기·멤버 목록·추방을 구현했다. API-12 N+1 전후 측정 후 중간 확인을 기다린다. 초대 코드 재생성(API-11)과 커버 업로드(API-14)는 미구현이다. 스키마는 변경하지 않았다.
v2 기준은 main `3d7ae28`의 maps·upload·notifications와 Prisma 스키마다. 현재 feature 브랜치의 maps 소스와 Prisma 스키마가 이 기준과 같은 것을 확인했다.
기존 동작은 소스·V1 SQL·Flutter 호출 코드로 확인했으며, 조회 49개와 쓰기 입력 38개는 실제 v2 HTTP로 관측해 fixture로 고정했다. 나머지 런타임 의존 항목도 구현 시 먼저 v2로 관측한다.

## 1. 확정된 경계

- Java 21, Spring Boot 3.5, Spring Data JPA, PostgreSQL 17, 기존 Flyway V1을 유지한다. v2·Flutter 소스는 읽기만 한다.
- UUID는 기존처럼 TEXT에 저장하는 문자열이며 앱에서 생성한다. 기존 `@UuidGenerator`, Clock 연결 JPA Auditing을 재사용한다. Prisma와 동일한 명시적 갱신 시각 처리도 유지한다.
- 모든 지도방 API는 인증 필요다. PUBLIC은 지도방 접근 범위이며 익명 API라는 뜻이 아니다. 기존 보호 경로 회원 상태 확인을 적용한다.
- 기본 `/api`, 오류 JSON, 입력 오류의 message 배열, 500의 error 키 생략, 요청 제한을 기존 기반으로 맞춘다. 지도방에는 별도 @Throttle이 없으므로 기존 전역 설정을 적용한다.
- libvips B 유지, 로컬 `/uploads/...` 상대 URL, 공유 볼륨, UID/GID 1000:1000, 파일/DB 실패 보상은 확정된 기준이다.
- 기존 v2 동작 개선은 이번 이관과 분리한다. 동시성·외래 키·알림의 차이를 발견해도 조용히 개선하거나 비교에서 제외하지 않는다.
- 선택 답변에 번호와 항목 이름이 함께 없으면 먼저 대상을 확인한다. 권장안은 승인으로 취급하지 않는다.

## 2. API 목록과 응답

표의 경로 앞에는 `/api/maps`가 붙는다. 일반 성공 코드는 GET/PUT/DELETE 200, POST 201이다.
`Map 기본 필드`는 id, ownerId, title, description, tags, coverImageUrl, visibility, inviteCode, createdAt, updatedAt이다.
`상세형`은 기본 필드에 owner {id, nickname, profileImageUrl}와 `_count` {places, favorites, members}를 더한다.
엔티티를 직접 JSON으로 노출하지 않고 API별 응답 DTO로 필드의 존재·null·날짜 형식을 고정한다.

| 번호 | 메서드·하위 경로 | v2 성공 응답·중요 동작 |
| --- | --- | --- |
| API-01 | GET `/` | `{items: 상세형[], nextCursor, hasMore}`. 기본 최신순, limit 기본 20·상한 50 |
| API-02 | GET `/me` | 상세형 배열. type=joined만 가입방(내 소유 제외), 나머지 값은 owned로 처리 |
| API-03 | POST `/` | Map 기본 필드. 방장 자신의 map_members 행도 같은 DB 작업에서 생성 |
| API-04 | GET `/:mapId` | 상세형. 공개방 또는 소유자 또는 비공개방 멤버만 접근 |
| API-05 | PUT `/:mapId` | Map 기본 필드. 방장만 수정. 공개 전환 시 코드 null, 비공개 전환 시 없는 코드 생성 |
| API-06 | DELETE `/:mapId` | `{message:"지도방이 삭제되었습니다."}`. 방장만, 타인 장소가 있으면 force=true 필요 |
| API-07 | GET `/:mapId/membership` | `{isMember:boolean}`. 읽기 권한 먼저 검사. 방장은 멤버 행이 없어도 true |
| API-08 | POST `/:mapId/join` | `{isMember:true}`. 공개방만. 반복 가입은 회원 행을 추가하지 않음 |
| API-09 | POST `/join-by-code` | 가입 후 상세형. 코드 trim·대문자화, 비공개방만 |
| API-10 | POST `/:mapId/leave` | `{message:"지도방에서 나갔습니다."}`. 방장 403, 비회원도 성공 |
| API-11 | POST `/:mapId/invite-code/regenerate` | 상세형. 비공개방 방장만, 이전 코드 제외 |
| API-12 | GET `/:mapId/members` | `{id,nickname,profileImageUrl,joinedAt,isOwner}[]`. 방장·멤버만, joinedAt 오름차순 |
| API-13 | DELETE `/:mapId/members/:userId` | `{message:"멤버를 내보냈습니다."}`. 방장만, 자기 자신 400, 없는 대상도 성공 |
| API-14 | POST `/:mapId/cover` | Map 기본 필드. 방장만, file 1개, `/uploads/maps/<UUID>.webp` |

Flutter `features/maps/services/maps_service.dart`가 이 14개를 호출한다. `MapModel`은 `_count`를 읽으며 없으면 0으로 처리한다. API마다 `_count` 유무가 다른 것을 그대로 유지한다.
정적 이미지 경로와 서버 기본 주소 결합을 유지한다. 커버 저장을 절대 URL이나 `/api/uploads/...`로 변경하지 않는다.

## 3. 모델과 조회 설계

패키지 제안은 기존 기능별 구조와 같은 `com.loco.maps`다. 엔티티명은 Java의 Map과 혼동을 피하는 `MapRoom`, `MapMember`, enum `Visibility`를 제안한다.
MapRoom은 maps의 전체 기본 컬럼, MapMember는 id/mapId/userId/createdAt을 매핑한다. `(mapId,userId)` UNIQUE와 inviteCode UNIQUE는 V1을 그대로 사용한다.
`Visibility`는 기존 User.provider와 같은 PostgreSQL named enum 매핑, tags는 기존 TEXT[] 매핑이다. JSON 컬럼이나 별도 태그 테이블로 바꾸지 않는다.
장소·즐겨찾기는 이번에 API를 이관하지 않는다. 개수·삭제 전 이미지 URL 조회에만 사용한다. 엔티티 범위는 결정 1·2에 따른다.

제안 파일과 필요한 이유:

| 역할·예정 파일 | 필요한 이유 |
| --- | --- |
| MapRoom, MapMember, Visibility | V1 컬럼·제약에 맞는 저장 모델 |
| MapController, MapInputs, MapResponses | 14개 경로 연결, 입력 검증과 명시적 응답 형태 분리 |
| MapService | 생성·수정·삭제·가입의 DB 작업 순서와 트랜잭션 |
| MapPermissions | 공개 여부·소유자·멤버 접근 규칙을 한 곳에서 사용 |
| MapRepository, MapMemberRepository | JPA 조회·갱신, 필요 시 JPA native SQL과 projection |
| MapQueryRepository(복잡한 조회 분리가 필요할 때만) | 정렬·커서·태그 검색을 일반 CRUD와 분리 |
| MapCoverService | DB 커밋 완료와 새 파일 보상 삭제 경계 |
| 지도방 비교 테스트 파일들 | 조회·변경·멤버·커버·동시성 케이스를 작은 묶음으로 관리 |

조회 호환 기준:

- visibility 생략: PUBLIC 또는 내가 소유한 방. 가입한 타인의 PRIVATE는 제외된다.
- PUBLIC 필터: 공개방만. PRIVATE 필터: 내가 소유하거나 가입한 비공개방만.
- search는 trim 후 title 부분 일치 또는 `array_to_string(tags, ' ') ILIKE pattern`이다. 태그 하나씩 검색하는 방식과 다르며, `%`, `_`, 역슬래시·태그 경계 공백을 관측한다. 파라미터 바인딩으로 SQL 삽입을 막되 검색 의미를 임의로 바꾸지 않는다.
- latest: createdAt DESC, id DESC. popular: favorites 수 DESC 이후 같은 순서. places: places 수 DESC 이후 같은 순서.
- v2는 cursor ID + skip 1이다. 정렬별 커서 행의 count/createdAt/id를 기준으로 뒤의 행을 조회하는 안을 검토하되, 커서가 필터 밖·삭제됨·존재하지 않을 때의 Prisma 결과를 먼저 관측한다. id 하나의 대소만으로 페이지를 자르지 않는다.
- limit은 JS parseInt 후 0·NaN·누락이면 20, 음수는 1, 최대 50이다. `12abc`, `1.5`, 빈 값, 반복 query parameter도 v2 관측 후 처리한다.
- items는 limit+1 조회로 hasMore를 계산하고, 다음 페이지가 있을 때 마지막 반환 항목의 id가 nextCursor다. 인기 수 변경 중 페이지 안정성을 v2보다 강하게 보장한다고 주장하지 않는다.
- getMembers는 createdAt만 정렬한다. 동률에 임의 ID 정렬을 추가하지 않는다. 비교 시 동률 행에만 명시적인 동률 그룹 비교를 허용하거나 순서가 구분되는 시드를 사용한다.
- 사용자 DTO와 달리 지도방 owner/member 조회에는 v2가 deletedAt 필터를 추가하지 않는다. 탈퇴 회원의 소유방·멤버 행을 임의로 숨기지 않는다. 요청자의 인증은 별도로 차단한다.

## 4. 쓰기·권한·실패 경계

생성은 방과 방장 멤버 행이 모두 성공하거나 모두 롤백되는 단일 트랜잭션이다. 멤버 INSERT 실패를 DB 트리거로 주입해 검증한다.
수정은 누락과 명시적 null을 구분한다. title 최대 100, description 최대 500, tags 배열의 모든 원소는 문자열, visibility는 PUBLIC/PRIVATE다. 길이·null·미정의 필드·빈 title·빈 객체·같은 값 수정은 기존 Users 입력 처리 방식을 참고하되 지도방 v2 응답을 먼저 고정한다.
`@IsOptional`이 null을 통과시켜도 DB에서는 실패할 수 있다. 모든 잘못된 값을 일괄 400으로 고치지 않는다.

공개 가입·코드 가입은 `(mapId,userId)`가 하나만 생기도록 한다. 이전에 선택한 인증 토큰의 조건부 UPDATE와 달리 여기에는 중복 INSERT 문제가 있어 결정 4가 필요하다.
반복 가입과 방장의 자기 가입도 v2는 알림을 호출한다. 초대 코드 성공 응답은 가입 후 member count를 포함한다.
탈퇴·추방은 멤버 행만 삭제하며 장소·즐겨찾기는 남긴다. 비공개방 비회원의 leave도 방 존재·방장 여부 검사 후 성공한다.

지도방 삭제 순서는 권한 확인 → 타인 장소 수 확인 → DB 삭제 커밋 → 커버·장소 이미지 삭제다. force는 문자열 `true`일 때만 켜진다.
V1에서 places, place_reviews(places 경유), map_members, place_marker_cells는 연쇄 삭제된다. **favorites.mapId는 RESTRICT**이므로 즐겨찾기가 남으면 DB 삭제가 실패한다. force=true도 이 제약을 우회하지 않는다. 소스상 500 예상이며 실제 응답을 먼저 고정한다.
reports.targetId에는 FK가 없어 지도방 삭제로 자동 삭제되지 않는다. 보고서 행을 임의 정리하지 않는다.
실패 트리거·RESTRICT 제약으로 DB 전체 롤백과 파일 보존을 검증한다. DB 커밋 후 파일 삭제 실패는 v2처럼 API 성공을 되돌리지 않는다.
프로세스 중단까지 보장하는 파일 삭제 큐나 outbox는 이번에 추가하지 않는다.

초대 코드는 v2가 Math.random 기반 base36 대문자 문자열을 만들고, 이전 코드와 기존 DB 중복을 확인하며 최대 10번 시도한다. 8자 고정 계약으로 단정하지 않는다.
10회 소진은 400 `초대 코드 생성에 실패했습니다. 다시 시도해주세요.`다. 조회 후 실제 INSERT/UPDATE 사이의 UNIQUE 충돌은 별도 경쟁이며, v2에 없는 재시도를 몰래 추가하지 않는다. 생성 방식은 결정 3으로 선택한다.
비공개→공개는 코드 삭제, 공개→비공개는 코드 생성, 재생성은 이전 코드 사용 불가를 연속 요청으로 확인한다.

## 5. 커버 이미지와 가입 알림

커버는 800×400, fit=cover, withoutEnlargement=true, WebP quality=80이다. 작은 원본이 항상 800×400이 되는 것은 아니므로 가로·세로·작은 원본을 v2와 비교한다.
변환기는 확정된 libvips B를 사용한다. profiles 512×512와 maps 800×400의 설정·저장 폴더를 분리하는 방식만 결정 5에서 선택한다.
v2 순서는 인증 → multipart MIME/크기 검사 → 파일 누락 → 지도방 존재 → 방장 권한 → 내용 디코딩/저장 → DB 갱신이다. 권한 오류와 손상 파일을 섞어 오류 우선순위까지 확인한다.
새 파일 저장 후 DB UPDATE 또는 커밋 실패 시 새 파일 삭제·기존 파일 보존, 성공 후 기존 파일 삭제를 기존 TransactionTemplate 방식으로 처리한다.
JPEG/PNG/WebP/애니메이션 WebP/AVIF, HEIC 500, 10MiB 경계, 위장 MIME, 깨진 multipart, 쓰기 실패와 정적 GET을 비교한다. HEIC의 빈 파일 잔여도 기준에 포함한다.
성공 업로드는 메타데이터·디코딩·인코딩 프로세스 3회와 PNG 중간 변환을 거친다. 이번에는 최적화하지 않고 부하 테스트에서 CPU·메모리·임시 I/O·지연을 측정한다.

가입 알림은 v2 NotificationsService를 그대로 무시할 수 없는 연관 동작이다. FIREBASE_SERVICE_ACCOUNT 미설정 시 꺼지고, 설정 시 방장의 FCM 토큰으로 제목·본문·data를 전송한다. 호출은 HTTP에서 await하지 않으며 실패가 가입을 취소하지 않는다.
등록 해제된 토큰 오류에는 fcmToken을 null로 갱신하는 부수 효과가 있다. 현재 FCM 토큰 저장 API만으로는 전송 구현이 충족되지 않는다.
결정 3-6 B에 따라 이번에는 인터페이스·호출 위치·mock 계약만 구현한다. 실제 외부 푸시는 비교 테스트에서 보내지 않는다. v2 HTTP/DB 비교는 Firebase 비활성 환경이며, v3의 payload·전송 실패 격리·커밋 이후 호출은 mock 계약으로 구분한다. 무효 토큰 해제는 실제 Firebase 전송 구현과 함께 검증한다. 비동기 결과가 필요한 후속 검증은 고정 sleep 대신 제한 시간 내 상태 조건으로 기다린다.

## 6. 비교 테스트 계획

기존 Testcontainers 환경·양쪽 Flyway V1·케이스 전 독립 DB 초기화·컨테이너 재시작·직접 JWT 발급을 재사용한다. 시간·UUID·초대 코드·파일명은 값만 무시하지 말고 형식, null, DB와 응답의 일치·참조 관계를 검증한다.
새 inviteCode는 케이스별 제외 항목으로 등록하되, 응답에서 추출해 다음 요청에 사용하고 이전 코드와 다름을 별도 검증한다. PRIVATE/PUBLIC의 null 전환은 제외하지 않는다.
HTTP 배열 순서는 기본적으로 비교한다. DB 행 대응은 제목·회원 email 등 의미가 있는 시드 키를 사용하고 UUID/시간 정렬로 결과를 맞추지 않는다. 새 map_members는 map 제목+user email로 연결을 비교한다.
기존 인증·사용자 테스트와 신규 지도방 테스트를 단계별로 검증한다. 실행 범위·성공/실패/알려진 차이·시간은 각 단계 커밋 본문에 남긴다. 아래 표 중 API-11/14는 후속 구현 때 검증한다.

| API | 정상·경계·오류 비교 케이스 | DB·파일·부수 효과 |
| --- | --- | --- |
| 01 목록 | visibility 3종, 정렬 3종, 0/1/다수/동률, limit 경계·비숫자, 여러 페이지, 유효/없는/필터 밖 커서, title·태그·와일드카드 검색 | 읽기 후 업무 DB 불변, count·응답 순서·nextCursor 검증 |
| 02 내 지도 | owned/joined/누락/잘못된 type, 소유하며 가입한 방 제외, 탈퇴 소유자·멤버 시드 | 배열 형태·순서·count, DB 불변 |
| 03 생성 | PUBLIC/PRIVATE, 필수 누락·빈 문자열·길이·tags·null·미정의 필드 | 방+방장 멤버 1행, 초대 코드 규칙, 멤버 INSERT 실패 전체 롤백 |
| 04 상세 | 공개 비회원/비공개 멤버/방장/비회원, 없는 방·UUID 아닌 id | 상세형 필드, inviteCode 포함 여부 그대로, DB 불변 |
| 05 수정 | 필드별·누락/null·동일 값·빈 객체, 공개↔비공개, 비방장·없는 방 | 변경 컬럼·updatedAt, 코드 유지/교체/삭제, UPDATE 실패 롤백 |
| 06 삭제 | 빈 방, 내 장소만, 타인 장소+force 유무, force 대소문자, 비방장·없는 방·즐겨찾기 존재 | 연쇄 삭제 대상·reports 보존, 실패 시 모든 행·파일 보존, 성공 시 해당 파일만 삭제 |
| 07 멤버십 | 방장(멤버 행 없음 포함), 공개 멤버/비회원, 비공개 비회원, 없는 방 | boolean, 오류 우선순위, DB 불변 |
| 08 공개 가입 | 최초/반복/방장/비공개/없는 방 | 멤버 유일성, 다른 행 불변, 반복 호출 포함 알림 계약 |
| 09 코드 가입 | 최초/반복/소문자·공백/빈 값·숫자/만료된 코드/공개방 코드 | 멤버 유일성, 가입 후 count, 응답 코드로 연속 요청 |
| 10 나가기 | 멤버/비회원/방장/없는 방 | 멤버만 제거, 장소·즐겨찾기 보존 |
| 11 코드 재생성 | 비공개 방장/공개 방장/비방장/없는 방, 생성 충돌·10회 소진 | 새 코드 != 이전 코드, 이전 코드 가입 404, UNIQUE 경쟁 별도 재현 |
| 12 멤버 목록 | 방장/멤버/공개 비회원/없는 방, 탈퇴 멤버, joinedAt 동률 | user 필드만 노출, joinedAt/isOwner·정렬·DB 불변 |
| 13 추방 | 멤버/없는 대상/자기 자신/비방장/없는 방 | 멤버만 제거, 반복 성공, 장소 보존 |
| 14 커버 | 정상 형식·크기별, AVIF·HEIC, 누락·손상·MIME·multipart·10MiB, 비방장+오류 조합 | 이미지 의미·픽셀·크기·정적 GET, 파일/DB 실패와 커밋 실패 보상 |

공통: 14개 경로의 미인증·만료·잘못된 서명·탈퇴·없는 회원 401, 존재하지 않는 라우트 404, 429 필수 검증. 동적 mapId가 달라도 같은 핸들러의 제한을 공유하는지 확인한다.
v2가 하는 입력 검증의 순서까지 비교한다. 에러 메시지 예외는 기존 원칙대로 케이스별 명시하며 전체 문자열 무시를 기본값으로 두지 않는다.
연속 요청: 생성 → 초대 → 멤버십/목록 → 코드 재생성 → 기존 코드 실패 → 나가기/추방 → 커버 교체 → 삭제.
동시성: 기존 DatabaseRequestRace로 같은 회원의 동시 가입, 서로 다른 회원 가입, 코드 재생성 경쟁을 실제 PostgreSQL에서 재현한다. v2의 성공 수·실패 코드·최종 행 수를 먼저 관측한다. v2에 없는 잠금·@Version·새 마이그레이션은 넣지 않는다.
Compose는 별도 임시 프로젝트에서 v2/v3 커버 파일 상호 읽기·교체를 검증한다. 실제 개발 DB·볼륨은 초기화하지 않는다.

## 7. 선택지와 확정 결과

확정: 3-1 엔티티 관계 B, 3-2 목록·태그·집계 조회 A, 3-3 초대 코드 생성 A, 3-4 중복 가입 처리 A, 3-5 이미지 저장 코드 A, 3-6 가입 푸시 알림 범위 B.
MapRoom.owner, MapMember.map/user는 LAZY ManyToOne이며 cascade를 지정하지 않는다.
API-12는 먼저 LAZY 순회로 N+1 SQL 수를 측정한 뒤 fetch join 또는 EntityGraph로 개선해 다시 측정한다. 전후 결과를 커밋 본문에 남기고 그 시점에 멈춘다.
구현한 API의 SQL 수는 실제 HTTP 요청(인증 조회 포함)으로 고정한다. 시드 준비 JDBC·트랜잭션 제어문은 측정에서 제외하며 테스트는 병렬 실행하지 않는다.
의도된 차이: v3 초대 코드는 SecureRandom 기반 8자 대문자 영숫자다. v2 Math.random/base36의 길이·분포를 재현하지 않는다.
동시 가입에서 v2가 실패하더라도 v3는 UNIQUE/ON CONFLICT로 성공하게 하고, 관측된 차이를 개선된 알려진 차이로 보고한다.
Firebase 실제 전송은 장소 추가 등 알림을 보내는 모든 API 이관 후 한 번에 구현한다. 이번에는 인터페이스·호출 위치·mock 계약까지만 구현하고, Firebase 설정 환경의 미전송은 알려진 차이로 보고한다.

아래 표는 선택 당시 장단점이다. 번호는 이번 묶음에서만 사용한다. 답변 예: `3-1 엔티티 관계: B`.

| 번호·항목 이름 | A | B |
| --- | --- | --- |
| 3-1 엔티티 관계 | **권장: MapRoom/MapMember에 FK 문자열을 보관**, User처럼 명시적 repository 조회. 장점: 기존 스타일·DB 범위가 작고 지연 조회가 숨지 않음. 단점: owner·회원 조합을 직접 작성 | LAZY ManyToOne 연관을 사용하고 DTO 조회를 별도 구성. 장점: 객체 관계 탐색·JPQL 조인이 자연스러움. 단점: 프록시·N+1 관리 필요, cascade 설정 실수 위험 |
| 3-2 목록·태그·집계 조회 | **권장: Spring Data JPA repository의 native SQL + projection**, 일반 쓰기는 JPA. 장점: TEXT[]·ILIKE·count 정렬을 V1 그대로 처리, Place/Favorite 전체 엔티티 이관 불필요. 단점: PostgreSQL 의존·커서 SQL을 직접 검증. MyBatis/JdbcTemplate은 추가하지 않음 | 필요한 Place/Favorite 읽기 모델까지 매핑하고 JPQL/Criteria 중심, 배열 검색만 DB 함수 처리. 장점: 엔티티 기반으로 추적 가능. 단점: 이번 범위 확대·여러 집계 조인의 중복 수 주의, DB 함수 의존은 남음 |
| 3-3 초대 코드 생성 | **권장: SecureRandom으로 대문자 영숫자 8자**, DB 중복 확인·이전 코드 제외·10회 한도 유지. 장점: 단순·예측 어려움. 단점: v2 Math.random의 드문 짧은 코드까지 같은 분포는 아님. 런타임 UNIQUE 경쟁 처리 변경은 별개 | v2의 난수→base36→최대 8자 방식에 맞춘 생성기. 장점: 생성 형식 의도를 더 가깝게 유지. 단점: 난수 강도가 낮고 Java/JS 수치 변환의 정확한 재현을 검증해야 함 |
| 3-4 중복 가입 처리 | **권장: JPA repository native INSERT ON CONFLICT DO NOTHING**, UNIQUE 제약으로 단일 멤버 유지. 장점: 경쟁 시 예외가 정상 흐름이 되지 않음, 기존 ID/createdAt 보존. 단점: PostgreSQL 전용, UUID·createdAt을 명시 공급해야 함. v2 경쟁 결과 관측 후 동일한지 판정 | JPA INSERT 후 UNIQUE 충돌을 트랜잭션 밖에서 처리하고 새 트랜잭션으로 기존 행 확인. 장점: 일반 JPA 저장 활용. 단점: 롤백된 영속성 컨텍스트를 재사용하면 안 되며 흐름·테스트가 복잡 |
| 3-5 이미지 저장 코드 | **권장: 기존 로컬 저장 로직을 공통화**, profile/cover의 폴더·크기만 타입으로 지정. 장점: 크기·형식·보상 정책 중복 방지. 단점: 완료된 프로필 이미지 회귀를 다시 검증해야 함. 클라이언트가 저장 폴더를 지정하지 못하게 함 | MapCoverStorage를 별도로 만들고 ImageConverter만 공유. 장점: 프로필 저장 코드 변경 범위가 작음. 단점: MIME·크기·경로·파일 삭제 로직 중복과 이후 수정 누락 위험 |
| 3-6 가입 푸시 알림 범위 | **권장: 이번에 Firebase Admin Java 전송 어댑터까지 구현**하고 테스트는 mock 계약. 장점: 자격 증명 설정 시 v2의 가입 알림·무효 토큰 정리까지 이관. 단점: 의존성·비동기 처리·설정 검증 추가, 실제 전달은 별도 환경 검증 필요 | 호출 경계와 mock 계약만 만들고 실제 전송은 이후 묶음으로 연기. 장점: 이번 지도방 구현을 줄임. 단점: Firebase 설정 환경의 기능 차이가 남으므로 알려진 차이로 명시하고 완료 범위를 제한해야 함 |

tags는 Hibernate 기본 ARRAY 매핑을 우선 검증하며 별도 라이브러리는 제안하지 않는다. enum은 현재 Provider 매핑을 재사용한다. 실패하면 다른 매핑·라이브러리를 임의 채택하지 않고 근거와 대안을 제시한다.
3-2 A와 3-4 A는 **JPA 안에서 사용하는 native SQL 허용 범위**에 대한 명시적 선택이다. 서로 같은 결정으로 묶지 않는다.
3-6 B로 확정했으므로 Firebase SDK와 외부 계정 연결은 이번 범위에 추가하지 않는다.

## 8. 승인 후 구현 순서와 확인 방법

1. v2 경계 입력 관측·fixture → 엔티티와 목록/내 목록/상세 조회.
2. 생성·수정·삭제: 트랜잭션·RESTRICT·파일 후처리.
3. 멤버십·가입·코드 가입·나가기·멤버 목록·추방: 중복 가입과 알림.
4. 초대 코드 재생성과 경쟁·연속 시나리오.
5. 커버 업로드와 Windows/Linux·Compose 공유 파일 검증.

각 단위는 한국어 커밋으로 나누고 본문에 전체/성공/실패/알려진 차이 수, 실행 범위와 시간을 기록한다. 파일을 추가할 때 역할과 핵심 설정을 설명한다.
빠른 테스트 `./gradlew test`, 실제 v2 비교 `./gradlew comparisonTest`, 통합 `./gradlew allTests`를 유지한다. 시간은 v2 이미지 캐시 여부와 네이티브 이미지 준비 포함 여부를 구분한다.
구현은 승인됐으며 API-12 N+1 전후 측정 완료 시 사용자 확인을 기다린다.

현재 조회 관측: `src/test/resources/maps/read-v2.json`의 49개 요청과 DB 불변을 검증한다. 커서가 필터 밖일 때도 Prisma skip:1이 첫 유효 행을 제외한다.
현재 SQL 기준: 목록·내 목록·공개/내 소유 상세 2회, 타인 비공개 상세 3회, 조회 파라미터 오류 1회(인증 조회). `MapReadIntegrationTest`가 고정하며 증가하면 실패한다.
생성은 공개 3회·비공개 4회, 수정은 일반 3회·공개→비공개 4회·빈 객체 2회, 삭제 성공 4회(인증 포함)다. 실패 경로의 SQL 수도 비교 테스트에서 별도로 고정한다.
쓰기 관측은 `src/test/resources/maps/write-v2.json`이다. 생성의 tags:null은 빈 배열, 수정의 tags:null·빈 배열 본문은 500이다. scalar 문자열 tags는 배열 오류 한 개만 반환한다. DB의 기존 NULL tags는 응답에서 빈 배열로 읽는다.
즐겨찾기 RESTRICT에 따른 삭제 500과 force 대소문자 구분, 생성·삭제의 지연 커밋 실패 롤백, 신고 행 보존, 커밋 이후에만 파일 삭제를 실제 v2와 비교했다.
`LocalImageFiles`는 공유 업로드 루트와 경로 안전성·정리 실패 무시를 공통으로 담당한다. 프로필 변환·크롭은 아직 기존 코드이며 커버 단계에서 크기 설정을 공통화한다.

## 9. 멤버 단계의 측정과 남은 차이

API-12는 실제로 LAZY 순회 구현을 실행한 뒤 `join fetch m.user`로 변경했다. MapRoom.owner와 MapMember.map/user의 기본 매핑은 모두 LAZY이며 cascade는 없다. 목록 조회 메서드에서만 user를 함께 읽는다.

| 조건 | LAZY 순회 실제 SQL | fetch join 실제 SQL |
| --- | --- | --- |
| 방장이 멤버 3명 조회 | 6 | 3 |
| 방장이 멤버 10명 조회 | 13 | 3 |

인증 회원 확인 1회·방 조회 1회·멤버 조회 1회를 포함한다. 기존 구현은 멤버별 User 조회가 추가됐다. HTTP 응답의 행 수·방장 표시·탈퇴 멤버 노출을 함께 검증하며, 시드 준비 JDBC와 트랜잭션 제어문은 제외한다.
`MapMemberSqlTest`는 기본적으로 두 크기 모두 3회를 요구한다. `SqlQueryCounter`는 테스트 전용 Hibernate StatementInspector이고 테스트 병렬 실행은 꺼져 있다. 단순 wall time이 아니라 실제 SQL 수로 N+1을 검증한다.
전후 측정 파일은 `build/reports/maps/n-plus-one-{naive,fetch}.json`이다. naive는 변경 전 실행 결과이며 현재 코드에서 재생성하면 6/13 기준에 실패하는 것이 정상이다.

| API | 정상 요청 SQL 수(인증 포함) |
| --- | --- |
| API-01 목록 / 02 내 목록 | 각각 2 |
| API-03 생성 | 공개 3, 비공개 4(첫 후보 사용 가능 시) |
| API-04 상세 | 공개·소유 2, 타인 비공개 3 |
| API-05 수정 | 일반 3, 빈 객체 2, 코드 신규 생성 4(첫 후보 사용 가능 시) |
| API-06 삭제 | 4 |
| API-07 멤버십 | 소유자 2, 타인 3 |
| API-08 공개 가입 | 4(반복 가입 포함) |
| API-09 코드 가입 | 5(반복 가입 포함) |
| API-10 나가기 | 3 |
| API-12 멤버 목록 | 방장 3, 일반 멤버 4; 멤버 수와 무관 |
| API-13 추방 | 3 |

`MapReadIntegrationTest`, `MapWriteComparisonTest`, `MapDeletionComparisonTest`, `MapMembershipComparisonTest`가 정상·오류 경로별 수를 고정한다. 코드 충돌 재시도는 후보마다 조회가 늘어나는 별도 경로이며 10회 한도를 단위 테스트로 검증한다. API-11/14의 SQL 기준은 구현 시 추가한다.

동시 가입은 DatabaseRequestRace가 지도방 부모 행을 잠그고 두 map_members INSERT의 실제 잠금 대기를 확인한 뒤 해제한다. 공개 가입·코드 가입 모두 v2 [201,500], v3 [201,201]과 동일 회원 행 1개를 관측했다. v3는 JPA native ON CONFLICT DO NOTHING을 사용한다. v2 실패를 재현하지 않으며 차이는 보고서에 `improved`로 표시한다.
Firebase 실제 전송과 무효 FCM 토큰 정리는 보류했다. `NotificationSender`는 전송 경계, `DeferredNotificationSender`는 명시적 보류 구현, `MemberJoinedNotification.Delivery`는 커밋 후 호출 지점이다. 반복 가입도 호출하며 롤백이면 호출하지 않는다. 전송 실패가 가입 성공을 취소하지 않는 mock 계약을 검증한다. 이후 실제 송신 구현에서 비동기 전송·전송 실패·무효 토큰 정리를 함께 다룬다.
전체 지도방 비교 범위에서는 의도된 코드 생성 1건, Firebase 보류 1건, 관측된 동시 가입 개선 2건을 보고한다. 알려진 차이는 테스트 실패나 SKIPPED와 별도 집계한다.

재검증(앱 디렉터리): `./gradlew test --tests '*MapMemberSqlTest'`로 SQL 3회 고정을 확인하고, `./gradlew comparisonTest --tests '*Map*Test'`로 v2 응답·DB·알려진 차이를 확인한다. 전체 회귀는 기존 `./gradlew allTests`다.

## 근거 파일·공식 참고

- v2: `../server/src/maps/maps.controller.ts`, `maps.service.ts`, `map-permissions.service.ts`, `dto/`, `../server/src/upload/`, `../server/src/notifications/notifications.service.ts`.
- DB: `../server/prisma/schema.prisma`, `src/main/resources/db/migration/V1__baseline.sql`.
- Flutter: `../mobile/lib/features/maps/services/maps_service.dart`, `../mobile/lib/shared/models/map_model.dart`.
- [Spring Data JPA native query](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html): JPA repository에서 native SQL을 사용할 수 있다.
- [Hibernate 6.6 문서](https://hibernate.org/orm/documentation/6.6/): ARRAY와 named enum 매핑을 기존 스키마에 맞춰 검증한다.
- [Firebase Admin 설치](https://firebase.google.com/docs/admin/setup): Java 전송 어댑터 선택 시 참고한다. 이번 설계 단계에서 SDK 추가는 하지 않았다.
