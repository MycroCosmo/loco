UPDATE place_reviews SET "updatedAt"='2026-01-02' WHERE content='review-two';
INSERT INTO place_reviews(id,"placeId","userId",rating,content,"createdAt","updatedAt") VALUES
('eeeeeeee-eeee-4eee-8eee-000000000004','cccccccc-cccc-4ccc-8ccc-000000000005','11111111-1111-4111-8111-111111111111',3,'public former member','2026-01-01','2026-01-03'),
('eeeeeeee-eeee-4eee-8eee-000000000005','cccccccc-cccc-4ccc-8ccc-000000000006','11111111-1111-4111-8111-111111111111',4,'private member','2026-01-01','2026-01-04'),
('eeeeeeee-eeee-4eee-8eee-000000000006','cccccccc-cccc-4ccc-8ccc-000000000007','11111111-1111-4111-8111-111111111111',5,'private former member','2026-01-01','2026-01-05');
