package org.jeecg.modules.wms.inorder.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.common.util.DateUtils;
import org.jeecg.common.util.RedisUtil;
import org.jeecg.modules.wms.config.WarehouseDictEnum;
import org.jeecg.modules.wms.inorder.entity.WmsStockInOrderItems;
import org.jeecg.modules.wms.inorder.entity.WmsStockInOrders;
import org.jeecg.modules.wms.inorder.mapper.WmsStockInOrderItemsMapper;
import org.jeecg.modules.wms.inorder.mapper.WmsStockInOrdersMapper;
import org.jeecg.modules.wms.inorder.service.IWmsStockInOrderItemsService;
import org.jeecg.modules.wms.inorder.service.IWmsStockInOrdersService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @Description: 入库单主表
 * @Author: jeecg-boot
 * @Date:   2026-09-28
 * @Version: V1.0
 */
@Service
public class WmsStockInOrdersServiceImpl extends ServiceImpl<WmsStockInOrdersMapper, WmsStockInOrders> implements IWmsStockInOrdersService {

	@Autowired
	private WmsStockInOrdersMapper wmsStockInOrdersMapper;
	@Autowired
	private WmsStockInOrderItemsMapper wmsStockInOrderItemsMapper;
    @Autowired
    private IWmsStockInOrderItemsService wmsStockInOrderItemsService;
	@Override
	@Transactional(rollbackFor = Exception.class)
	public void saveMain(WmsStockInOrders wmsStockInOrders, List<WmsStockInOrderItems> wmsStockInOrderItemsList) {
		wmsStockInOrdersMapper.insert(wmsStockInOrders);
		if(wmsStockInOrderItemsList!=null && wmsStockInOrderItemsList.size()>0) {
			for(WmsStockInOrderItems entity:wmsStockInOrderItemsList) {
				//外键设置
				entity.setOrderId(wmsStockInOrders.getId());
				wmsStockInOrderItemsMapper.insert(entity);
			}
		}
	}

    @Autowired
    private RedisUtil redisUtil;

    @Override
    public void add(WmsStockInOrders wmsStockInOrders) {
        // 1. 生成符合规则的入库单号
        String orderNumber = generateOrderNumber();
        wmsStockInOrders.setOrderNumber(orderNumber);
        // 2. 设置默认状态为初始状态
        wmsStockInOrders.setStatus(WarehouseDictEnum.INBOUND_INITIAL.getCode());
        // 3. 仅插入主表数据
        wmsStockInOrdersMapper.insert(wmsStockInOrders);
    }

    /** 生成入库单号：ASN + 8位年月日 + 4位序号 */
    private String generateOrderNumber() {
        // 步骤1：获取8位年月日（如20250805）
        String dateStr = DateUtils.now().substring(0, 10).replace("-", "");
        // 步骤2：Redis生成当日递增序号（避免并发重复）
        String redisKey = "wms:asn_number:" + dateStr;
        long seq = redisUtil.incr(redisKey, 1);
        // 步骤3：设置Redis key过期时间（24小时+10秒）
        if (seq == 1) {
            redisUtil.expire(redisKey, 24 * 60 * 60 + 10);
        }
        // 步骤4：序号补零为4位（如1→0001）
        String seqStr = String.format("%04d", seq);
        // 步骤5：拼接最终单号
        return "ASN" + dateStr + seqStr;

    }
    @Override
    @Transactional(rollbackFor = Exception.class) // 异常回滚
    public void updateMain(WmsStockInOrders wmsStockInOrders, List<WmsStockInOrderItems> wmsStockInOrderItemsList) {
        // 规则1：仅初始/审核失败状态可修改
        String status = wmsStockInOrders.getStatus();
        if (!(WarehouseDictEnum.INBOUND_INITIAL.getCode().equals(status)
                || WarehouseDictEnum.INBOUND_REJECTED.getCode().equals(status))) {
            throw new JeecgBootException("非初始状态、审核失败状态入库单不允许修改");
        }


        // 步骤1：删除原子表数据（按主表id删除）
        wmsStockInOrderItemsMapper.deleteByMainId(wmsStockInOrders.getId());

        // 步骤2：子表数据按商品分组合并（同一商品合并数量）
        Map<String, List<WmsStockInOrderItems>> productGroup = wmsStockInOrderItemsList.stream()
                .collect(Collectors.groupingBy(WmsStockInOrderItems::getProductId));
        List<WmsStockInOrderItems> mergeItems = new ArrayList<>();
        productGroup.forEach((productId, items) -> {
            WmsStockInOrderItems mergeItem = items.get(0);
            // 设置明细状态为初始状态
            mergeItem.setStatus(WarehouseDictEnum.INBOUND_DETAIL_INITIAL.getCode());
            // 关联主表id
            mergeItem.setOrderId(wmsStockInOrders.getId());
            // 合并数量（多条明细求和）
            if (items.size() > 1) {
                int totalQty = items.stream().mapToInt(WmsStockInOrderItems::getExpectedQuantity).sum();
                mergeItem.setExpectedQuantity(totalQty);
            }
            mergeItems.add(mergeItem);
        });

        // 步骤3：计算主表总预期数量
        int totalTotalQty = mergeItems.stream().mapToInt(WmsStockInOrderItems::getExpectedQuantity).sum();
        wmsStockInOrders.setTotalExpectedQuantity(totalTotalQty);

        // 步骤4：插入新的子表数据
        boolean saveBatch = wmsStockInOrderItemsService.saveBatch(mergeItems);
        if (!saveBatch) {
            throw new JeecgBootException("保存入库单明细失败");
        }

        // 步骤5：更新主表数据
        int updateCount = wmsStockInOrdersMapper.updateById(wmsStockInOrders);
        if (updateCount <= 0) {
            throw new JeecgBootException("更新入库单失败");
        }
    }

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void delMain(String id) {
		wmsStockInOrderItemsMapper.deleteByMainId(id);
		wmsStockInOrdersMapper.deleteById(id);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void delBatchMain(Collection<? extends Serializable> idList) {
		for(Serializable id:idList) {
			wmsStockInOrderItemsMapper.deleteByMainId(id.toString());
			wmsStockInOrdersMapper.deleteById(id);
		}
	}
	
}
