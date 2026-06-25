-- Seed data for the NFR-PERF-1 load test (issue #37). NOT a Flyway migration —
-- run manually against a disposable local/staging database only, never production.
--
-- Usage:
--   docker compose -f docker/docker-compose.yml up -d
--   ./gradlew bootRun --args='--spring.profiles.active=local'   # applies Flyway migrations once
--   psql "postgresql://flatio:flatio_local@localhost:5432/flatio" -f scripts/load-test/seed-listings.sql
--
-- Cleanup after the run (the default docker-compose.yml volume is a persistent local dev DB,
-- not a throwaway one — do not leave synthetic rows in it):
--   DELETE FROM listings WHERE external_id LIKE 'load-test-%';

INSERT INTO listings (
  external_id, source_id, title, deal_type, property_type, price, currency_id,
  rooms, area_total_m2, country_id, city, status, source_url, published_at
)
SELECT
  'load-test-' || gs,
  (SELECT id FROM source WHERE code = 'ONLINER'),
  'Load test listing ' || gs,
  'RENT',
  (ARRAY['APARTMENT', 'HOUSE', 'ROOM'])[1 + (gs % 3)],
  500 + (gs % 50) * 30,
  (SELECT id FROM currency WHERE code = 'BYN'),
  1 + (gs % 4),
  35 + (gs % 80),
  (SELECT id FROM country WHERE code = 'BY'),
  (ARRAY['Минск', 'Гомель', 'Брест', 'Витебск'])[1 + (gs % 4)],
  'ACTIVE',
  'https://onliner.by/load-test/' || gs,
  NOW() - (gs % 90 || ' days')::interval
FROM generate_series(1, 1200) AS gs
ON CONFLICT (external_id, source_id) DO NOTHING;
