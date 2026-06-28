INSERT INTO source (code, name, url, active, country_id)
VALUES ('REALT_ROOM', 'Realt.by (Room Rent)', 'https://realt.by', TRUE,
        (SELECT id FROM country WHERE code = 'BY'));
