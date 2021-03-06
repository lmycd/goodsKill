package org.seckill.service.impl;

import com.github.pagehelper.PageInfo;
import org.seckill.api.dto.Exposer;
import org.seckill.api.dto.SeckillExecution;
import org.seckill.api.dto.SeckillInfo;
import org.seckill.api.enums.SeckillStatEnum;
import org.seckill.api.exception.RepeatKillException;
import org.seckill.api.exception.SeckillCloseException;
import org.seckill.api.exception.SeckillException;
import org.seckill.api.service.SeckillService;
import org.seckill.dao.GoodsMapper;
import org.seckill.dao.RedisDao;
import org.seckill.dao.SeckillMapper;
import org.seckill.dao.SuccessKilledMapper;
import org.seckill.dao.ext.ExtSeckillMapper;
import org.seckill.entity.*;
import org.seckill.service.common.trade.alipay.AlipayRunner;
import org.seckill.util.common.util.DateUtil;
import org.seckill.util.common.util.MD5Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.lang.reflect.InvocationTargetException;
import java.util.Date;
import java.util.concurrent.CountDownLatch;

/**
 * Created by heng on 2016/7/16.
 */
@Service
public class SeckillServiceImpl extends CommonServiceImpl<SeckillMapper, SeckillExample, Seckill> implements SeckillService {
    @Autowired
    private AlipayRunner alipayRunner;
    private Logger logger = LoggerFactory.getLogger(this.getClass());
    @Autowired
    private ExtSeckillMapper extSeckillMapper;
    @Autowired
    private SuccessKilledMapper successKilledMapper;
    @Autowired
    private RedisDao redisDao;
    @Autowired
    private GoodsMapper goodsMapper;
    @Resource(name = "taskExecutor")
    ThreadPoolTaskExecutor taskExecutor;

    public void setAlipayRunner(AlipayRunner alipayRunner) {
        this.alipayRunner = alipayRunner;
    }

    public void setExtSeckillMapper(ExtSeckillMapper extSeckillMapper) {
        this.extSeckillMapper = extSeckillMapper;
    }

    public void setSuccessKilledMapper(SuccessKilledMapper successKilledMapper) {
        this.successKilledMapper = successKilledMapper;
    }

    public void setRedisDao(RedisDao redisDao) {
        this.redisDao = redisDao;
    }

    public void setGoodsMapper(GoodsMapper goodsMapper) {
        this.goodsMapper = goodsMapper;
    }

    @Override
    public PageInfo getSeckillList(int pageNum, int pageSize) {
        return selectByPage(new SeckillExample(), pageNum, pageSize);
    }

    @Override
    public SeckillInfo getById(long seckillId) throws InvocationTargetException, IllegalAccessException {
        Seckill seckill = extSeckillMapper.selectByPrimaryKey(seckillId);
        SeckillInfo seckillInfo = new SeckillInfo();
        BeanUtils.copyProperties(seckill, seckillInfo);
        Goods goods = goodsMapper.selectByPrimaryKey(seckill.getGoodsId());
        seckillInfo.setGoodsName(goods.getName());
        return seckillInfo;
    }

    @Override
    public Exposer exportSeckillUrl(long seckillId) {
        //从redis中获取缓存秒杀信息
        Seckill seckill = redisDao.getSeckill(seckillId);
        if (seckill == null) {
            seckill = extSeckillMapper.selectByPrimaryKey(seckillId);
            if (seckill != null) {
                redisDao.putSeckill(seckill);
            } else {
                return new Exposer(false, seckillId);
            }
        }
        Date startTime = seckill.getStartTime();
        Date endTime = seckill.getEndTime();
        Date nowTime = new Date();
        if (nowTime.getTime() < startTime.getTime() || nowTime.getTime() > endTime.getTime()) {
            return new Exposer(false, seckillId, nowTime.getTime(), startTime.getTime(), endTime.getTime());
        }
        String md5 = MD5Util.getMD5(seckillId);
        return new Exposer(true, md5, seckillId);
    }


    @Transactional
    @Override
    public SeckillExecution executeSeckill(long seckillId, String userPhone, String md5) {
        if (md5 == null || !md5.equals(MD5Util.getMD5(seckillId))) {
            throw new SeckillException("seckill data rewrite");
        }
        Date nowTime = DateUtil.getNowTime();
        try {
            int updateCount = extSeckillMapper.reduceNumber(seckillId, nowTime);
            if (updateCount <= 0) {
                throw new SeckillCloseException("seckill is closed");
            } else {
                SuccessKilled successKilled = new SuccessKilled();
                successKilled.setSeckillId(seckillId);
                successKilled.setUserPhone(userPhone);
                int insertCount = successKilledMapper.insertSelective(successKilled);
                String QRfilePath = alipayRunner.trade_precreate(seckillId);
                if (insertCount <= 0) {
                    throw new RepeatKillException("seckill repeated");
                } else {
                    SuccessKilledKey key = new SuccessKilledKey();
                    key.setSeckillId(seckillId);
                    key.setUserPhone(userPhone);
                    return new SeckillExecution(seckillId, SeckillStatEnum.SUCCESS, successKilledMapper.selectByPrimaryKey(key), QRfilePath);
                }
            }
        } catch (SeckillCloseException e1) {
            logger.info(e1.getMessage(), e1);
            throw e1;
        } catch (RepeatKillException e2) {
            logger.info(e2.getMessage(), e2);
            throw e2;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new SeckillException("seckill inner error:" + e.getMessage());
        }
    }

    @Override
    public int addSeckill(Seckill seckill) {
        return extSeckillMapper.insert(seckill);
    }

    @Override
    public int deleteSeckill(Long seckillId) {
        return extSeckillMapper.deleteByPrimaryKey(seckillId);
    }

    @Override
    public int updateSeckill(Seckill seckill) {
        return extSeckillMapper.updateByPrimaryKeySelective(seckill);
    }

    @Override
    public Seckill selectById(Long seckillId) {
        return extSeckillMapper.selectByPrimaryKey(seckillId);
    }

    @Override
    public int deleteSuccessKillRecord(long seckillId) {
        SuccessKilledExample example = new SuccessKilledExample();
        example.createCriteria().andSeckillIdEqualTo(seckillId);
        return successKilledMapper.deleteByExample(example);
    }

    @Override
    public void executeWithSynchronized(Long seckillId, int executeTime) {
        CountDownLatch countDownLatch = new CountDownLatch(executeTime);
        for (int i = 0; i < executeTime; i++) {
            int userId = i;
            taskExecutor.execute(() -> {
                synchronized (this) {
                    Seckill seckill = extSeckillMapper.selectByPrimaryKey(seckillId);
                    if (seckill.getNumber() > 0) {
                        extSeckillMapper.reduceNumber(seckillId, new Date());
                        SuccessKilled record = new SuccessKilled();
                        record.setSeckillId(seckillId);
                        record.setUserPhone(String.valueOf(userId));
                        record.setStatus((byte) 1);
                        record.setCreateTime(new Date());
                        successKilledMapper.insert(record);
                    } else {
                        logger.warn("库存不足，无法继续秒杀！");
                    }
                }
                countDownLatch.countDown();
            });
        }
        // 等待线程执行完毕，阻塞当前进程
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        SuccessKilledExample example = new SuccessKilledExample();
        example.createCriteria().andSeckillIdEqualTo(seckillId);
        logger.info("成功笔数:{}",successKilledMapper.countByExample(example));
    }
}
