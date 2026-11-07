# 1번 묶음: 인증

현재 구현 범위는 인증 엔티티와 POST /api/auth/register·login·refresh·logout·forgot-password·reset-password·oauth/{provider}이다. 성공 상태는 v2와 같은 201이다.
엔티티부터 요청 제한까지 단계별로 검증·커밋한다. 스키마와 Flyway V1은 변경하지 않는다.

## 확정한 설계

- users: User·Provider·UserRepository. auth: 인증과 두 토큰 엔티티. 메일은 이후 mail에 분리한다.
- 토큰의 회원 참조는 String userId로 매핑한다. DB 외래 키는 기존 V1 제약을 그대로 사용한다.
- ID는 @UuidGenerator(style = RANDOM)와 String이다. DB는 TEXT이며 생성자에서 ID를 채우지 않는다.
- Spring Data는 null ID를 신규로 판단해 persist하고 Hibernate가 UUID를 생성한다. 수동 isNew 구현은 필요하지 않다.
- User의 @LastModifiedDate와 각 엔티티의 @CreatedDate는 JPA Auditing으로 처리한다.
- DateTimeProvider에 UTC Clock을 연결하고 TIMESTAMP(3)에 맞춰 밀리초로 절삭한다.
- 기존 "Provider" PostgreSQL enum은 Hibernate NAMED_ENUM으로 매핑한다.
- bcrypt는 기존 Spring Crypto의 v2 호환 어댑터로 $2b$·cost 10·UTF-8 72바이트 처리 규칙을 유지한다.
- refresh는 조건부 UPDATE와 새 토큰 저장을 한 트랜잭션에서 처리한다. 비관적 락 비교 실험은 별도다.
- 원본 JSON·DTO 검증 어댑터로 알 수 없는 필드·타입·오류 배열을 맞춘다.
- OAuth는 RestClient와 MockRestServiceServer, 메일은 Boot Mail starter와 JavaMailSender를 사용한다.
- 요청 제한은 v2와 같은 처리 메서드·IP 기준으로 프로세스 메모리에 저장한다.

위 설계를 1번 묶음의 인증 API와 공통 인증·요청 제한에 적용한다.

## 읽는 순서

1. users/User.java: 회원 필드와 nullable 값, 기존 컬럼 이름을 확인한다.
2. auth/RefreshToken.java, PasswordResetToken.java: 회원 ID, 토큰 해시, 만료·소비 상태를 확인한다.
3. 각 Repository: JPA가 저장·조회하는 진입점이다. SQL로 테이블을 생성하지 않는다.
4. config/JpaAuditingConfig.java: Clock → DateTimeProvider → 감사 필드의 연결을 확인한다.
5. auth/EntityPersistenceTest.java: 실제 PostgreSQL에서 String UUID, TEXT 타입, enum, 감사 시각을 검증한다.

columnDefinition은 기존 DB 타입을 설명하는 매핑이다. Hibernate DDL은 꺼져 있으며 스키마 관리는 Flyway가 담당한다.
camelCase 컬럼은 이름을 명시하고 인용한다. physical naming strategy는 StandardImpl로 설정해 Boot 기본 snake_case 변환을 끈다.
엔티티를 응답으로 직접 반환하지 않는다. 비밀번호·토큰 해시는 서버 내부 데이터다.

## Auditing의 적용 범위

일반 엔티티 변경은 AuditingEntityListener가 시각을 채운다.
JPQL 벌크 UPDATE는 엔티티 콜백을 거치지 않는다. User를 벌크 수정한다면 같은 Clock의 updatedAt을 쿼리에 명시해야 한다.
값이 전혀 바뀌지 않은 수정은 dirty checking에 의해 UPDATE가 생략될 수 있다.
v2에서 이 경우에도 updatedAt이 갱신되는 API를 이관할 때 별도 호환 검증과 감사 처리 보완이 필요하다.
두 토큰 테이블에는 updatedAt이 없으며 새 컬럼을 추가하지 않는다.

## v2 기준과 후속 구현 순서

main의 기준에는 재설정 코드 원자적 소비와 OAuth 이메일 자동 연결 차단이 포함된다.
원본 커밋은 88975d9, 3fb485f이며 기존 탈퇴 회원 회귀 수정도 유지한다.
SMTP 실패 때 앞서 저장한 재설정 토큰 데이터가 남는 동작은 유지한다.

엔티티 → 회원가입·로그인 → 재발급·로그아웃은 확인을 마쳤다.
비밀번호 찾기·재설정 → OAuth → 보호 경로 회원 상태 확인 → 요청 제한까지 검증하며, 완료 후 다음 묶음은 사용자 확인을 기다린다.
공개 경로에 공통 인증용 회원 조회를 추가하지 않는다.

## 회원가입·로그인 흐름

AuthController → AuthInputs → AuthService → UserRepository / V2PasswordEncoder / TokenIssuer 순서로 읽는다.
AuthInputs는 원본 JSON의 추가 필드·타입·길이·이메일을 검사하고 typed record를 만든다. 오류 배열의 순서도 v2와 비교한다.
V2Email은 저장소에 고정된 validator.js 13.15.35의 기본 규칙을 적용한다. 출처의 MIT 허가는 resources/licenses/validatorjs-MIT.txt에 보관한다.
V2PasswordEncoder는 UTF-8 바이트를 최대 72바이트로 처리해 $2b$ cost 10 해시를 만든다. 실제 v2 bcrypt로 양방향 검증한다.
회원가입은 이메일 중복을 확인하고 회원을 저장한 뒤 토큰을 발급한다. v2와 같이 회원·토큰 저장 전체를 새 트랜잭션으로 묶지 않는다.
로그인은 없는 회원·비밀번호 없는 회원·탈퇴 회원·틀린 비밀번호를 v2와 같은 순서와 문구로 처리한다.
TokenIssuer는 HS256 JWT(sub, email, iat, exp; 7일)와 48바이트 무작위 refreshToken(base64url)을 만든다.
DB에는 refreshToken의 SHA-256 해시와 30일 만료만 저장한다. 원문 토큰은 응답으로만 반환한다.
ApiProblem은 업무 오류를, ApiExceptionHandler는 업무·검증·파싱·예상하지 못한 오류를 구분한다. 500에는 error 키를 넣지 않는다.

AuthenticationCases는 실제 API 오류 사례, AuthenticationFlowTest는 연속 발급·DB·교차 인증, InputCompatibilityTest는 v2 라이브러리와의 교차 검증이다.
비밀번호 해시는 단순 제외하지 않고 형식·cost·평문 일치를 확인한다. 여러 refreshToken은 캡처한 응답 토큰의 해시로 역할을 붙여 정렬한다.
JDK HTTP 클라이언트로 POST 401 응답 본문까지 비교한다. HttpURLConnection streaming 모드의 인증 오류 본문 유실을 피하기 위한 설정이다.

## 재발급·로그아웃 흐름

AuthService.refresh는 해시로 토큰을 찾고 폐기·만료·회원 상태를 검사한다. 공개 API의 업무 검증이며 공통 JWT 인증용 회원 조회와 구분한다.
RefreshTokenRotation은 별도 Spring 빈이다. 프록시의 @Transactional 안에서 `id = :id AND revokedAt IS NULL` 조건부 UPDATE와 새 토큰 INSERT를 묶는다.
UPDATE 건수가 1인 요청만 새 토큰을 저장한다. 같은 토큰의 동시 요청은 하나만 201, 나머지는 401이다.
새 토큰 저장 실패 시 이전 토큰 폐기도 롤백된다. v2처럼 사전 조회는 트랜잭션 밖, JWT 서명은 커밋 뒤에 수행한다.
로그아웃은 전달된 해시의 미폐기 토큰만 수정한다. 토큰 생략·null·빈 값·없는 토큰·반복 요청도 201이며 다른 토큰은 유지한다.
두 API는 공개 경로여서 Bearer가 없어도 된다. 추가 필드·잘못된 타입은 AuthInputs가 v2의 DTO 오류 배열로 반환한다.
HTTP JSON 본문은 Express의 기본 strict 규칙에 맞춰 객체·배열만 받는다. 깨진 JSON·null·boolean 본문의 400 문구는 케이스별 예외로 비교한다.

테스트 파일은 다음 순서로 읽는다.

- AuthenticationSupport: 발급 응답의 JWT 서명·claim·만료와 DB 해시를 검증하고, 토큰별 역할로 DB 행을 정렬한다. 회원가입·로그인·재발급 테스트가 공유한다.
- RefreshCases / RefreshComparisonTest: refresh·logout의 입력 오류와 데이터 불변 사례 11건이다.
- RefreshFlowTest: 로그인 → 회전 → 이전 토큰 거절 → 로그아웃, 만료·폐기·탈퇴 상태, 멱등성, 동시 요청과 롤백 등 9건이다.
- compat/DatabaseRequestRace: PostgreSQL 행을 잠근 뒤 두 HTTP 요청이 실제 DB 잠금 대기에 도달한 것을 관측하고 잠금을 푼다. 특정 UPDATE 문장 형태를 요구하지 않으므로 이후 비관적 락 비교 실험에서도 재사용한다.

테스트 HTTP 클라이언트 설정은 요청 전에 완료하고 경쟁 요청 안에서는 바꾸지 않는다. 새 토큰 INSERT를 실패시키는 테스트용 DB 트리거는 finally에서 제거한다.

## 비밀번호 찾기·재설정

PasswordRecoveryService는 없는 회원·탈퇴 회원·OAuth 회원에게도 같은 찾기 성공 응답을 주고 메일은 보내지 않는다.
대상 회원의 이전 미사용 코드를 사용 처리하고 새 6자리 코드의 SHA-256과 15분 만료를 저장한다. SMTP 전송 전 각각 커밋하므로 전송 실패에도 두 DB 변경은 남는다.
PasswordResetMail은 승인한 Boot Mail starter / JavaMailSender를 사용한다. mail/password-reset.html은 v2 본문이며 닉네임·코드만 채운다.
SMTP_HOST·PORT·USER·PASS·SECURE를 두 서버에 같은 환경변수로 제공한다. 기본 호스트·포트·암시적 TLS 여부는 v2와 같다. STARTTLS는 서버가 제공하면 협상한다. 실제 자격 증명은 .env에만 둔다.

재설정은 유효 코드를 먼저 조회한 뒤 bcrypt 해시를 만들고 PasswordResetTransaction에 진입한다.
`usedAt IS NULL AND expiresAt > now` 조건부 UPDATE의 성공 건수가 1이어야 비밀번호 변경과 모든 미폐기 refreshToken 폐기가 진행된다. 세 변경은 함께 커밋·롤백된다.
User 변경은 JPA dirty checking과 Auditing으로 updatedAt을 갱신한다. 코드·토큰 벌크 UPDATE에는 같은 Clock의 시각을 명시한다.

PasswordRecoveryTest는 실제 Mailpit SMTP 수신과 550 오류 주입을 사용한다. 동시 재설정은 기존 DatabaseRequestRace로 두 요청의 DB 경쟁을 확인하며 하나만 성공해야 한다.
후속 refresh 폐기 실패를 DB 트리거로 주입해 비밀번호와 코드 소비까지 롤백되는지 확인한다. 오류 주입은 finally에서 해제한다.

## OAuth 계약과 외부 통신

OAuthProfiles는 카카오 POST form 토큰 교환·네이버 GET query 토큰 교환 후 Bearer 프로필 조회를 수행한다. 환경변수 키는 두 .env.example에만 선언한다.
OAuthLoginService는 제공자·제공자 ID로 기존 회원을 찾는다. 없으면 이메일 중복을 확인하고, 중복 이메일은 회원 상태·가입 방식에 관계없이 409로 거절한다. 이메일만으로 자동 연결하지 않는다(3fb485f 기준).
기존 제공자 회원은 email·nickname·제공된 프로필 이미지를 갱신한다. 이미지 생략 시 기존 값을 보존하며 v2처럼 갱신 커밋 후 탈퇴 여부를 검사한다.
이메일·닉네임이 없는 프로필의 대체 값, 네이버 ID 누락, 외부 오류 메시지와 상태도 v2 코드의 계약을 따른다.
V2Url은 DTO의 IsUrl({require_tld:false}) 규칙을 담당한다. 실제 v2 validator와 개발 URL·인코딩·포트·IPv6 사례를 대조한다.

OAuthContractTest는 **v3 계약 테스트**다. RestClient.Builder에 MockRestServiceServer를 연결해 외부 네트워크 요청을 차단하고, 요청 URL·method·form/query·Bearer와 HTTP 응답·DB 변경·발급 토큰을 검사한다.
v2의 고정 OAuth 외부 주소를 바꾸거나 실제 제공자 계정으로 로그인하지 않는다. 따라서 v2 HTTP 비교 통과로 집계하지 않는다.
메일 템플릿은 코드부터 치환한 뒤 닉네임을 넣어, 닉네임 안의 `${code}` 같은 문자가 다시 치환되지 않게 한다. 실제 양쪽 Mailpit 메일로 이 사례도 확인한다.

## 보호 경로의 현재 회원 확인

JWT 필터는 서명·만료만 확인한다. ApiWebConfig의 MVC interceptor가 @AuthenticatedApi를 선택한 경우에만 UserRepository의 인증 projection으로 ID·email·deletedAt을 읽는다.
탈퇴·없는 회원은 DTO 검증 전에 v2와 같은 `{"statusCode":401,"message":"Unauthorized"}`로 거절한다. 인증 정보의 email은 JWT의 과거 값 대신 DB의 현재 값이다.
공개 메서드의 클래스 선언 덮어쓰기와 없는 경로는 회원 조회를 하지 않는다. 로그인·재발급처럼 공개 API 자체에 필요한 업무 조회는 이 공통 인증 조회와 별개다.
MemberAuthenticationTest는 테스트 DB의 users 테이블을 잠시 다른 이름으로 바꿔, 회원 조회가 불가능해도 공개·없는 경로는 통과하고 유효 JWT 보호 요청만 500이 되는지 확인한다. 이름은 finally에서 복원한다.

## 요청 제한과 처리 순서

RequestLimit은 v2 @Throttle의 메서드별 한도다. 지정하지 않으면 120회/분이며 카카오·네이버는 같은 OAuth 메서드 한도를 공유한다.
RequestRateLimiter는 메서드·접속 IP별 요청 시각을 보관한다. 각 요청은 60초 뒤 제외하고 초과 시점부터 60초 차단한다. 차단 중 재요청은 차단 시간을 연장하지 않는다.
동기화된 계산으로 동시 요청도 한도를 넘겨 통과하지 않는다. 오래된 항목은 다음 요청의 정리 주기에 제거한다. v2와 같이 프로세스 메모리 기준이므로 여러 인스턴스 사이의 공유 제한은 제공하지 않는다.
RateLimitInterceptor는 회원 확인보다 먼저 실행한다. 성공 구간에는 X-RateLimit-Limit/Remaining/Reset, 차단에는 Retry-After와 `{"statusCode":429,"message":"ThrottlerException: Too Many Requests"}`를 반환한다.
접속 IP는 request.remoteAddr다. 신뢰 프록시 설정을 추가하지 않았으므로 임의 X-Forwarded-For 값으로 제한을 우회할 수 없다.

GlobalHttpFilter 안의 RequestBodyParser → Helmet 호환 헤더 → CORS/OPTIONS → JWT 필터 → MVC 경로 선택 → 요청 제한 → 보호 경로 회원 확인 → DTO 검증 → 메서드 권한 → API 순서다.
기존 JsonBodyFilter의 JSON 선검사·10MiB 제한을 RequestBodyParser로 옮기고 URL-encoded form·문자셋·gzip/deflate 해제 후 크기 제한을 보완했다. JSON 구문 오류·원시 값·뒤따르는 JSON과 form 파라미터 1,000개 초과는 인증·요청 제한 전에 거절한다. 정상 본문은 MVC에서 다시 읽을 수 있게 보관한다. 조기 파서 오류에는 Helmet/CORS가 없으며, 10-1 A로 X-Powered-By만 생략한다. [전역 HTTP 비교](src/test/resources/globals/README.md).
RequestRateLimiterTest는 가상 시계로 개별 요청 만료·차단 해제·IP/메서드 분리·동시성을 확인한다. RateLimitCasesTest는 실제 v2/v3의 모든 인증 API 한도와 HTTP 응답을 비교한다.
HarnessRateLimitTime은 테스트 사이 제한 시계만 이동하는 도구다. 운영 제한기나 JWT 시계를 끄거나 조작하지 않는다. C 방식의 컨테이너 재시작 범위는 COMPARISON_TESTS.md를 참고한다.

학습 순서: 조건부 UPDATE의 행 수 → 트랜잭션 프록시와 롤백 → SMTP의 DB 외부 부작용 → RestClient 계약 테스트 → JWT와 현재 회원 상태의 차이 → MVC interceptor 순서 → 요청별 만료와 차단 구간.
