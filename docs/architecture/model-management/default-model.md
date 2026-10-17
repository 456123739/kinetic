# Intrinsic default

Intrinsic default 按[分类降级与启动基线](../../product-decisions/decisions/model-fallback.md#dddefault-model-is-reliability-baseline)承担启动硬依赖。构建期 contract 固定它的 `ModelId` 和默认动画集合；运行时从 builtin raw source 生成容器并再次校验身份。

## 内置模型库与皮肤映射

模组只内置原版玩家模型，不再有独立的 `default` 模型：

| 内置模型路径 | 角色 |
|---|---|
| `misc/2_steve` | intrinsic default；宽手臂（default skin），同时作为共享默认动画的提供者 |
| `misc/1_alex` | 窄手臂（slim skin）玩家模型，动画经默认动画回退继承 Steve 的集合 |

- 路径常量唯一来源是 `BuiltinModelIndex.INTRINSIC_DEFAULT_PATH` / `SLIM_PLAYER_PATH`；构建期 `gradle/builtin-index.gradle` 与 `BuiltinModelIndexTool` 的 default root 必须与之一致。
- 皮肤类型只在客户端可知，取值来自 `AbstractClientPlayer#getModelName()`（其下层是 `PlayerInfo#getModelName()`）：`slim` → `misc/1_alex`，其余 → intrinsic default。映射函数是 `BuiltinModelIndex.playerPathForSkin`。
- 宽/细手臂与皮肤的一致性分两层保证。会话层：客户端在选择请求里发送按皮肤映射的模型 hash，服务端只负责在客户端未给出可用选择时回退到 intrinsic default。渲染层：客户端在每次模型更新前，把「当前选择属于这两个内置玩家模型」的玩家收敛到与当前皮肤一致的变体，作为实际绘制的依据。第二层不可省略——服务端既看不到皮肤，也只在会话协商时对齐一次，玩家在游戏中换皮肤（含在皮肤类模组里切换宽/细）之后不会重新协商，只靠第一层会让变体停留在登录时的宽度，并与皮肤贴图错位。两层都只作用于这两个内置玩家模型：当前选择是别的模型时客户端不改写选择。
- 原 `default/animations/**`（含 `parcool.animation.json`）已按动画名合并进 Steve 与 Alex 的动画集；模型自带同名动画优先。合并后 Steve 的动画集合即构建期 contract 的 default animations，Alex 中与之逐字节相同的条目由既有 dedup 过滤移除并在运行期回退。
- 构建期 `generateBuiltinDefaultContract` 仍会物化 intrinsic default 以冻结身份与默认动画集合，但它的输入已改为 `misc/2_steve`；它只从源码树生成，不会把已删除的模型重新带回产物。

Client 在 private service candidate 内完成 intrinsic default 的 parse、bake、default render target、animation 与 residency 验证，随后才发布唯一可达的 model service。任一步失败都关闭尚未发布且 ownership 固定的 candidate resources，并传播原始 cause；系统不暴露 partial service、readiness enum、startup failure cache 或 timeout recovery。Optional builtin work 只在 service publication 之后沿普通 catalog owner 启动，不能回滚 required publication。

当前构造由 builtin raw source 直接生成内存中的容器 bytes，再通过 `ManagedContainer.openResidentDefault` 完成 descriptor、lazy chunks 与驻留表示。Client 在 private candidate 中完成 default render 初始化并持有 required lease；dedicated server 只保留本侧只读内容。路径不创建临时容器、delegate handoff 或清理目录，缓存命中也不能跳过每次启动物化。设计理由见[默认内容不建立磁盘缓存](design-rationale.md#默认内容不建立磁盘缓存)。

Default 的独立生命周期与启动失败边界见[默认基线契约](../../product-decisions/decisions/model-fallback.md#bcdefault-startup-is-required)；会话失败降级见[session-failure](../../product-decisions/decisions/session-failure.md)。Selection 由 ID 17 `PlayerStateUpdate.model` / `ModelSelectionState` 表达，独立于 model-session collection publication。

`builtin_default` 在 wire 上不携带模型 hash；client 将其应用到 player 时确定性映射到本进程常驻 default 的 `ModelId`，并作为 primary binding 使用。只有其他权威目标不可用，或尚无可应用目标时，default target 才带 fallback 标记；wire authority 与 client-local applied identity 因此是单向投影，不构成第二套选择 authority。

## 默认资产构建设计

默认来源、两侧物化职责和驻留范围由[model-fallback](../../product-decisions/decisions/model-fallback.md)定义；无磁盘中间表示的设计与工程理由统一见[默认内容不建立磁盘缓存](design-rationale.md#默认内容不建立磁盘缓存)。
