UPDATE listings
SET source_url = REPLACE(source_url, '/sale-room/object/', '/sale-rooms/object/')
WHERE source_id = (SELECT id FROM source WHERE code = 'REALT_ROOM_SALE')
  AND source_url LIKE '%/sale-room/object/%';
