-- queue_enter.lua
-- KEYS[1] = queue:{scheduleId}, ARGV[1] = maxQueueSize, ARGV[2] = userId, ARGV[3] = score
local queueKey = KEYS[1]
local maxQueueSize = tonumber(ARGV[1])
local userId = ARGV[2]
local score = tonumber(ARGV[3])

if redis.call('ZCARD', queueKey) >= maxQueueSize then
    return 0
end

redis.call('ZADD', queueKey, score, userId)
return 1
