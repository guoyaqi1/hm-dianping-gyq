package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static org.apache.tomcat.jni.Lock.unlock;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    private CacheClient cacheClient;


    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 根据id查询商铺信息
     * @param id
     * @return
     */
    public Result queryById(Long id){
        //缓存穿透——逻辑过期
        // Shop shop = queryWithPassThought(id);
//        Shop shop = cacheClient
//                .queryWithPassThought(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //缓存击穿——互斥锁解决缓存击穿问题
//        Shop shop = queryWithMutex(id);

        //缓存击穿——逻辑过期解决缓存击穿问题
//        Shop shop = queryWithLogicExpire(id);
        Shop shop = cacheClient
                .queryWithLogicExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop==null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);

    }

    /**
     * 缓存击穿_互斥锁解决缓存击穿问题
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        String key =CACHE_SHOP_KEY + id;
        //存入redis
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是不是空值
        if (shopJson!=null){
            return null;
        }
        //TODO 实现缓存重建
        //获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;

        Shop shop = null;
        try {
            Boolean isLock = tryLock(lockKey);
            //判断是否获取成功
            if (!isLock){
                //失败，休眠重试
                Thread.sleep(50);
                return queryWithMutex(id);

            }

            //成功，根据id查询数据库
            shop = getById(id);
            //模拟延迟
            Thread.sleep(200);
            //不存在则 在redis中存入
            if (shop == null){
                //在redis中存入空值
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);

                return null;
            }
            // 将查询结果存入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放互斥锁
            unlock(Long.parseLong(lockKey));
        }

        //返回shop
        return shop;
    }

    //新增线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    /**
     * 缓存击穿—逻辑过期解决缓存击穿问题
     * @param id
     * @return
     */
    public Shop queryWithLogicExpire(Long id){
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
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //未过期 直接返回店铺信息
            return shop;
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
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(Long.parseLong(lockKey));
                }

            });
        }
        return shop;
    }

    /**
     * 缓存击穿——逻辑过期解决缓存击穿问题
     * @param id
     * @param expireSeconds
     */
    public void saveShop2Redis(Long id ,Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透代码封装
     * @param id
     * @return
     */
    public Shop queryWithPassThought(Long id){
        String key =CACHE_SHOP_KEY + id;
        //存入redis

        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是不是空值
        if (shopJson!=null){
            return null;
        }
        Shop shop = getById(id);

        //不存在则 在redis中存入
        if (shop == null){
            //在redis中存入空值
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        // 将查询结果存入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
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

    /**
     * 更新商铺信息
     * @param shop
     * @return
     */
    @Transactional
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空");
        }
        //1.先更新数据库
        updateById(shop);
        //2.再删除缓存
         stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();
    }

}
