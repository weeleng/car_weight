# C-06 模块边界

## 适用范围

九个业务逻辑模块、建议的 Maven 结构、跨模块交互与 IoT Gateway 部署边界。

## 已确认约束

跨模块交互使用公开 API 或事件。IoT Gateway 独立部署。实际 Maven 模块与依赖关系以当前仓库的源码及 POM 为准。

以下物理结构转录自已提交的[九模块映射方案](../../engineering/module-mapping.md)，属于建议。

| 逻辑模块 | 建议物理 Maven 映射 |
| --- | --- |
| `sanitation-common` | 单一基础 JAR |
| `sanitation-base` | API + Biz |
| `sanitation-gis` | API + Biz |
| `sanitation-device` | API + Biz |
| `sanitation-operation` | API + Biz |
| `sanitation-billing` | API + Biz |
| `sanitation-analysis` | 单独 Biz，有同步调用方时再提取 API |
| `sanitation-event` | API + Biz |
| `sanitation-integration` | 单独 Biz，外部契约有复用需求时再提取 API |

## 禁止事项

不得跨模块直接引用其他业务模块的 Mapper、DO 或内部 Biz 实现，不得形成循环依赖。本任务不得创建上述正式业务模块或改变 IoT Gateway 部署方式。

## 验证要求

引入或调整依赖时核对实际 POM 和源码引用，确认依赖方向、API 可见性及无循环。涉及部署时确认 IoT Gateway 的独立构件和运行边界。

## 来源与待核验内容

来源为 ENG-001B-01 提供的 C-06 和已提交的[九模块映射方案](../../engineering/module-mapping.md)。具体 Maven artifactId、依赖清单及模块落地需要按后续批准范围核验。
