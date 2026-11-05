INSERT INTO users(id,email,nickname,provider,"createdAt","updatedAt") VALUES
('11111111-1111-4111-8111-111111111111','compat@example.test','compat','LOCAL','2026-01-01','2026-01-01'),
('22222222-2222-4222-8222-222222222222','owner@example.test','owner','LOCAL','2026-01-01','2026-01-01'),
('33333333-3333-4333-8333-333333333333','member@example.test','member','LOCAL','2026-01-01','2026-01-01'),
('44444444-4444-4444-8444-444444444444','deleted@example.test','deleted','LOCAL','2026-01-01','2026-01-01');
UPDATE users SET "deletedAt"='2026-01-02' WHERE email='deleted@example.test';
INSERT INTO maps(id,"ownerId",title,tags,visibility,"inviteCode","createdAt","updatedAt")
SELECT 'aaaaaaaa-aaaa-4aaa-8aaa-'||lpad(n::text,12,'0'),CASE WHEN n=1 THEN '11111111-1111-4111-8111-111111111111' ELSE '22222222-2222-4222-8222-222222222222' END,
'room-'||n,ARRAY['Seoul'],CASE WHEN n IN(2,3) THEN 'PRIVATE'::"Visibility" ELSE 'PUBLIC'::"Visibility" END,
CASE WHEN n IN(2,3) THEN 'INVITE0'||n END,'2026-01-01','2026-01-01' FROM generate_series(1,4)n;
INSERT INTO map_members(id,"mapId","userId","createdAt")
SELECT 'bbbbbbbb-bbbb-4bbb-8bbb-'||lpad(n::text,12,'0'),'aaaaaaaa-aaaa-4aaa-8aaa-'||lpad(n::text,12,'0'),
'11111111-1111-4111-8111-111111111111','2026-01-01' FROM generate_series(1,2)n;
INSERT INTO places(id,"mapId","createdById",name,address,"roadAddress",latitude,longitude,tags,"imageUrls","createdAt","updatedAt")
SELECT 'cccccccc-cccc-4ccc-8ccc-'||lpad(n::text,12,'0'),
'aaaaaaaa-aaaa-4aaa-8aaa-'||lpad((CASE WHEN n<=4 THEN 1 WHEN n=5 THEN 4 WHEN n=6 THEN 2 ELSE 3 END)::text,12,'0'),
CASE WHEN n=4 THEN '22222222-2222-4222-8222-222222222222' ELSE '11111111-1111-4111-8111-111111111111' END,
CASE WHEN n IN(1,4) THEN 'Alpha' WHEN n=2 THEN 'Beta' WHEN n=3 THEN 'Gamma' ELSE 'place-'||n END,
'Seoul address',CASE WHEN n=1 THEN 'road cafe' END,37.4444,127.8888,
CASE WHEN n=1 THEN ARRAY['foo','bar'] WHEN n=2 THEN ARRAY['Foo'] ELSE ARRAY[]::text[] END,
ARRAY[]::text[],CASE WHEN n=5 THEN timestamp '2026-01-02 12:00' WHEN n IN(1,4) THEN timestamp '2026-01-03' WHEN n=2 THEN timestamp '2026-01-02' ELSE timestamp '2026-01-01' END,
'2026-01-01' FROM generate_series(1,7)n;
INSERT INTO place_reviews(id,"placeId","userId",rating,content,"createdAt","updatedAt") VALUES
('eeeeeeee-eeee-4eee-8eee-000000000001','cccccccc-cccc-4ccc-8ccc-000000000002','11111111-1111-4111-8111-111111111111',2,'review-one','2026-01-01','2026-01-01'),
('eeeeeeee-eeee-4eee-8eee-000000000002','cccccccc-cccc-4ccc-8ccc-000000000002','22222222-2222-4222-8222-222222222222',5,'review-two','2026-01-01','2026-01-01'),
('eeeeeeee-eeee-4eee-8eee-000000000003','cccccccc-cccc-4ccc-8ccc-000000000003','22222222-2222-4222-8222-222222222222',4,'review-three','2026-01-01','2026-01-01');
