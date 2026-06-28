INSERT INTO source (code, name, url, active, country_id)
VALUES ('REALT_HOUSE_RENT', 'Realt.by (House Rent)', 'https://realt.by', TRUE,
        (SELECT id FROM country WHERE code = 'BY'));
