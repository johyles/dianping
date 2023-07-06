-- 锁的key
local key = KEYS[1]

--当前线程标识
local threadID = ARGV[1]

--获取锁中的线程标识 get key
id = redis.call('get',key)

--比较线程标识和锁中的标识是否一致
if(id == threadID) then
    --释放锁
    redis.call('del',key)
end
return 0