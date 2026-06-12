ALTER TABLE city_aliases
  ADD CONSTRAINT uq_city_alias_source UNIQUE (alias, source_id);
