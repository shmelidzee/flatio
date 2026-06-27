INSERT INTO source (code, name, url, active, country_id)
VALUES ('REALT_SALE', 'Realt.by (Sale)', 'https://realt.by', TRUE,
        (SELECT id FROM country WHERE code = 'BY'));
