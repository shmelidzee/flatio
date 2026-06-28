INSERT INTO source (code, name, url, active, country_id)
VALUES
    ('KUFAR_APARTMENT_RENT', 'Kufar.by (Apartment Rent)', 'https://www.kufar.by', TRUE,
     (SELECT id FROM country WHERE code = 'BY')),
    ('KUFAR_APARTMENT_SALE', 'Kufar.by (Apartment Sale)', 'https://www.kufar.by', TRUE,
     (SELECT id FROM country WHERE code = 'BY')),
    ('KUFAR_ROOM_RENT', 'Kufar.by (Room Rent)', 'https://www.kufar.by', TRUE,
     (SELECT id FROM country WHERE code = 'BY')),
    ('KUFAR_ROOM_SALE', 'Kufar.by (Room Sale)', 'https://www.kufar.by', TRUE,
     (SELECT id FROM country WHERE code = 'BY')),
    ('KUFAR_HOUSE_RENT', 'Kufar.by (House Rent)', 'https://www.kufar.by', TRUE,
     (SELECT id FROM country WHERE code = 'BY')),
    ('KUFAR_HOUSE_SALE', 'Kufar.by (House Sale)', 'https://www.kufar.by', TRUE,
     (SELECT id FROM country WHERE code = 'BY'));
