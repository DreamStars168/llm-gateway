-- quota_reserve.lua
-- 原子预扣。返回:
--   1 = 预扣成功
--   0 = 余额不足
--   2 = 幂等命中（该 requestId 已预扣过）
--
-- KEYS[1] quota:{tenantId}:balance
-- KEYS[2] quota:{tenantId}:reserved
-- KEYS[3] reservation:{requestId}
-- ARGV[1] amount(micro-USD)
-- ARGV[2] ttlSeconds
-- ARGV[3] tenantId
-- ARGV[4] apiKeyId
-- ARGV[5] nowMillis

if redis.call('EXISTS', KEYS[3]) == 1 then
  return 2
end

local bal = tonumber(redis.call('GET', KEYS[1]) or '0')
local res = tonumber(redis.call('GET', KEYS[2]) or '0')
local amt = tonumber(ARGV[1])

if bal - res < amt then
  return 0
end

redis.call('INCRBY', KEYS[2], amt)
redis.call('HSET', KEYS[3],
  'tenant', ARGV[3],
  'key', ARGV[4],
  'amount', amt,
  'status', 'RESERVED',
  'ts', ARGV[5])
redis.call('EXPIRE', KEYS[3], ARGV[2])
return 1
