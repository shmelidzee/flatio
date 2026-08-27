CREATE TABLE exchange_rates (
  id               BIGSERIAL PRIMARY KEY,
  base_currency    VARCHAR(10)              NOT NULL,
  target_currency  VARCHAR(10)              NOT NULL,
  rate             NUMERIC(15, 6)           NOT NULL,
  effective_date   DATE                     NOT NULL,
  created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_exchange_rates_base_target_date UNIQUE (base_currency, target_currency, effective_date)
);
