-- Releases the holds of one booking. A key is deleted only if this booking still owns it, so a hold
-- that expired and was taken by another customer is left alone.
-- KEYS: one hold key per seat. ARGV[1]: booking id. Returns the number of holds released.
local released = 0
for _, key in ipairs(KEYS) do
  if redis.call('GET', key) == ARGV[1] then
    redis.call('DEL', key)
    released = released + 1
  end
end
return released
