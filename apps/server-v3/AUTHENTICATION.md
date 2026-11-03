# 1번 묶음: 인증

현재 구현 범위는 User·RefreshToken·PasswordResetToken 엔티티와 JPA 저장소, UUID 생성, 감사 시각이다.
인증 API는 다음 구현 단위에서 추가한다. 스키마와 Flyway V1은 변경하지 않는다.

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

아직 구현하지 않은 항목도 위 목록은 승인된 설계다. 구현 완료 목록으로 해석하지 않는다.

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

엔티티 → 회원가입·로그인 → 재발급·로그아웃까지 각 단위를 검증·커밋하고 멈춘다.
그 뒤 확인을 받고 비밀번호 찾기·재설정 → OAuth → 보호 경로 회원 상태 확인 → 요청 제한 순서로 진행한다.
공개 경로에 공통 인증용 회원 조회를 추가하지 않는다.
