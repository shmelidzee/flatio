UPDATE listings
SET source_url = REPLACE(source_url, '/rent-room-for-long/object/', '/rent-rooms-for-long/object/')
WHERE source_id = (SELECT id FROM source WHERE code = 'REALT_ROOM')
  AND source_url LIKE '%/rent-room-for-long/object/%';
