package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;




/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    private static final String SHOP_TYPE_LIST_KEY = "shop:type:list";
    private static final long CACHE_EXPIRE_TIME = 60;

    @Resource
    private RedisTemplate<String, List<ShopType>> redisTemplate;



    /**
     * 查询店铺状态列表
     * @return
     */
    @Override
    public List<ShopType> queryTypeList() {
        // 先从 Redis 中获取缓存数据
        List<ShopType> typeList = redisTemplate.opsForValue().get(SHOP_TYPE_LIST_KEY);
        if (typeList != null) {
            return typeList;

        }
        // 如果缓存中不存在数据，从数据库中查询
        QueryWrapper<ShopType> queryWrapper = new QueryWrapper<>();
        queryWrapper.orderByAsc("sort");
        typeList = this.list(queryWrapper);
        if (typeList != null&& !typeList.isEmpty()) {
           // 将查询结果存入 Redis 缓存，并设置过期时间
            redisTemplate.opsForValue().set(SHOP_TYPE_LIST_KEY,typeList,CACHE_EXPIRE_TIME, TimeUnit.MINUTES);
        }
        return typeList;
    }
}
