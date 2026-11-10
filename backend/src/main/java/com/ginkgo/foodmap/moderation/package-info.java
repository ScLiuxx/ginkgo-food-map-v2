/**
 * 内容举报与管理员处置模块。
 *
 * <p>第一版只受理点评和回复举报，被举报内容在核查前保持公开；回复与点评的删除细节仍由 review
 * 模块的应用服务负责，本模块不直接访问其数据表。
 */
package com.ginkgo.foodmap.moderation;
