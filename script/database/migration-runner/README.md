# 独立 Flyway 运行器

独立 Maven 工具，使用 Flyway OSS Core/MySQL 11.20.3、Connector/J 9.7.0、Java release 17 和 JDK 21。运行器和验证依赖均不加入默认 Reactor 或 Server。Boot 不会加载独立迁移器。

使用已批准的隔离环境变量（见[初始化](../bootstrap/README.md)），显式传入动作、逻辑库和迁移根目录。示例在仓库根执行，`$runtime_libs` 为验证隔离目录。

```bash
bash script/shell/check-build-toolchain.sh
mvn -f script/database/migration-runner/pom.xml -DskipTests package \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies \
  -DincludeScope=runtime "-DoutputDirectory=$runtime_libs"
java -cp "script/database/migration-runner/target/classes:$runtime_libs/*" \
  cn.iocoder.sanitation.tools.Runner validate ingest db/migration
java -cp "script/database/migration-runner/target/classes:$runtime_libs/*" \
  cn.iocoder.sanitation.tools.Runner migrate ingest db/migration
```

退出 0 表示命令完成，1 为数据库或 Flyway 拒绝，2 为输入/环境格式拒绝。每次物理连接核对回环配置、MySQL 8.4、隔离标识、实例 UUID、预期 Schema、迁移用户及 UTC 会话。迁移目录只读取对应逻辑库的直接版本脚本；每库写独立历史。validate 接受待运行的新版本，但拒绝现有历史漂移或缺失。migrate 前校验，完成后以禁止 pending 的方式复校验。工具只暴露 validate/migrate，禁用 clean、自动 baseline 和 outOfOrder；无 repair。由于 MySQL DDL 隐式提交，迁移失败可能留下已应用 DDL，保留失败现场并根据独立审核执行受控恢复。

生产执行方式和 TLS/运维凭据另行批准。本版运行器只接受隔离标识及本机回环地址。
