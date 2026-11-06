# PR 초안 — LOCO v3 Spring 서버 이관 (0~9번 묶음)

대상: `feature/loco-v3-foundation` → `main`. 이 파일은 본문 초안이며 PR 생성·머지·배포는 하지 않았다.

## 변경 내용

NestJS v2의 API 계약을 유지하면서 Java 21·Spring Boot 3.5·Spring Data JPA 기반 서버를 `apps/server-v3`에 추가했다. 인증부터 지도방·장소·리뷰·이미지·마커·기타 API까지 업무 API 47개, v3 health 1개, Firebase 발송과 예약 토큰 정리를 구현했다. Flutter의 기존 경로·응답·상대 이미지 URL을 기준으로 이관했다.

Flyway V1은 Prisma 스키마에서 생성한 SQL이며 UUID 문자열·TEXT ID와 기존 업무 스키마를 유지한다. PostgreSQL 17 공유 개발 Compose는 DB 준비→v3 Flyway/health→v2 순서로 시작하고 Mailpit·공유 업로드 볼륨을 제공한다. v2와 Flutter의 승인된 기준 수정은 이미 main에 포함되어 있으며, 현재 브랜치의 main 대비 두 디렉터리 변경은 없다.

- JWT HS256·탈퇴/없는 회원 차단, bcrypt·리프레시/재설정 토큰의 원자적 소비, v2의 경로별 400/401/404/500 및 429 계약을 이관했다.
- LAZY 관계와 native projection, TransactionTemplate을 사용했다. SQL 수 회귀 검사를 두고 지도방 멤버 조회의 N+1을 측정 후 제거했다. 장소 쓰기와 마커 재집계의 부분 커밋 등 승인된 v2 동작은 유지했다.
- libvips 기반 이미지 변환·로컬 저장·파일 보상 처리를 공유한다. 장소 이미지의 정원과 동시 삭제/업로드는 조건부 원자적 UPDATE로 보강했다.
- 카카오 검색은 전용 HttpClient 5, 연결2초·소켓 무활동5초·재시도 없음·고정 오류 정책을 사용한다. FCM은 HTTP v1+Google 인증 라이브러리, 커밋 후 제한 실행기, 수신자 JOIN 조회와 재시도 정책을 적용했다. cleanup은 서울03시·인스턴스별 실행이다.
- Testcontainers 하네스는 원본 v2 컨테이너를 공유하고 두 DB를 같은 V1으로 만든다. 상태·JSON·DB 관계·파일·메일을 비교하며 연속 시나리오와 실제 동시 요청을 검증한다. 외부 서비스는 fail-closed 로컬 대역으로 대체한다.

## 호환성과 승인된 차이

알려진 차이는 **개선 6건·의도 4건·보류 0건**이다. 동시 지도방 가입 2건, 이미지 경쟁 3건, 발송 중 바뀐 FCM 토큰 보존 1건을 개선했다. 초대 코드 생성, 카카오 오류 노출·무활동 timeout, cleanup 시간대 4건은 의도된 정책 차이다. 과거 지도방·장소 알림 발송 보류 2건은 해소했다. [전체 ID·관측 결과](MIGRATION_SUMMARY.md).

마커 셀 누락/집계 경합, 비공개방을 나간 작성자의 쓰기 권한, 일부 중복 요청의 v2 오류 등은 호환 동작으로 남아 있다. 개선 완료로 주장하지 않으며 [통합 백로그](MIGRATION_SUMMARY.md)에서 별도 정책 결정·실험 대상으로 관리한다. PostGIS 공간 확장·성능 개선·다중 서버 잠금은 이 PR 범위가 아니다.

## 검증

9번 묶음 완료 기준 커밋 `8a139b7`에서 **전체 1,340건: 성공 1,339 / 실패 0 / skip 1**, 알려진 차이 10건, Spotless/build 통과. 빠른 `test`는 279건(278 성공·1 skip), v2 `comparisonTest`는 1,061건 모두 성공했다. Windows Java 21·Gradle 8.14.6·Docker Desktop, v2 이미지/도구 캐시 기준 전체 **885.749초**다. 이 문서 정리에서는 운영 변경이 없어 전체 회귀를 재실행하지 않고 문서의 링크·수치·범위를 검증한다.

skip은 Windows HEIC 단독 코덱의 v2·v3 공통 지원 제약 1건이다. 실제 HEIC 업로드의 HTTP·파일 결과는 필수 비교한다. AVIF·애니메이션 WebP는 libvips로 검증했다. 알려진 차이는 조용히 skip하는 항목이 아니다.

중간 전체 실행에는 DB 결과 검증용 연결에서 Windows TCP 4227/BindException 실패 1건이 있었다. 운영/하네스/OS 설정을 바꾸지 않고 해당31건(35.538초), 이후 위 전체 회귀가 통과했다. 환경 문제를 해결했다고 주장하지 않으며 [실패 기록과 한계](NOTIFICATIONS_DESIGN.md)에 남겼다.

재현(저장소 루트, PowerShell; Java 21·Node/npm·Docker):

```powershell
cd apps/server-v3
.\gradlew.bat test --rerun --console=plain
.\gradlew.bat comparisonTest --rerun --console=plain
# 전체 검증 + 포맷 검사 + 패키징
.\gradlew.bat build --rerun --console=plain
```

빠른 그룹에도 DB용 Docker가 필요할 수 있다. 실제 Firebase/FCM/Google OAuth·Kakao 호출과 Flutter 수동 화면 검증은 수행하지 않았다. OAuth는 v3 계약 테스트이며 v2 외부 API 비교로 계산하지 않는다. [Flutter 수동 체크리스트](FLUTTER_SMOKE_TEST.md)의 항목은 아직 미확인이다.

## 전환 시 확인 사항

- 실제 DB 연결 전 V1과 스키마를 비교한다. baselineOnMigrate 기본 false를 유지하고 검증된 기존 DB의 최초 편입만 별도로 처리한다.
- v2/v3 JWT_SECRET을 동일한 외부 설정으로 제공한다. 키·서비스 계정·DB 비밀번호를 저장소에 넣지 않는다.
- 공유 DB의 이미지 쓰기를 v2와 v3에 섞으면 v3 정합성 보장이 깨진다. 전환 시 쓰기 경로를 한쪽으로 모으고 기존 업로드 볼륨을 보존한다.
- FCM 비활성 로그·풀 포화 거절·메모리 큐 유실 한계, scheduler 활성 인스턴스 수와 시간대를 확인한다. 분산 잠금은 아직 없다.
- Flutter API/이미지 주소를 함께 변경한다. 웹은 현 코드에 별도 CORS 허용이 없어 동일 출처 프록시 절차가 필요하고, 네이티브 로컬 HTTP 정책·실제 외부 설정은 수동 확인한다.

## 리뷰 안내

[이관 요약](MIGRATION_SUMMARY.md)에 묶음별 API·회귀 시간·결정 번호와 설계 문서, 알려진 차이, 2/3단계 실험을 모았다. [비교 하네스](COMPARISON_TESTS.md)와 [시간 분석](TEST_PERFORMANCE.md)은 검증 방식을 설명한다. 현재 변경의 범위·실패 경계·예외가 의도대로 유지되는지 해당 묶음 설계와 테스트를 함께 검토하면 된다.
