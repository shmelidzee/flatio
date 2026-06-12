-- Seed data: major cities of Belarus with Russian canonical names,
-- Belarusian names, English names and approximate coordinates.
-- country_id resolved by code to avoid hardcoded values.

INSERT INTO cities (name_ru, name_be, name_en, country_id, latitude, longitude) VALUES
  ('Минск',   'Мінск',   'Minsk',   (SELECT id FROM country WHERE code = 'BY'), 53.904841, 27.561523),
  ('Брест',   'Брэст',   'Brest',   (SELECT id FROM country WHERE code = 'BY'), 52.097600, 23.734400),
  ('Гродно',  'Гродна',  'Grodno',  (SELECT id FROM country WHERE code = 'BY'), 53.677700, 23.829700),
  ('Гомель',  'Гомель',  'Gomel',   (SELECT id FROM country WHERE code = 'BY'), 52.441400, 30.987100),
  ('Витебск', 'Віцебск', 'Vitebsk', (SELECT id FROM country WHERE code = 'BY'), 55.184400, 30.202800),
  ('Могилёв', 'Магілёў', 'Mogilev', (SELECT id FROM country WHERE code = 'BY'), 53.906600, 30.332500);

-- Aliases: Belarusian name variants used by external sources
INSERT INTO city_aliases (city_id, alias, source_id) VALUES
  -- Минск
  ((SELECT id FROM cities WHERE name_ru = 'Минск'), 'Минск',   'realt'),
  ((SELECT id FROM cities WHERE name_ru = 'Минск'), 'Минск',   'onliner'),
  ((SELECT id FROM cities WHERE name_ru = 'Минск'), 'Минск',   'kufar'),
  ((SELECT id FROM cities WHERE name_ru = 'Минск'), 'Мінск',   'realt'),
  ((SELECT id FROM cities WHERE name_ru = 'Минск'), 'Мінск',   'onliner'),
  ((SELECT id FROM cities WHERE name_ru = 'Минск'), 'Мінск',   'kufar'),
  -- Брест
  ((SELECT id FROM cities WHERE name_ru = 'Брест'), 'Брест',   'realt'),
  ((SELECT id FROM cities WHERE name_ru = 'Брест'), 'Брест',   'onliner'),
  ((SELECT id FROM cities WHERE name_ru = 'Брест'), 'Брест',   'kufar'),
  ((SELECT id FROM cities WHERE name_ru = 'Брест'), 'Брэст',   'realt'),
  ((SELECT id FROM cities WHERE name_ru = 'Брест'), 'Брэст',   'onliner'),
  ((SELECT id FROM cities WHERE name_ru = 'Брест'), 'Брэст',   'kufar'),
  -- Гродно
  ((SELECT id FROM cities WHERE name_ru = 'Гродно'), 'Гродно',  'realt'),
  ((SELECT id FROM cities WHERE name_ru = 'Гродно'), 'Гродно',  'onliner'),
  ((SELECT id FROM cities WHERE name_ru = 'Гродно'), 'Гродно',  'kufar'),
  ((SELECT id FROM cities WHERE name_ru = 'Гродно'), 'Гродна',  'realt'),
  ((SELECT id FROM cities WHERE name_ru = 'Гродно'), 'Гродна',  'onliner'),
  ((SELECT id FROM cities WHERE name_ru = 'Гродно'), 'Гродна',  'kufar'),
  -- Гомель
  ((SELECT id FROM cities WHERE name_ru = 'Гомель'), 'Гомель',  'realt'),
  ((SELECT id FROM cities WHERE name_ru = 'Гомель'), 'Гомель',  'onliner'),
  ((SELECT id FROM cities WHERE name_ru = 'Гомель'), 'Гомель',  'kufar'),
  -- Витебск
  ((SELECT id FROM cities WHERE name_ru = 'Витебск'), 'Витебск', 'realt'),
  ((SELECT id FROM cities WHERE name_ru = 'Витебск'), 'Витебск', 'onliner'),
  ((SELECT id FROM cities WHERE name_ru = 'Витебск'), 'Витебск', 'kufar'),
  ((SELECT id FROM cities WHERE name_ru = 'Витебск'), 'Віцебск', 'realt'),
  ((SELECT id FROM cities WHERE name_ru = 'Витебск'), 'Віцебск', 'onliner'),
  ((SELECT id FROM cities WHERE name_ru = 'Витебск'), 'Віцебск', 'kufar'),
  -- Могилёв
  ((SELECT id FROM cities WHERE name_ru = 'Могилёв'), 'Могилев', 'realt'),
  ((SELECT id FROM cities WHERE name_ru = 'Могилёв'), 'Могилев', 'onliner'),
  ((SELECT id FROM cities WHERE name_ru = 'Могилёв'), 'Могилев', 'kufar'),
  ((SELECT id FROM cities WHERE name_ru = 'Могилёв'), 'Магілёў', 'realt'),
  ((SELECT id FROM cities WHERE name_ru = 'Могилёв'), 'Магілёў', 'onliner'),
  ((SELECT id FROM cities WHERE name_ru = 'Могилёв'), 'Магілёў', 'kufar');
