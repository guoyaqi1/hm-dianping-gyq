package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * @Author:guoyaqi
 * @Date: 2025/3/11 0:43
 */
public class SimpleRedisLock implements ILock{

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private String name;

    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识
        Long threadId = Thread.currentThread().getId();

        //获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId+"", timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }


    @Override
    public void unlock() {

        stringRedisTemplate.delete(KEY_PREFIX + name);

    }
}
