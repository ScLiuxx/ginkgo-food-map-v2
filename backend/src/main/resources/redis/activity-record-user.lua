-- 仅定义活跃集合写入；调用方负责登录鉴权、管理员排除、北京时间日期及启用边界。
-- KEYS[1]: foodmap:activity:users:YYYY-MM-DD
-- ARGV[1]: 账号ID的十进制字符串；ARGV[2]: 统计日期加90天零点的Unix秒。
-- 返回1表示当天首次记录，0表示已记录，-1表示保留期已结束；不刷新为滑动TTL。
if #KEYS ~= 1 or #ARGV ~= 2 then
    return redis.error_reply('ACTIVITY_INVALID_ARGUMENTS')
end

local userId = ARGV[1]
local expiresAt = tonumber(ARGV[2])
if not string.match(userId, '^[1-9][0-9]*$') or #userId > 19
        or (#userId == 19 and userId > '9223372036854775807')
        or not string.match(ARGV[2], '^[1-9][0-9]*$')
        or not expiresAt or expiresAt ~= math.floor(expiresAt)
        or expiresAt <= 0 or expiresAt > 253402300799 then
    return redis.error_reply('ACTIVITY_INVALID_ARGUMENTS')
end

if expiresAt <= tonumber(redis.call('TIME')[1]) then
    return -1
end

-- 原子设置集合及绝对到期时间，避免SADD成功而设置TTL前中断留下永久账号集合。
local added = redis.call('SADD', KEYS[1], userId)
redis.call('EXPIREAT', KEYS[1], ARGV[2])
return added
