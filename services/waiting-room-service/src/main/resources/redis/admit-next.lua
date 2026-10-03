-- Moves the longest-waiting users into the room, as many as there are free places, in arrival order.
-- KEYS: active, queue. ARGV: now ms, capacity, admission ttl ms. Returns how many were admitted.
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
local free = tonumber(ARGV[2]) - redis.call('ZCARD', KEYS[1])
if free <= 0 then
  return 0
end
local next = redis.call('ZPOPMIN', KEYS[2], free)
local expiry = tonumber(ARGV[1]) + tonumber(ARGV[3])
for i = 1, #next, 2 do
  redis.call('ZADD', KEYS[1], expiry, next[i])
end
return #next / 2
