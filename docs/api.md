# LOCO API 문서

Base URL: `http://localhost:3000/api`

인증이 필요한 API는 `Authorization: Bearer <access_token>` 헤더 필요

---

## Auth

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| POST | `/auth/register` | 회원가입 (JWT 발급) | ✗ |
| POST | `/auth/login` | 로그인 (JWT 발급) | ✗ |

### POST /auth/register
```json
// Request
{ "email": "user@example.com", "password": "password123", "nickname": "로코유저" }

// Response
{ "accessToken": "eyJ..." }
```

### POST /auth/login
```json
// Request
{ "email": "user@example.com", "password": "password123" }

// Response
{ "accessToken": "eyJ..." }
```

---

## Users

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| GET | `/users/me` | 내 정보 조회 | ✓ |
| PUT | `/users/me` | 내 정보 수정 | ✓ |
| DELETE | `/users/me` | 회원 탈퇴 (soft delete) | ✓ |

---

## Maps

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| GET | `/maps` | 지도 목록 조회 | ✓ |
| POST | `/maps` | 지도 생성 | ✓ |
| GET | `/maps/:mapId` | 지도 상세 조회 | ✓ |
| PUT | `/maps/:mapId` | 지도 수정 | ✓ |
| DELETE | `/maps/:mapId` | 지도 삭제 | ✓ |

### POST /maps
```json
// Request
{
  "title": "서울 맛집 지도",
  "description": "내가 찜한 서울 맛집들",
  "visibility": "PUBLIC"
}

// visibility: "PUBLIC" | "PRIVATE"
// PRIVATE일 때 inviteCode 자동 생성
```

---

## Places

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| GET | `/places/search?query=을지로&x=126.97&y=37.56` | 카카오 장소 검색 | ✓ |
| GET | `/maps/:mapId/places` | 지도별 장소 목록 | ✓ |
| POST | `/maps/:mapId/places` | 장소 추가 | ✓ |
| PUT | `/places/:placeId` | 장소 수정 | ✓ |
| DELETE | `/places/:placeId` | 장소 삭제 | ✓ |

### POST /maps/:mapId/places
```json
// Request
{
  "name": "을지로 노가리 골목",
  "address": "서울 중구 을지로",
  "roadAddress": "서울 중구 을지로 119",
  "latitude": 37.5665,
  "longitude": 126.9780,
  "category": "음식점",
  "memo": "노가리랑 맥주가 최고",
  "tags": ["맥주", "노가리", "을지로"]
}
```

---

## Favorites

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| GET | `/favorites` | 내 즐겨찾기 목록 | ✓ |
| POST | `/favorites` | 즐겨찾기 추가 | ✓ |
| DELETE | `/favorites/:favoriteId` | 즐겨찾기 삭제 | ✓ |

---

## Feedback

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| POST | `/feedback` | 피드백 제출 | ✓ |

---

## Reports

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| POST | `/reports` | 신고 접수 | ✓ |

### POST /reports
```json
// Request
{
  "targetType": "PLACE",
  "targetId": "uuid",
  "reason": "부적절한 내용",
  "detail": "상세 사유..."
}
// targetType: "MAP" | "PLACE" | "USER"
```
