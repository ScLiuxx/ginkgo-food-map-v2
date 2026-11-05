package com.ginkgo.foodmap.restaurant.exception;

/** 当前资料已变化；应用层必须丢弃ES整页总数和排序，重新执行完整SQL查询。 */
public class RestaurantSearchStaleException extends RuntimeException {
    public RestaurantSearchStaleException() { super("搜索投影已过期"); }
}
