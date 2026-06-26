INSERT INTO source (code, name, url, active, country_id)
VALUES ('REALT', 'Realt.by', 'https://realt.by', TRUE,
        (SELECT id FROM country WHERE code = 'BY'));
