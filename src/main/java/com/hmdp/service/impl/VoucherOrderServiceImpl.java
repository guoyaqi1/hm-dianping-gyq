package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 郭雅琦
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 秒杀优惠券
     * @param voucherId
     * @return
     */
    public Result seckillVoucher(Long voucherId) {

        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2. 检查 voucher 是否为 null
        if (voucher == null) {
            return Result.fail("优惠券不存在！");
        }
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            //尚未开始
            return Result.fail("秒杀尚未开始");

        }
        if(voucher.getEndTime().isAfter(LocalDateTime.now())){
            //秒杀已经结束
            return Result.fail("秒杀已经结束");
        }
        //TODO 实现秒杀优惠券逻辑
        //判断库存
        if(voucher.getStock()<1){
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        //创建锁对象 分布式锁
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);

        boolean isLock = lock.tryLock(1200);
        if(!isLock){
            return Result.fail("不允许重复下单");
        }
        try {
            //获取代理对象 因为事务必须 针对this
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            //返回订单id
            return proxy.createVoucherOrder(voucherId);
        }finally {
            lock.unlock();
        }

        /*synchronized (userId.toString().intern()) {
            //获取代理对象 因为事务必须 针对this
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            //返回订单id
            return proxy.createVoucherOrder(voucherId);
        }*/

    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //判断一人一单
        Long userId = UserHolder.getUser().getId();

        //节约资源 将锁放在下单

            //查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();

            if (count > 0) {
                return Result.fail("用户已经购买过一次");
            }
            //扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock -1").eq("voucher_id", voucherId).gt("stock", 0)
                    .update();

            //获取优惠券成功
            if (!success) {
                return Result.fail("库存不足");
            }
            //创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);

            voucherOrder.setUserId(userId);
            //代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            return Result.ok(orderId);

    }
}
