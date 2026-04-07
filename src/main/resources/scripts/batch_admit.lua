-- batch_admit.lua
-- KEYS[1] = active:{scheduleId}, KEYS[2] = queue:{scheduleId}
-- ARGV[1] = maxActiveUsers, ARGV[2] = batchSize, ARGV[3] = now, ARGV[4] = heartbeatTimeout

local activeKey = KEYS[1]
local queueKey = KEYS[2]
local maxActive = tonumber(ARGV[1])
local batchSize = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local timeout = tonumber(ARGV[4])

redis.call('ZREMRANGEBYSCORE', activeKey, '-inf', now - timeout)

local currentActive = redis.call('ZCARD', activeKey)
local available = maxActive - currentActive
if available <= 0 then
    return cjson.encode({admitted = {}, activeCount = currentActive, queueSize = redis.call('ZCARD', queueKey)})
end

local toAdmit = math.min(available, batchSize)
local candidates = redis.call('ZRANGE', queueKey, 0, toAdmit - 1)

if #candidates == 0 then
    return cjson.encode({admitted = {}, activeCount = currentActive, queueSize = redis.call('ZCARD', queueKey)})
end

for _, userId in ipairs(candidates) do
    redis.call('ZADD', activeKey, now, userId)
end
redis.call('ZREMRANGEBYRANK', queueKey, 0, #candidates - 1)

return cjson.encode({
    admitted = candidates,
    activeCount = currentActive + #candidates,
    queueSize = redis.call('ZCARD', queueKey)
})
