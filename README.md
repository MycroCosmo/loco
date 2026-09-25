# LOCO

LOCO is a map-based place sharing service for collecting and sharing restaurants, cafés, and places with a group.

The current implementation is a personal rebuild using **Flutter + NestJS + PostgreSQL/Prisma**. The project focuses on backend state management, authorization, token lifecycle, and map-query behavior rather than only CRUD screens.

## Architecture

```text
Flutter app
   │
   ▼
NestJS REST API
   │
   ├── Auth / OAuth / token rotation
   ├── Maps / membership / invitations
   ├── Places / reviews / favorites
   ├── Marker query / clustering
   ├── Image processing
   └── FCM notifications
   │
   ▼
PostgreSQL (Prisma)
```

## Tech stack

| Area | Technology |
|---|---|
| Mobile | Flutter |
| Backend | NestJS, TypeScript |
| Database | PostgreSQL |
| ORM | Prisma |
| Authentication | JWT, bcrypt, OAuth |
| Map search | Kakao APIs |
| Image processing | Sharp, WebP |
| Push | Firebase Cloud Messaging |
| Tests | Jest / Nest testing tools |

## Backend decisions

### Single-use refresh-token rotation

Refresh tokens are stored as hashes rather than raw token values.

When a refresh request succeeds, the server updates the existing token only when `revokedAt IS NULL`. The next token is issued inside the same transaction.

This means two concurrent requests cannot both consume the same refresh token successfully.

```text
refresh request
  → hash token
  → validate user/token state
  → UPDATE ... WHERE revokedAt IS NULL
  → only the successful request creates the next refresh token
```

Password reset and account-state changes also revoke active refresh tokens so an old session cannot continue only because an access token still exists.

### Authorization is modeled separately from visibility

A map can be public or private, but write/read decisions also depend on ownership and membership.

Examples:

- public map: readable according to public rules;
- private map: owner/member only;
- invite codes: used only for private-map membership;
- editing/deleting: checked independently from read access.

This avoids treating “can view” and “can modify” as the same permission.

### Place list and map markers use different query strategies

A place list and a map viewport have different access patterns.

The list API uses cursor pagination, search/tag filters, and batched review summaries.

Marker requests are bounded by the current viewport and zoom level. For larger result sets, the server switches to clustered marker cells instead of returning every place.

The data model keeps precomputed marker-cell rows at multiple precisions to limit map payload size.

### File/DB failure paths are handled explicitly

Image replacement is not treated as a single happy-path operation.

For example, when a new cover image is stored, a failed database update removes the newly created file. The previous image is removed only after the database update succeeds.

## Main domain model

```text
User
 ├── RefreshToken
 ├── PasswordResetToken
 ├── Map (owner)
 ├── MapMember
 ├── Place
 ├── PlaceReview
 └── Favorite

Map
 ├── MapMember
 ├── Place
 └── PlaceMarkerCell
```

Important constraints include:

- unique membership per `(mapId, userId)`;
- unique review per `(placeId, userId)`;
- unique favorite per `(mapId, userId)`;
- hashed refresh/reset tokens with explicit expiration/revocation state.

## Main features

- Email registration/login and password reset
- Kakao/Naver OAuth flow
- Access/refresh-token authentication
- Public/private map rooms
- Invite-code membership
- Place creation, search, tagging, and image upload
- Reviews and average-rating summaries
- Favorites
- Viewport-aware markers and clustering
- FCM notifications
- Account deletion and session invalidation

## Repository structure

```text
loco/
├── apps/
│   ├── mobile/        # Flutter client
│   └── server/        # NestJS API
│       ├── src/
│       │   ├── auth/
│       │   ├── maps/
│       │   ├── places/
│       │   ├── users/
│       │   ├── notifications/
│       │   └── tasks/
│       └── prisma/
│           └── schema.prisma
└── README.md
```

## Local setup

### Server

```bash
cd apps/server
npm install
cp .env.example .env
npx prisma migrate dev
npm run start:dev
```

Required environment values are documented in `apps/server/.env.example`.

### Flutter

```bash
cd apps/mobile
flutter pub get
flutter run
```

For Android emulator development, the default server address can use `10.0.2.2`. Production endpoints are passed with `--dart-define`.

## Security notes

- Real `.env` files are ignored.
- JWT/SMTP/OAuth/Firebase credentials are supplied through environment variables.
- Passwords are hashed with bcrypt.
- The server includes throttling and HTTP security middleware.
- Repository examples contain placeholders only; production credentials must never be committed.
