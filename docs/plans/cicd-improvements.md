# CI/CD完善与腾讯云首次部署

## 已确认范围

- 基线为develop的`9769e83`，当前分支`feat/cicd-improvements`。
- 目标为全新腾讯云服务器`43.139.87.34`，2核8GB、80GB磁盘。用户提供的实际输出为Ubuntu 22.04.5 LTS、x86_64，账号`ubuntu`属于sudo组；此前口述24.04已按实测修正。
- 向develop发起PR时自动检查；合并并推送develop后再次检查，全部通过则自动发布，无逐次手动确认。
- GitHub托管机器执行测试及构建；服务器专用Runner仅下载并发布本次通过检查的产物，继续使用健康检查及程序回滚。

## 当前进度

- 已调整仓库配置：增加PR检查，修正仓库名称，新增统计结构／后端集成测试的显式开关及隔离连接。其他专项集成测试仍按原开关管理，本阶段不声称覆盖所有可选性能、故障或真实OSS验收。
- 自动发布使用仓库变量`AUTO_DEPLOY_ENABLED=true`作为首次就绪开关；未设置时只检查，服务器配置完成后一次开启，之后develop推送自动发布。
- 发布作业使用`production`环境及`foodmap-tencent-production`专属Runner标签，与旧服务器隔离。PR只在GitHub托管机器执行，不向自托管Runner交付不可信PR代码。
- 当前在本地功能分支保存改动，按用户要求与COS接入一并提交；未推送或连接服务器，未开启自动发布。实际GitHub运行及新服务器发布尚未验收。
- 用户通过自行建立的SSH连接完成基础安装并回传结果：OpenJDK21.0.13.0.3、Nginx1.18.0且active，Docker29.10.0、Compose插件5.8.0且Docker服务active。Docker软件源因下载慢切换为清华docker-ce镜像；该APT软件源不改变容器镜像下载来源。以上依据用户终端输出，未由代理直接登录复核；数据库、Redis、ES及Runner尚未配置。

## 分步教学与验收

1. 用户核对系统、账号、内存和磁盘；此步已完成，随后切换Windows自带SSH连接。Ubuntu22.04可继续部署，无需重装，Java必须为21。
2. 安装Java21、Nginx及基础工具，随后安装Docker并核对服务状态；用户输出确认此步已完成。
3. 配置MySQL8.4、Redis、Elasticsearch及IK词库、持久化目录、访问边界和必要运行配置。全新服务器的数据库从空库迁移；是否迁移原数据及哪些数据需单独确定，不能直接搬入本地演示统计。
4. 建立`foodmap`运行账号及`foodmap-deploy`发布账号，准备systemd、Nginx、应用配置和限定sudo。高德安全代理、OSS和SMTP配置需按实际环境核对，密码、私钥及安全密钥不进入仓库或聊天。
5. 在GitHub注册新服务器Runner，名称和标签使用`foodmap-tencent-production`。安装路径为`/opt/foodmap-runner`，以`foodmap-deploy`账号运行。
6. 配置GitHub的`production`环境及仓库就绪变量。自动发布模式不设置逐次人工审批；此模式下可信develop推送权限即发布权限。
7. 推送功能分支并创建PR，查看三项检查；合并前核对实际报告与统计集成测试未被跳过。服务器准备就绪后再开启发布开关并合并。
8. 验证同一提交的前后端版本、健康检查、注册登录和主要业务链路，以及下一次自动发布和失败回滚。程序回滚不撤销Flyway迁移；域名、HTTPS、备份和磁盘清理纳入上线收尾。

详细运行约定及历史服务器信息见[自动检查与发布](../operations/deployment.md)。
