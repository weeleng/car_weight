# ADR-010 坐标体系与坐标转换

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`ecd6ed655cec214a0ed945b6e5f2e21d3dc71ee6`
- 编号、标题和设计要点来自 ENG-001B-02C-02，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-02 提供候选坐标体系和转换要求。已只读核查 [IotDevicePropertyServiceImpl.java](../../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/service/device/property/IotDevicePropertyServiceImpl.java)、[IotDeviceServiceImpl.java](../../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/service/device/IotDeviceServiceImpl.java)、[IotDeviceDO.java](../../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/dal/dataobject/device/IotDeviceDO.java)、[IotDeviceMessageServiceImpl.java](../../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/service/device/message/IotDeviceMessageServiceImpl.java)、[application.yaml](../../../yudao-server/src/main/resources/application.yaml)。现有 IoT 解析 GeoLocation 并校验经纬度范围，更新设备当前十进制经纬度，补传历史点不覆盖当前坐标。仓库还提供设备消息存储及 IoT 时序存储配置。上述源码未建立项目所需的 TRACK 轨迹、坐标参考系与转换版本模型。

## 背景

GNSS、终端 SDK 与地图供应商可能给出不同坐标参考系。直接比较或混存会影响围栏、距离和历史复核。

## 决策

- 区分 WGS84、CGCS2000、GCJ-02、BD-09 和地图投影坐标。GNSS 原始输入的 WGS84 为候选，须查设备文档和实测。Android、PDA 坐标须按 SDK、系统或供应商明确声明。
- 分别保存原始输入、统一业务空间计算、供应商展示及必要投影坐标。统一业务空间计算的 WGS84 为候选，须结合天地图、CGCS2000、MySQL 空间能力及适用合规要求确认。
- 坐标模型表达经纬度、空间参考系、坐标来源、采集时间、质量、转换标志及转换来源、版本。坐标轴顺序须显式记录，核对 API、GeoJSON、MySQL 空间类型与地图 SDK。
- 拟以精确十进制保存需稳定精度的业务经纬度，分别评估定位误差、转换误差及存储精度。
- 统一 GIS 提供转换，明确输入、输出、方法、方向、适用区域、误差、失败处理与版本。优先核验授权转换服务。围栏、相交和路径距离计算前核对一致且适用的空间参考系，米制距离、面积与缓冲使用适用地理或投影方法。
- 未知坐标系、非法或颠倒坐标、无效定位、转换失败、质量不足及来源冲突须明确标记。保留原始称重事件。

## 适用范围

定位来源、API 与空间存储、坐标转换和空间计算的输入约束。

## 约束与禁止事项

禁止以数值推断坐标系、未经校验交换坐标轴、以展示坐标覆盖原始坐标、在业务模块独立实现未经统一验证的转换。禁止假设 GCJ-02 与 BD-09 完全可逆或无误差。禁止异坐标系直接比较、对角度应用米制阈值、用零值或任意默认点充当作业位置。坐标小数位不得作为定位精度证明。

## 备选方案及取舍

直接依赖厂商坐标便于展示，却会限制数据复用及审计。原始坐标与派生坐标分离，并按场景选用计算参考系，需维护轴顺序和转换版本。

## 与其他 ADR/ACR 的关系

遵循 [ADR-014](ADR-014-gnss-and-tracking.md)保护原始点及 [ADR-008](ADR-008-api-conventions.md)的 API 契约，经 [ADR-009](ADR-009-gis-architecture.md)提供统一转换。约束 [ADR-011](ADR-011-geofencing.md)和 [ADR-019](ADR-019-collection-route-management.md)的空间输入。适用 C-02、C-05、C-06，出处见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

后续覆盖 GNSS 原点留存、Android/PDA 来源、多厂商转换、轴顺序、往返误差、跨坐标系围栏输入、非法坐标、米制距离及失败处理。

## 待确认事项

设备实际输出、主空间参考系、字段精度、空间类型、SRID、索引、转换许可、测试样本与允许误差待确认。

## 批准记录

草案待人工审查。历史批准原文、版本、批准人、日期及证据待取得。本次仅核查源码及文档，未运行功能或供应商验证。
