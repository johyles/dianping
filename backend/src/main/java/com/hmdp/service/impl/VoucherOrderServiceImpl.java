package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
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
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    //加载lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct //注解含义，在当前类初始化完毕，执行
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            while(true){
                try {
                    //1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息获取成功
                    if(list ==null || list.isEmpty()){
                        //获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    //3.获取成功，解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4.获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //5.ACK确认  SACK STREAM.ORDERS g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常！",e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while(true){
                try {
                    //1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1  STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2.判断消息获取成功
                    if(list ==null || list.isEmpty()){
                        //获取失败，说明pending-list没有异常消息，结束循环
                        break;
                    }
                    //3.解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4.获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //5.ACK确认  SACK STREAM.ORDERS g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常！",e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }

    //阻塞队列
    /*
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while(true){
                try {
                    //1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常！",e);
                }
            }
        }
    }*/

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户
        Long userId = voucherOrder.getUserId();
        //2.创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //3.获取锁
        boolean isLock = lock.tryLock();
        //4.判断是否获取锁成功
        if (!isLock) {
            //获取失败
            log.error("不允许重复下单！");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }



    private IVoucherOrderService proxy;

    //消息队列实现异步秒杀
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        long orderID = redisIdWorker.nextId("order");
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderID)
        );
        //2.判断结果是否是0
        int r = result.intValue();
        if (r!=0) {
            //2.1 不为0，代表没有购买资格
            return Result.fail(r==1?"库存不足！":"不能重复下单！");
        }

        //3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //3.返回订单id
        return Result.ok(orderID);
    }

    //阻塞队列实现异步秒杀
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        //2.判断结果是否是0
        int r = result.intValue();
        if (r!=0) {
            //2.1 不为0，代表没有购买资格
            return Result.fail(r==1?"库存不足！":"不能重复下单！");
        }
        //2.2 为o，有购买资格，把下单信息保存到阻塞队列中
        VoucherOrder voucherOrder = new VoucherOrder();
        //2.3添加订单id
        long orderID = redisIdWorker.nextId("order");
        voucherOrder.setId(orderID);
        //2.4 用户id
        voucherOrder.setUserId(userId);
        //2.5 代金券id
        voucherOrder.setVoucherId(voucherId);
        //2.6 创建阻塞队列
        orderTasks.add(voucherOrder);

        //3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //3.返回订单id
        return Result.ok(orderID);
    }*/

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1.查询优惠卷
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //2.判断是否秒杀开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            //尚未开始
//            return Result.fail("秒杀尚未开始！");
//        }
//        //3.判断秒杀是否已经结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            //尚未开始
//            return Result.fail("秒杀已经结束！");
//        }
//        //4.判断库存是否充足
//        if (voucher.getStock() < 1) {
//            //库存不足
//            return Result.fail("库存不足！");
//        }
//
//
//        Long userId = UserHolder.getUser().getId();
//        /*// 给用户id加锁，只要id一样，才需要锁。
//        // id toString后，会new一个String，值一样，地址不一样，调用intern，获取常量池中，值一样的字符串，值一样，锁一样
//        // 锁加在方法外，是为了事务在锁释放前提交，更安全
//        synchronized(userId.toString().intern()) {
//            //为了拿到当前类动态代理的对象，只有这样事务才能生效
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }*/
//        //使用分布式锁
//        //创建锁对象
//        //SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate,"order:" + userId);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        //获取锁
//        boolean isLock = lock.tryLock();
//        if (!isLock) {
//            //获取失败
//            return Result.fail("不允许重复下单!");
//        }
//        try {
//            //为了拿到当前类动态代理的对象，只有这样事务才能生效
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        //5.一人一单
        Long userId = voucherOrder.getUserId();

        //5.1.查询用户订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //5.2.判断是否存在
        if (count > 0) {
            //用户已经购买过了
            log.error("用户已经购买过一次了！");
            return;
        }

        //6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) //CAS解决超卖问题 where id=? ans storck >0
                .update();
        if (!success) {
            //扣减失败
            log.error("库存不足！");
            return ;
        }

        //保存订单
        save(voucherOrder);

    }
}
