package com.ginkgo.foodmap.user.service;

import com.ginkgo.foodmap.user.dto.PublicAuthor;
import com.ginkgo.foodmap.user.mapper.PublicAuthorMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 面向其他模块的批量署名查询；加入调用方快照，不跨模块泄露持久化实体。 */
@Service
@RequiredArgsConstructor
public class PublicAuthorService {
    private final PublicAuthorMapper mapper;
    private final com.ginkgo.foodmap.growth.service.GrowthQueryService growth;

    /** 每次最多50位作者；空页不发SQL，按账号ID匹配，永不按用户名合并。 */
    @Transactional(readOnly = true)
    public Map<String, PublicAuthor> find(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        if (ids.size() > 50) throw new IllegalArgumentException("作者批量查询最多50人");
        var result = mapper.find(ids);
        var levels = growth.authorLevels(result.stream().map(a -> Long.parseLong(a.getId())).toList());
        result.forEach(a -> a.setLevel(levels.get(Long.parseLong(a.getId()))));
        return result.stream().collect(Collectors.toMap(PublicAuthor::getId, a -> a));
    }
}
