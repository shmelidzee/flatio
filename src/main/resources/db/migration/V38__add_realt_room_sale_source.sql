INSERT INTO source (code, name, url, active, country_id)
VALUES ('REALT_ROOM_SALE', 'Realt.by (Room Sale)', 'https://realt.by', TRUE,
        (SELECT id FROM country WHERE code = 'BY'));
