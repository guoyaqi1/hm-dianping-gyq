package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;


import static com.hmdp.utils.RedisConstants.*;
import static org.apache.tomcat.jni.Lock.unlock;

/**
 * @Author:guoyaqi
 * @Date: 2025/3/2 23:34
 */

@Slf4j
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    /**
     *  将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(time));
        //写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 基于传入的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
     * @param keyPrefix
     * @param id
     * @param type
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R,ID> R queryWithPassThought(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit){//,Long time,TimeUnit unit
        String key =keyPrefix + id;
        //存入redis

        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(json)){

            return JSONUtil.toBean(json,type);
        }
        //判断命中的是不是空值
        if (json!=null){
            return null;
        }

        R r = dbFallback.apply(id);

        //不存在则 在redis中存入
        if (r == null){
            //在redis中存入空值
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        // 将查询结果存入redis
//        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(r),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        this.set(key,r,time,unit);
        return r;
    }


    /**
     * 新增线程池
     */

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    /**
     * 缓存击穿—逻辑过期解决缓存击穿问题
     * @param id
     * @return
     */
    public <R,ID> R queryWithLogicExpire(String keyPrefix,ID id,Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit){
        String key =CACHE_SHOP_KEY + id;
        //存入redis
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isBlank(shopJson)){
            //未命中 返回
            return null;
        }
        //4.命中 将Json转化为序列化对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //未过期 直接返回店铺信息
            return r;
        }
        //6.已过期 缓存重建
        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        //6.2判断是否获取成功
        Boolean isLock = tryLock(lockKey);
        if (isLock){
            //成功 开启独立线程 实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(Long.parseLong(lockKey));
                }

            });
        }
        return r;
    }



    /**
     * 记录是否获取锁标记
     * @param key
     * @return
     */
    private Boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }



}
