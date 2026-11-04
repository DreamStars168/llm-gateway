-- quota_settle.lua
-- 原子结算：释放预扣并把实际消耗记账。返回:
--   1 = 结算成功
--   2 = 幂等命中（已结算）
--   3 = 无预扣记录（降级场景，直接按实际扣减）
--
-- KEYS[1] quota:{tenantId}:balance
-- KEYS[2] quota:{tenantId}:reserved
-- KEYS[3] reservation:{requestId}
-- ARGV[1] actual(micro-USD)
-- ARGV[2] usageSource (UPSTREAM|ESTIMATED)

local st = redis.call('HGET', KEYS[3], 'status')
if st == 'SETTLED' then
  return 2
end

local actual = tonumber(ARGV[1])

if st == false then
  -- 无预扣记录：直接扣减，允许为负（欠款）
  redis.call('INCRBY', KEYS[1], -actual)
  return 3
end

local est = tonumber(redis.call('HGET', KEYS[3], 'amount') or '0')
redis.call('INCRBY', KEYS[2], -est)
redis.call('INCRBY', KEYS[1], -actual)
redis.call('HSET', KEYS[3],
  'status', 'SETTLED',
  'actual', actual,
  'usage_source', ARGV[2])
return 1
