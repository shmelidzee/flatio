INSERT INTO source (code, name, url, active, country_id)
VALUES ('ONLINER_SALE', 'Onliner (Продажа)', 'https://pk.onliner.by', TRUE,
        (SELECT id FROM country WHERE code = 'BY'));
