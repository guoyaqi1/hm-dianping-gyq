package com.hmdp.service.impl;

import ch.qos.logback.classic.Level;
import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 郭雅琦
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    /**
     * lua脚本加载
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //代理对象
    private IVoucherOrderService proxy;


    /**
     * 秒杀优惠券
     * @param voucherId
     * @return
     *//*
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        //判断结果
        int r = result.intValue();

        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //为0 有购买资格 把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        //优惠券id
        voucherOrder.setVoucherId(voucherId);
        //保存到阻塞队列
        orderTasks.add(voucherOrder);

        //获取代理对象
        proxy= (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);
    }*/

    /**
     * 秒杀优惠券
     * @param voucherId
     * @return
     */
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();

        Long orderId = redisIdWorker.nextId("order");
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId)
        );
        //判断结果
        int r = result.intValue();

        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }


        //获取代理对象
        proxy= (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);
    }


    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderrTask());
    }

    private class VoucherOrderrTask implements Runnable{
        String queueName = "stream.orders";

        @SneakyThrows
        @Override
        public void run() {
            while (true){
                try {
                    //获取队列中的订单信息
//                    VoucherOrder voucherOrder = orderTasks.take();
                    //获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

                    //判断订单信息是否获取成功
                    if(list == null || list.isEmpty()){
                        //获取失败 继续下一次循环
                        continue;
                    }
                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //处理订单
                    handleVoucherOrder(voucherOrder);

                    //确认消息 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() throws InterruptedException {
            while (true){
                try {

                    //获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );

                    //判断订单信息是否获取成功
                    if(list == null || list.isEmpty()){
                        //获取失败 继续下一次循环
                        continue;
                    }
                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //处理订单
                    handleVoucherOrder(voucherOrder);

                    //确认消息 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    Thread.sleep(50);
                }
            }
        }

        private void handleVoucherOrder(VoucherOrder voucherOrder) {

            //获取用户id
            Long userId = voucherOrder.getUserId();
            //创建锁对象
            RLock lock = redissonClient.getLock("lock:order:" + userId);
            boolean isLock = lock.tryLock();
            if(!isLock){
                return;
            }
            try {
                //获取代理对象 因为事务必须 针对this
                IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
                //返回订单id
                proxy.createVoucherOrder(voucherOrder);
            }finally {
                lock.unlock();
            }
        }
    }

    /**
     * 初始化线程池
     */

    /**
     * 秒杀优惠券
     *
     * @param
     */
    /*public Result seckillVoucher(Long voucherId) {

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
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        boolean isLock = lock.tryLock();
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

        *//*synchronized (userId.toString().intern()) {
            //获取代理对象 因为事务必须 针对this
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            //返回订单id
            return proxy.createVoucherOrder(voucherId);
        }*//*

    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //判断一人一单
        Long userId =voucherOrder.getUserId();

        //节约资源 将锁放在下单

            //查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder).count();

            if (count > 0) {
                return;
            }
            //扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock -1").eq("voucher_id", voucherOrder).gt("stock", 0)
                    .update();

            //获取优惠券成功
            if (!success) {
                return;
            }
           /* //创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);

            voucherOrder.setUserId(userId);
            //代金券id
            voucherOrder.setVoucherId(voucherId);*/
            save(voucherOrder);


    }
}
