-- Holds every seat for one booking, or none of them.
-- KEYS: one hold key per seat. ARGV[1]: booking id. ARGV[2]: time to live in milliseconds.
-- Returns 0 when all seats are held, otherwise the 1-based position of the first seat someone else holds.
-- Redis runs a script without interleaving other commands, so check-then-set cannot race.
for i, key in ipairs(KEYS) do
  local owner = redis.call('GET', key)
  if owner and owner ~= ARGV[1] then
    return i
  end
end
for _, key in ipairs(KEYS) do
  redis.call('SET', key, ARGV[1], 'PX', ARGV[2])
end
return 0
