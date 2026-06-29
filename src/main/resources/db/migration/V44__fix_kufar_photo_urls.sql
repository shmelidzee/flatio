-- One-time backfill: prepend the Kufar CDN base URL to photo_url values that are
-- relative paths (e.g. "adim1/{uuid}.jpg") stored before this fix was applied.
-- Applies only to Kufar source listings where photo_url does not already start with "https://".
-- Future fetches will produce full URLs directly via KufarApiClient.extractPhotoUrls().
UPDATE listings
SET
    photo_url  = 'https://rms.kufar.by/v1/gallery/' || photo_url,
    updated_at = NOW()
WHERE source_id IN (SELECT id FROM source WHERE code LIKE 'KUFAR_%')
  AND photo_url IS NOT NULL
  AND photo_url NOT LIKE 'http%';
