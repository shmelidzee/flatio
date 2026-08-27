CREATE INDEX idx_exchange_rates_base_target_date ON exchange_rates (base_currency, target_currency, effective_date DESC);
