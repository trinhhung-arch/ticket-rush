-- Read-only view of one user. KEYS: active, queue. ARGV: user, now ms.
-- Returns {1, expiry ms} when admitted, {0, position} when queued, {-1, 0} when neither.
local admittedUntil = redis.call('ZSCORE', KEYS[1], ARGV[1])
if admittedUntil and tonumber(admittedUntil) > tonumber(ARGV[2]) then
  return {1, tonumber(admittedUntil)}
end
local rank = redis.call('ZRANK', KEYS[2], ARGV[1])
if rank then
  return {0, rank + 1}
end
return {-1, 0}
