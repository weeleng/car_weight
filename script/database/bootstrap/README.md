# 隔离初始化与权限

仅适用带 `org.weeleng.eng002c=eng002c02-isolated` 标签、`--report-host=eng002c02-isolated`、本机非默认回环端口的 MySQL 8.4 Docker 容器。要求本地 Unix Docker endpoint 和 `ENG002C_ENV=isolated`。脚本从指定容器内的初始化管理员执行，并打印实例 UUID 与端口，供连接配置绑定。正式部署和账号治理须单独授权。

`python3 script/database/bootstrap/bootstrap.py --container <isolated-container>` 创建三个 Schema 及各库迁移、运行、审计、备份、恢复角色与账号。使用 `--domain <ingest|business|track>` 可限定库，允许多次指定。凭据从 [环境键模板](environment.example) 对应环境变量注入，使用独立随机十六进制值。角色授权限自身 Schema。运行及审计账户初次没有表级权限。迁移有获批验证所需 DDL/DML，刻意保留 DROP 限制，因此删除性迁移必须另审。恢复角色与应用分离，有隔离恢复所需限库 DDL。重复运行不轮换已有账号密码，也不撤销 DBA 手工增加的权限，独立运行的夹具只使用新建容器。

`--grant-fixtures` 只针对验证目录定义的现有 `probe_` 表授予运行 SELECT/INSERT 和审计 SELECT。没有生产数据表定义。
