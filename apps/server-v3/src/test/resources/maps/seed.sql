INSERT INTO users (id,email,nickname,provider,"createdAt","updatedAt") VALUES
('11111111-1111-4111-8111-111111111111','compat@example.test','compat','LOCAL','2026-01-01','2026-01-01'),
('22222222-2222-4222-8222-222222222222','owner@example.test','owner','LOCAL','2026-01-01','2026-01-01'),
('33333333-3333-4333-8333-333333333333','member@example.test','member','LOCAL','2026-01-01','2026-01-01'),
('44444444-4444-4444-8444-444444444444','deleted@example.test','deleted','LOCAL','2026-01-01','2026-01-01');
UPDATE users SET "deletedAt"='2026-01-02' WHERE email='deleted@example.test';
INSERT INTO maps (id,"ownerId",title,description,tags,visibility,"inviteCode","createdAt","updatedAt")
SELECT 'aaaaaaaa-aaaa-4aaa-8aaa-00000000000'||n,
CASE WHEN n<=2 THEN '11111111-1111-4111-8111-111111111111' ELSE '22222222-2222-4222-8222-222222222222' END,
'room-'||n, NULL, ARRAY['Seoul','coffee shop'],
CASE WHEN n IN (2,4,5) THEN 'PRIVATE'::"Visibility" ELSE 'PUBLIC'::"Visibility" END,
CASE WHEN n IN (2,4,5) THEN 'INVITE0'||n ELSE NULL END,
CASE WHEN n IN (1,3) THEN timestamp '2026-01-02' ELSE timestamp '2026-01-01' + n * interval '1 day' END,
timestamp '2026-01-01'
FROM generate_series(1,6) n;
INSERT INTO map_members (id,"mapId","userId","createdAt")
SELECT 'bbbbbbbb-bbbb-4bbb-8bbb-00000000000'||right(id,1),id,"ownerId",'2026-01-01' FROM maps;
INSERT INTO map_members (id,"mapId","userId","createdAt") VALUES
('bbbbbbbb-bbbb-4bbb-8bbb-000000000007','aaaaaaaa-aaaa-4aaa-8aaa-000000000004','11111111-1111-4111-8111-111111111111','2026-01-02'),
('bbbbbbbb-bbbb-4bbb-8bbb-000000000008','aaaaaaaa-aaaa-4aaa-8aaa-000000000006','33333333-3333-4333-8333-333333333333','2026-01-02'),
('bbbbbbbb-bbbb-4bbb-8bbb-000000000009','aaaaaaaa-aaaa-4aaa-8aaa-000000000006','44444444-4444-4444-8444-444444444444','2026-01-03');
INSERT INTO places (id,"mapId","createdById",name,address,latitude,longitude,tags,"imageUrls","createdAt","updatedAt")
SELECT 'cccccccc-cccc-4ccc-8ccc-00000000000'||n,
CASE WHEN n=1 THEN 'aaaaaaaa-aaaa-4aaa-8aaa-000000000001' ELSE 'aaaaaaaa-aaaa-4aaa-8aaa-000000000006' END,
'11111111-1111-4111-8111-111111111111','place-'||n,'address',37,127,ARRAY[]::text[],ARRAY[]::text[],'2026-01-01','2026-01-01'
FROM generate_series(1,3) n;
INSERT INTO favorites (id,"mapId","userId","createdAt") VALUES
('dddddddd-dddd-4ddd-8ddd-000000000001','aaaaaaaa-aaaa-4aaa-8aaa-000000000003','11111111-1111-4111-8111-111111111111','2026-01-01'),
('dddddddd-dddd-4ddd-8ddd-000000000002','aaaaaaaa-aaaa-4aaa-8aaa-000000000006','11111111-1111-4111-8111-111111111111','2026-01-01'),
('dddddddd-dddd-4ddd-8ddd-000000000003','aaaaaaaa-aaaa-4aaa-8aaa-000000000006','33333333-3333-4333-8333-333333333333','2026-01-01');
