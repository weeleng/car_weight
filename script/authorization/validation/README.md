# ENG-002D-02 隔离授权验证

此 Maven 工程位于根 Reactor 外。测试使用合成人员、服务、设备、租户、项目、角色、业务对象和授权版本。Spring Security 使用真实 `SecurityContextHolder`，HTTP 用 Spring Mock Servlet 和隔离过滤器，租户、忽略标志、项目及审计使用测试范围载体。后台消息及任务以受限服务身份和已登记工作单执行。测试不会加载 Server 或操作真实业务身份、存储和授权记录。

## 执行

从仓库根执行。使用 JDK 21、Maven 3.9.12，产物编译目标为 Java release 17。命令消费的 Maven settings 或缓存可通过相应 Maven 参数指定。

```bash
bash script/shell/check-build-toolchain.sh
mvn -B -ntp -f script/authorization/validation/pom.xml clean verify
mvn -B -ntp -f script/authorization/validation/pom.xml test -Dgroups=negative
python3 script/authorization/validation/run-mysql.py
python3 script/authorization/validation/probes/check-source.py
python3 script/authorization/validation/probes/check-source.py --inventory
python3 script/authorization/validation/probes/run-tenant-probe.py \
  /path/to/local/yudao-server.jar /tmp/eng002d-tenant-evidence
```

普通 Maven 验证排除标为 `mysql` 的用例。MySQL 包装脚本使用本机 Docker Unix Socket，初始化独立 MySQL 8.4 容器，生成随机凭据，仅映射动态回环端口。JDBC 在建会话临时表前核对版本、报告标识、实例 UUID 和 Schema。执行结束移除容器与卷。无需现有数据库密码。

Tenant 探针从本次本地构建的 Server JAR 解包依赖，只载入组件并运行 SQL 字符串重写及最小 AOP。正常模式退出 0。负例用同一组件断言合成未登记表必须带租户谓词，预期退出 1 且必须命中该特定断言。包裹脚本在两个结果均符合预期时退出 0。源码检查用于记录认证和通用下载的既有边界，适用于当前基线。

## 能力边界

- 缓存是同进程、可控权威版本模型。测试中的有效期来自可信时钟和授予截止时间。实际 Redis、TTL、撤权 SLA 和多节点一致性待专项验收。
- 文件和导出为应用代理控制流夹具，暂存与审计记录均在内存。存储策略、真实签名链接、反向代理、下载中断与清理的部署级复验另行安排。
- MySQL 用例执行隔离 JDBC SQL。生产 MyBatis-Plus、正式 Mapper、RabbitMQ 或 Quartz 调度不在此工程装配。
- 决策对象是隔离模型中的短时使用结果。对象及高风险执行阶段重新查询策略。缓存版本、线程与事务处理不得直接视作正式实现。
