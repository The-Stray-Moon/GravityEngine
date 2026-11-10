# GravityEngine API 接入指南

本文面向外部内容模组，描述当前源码的公开契约。主目标为 Minecraft 1.21.1、NeoForge 21.1.265、Java 21；common 保持 Java 17。模组版本以 metadata 声明为准。其余目标标记为 `SCAFFOLD_UNVERIFIED`，不能据此假定 API 或运行行为相同。

Java 方法及参数细节可通过 `:javadoc` 生成；本指南中的完整示例也参与成品 JAR 消费者编译。

## 支持范围

| 包 | 对外用途 |
| --- | --- |
| `cc.sighs.gravityengine.api` | NeoForge 入口、provider、发布句柄、实体适配声明；common 同包提供只读重力观测值 |
| `cc.sighs.gravityengine.api.field` | 纯场求值 SPI、贡献、覆盖判定、发现域、影响体积及工厂 |
| `cc.sighs.gravityengine.api.math` | 不可变 `Vec3d` |

上述包以外的 `gravity.*`、`attitude.*`、`look.*`、`math.*`、`client.*`、`network.*`、`protocol.*`、Mixin 和附件均为内部实现。Java `public` 不构成兼容承诺。DIRECT 写入与释放使用下文公开入口；碰撞 provider、身体事务、角动力学策略注入和网络包不属于公开扩展点。

引擎拥有场组合、实体赋值、应用提交、几何安装和同步。内容模组拥有源数据、源生命周期、每个查询的本域覆盖证明、字段参数和游戏玩法。不要从外部写实体内部 component 或自己发送 GE 状态包。

## 入口清单

均位于 `GravityEngineApi`：

| 方法 | 用途及调用条件 |
| --- | --- |
| `registerFieldProvider(ResourceLocation, Function<ServerLevel, GravityFieldProvider>)` | 初始化期注册域；首个服务端 Level runtime 创建后冻结；重复或迟注册失败 |
| `registerEntityAdapter(EntityType<E>, Function<E, EntityGravityAdapter>)` | 两端初始化时按精确 EntityType 注册；首次能力查询冻结；非继承树匹配 |
| `publicationProvider(ServerLevel, ResourceLocation, Function<GravityFieldQuery, FieldCoverage>)` | 构造以发布注册表为来源的 session；覆盖证明仍由调用方提供 |
| `samplePublications(Level, ResourceLocation, GravityFieldQuery)` | 在所属 provider 的 evaluate 中取得未预组合的贡献列表 |
| `publish(Level, ResourceLocation, GravityFieldDefinition)` | 发布或替换所属域内的一个源；返回 `FieldPublication` |
| `sample(Level, Vec3d)` | 位置采样：速度为零、gameTick 为 0、intervalTicks 为 0 |
| `sample(Level, Vec3d, Vec3d, long, double)` | 带位置、世界速度、tick 和时间间隔的完整场采样 |
| `setDirectGravity(Entity, Vec3d, double)` | 服务端显式重力赋值并同步；方向归一化，强度非负 |
| `releaseDirectGravity(Entity)` | 完整 FIELD 查询后释放 DIRECT；不完整则保留 DIRECT |
| `entityGravity(Entity)` | 只读 `Optional<EntityGravitySnapshot>`；不创建 runtime、不调用 provider、不重新采样 |

除初始化注册外，Level/Entity 入口必须在所属游戏线程调用。场发布、provider 求值和组合采样只允许服务端；`entityGravity` 可在各自所属线程读取两端状态。纯 `GravityField.sample` 可以用于客户端预览，但不能据此发现域、声明覆盖或重新赋值。

## 坐标与单位

- `position`、影响范围：世界空间，单位 block；字段中心不等同于实体位置锚点。
- `velocity`：世界空间 block/tick；`acceleration`、重力 strength：block/tick²。
- `gameTick`：非负逻辑 tick；`intervalTicks`：有限非负 tick 间隔，位置查询允许 0。
- `Vec3d` 不可变；支持算术、点积、叉积、长度、距离、归一化和插值。向量构造本身不保证有限值，查询和结果等边界会验证；零向量或非有限长度不能归一化。
- 环境参考帧、碰撞轴、身体姿态、语义瞄准和渲染分别拥有权威。`GravityFrameView` 不是碰撞几何或身体旋转接口。

## Provider 生命周期与覆盖

每个注册域在每个服务端 Level 创建一个 session，生命周期如下：

1. factory 只构造并返回非 null session；不能发布或采样。
2. 所有 factory 完成后，按稳定 provider ID 顺序执行 `onOpen()`；可发布本域初始源，不能采样或发布其他域的源。
3. `evaluate(query)` 捕获当前来源并返回 `GravityFieldProviderResult(coverage, contributions)`；不能递归采样组合结果或修改发布。
4. `blockDiscovery()` 提供方块唤醒域，与查询覆盖独立。
5. Level 卸载或初始化失败时关闭 session，释放内容侧资源；不要在 close 中重新采样或创建发布。原有 lease 可关闭。

factory/onOpen 失败会撤销初始化发布并尝试逆序关闭已获得的 session。运行时 provider 异常附带域、Level 和阶段上下文，保留原始原因；不能把程序异常转换成 COMPLETE + empty。

`FieldCoverage` 只描述当前查询。每个域都 COMPLETE 才能授权整体结果：

| 总覆盖 | 有贡献 | FIELD 处理 |
| --- | --- | --- |
| INCOMPLETE | 任意 | UNKNOWN，保留最后权威赋值及允许的连续性 |
| COMPLETE | 是，包括零合力 | PRESENT，提交完整组合 |
| COMPLETE | 否 | ABSENT，回到默认 FIELD 赋值 |

`INCOMPLETE + 非空贡献` 合法，但只代表部分证据。注册表为空、某个 chunk 加载完、已经经过若干 tick、revision 未变，都不证明完整。DIRECT 权威不由 FIELD 覆盖重新裁决。

来源索引需要自己定义生命周期：若只让已加载核心生效，必须证明所有可能影响查询的相关已加载区域都完成发现；若卸载核心仍需生效，内容模组应拥有持久化源索引。GE 不提供“世界初始化完成”开关。

## 最小可编译示例

以下域只拥有一个固定源，因而可以对所有查询返回 COMPLETE；其他维度返回完整空贡献。示例不会自动运行，外部模组需在初始化期调用 `register()`。不要把这份覆盖声明照搬到依赖 chunk 扫描的源索引。

```java
package com.example.examplemod.gravity;

import cc.sighs.gravityengine.api.*;
import cc.sighs.gravityengine.api.field.*;
import cc.sighs.gravityengine.api.math.Vec3d;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** Compiled documentation example; not a test mod or automatic registration. */
public final class DocumentedApiExample {
    private static final ResourceLocation PROVIDER = ResourceLocation.parse("examplemod:fixed_domain");
    private static final ResourceLocation SOURCE = ResourceLocation.parse("examplemod:fixed_field");

    /** Invoke once during mod initialization, before server Levels exist. */
    public static void register() {
        GravityEngineApi.registerFieldProvider(PROVIDER, level -> new GravityFieldProvider() {
            private FieldPublication lease;

            @Override public void onOpen() {
                if (level.dimension().equals(Level.OVERWORLD)) {
                    lease = GravityEngineApi.publish(level, PROVIDER,
                            GravityFieldDefinition.named(SOURCE,
                                    query -> new GravityFieldSample(new Vec3d(0, -0.04, 0)),
                                    GravityFields.sphereInfluence(new Vec3d(0, 80, 0), 16),
                                    GravityFieldCompositionMode.ADDITIVE, 0));
                }
            }

            @Override public GravityFieldProviderResult evaluate(GravityFieldQuery query) {
                // This domain consists ONLY of the fixed source above. No chunks
                // or external source index need discovery. COMPLETE is justified.
                return new GravityFieldProviderResult(FieldCoverage.COMPLETE,
                        GravityEngineApi.samplePublications(level, PROVIDER, query));
            }

            @Override public void close() {
                if (lease != null) { lease.close(); lease = null; }
            }
        });
    }

    public static GravityMutationResult direct(net.minecraft.world.entity.Entity entity) {
        return GravityEngineApi.setDirectGravity(entity, new Vec3d(0, -1, 0), 0.04);
    }

    public static GravityMutationResult release(net.minecraft.world.entity.Entity entity) {
        return GravityEngineApi.releaseDirectGravity(entity);
    }

    public static ComposedGravitySample sample(Level level, Vec3d position, Vec3d velocity) {
        return GravityEngineApi.sample(level, position, velocity, level.getGameTime(), 1);
    }
}

```

完整源文件：[DocumentedApiExample.java](../verification/api-consumer/com/example/examplemod/gravity/DocumentedApiExample.java)。示例影响球内的 0.04 是绝对场加速度，不是在每种实体原版重力上再加 0.04。

## 发布、标识与替换

`GravityFieldDefinition` 保存 `id, source, field, influence, compositionMode, revision`。维度由 publish 的 Level 决定。

- `named(fieldTypeId, field, influence, mode, revision)`：使用 fieldTypeId 作为实例 ID，适合单一命名源。
- `block(fieldTypeId, BlockPos, field, influence, mode, revision)`：按类型与方块坐标生成实例 ID；使用 `GravityFieldIds.blockInstance` 可取得相同标识。
- `GravityFieldSource.named/block` 表达稳定来源排序，不表示加载顺序或优先级。
- 实例 ID 应在 Level 内唯一，包括不同 provider；同类型不同方块必须用不同实例 ID。
- revision 非负，只比较同一实例的当前注册版本；等于或低于当前 revision 返回 `REJECTED_STALE`，不会替换旧源。
- `FieldPublication.accepted/status/fieldId/revision` 描述本次提交；`isClosed` 表示本地释放状态，不表示注册表中是否仍为当前源。
- 替换顺序：先 publish 新版本；仅在 accepted 后保存新句柄并关闭旧句柄。旧句柄不会删除新发布，即使后续重用了 ID 与 revision。
- `close()` 删除仍由该句柄拥有的发布；卸载后和重复成功关闭均无害。非法线程或回调阶段关闭被拒绝时，句柄仍可在合法阶段关闭。

发布的场与影响体积应为不可变快照。源数据变化时捕获新参数并发布新版本，不要让 evaluator 闭包读活跃 BlockEntity 或可变全局数据。

## 纯场 SPI 与组合

`GravityField.sample(GravityFieldQuery)` 返回 `GravityFieldSample`，加速度必须有限。`ZERO` 是有效的“存在但加速度为零”，不是缺失源。自定义场只读取不可变查询及捕获参数。

`GravityInfluenceVolume.contains(position)` 决定精确参与范围；`finiteBounds()` 返回覆盖整个体积的 `Optional<GravityFieldBounds>`，空值表示无界。边界只供候选索引使用，不是碰撞形状。

`GravityContribution` 包含 namespaced 字符串 ID、sourceType、整数 X/Y/Z、加速度、组合模式和非负 revision。动态 provider 可直接构造贡献，不必通过发布；仍须保证身份唯一、来源顺序稳定和同一查询的覆盖正确。

组合规则：有任意活动 OVERRIDE 时，只对所有 OVERRIDE 求和；否则对所有 ADDITIVE 求和。不是“最后一个覆盖前一个”，也不是最大强度获胜。稳定顺序为 sourceType、数值 X/Y/Z、实例 ID；revision 和注册时刻不参与排序。来自不同域的贡献由 GE 统一组合。

`ComposedGravitySample` 提供 samplePoint、acceleration、contributions、coverage，以及 `fieldPresent()`、`hasAcceleration()`、`accelerationMagnitude()`、`down()`。contributions 是有效组合组的只读视图，包含 `GravityContributionView` 的fieldId、acceleration 和 revision。先判断 COMPLETE，再解释 presence；零合力时 down 为空。完整空样本的零向量是“没有场贡献”，不意味着实体应关闭原版重力。

## 内置工厂

全部位于 `GravityFields`，不会自动注册世界重力场：

| 方法 | 语义 |
| --- | --- |
| `zeroGravity()` | 零加速度；配合 OVERRIDE 可排除 ADDITIVE，但仍会与其他 OVERRIDE 求和 |
| `sphericalMass(center, mass, referenceDensity, surfaceRadius, gravityConstant)` | 有符号质量球形场；外部反平方、内部 enclosed-mass 分布；G 由内容方决定 |
| `height(fullGravityY, zeroGravityY, accelerationMagnitude)` | 世界 -Y 方向；低处全强度，中间 smoothstep，高处零加速度但保留贡献 |
| `sphereInfluence(center, radius)` | 有限球形影响范围，包含表面 |
| `infiniteInfluence()` | 全局候选体积 |
| `uniformDensityRadius(absoluteMass, referenceDensity)` | 均匀密度球半径 |
| `uniformDensity(absoluteMass, radius)` | 均匀密度 |
| `surfaceAccelerationMagnitude(G, mass, surfaceRadius)` | 表面加速度幅值 |
| `sphericalCutoffRadius(G, mass, surfaceRadius, minimumFieldAcceleration)` | 外部阈值截断半径，不小于物理表面 |

场强、密度、半径等须满足工厂参数验证；密度与物理表面半径为正，G 非负，各量有限。`referenceDensity` 不自动等于平均密度；均匀内部剖面应使用 `uniformDensity` 推导。

## 方块发现不是覆盖证明

`GravityFieldDiscovery(bounds, unbounded)` 由 provider 的 `blockDiscovery()` 返回：

- `SAMPLING_ONLY`：没有额外自动方块扫描域；不影响实体采样。
- 有限 bounds：声明这一 tick 可能受影响的保守世界域。
- `UNBOUNDED`：显式启用有预算的已加载世界遍历，不意味着同一 tick 完成无限扫描。

已发布 influence 的范围会自动进入发现；动态、未发布来源需要自行提供发现域。移动或移除来源还需要清理过去影响的单元。自动发现主要接入下落方块启动/恢复，不是通用“任意方块刚体化”接口。

## 实体重力观测

`entityGravity(entity)` 返回不可变快照，缺少引擎适配时为 Optional.empty，null 参数被拒绝。

| 值 | 含义 |
| --- | --- |
| `authority` | FIELD 或 DIRECT 的赋值权威 |
| `assignedDirection/Strength` | 最新权威赋值，可能是 UNKNOWN 下保留的种子 |
| `effectiveDirection/Strength` | 当前可用求值；否则回退到 assigned |
| `appliedDirection/Strength` | 已提交 GE 物理应用中的值，不等同于 pending assignment |
| `fieldPresence` | PRESENT / ABSENT / UNKNOWN；主要按 FIELD 权威解释 |
| `applicationEpoch` | 已提交应用的观测标识，不是协议版本或持久化格式 |
| `referenceFrame` | 可选完整环境参考帧：samplePoint、down/up/left/forward、strength；不是碰撞轴 |
| `observationSource` | ACTIVE_OPERATION、CURRENT_TICK 或 ASSIGNMENT_FALLBACK |

`assignedAcceleration/effectiveAcceleration/appliedAcceleration` 均由相应方向乘强度构成。不能只看数值就推断显式赋值权威已被撤销。

当前限制：活动操作可读其冻结求值；操作外只复用无本地 registry 依赖且权威、应用、tick、身体采样点和速度都匹配的 tick 求值。服务端 FIELD 求值在操作外因此回退为 ASSIGNMENT_FALLBACK。无场弹道实体可继续使用原版实体自己的重力，例如物品 0.04；快照中的 GE 默认 0.08 不能直接解读为实际使用的逐 tick 原版加速度。需要严格物理观测时必须检查 observationSource；此 API 不是完整原版加速度遥测接口。

## 显式实体重力

在实体所属服务端线程调用 `setDirectGravity(entity, direction, strength)`，单位为 block/tick²。direction 有限、长度平方至少为 1e-7，会归一化；strength 有限且非负。零强度仍要求有效方向，用于保留参考方向。传入与默认值相同的数值也仍是 DIRECT 权威，不能代替释放。

返回 `GravityMutationResult(status, assignmentChanged)`：

| status | 调用结果 |
| --- | --- |
| UNCHANGED | 物理应用无需改变；assignmentChanged 仍可能为 true，例如仅权威变化 |
| COMMITTED | 已提交应用；实体类型、noGravity 及运动能力限制仍适用 |
| DEFERRED | 赋值保留，等待既有物理提交边界；常见于玩家身体交接 |
| FAILED | 预期的几何或碰撞证据拒绝；赋值保留，应用仍 pending |
| FIELD_INCOMPLETE | 释放未执行，DIRECT 保持；没有自动排队的释放请求，调用方可稍后重试 |

`releaseDirectGravity(entity)` 对已经是 FIELD 的实体无操作。对 DIRECT 先作完整服务端场查询：完整有贡献采用组合结果；完整空结果回到默认 FIELD 及适用的原版行为；不完整不切换权威。不要用手工设置默认方向与强度来代替释放。

赋值经已有附件持久化，变更由引擎同步，调用方不发送额外 GE 包。需要观察实际应用时，稍后读取 `entityGravity` 的 assigned、applied 与 observationSource；不能把 DEFERRED/FAILED 当成几何已安装。

这两个入口是最后写入者赋值，不是可嵌套 lease 或按模组堆栈恢复。多个内容模组应协调谁拥有实体 DIRECT 控制；释放不恢复其他模组历史 DIRECT 值。非法客户端、非所属线程、已移除实体、运动/身体事务内调用、递归调用和 provider 回调内调用被拒绝。应在普通服务端事件或排队到合法 tick 时调用。安装的非预期异常回滚并传播；不要把异常当成成功。

## 实体类型适配

`registerEntityAdapter` 的回调按需执行，必须纯、非重入、返回非 null；两端应使用一致声明。显式声明优先于内置分类，但仍受匹配重力接缝、姿态尺寸及运动状态约束。

| 枚举 | 允许接入 |
| --- | --- |
| NATIVE | 保留原版运动/碰撞/呈现；场仍可被查询 |
| CHARACTER | LivingEntity 普通 travel；当前尺寸和状态必须支持字符碰撞体 |
| BALLISTIC | 已适配的 ThrowableProjectile / AbstractArrow / LlamaSpit / FishingHook 家族 |
| PASSIVE_BALLISTIC | ItemEntity / ExperienceOrb / PrimedTnt / FallingBlockEntity 家族 |
| CONTROLLED_FLIGHT | 飞行控制能力分类；不自动启用角色移动或替换推进模型 |

自定义 `travel` 默认不会自动视作支持角色内核。显式 CHARACTER 是选择既有契约，不是为任意自定义 travel 自动生成适配。宽短姿态、marker 盔甲架及特殊原版运动仍有独立限制。不要把一个任意实体声明成 BALLISTIC 后假定它已获得原版重力调用点。

## 迁移与故障处理

旧的无 provider 发布入口和 `markFieldBootstrapComplete(Level)` 不属于当前契约。迁移为初始化注册域、每 Level session、每查询 coverage；客户端改为消费服务端结果。构造实体快照需提供 assigned 值和 observationSource，presence 使用三态而非布尔值。旧 GE 内部类型调用应改为上述支持包，wire 和姿态内部接口没有兼容层承诺。

非有限查询/结果、负 revision、无效 namespaced ID 等会被拒绝。重复注册通常为 IllegalArgumentException，迟注册/非法生命周期为 IllegalStateException；provider 回调错误带上下文传播。INCOMPLETE 只表达预期的发现不确定性，不应吞掉代码异常。

发布源的持久化是内容方的责任，发布 lease 和查询 coverage 不持久化；实体赋值的保存与同步由公开写入入口委托引擎处理。引擎记录只校验当前字段结构，不保存独立格式号，也不提供旧结构迁移。网络注册直接使用 loader metadata 的 MOD 版本，不另设协议版本。

## 编译与验证

在仓库根目录，用匹配的 Java 21 环境执行：

```powershell
.\targets\neoforge-1.21.1\gradlew.bat -p targets/neoforge-1.21.1 :verifyApiConsumerImports :compileApiArtifact :javadoc --offline --console plain
```

生成的 Javadoc 位于 `targets/neoforge-1.21.1/build/docs/javadoc/index.html`。`compileApiArtifact` 使用构建出的 GE JAR 和平台依赖编译消费者 fixture，排除 common 输出目录和可选模组依赖；它证明调用可编译，不证明游戏运行行为。

行为验收使用 [AutoJS 驱动](../scripts/verify-autojs.py) 与 [仓库场景](../scripts/autojs)。文档能力清单不替代真实客户端、多人延迟或可选模组交互验收。使用现有已接受 EULA 的隔离实例，不自动创建许可记录。
