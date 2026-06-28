INSERT INTO source (code, name, url, active, country_id)
VALUES ('REALT_HOUSE_SALE', 'Realt.by (House Sale)', 'https://realt.by', TRUE,
        (SELECT id FROM country WHERE code = 'BY'));
