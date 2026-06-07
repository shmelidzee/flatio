INSERT INTO country (code, name) VALUES
  ('BY', 'Belarus');

INSERT INTO currency (code, symbol) VALUES
  ('BYN', 'Br'),
  ('USD', '$'),
  ('EUR', '€');

INSERT INTO source (code, name, url, active, country_id) VALUES
  ('ONLINER', 'Onliner', 'https://onliner.by', TRUE, (SELECT id FROM country WHERE code = 'BY'));
