package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>.
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getList() {
        String key = "cache:typelist";
        //1.先从redise查询商户类型
        List<String> shopTypeList = stringRedisTemplate.opsForList().range(key, 0, -1);
        //2.判断是否存在
        if(!shopTypeList.isEmpty()){
            //3.缓存中存在，返回
            List<ShopType> typeList = new ArrayList<>();
            for(String type:shopTypeList){
                ShopType shopType = JSONUtil.toBean(type,ShopType.class);
                typeList.add(shopType);
            }
            return Result.ok(typeList);
        }
        //4.缓存中不存在，查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //5.数据库中不存在，返回错误
        if(typeList.isEmpty()){
            return Result.fail("不存在商品类别！！");
        }
        //6.存在，添加进redis中
        for(ShopType shopType:typeList){
            String s = JSONUtil.toJsonStr(shopType);
            shopTypeList.add(s);
        }
        stringRedisTemplate.opsForList().rightPushAll(key,shopTypeList);
        //7。返回
        return Result.ok(typeList);
    }
}
