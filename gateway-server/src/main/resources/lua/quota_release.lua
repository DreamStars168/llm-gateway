-- quota_release.lua
-- 释放预扣（上游失败 / 客户端取消且无任何输出）。返回:
--   1 = 释放成功
--   2 = 已结算或已释放（幂等）
--   3 = 无预扣记录（无需处理）
--
-- KEYS[1] quota:{tenantId}:reserved
-- KEYS[2] reservation:{requestId}

local st = redis.call('HGET', KEYS[2], 'status')
if st == 'SETTLED' or st == 'RELEASED' then
  return 2
end
if st == false then
  return 3
end

local est = tonumber(redis.call('HGET', KEYS[2], 'amount') or '0')
redis.call('INCRBY', KEYS[1], -est)
redis.call('HSET', KEYS[2], 'status', 'RELEASED')
return 1
