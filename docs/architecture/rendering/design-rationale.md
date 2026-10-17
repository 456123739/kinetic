# 渲染决策理由

本文记录渲染职责与数据布局的工程取舍；产品入口见[一致渲染模型决策](../../product-decisions/requirements/req-render-model-consistently.md)，权威分层见[文档政策](../../governance/documentation-policy.md)。

## Native 只承担有实际消费者的能力

Catalog、metadata transfer、remote cache 和 selection 由 Java 领域层拥有；native renderer 只消费已经验证、烘焙和求值完成的数据。跨语言系统不要求两侧拥有对称的 identity API 或状态机：在 native 没有直接消费者时预先复制这些机制，只会产生第二份 authority、生命周期和错误路径。

格式标准仍然约束所有实现。只有 native 真正需要读取某项格式事实或提供对应能力时，才增加最窄的输入边界；不为架构外观对称提前建立 adapter、session 或 cache 管理 API。

## 派生 cache 按精确输入隔离

同一模型在不同容器、纹理或能力配置下可能产生不同热数据，业务身份不足以证明可复用。精确 key 由[模型管理](../model-management/storage-and-cache.md)生成并封装，renderer 只提供[Bake profile](bake-and-partition.md#三种表示)；不把 serialized cache 提升为跨实现格式。

## 渲染不建立第二套 residency

Entity、GUI 与 required default 复用[模型管理的资源生命周期](../model-management/ownership-and-lifecycle.md)，渲染只持有 lease 和 entity-local mutable state。另建 target cache、default pin/preload owner 或引用计数会让同一 baked target 出现两套加载、淘汰和关闭判断。临时输出仍由固定 owner 释放，避免容量敏感对象等待 Cleaner。

## 输出路径等价不构成通用恢复

[输出等价契约](../../product-decisions/decisions/geometry-regions.md#bcoutput-paths-are-visually-equivalent)只要求受支持路径保持作品语义。共同求值或绘制阶段的错误不因切换顶点输出路径而消失，恢复仍由受影响目标及故障 owner 判断；不能把另一条输出路径视为默认可用的恢复机制。

## 逐帧配置读取与皮肤解析不缓存

渲染热路径里的逐帧 `ClientConfig.X.get()` 刻意不缓存。Forge 47.4.3 的 `ConfigValue.get()` 返回缓存字段，实测约 1.5 ns/次（40 玩家 / 144 fps 下约一个核心的 0.002%）；换成永久字段缓存还需要在 `ConfigCheckBox.onPress` 挂钩子，因为配置界面直接改写值、**不**触发 `ModConfigEvent`。用一个无法自动失效的新缓存面去换可忽略的成本不成立。

`VanillaPlayerRenderEvent` 的皮肤解析同样不缓存。稳态解析约 10–20 ns 且不分配；真正会使缓存失效的转变（`SkinManager` 写 `PlayerInfo.skinLocation`）没有可挂的钩子，而皮肤变体路径已经拥有消费一次的 `SkinVariantInvalidation`。再加一层缓存只会多一个无法保证失效的副本。逐帧缓存与去分配的当前清单见[实体与帧状态](../animation/entity-and-frame-state.md#逐帧缓存与分配)。
