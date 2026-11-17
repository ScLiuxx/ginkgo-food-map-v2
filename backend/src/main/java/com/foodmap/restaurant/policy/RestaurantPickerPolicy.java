package com.foodmap.restaurant.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/** 在完整合格ID集合上执行部分Fisher–Yates，不按评分截断，也不按主键间隙赋予概率。 */
public final class RestaurantPickerPolicy {
    private RestaurantPickerPolicy() {}

    /** 每步均匀选择剩余位置；不修改输入；生产使用线程局部随机源，测试可注入确定性源。 */
    public static <T> List<T> sample(List<T> eligible, int limit, RandomGenerator random) {
        var pool = new ArrayList<>(eligible);
        int count = Math.min(limit, pool.size());
        for (int i = 0; i < count; i++) {
            int selected = i + random.nextInt(pool.size() - i);
            T value = pool.get(i);
            pool.set(i, pool.get(selected));
            pool.set(selected, value);
        }
        return List.copyOf(pool.subList(0, count));
    }
}
