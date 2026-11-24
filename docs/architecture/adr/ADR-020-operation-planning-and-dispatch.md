# ADR-020 作业计划与车辆调度

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`f378d3f33d4b96f50eac15e3c361d012bd3b9891`
- 编号、标题及设计要点来自 ENG-001B-02C-03，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-03 提供拟议运营方案。已只读核查 [SchedulerManager.java](../../../yudao-framework/yudao-spring-boot-starter-job/src/main/java/cn/iocoder/yudao/framework/quartz/core/scheduler/SchedulerManager.java)、[JobServiceImpl.java](../../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/service/job/JobServiceImpl.java)、[pom.xml](../../../yudao-framework/yudao-spring-boot-starter-job/pom.xml)、[application-local.yaml](../../../yudao-server/src/main/resources/application-local.yaml)，现有 Quartz 管理支持 Cron 调度、暂停、恢复和主动触发，local 配置排除 Quartz 自动装配。另核查 [DeptDataPermissionRule.java](../../../yudao-framework/yudao-spring-boot-starter-biz-data-permission/src/main/java/cn/iocoder/yudao/framework/datapermission/core/rule/dept/DeptDataPermissionRule.java)、[TenantContextHolder.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/context/TenantContextHolder.java)、[TenantUtils.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/util/TenantUtils.java)、[application.yaml](../../../yudao-server/src/main/resources/application.yaml)，已有部门数据权限及租户上下文机制，项目调度授权仍须按环卫业务实现与验证。

## 背景

固定周期、临时及补充清运需要将可复用计划转为具体任务，并管理设备、驾驶员和站点变化。线路模板和车辆档案各自保留业务职责。

## 决策

- 区分计划模板、周期计划、单次计划、执行任务、任务站点、车辆分配、人员分配及变更记录。计划表达租户、项目、名称、日期和时间窗口、垃圾类别、线路及版本、站点、预计收运量、车辆及人员需求、状态和版本。周期按明确业务时区计算，时间遵循 C-02。
- 任务生成绑定当时有效的线路版本、站点及分配关系，具备幂等控制。周期重复触发、服务重启和重复消息不得重复生成正式任务。Quartz 作为周期生成候选，其调度状态与任务业务状态独立。
- 车辆分配核对项目及有效授权、车型、载重、称重机构、设备状态、可用时间、已有任务、垃圾类别、通行限制及设施接收条件。检测冲突，设备在线仅是一个输入。人员分配关联驾驶员和必要作业人员，核查项目授权、岗位权限、有效任职及时间冲突。
- 候选任务状态为待分配、待下发、已下发、已接收、执行中、暂停、已完成、已取消及异常终止。分别记录计划状态、任务状态、消息送达、人员接收确认及设备连接。下发成功后仍需人员确认，接收与开始执行分别确认。
- 授权后可换车、换人、增减站点、调整线路或时间、暂停、取消及生成补充任务。已执行部分保留历史。取消后到达的真实历史事件按接入规范处理。
- P0 候选为人工调度、固定线路计划和任务下发。自动优化、多车调度、动态重规划为后续候选，届时评估容量、站点垃圾量、时间窗口、限行、设施容量与成本。

## 适用范围

项目计划、任务生成、车辆和人员分配、下发与变更。

## 约束与禁止事项

禁止以线路模板代替任务、以车辆档案代替分配、无条件重复分配冲突车辆或重生成任务覆盖正式称重事件。终端登录不授予全部项目权限。普通地图规划不得视为车辆路径优化系统。

## 备选方案及取舍

P0 人工调度便于处理例外并审计。自动调度需要更完整约束与可靠数据。周期触发可复用 Quartz，业务幂等及状态由领域负责。

## 与其他 ADR/ACR 的关系

采用 [ADR-004](ADR-004-organization-and-project.md)、[ADR-005](ADR-005-user-and-permissions.md)的项目与人员授权，[ADR-017](ADR-017-asset-and-device-management.md)的车辆及设备状态、[ADR-019](ADR-019-collection-route-management.md)的线路版本。执行见 [ADR-021](ADR-021-collection-operation-execution.md)。遵循 [C-02](../standards/time-conventions.md)、[C-05](../standards/transaction-boundaries.md)、[C-06](../standards/module-boundaries.md)及 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

后续覆盖周期重触发与幂等、车辆人员冲突、下发失败、终端离线、接收确认、换车、线路调整、取消后迟到事件、跨项目越权和重启恢复。

## 待确认事项

唯一键、周期规则、最终状态转换、调度冲突与特殊授权、Quartz 部署及优化算法待确认。

## 批准记录

草案待人工审查。历史批准原文、版本、批准人、日期及证据待取得。本次仅核查源码和文档，未执行运行验收。
