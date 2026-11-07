package com.ginkgo.foodmap.growth.service;

import com.ginkgo.foodmap.growth.mapper.GrowthAccountMapper;
import com.ginkgo.foodmap.growth.policy.GrowthLevelPolicy;
import com.ginkgo.foodmap.growth.vo.GrowthSummaryVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 成长查询只读取本模块账户；未获奖用户返回初始等级，不制造写入或补发历史经验。 */
@Service
@RequiredArgsConstructor
public class GrowthQueryService {
    private final GrowthAccountMapper accounts;
    private final GrowthLevelPolicy levels;

    /** 供已验证身份的控制器调用；用户身份不由请求参数指定。 */
    @Transactional(readOnly = true)
    public GrowthSummaryVo summary(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("Invalid growth user");
        var account = accounts.find(userId);
        return levels.summarize(userId, account == null ? 0 : account.getExperience());
    }
    /** 内部批量等级查询，复用成长规则；缺失账户视为零经验，不创建账户或暴露经验。 */
    @Transactional(readOnly = true)
    public java.util.Map<Long, Integer> authorLevels(java.util.List<Long> userIds) {
        var users = userIds.stream().distinct().toList();
        var result = new java.util.HashMap<Long, Integer>();
        for (long user : users) {
            if (user <= 0) throw new IllegalArgumentException("Invalid growth user");
            result.put(user, levels.summarize(user, 0).getLevel());
        }
        // 限制IN参数数量，避免逐作者查询；参与外层只读事务保持等级与点评快照一致。
        for (int start = 0; start < users.size(); start += 500) {
            for (var account : accounts.findAll(users.subList(start, Math.min(start + 500, users.size()))))
                result.put(account.getUserId(), levels.summarize(account.getUserId(), account.getExperience()).getLevel());
        }
        return result;
    }
}
