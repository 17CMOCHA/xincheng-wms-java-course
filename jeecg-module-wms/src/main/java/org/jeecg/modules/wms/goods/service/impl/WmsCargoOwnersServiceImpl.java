package org.jeecg.modules.wms.goods.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.common.util.RedisUtil;
import org.jeecg.modules.wms.goods.entity.WmsCargoOwners;
import org.jeecg.modules.wms.goods.mapper.WmsCargoOwnersMapper;
import org.jeecg.modules.wms.goods.service.IWmsCargoOwnersService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @Description: 货主表
 * @Author: jeecg-boot
 * @Date:   2026-08-29
 * @Version: V1.0
 */
@Service
public class WmsCargoOwnersServiceImpl extends ServiceImpl<WmsCargoOwnersMapper, WmsCargoOwners> implements IWmsCargoOwnersService {
    @Autowired
    private RedisUtil redisUtil;
    @Autowired
    private WmsCargoOwnersMapper wmsCargoOwnersMapper;
    // Redis中存储自增序号的key（自定义，确保唯一，建议按"模块_功能_编码"命名）
    private static final String REDIS_INCR_KEY = "WMS_CARGO_OWNERS_CODE";

    /**
     * 新增货主核心实现
     * 加@Transactional：确保编码生成和数据保存要么一起成功，要么一起失败
     */
    @Override
    public void add(WmsCargoOwners cargoOwners) {
        // 1. 生成货主编码（调用下面的工具方法）
        String ownerCode = generateOwnerCode();
        // 2. 给实体设置编码
        cargoOwners.setOwnerCode(ownerCode);
        // 3. 保存到数据库（MyBatis-Plus的ServiceImpl自带save方法）
        this.save(cargoOwners);
    }

    private String generateOwnerCode() {
        try {
            while (true) {
                // 第一步：Redis自增生成基础编码
                long seq = redisUtil.incr(REDIS_INCR_KEY, 1);
                String seqStr = String.format("%05d", seq);
                String code = "C" + seqStr;

                // 第二步：查询数据库，判断编码是否已存在
                Long count = wmsCargoOwnersMapper.checkCodeExists(code);
                if (!(count != null && count > 0)) {
                    // 编码不存在，直接返回使用
                    return code;
                }

                //  第三步：编码已存在，校准Redis自增序列(获取最大值 + 1)
                String maxOwnerCode = wmsCargoOwnersMapper.selectMaxOwnerCode();
                String seqStrDB = maxOwnerCode.substring("C".length());
                Long maxSeq = Long.parseLong(seqStrDB);
                if (maxSeq == null) {
                    throw new JeecgBootException("数据库中存在非法货主编码：" + maxOwnerCode);
                }

                redisUtil.set(REDIS_INCR_KEY, maxSeq);
            }


        } catch (Exception e) {
            // 捕获Redis异常（如Redis连接失败），抛出业务异常，让前端提示
            throw new JeecgBootException("货主编码生成失败");
        }
    }
}
