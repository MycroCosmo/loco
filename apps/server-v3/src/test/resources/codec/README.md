# 코덱 입력 fixture

외부 사진을 복사하지 않고 이 저장소에서 만든 64×48 RGB 사분면 패턴이다.
좌상/우상/좌하/우하 색은 (240,32,32), (32,224,32), (32,32,240), (240,224,32)다.
파일 SHA-256은 `v2-observation.json`에 있으며 Java 검증은 관측한 바이트와 같은지 먼저 확인한다.

- JPEG: sharp 0.33.5, quality 95.
- PNG·WebP·AVIF: 같은 sharp, lossless 출력.
- HEIC: 위 PNG를 libheif-examples 1.15.1-1+deb12u1, libx265 3.5-2+b1로 인코딩.
  `heif-enc -L quadrants.png -o quadrants.heic`
- `heif-info`에서 MIME image/heic, brand heic, 64×48 확인.
- 독립 도구 `heif-convert quadrants.heic /tmp/proof.png`가 성공해 유효한 입력임을 확인했다.
- v2 관측은 sharp의 metadata뿐 아니라 전체 raw 디코딩과 색상 검사를 수행했다.
  HEIC는 메타데이터의 compression=hevc까지 읽지만 픽셀 디코딩은 실패한다.

생성·관측 코드는 `apps/server-v3/scripts/codec-fixtures.cjs`에 있다.
이 스크립트는 v2 이미지의 `/app/node_modules/sharp`를 사용한다.
`generate <디렉터리>`는 HEIC를 제외한 4종을 생성하고,
`observe <디렉터리>`는 5종의 버전·해시·디코딩 결과를 출력한다.
일반 테스트는 이미 커밋된 fixture를 사용하므로 Node나 HEIC 생성 도구를 설치할 필요가 없다.

현재 RGB 허용 오차 24는 압축 손실이 있는 합성 이미지의 네 지점을 확인하기 위한 값이다.
실제 사진의 크롭·화질 동등성이나 모든 HEIC/AVIF 변형의 지원을 증명하는 기준은 아니다.
