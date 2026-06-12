CREATE TABLE cities (
  id         BIGSERIAL    PRIMARY KEY,
  name_ru    VARCHAR(255) NOT NULL,
  name_be    VARCHAR(255),
  name_en    VARCHAR(255),
  country_id BIGINT       NOT NULL REFERENCES country (id),
  latitude   DECIMAL(9, 6),
  longitude  DECIMAL(9, 6),
  created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE city_aliases (
  id        BIGSERIAL    PRIMARY KEY,
  city_id   BIGINT       NOT NULL REFERENCES cities (id),
  alias     VARCHAR(255) NOT NULL,
  source_id VARCHAR(100) NOT NULL
);
