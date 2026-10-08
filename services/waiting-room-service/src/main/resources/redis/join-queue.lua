-- Admits the caller straight away while the room has space and nobody is waiting; otherwise puts
-- them at the back of the queue. Joining again changes nothing, so refreshing never loses a place.
-- KEYS: active (zset user -> admission expiry ms), queue (zset user -> arrival number), sequence
-- ARGV: user, now ms, capacity, admission ttl ms
-- Returns {1, expiry ms} when admitted, {0, position} when queued.
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[2])
local admittedUntil = redis.call('ZSCORE', KEYS[1], ARGV[1])
if admittedUntil then
  return {1, tonumber(admittedUntil)}
end
local rank = redis.call('ZRANK', KEYS[2], ARGV[1])
if rank then
  return {0, rank + 1}
end
if redis.call('ZCARD', KEYS[2]) == 0 and redis.call('ZCARD', KEYS[1]) < tonumber(ARGV[3]) then
  local expiry = tonumber(ARGV[2]) + tonumber(ARGV[4])
  redis.call('ZADD', KEYS[1], expiry, ARGV[1])
  return {1, expiry}
end
redis.call('ZADD', KEYS[2], redis.call('INCR', KEYS[3]), ARGV[1])
return {0, redis.call('ZRANK', KEYS[2], ARGV[1]) + 1}
