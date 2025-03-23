local voucherId = ARGV[1]
--1.用户id
local userId = ARGV[2]
--2.订单id
local orderId = ARGV[3]
--3.库存key
local stockKey = 'seckill:stock:'.. voucherId

local orderKey = 'seckill:order:'.. voucherId

if (tonumber(redis.call('get',stockKey))<=0) then
    return 1
end
if (redis.call('sismember',orderKey,userId)) then
    return 2
end

redis.call('incrby',stockKey,-1)

redis.call('sadd',orderKey,userId)
--将下单信息保存到订单流中
redis.call('xadd','stream.orders','*','userId',userId,'vourcherId',voucherId,'id',orderId)