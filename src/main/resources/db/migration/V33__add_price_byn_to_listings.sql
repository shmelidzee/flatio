-- Stores the BYN equivalent of the listing price for sources that publish in USD (e.g. Realt.by).
-- Null for sources that already store prices in BYN (Onliner, Kufar).
-- Price filter uses COALESCE(price_byn, price) so the filter always operates in BYN.
ALTER TABLE listings ADD COLUMN price_byn NUMERIC(15, 2);
