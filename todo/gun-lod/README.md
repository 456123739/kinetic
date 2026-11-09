# 枪械 LOD（暂存，未打包）

这份代码在 **3.0-dev 之后被整体移出模组**，不再参与构建、不再随 jar 发布。保留在这里是因为
它记录了大量实测数据（面数、内存、缓存 ABI、TaCZ 枪包结构），将来重做时可以直接拿来用；
但**当前实现不作为产品能力**，也不要在它上面继续打补丁。

## 为什么移出

- 引擎需要大改：减面策略、按持有回收、预热并发与内存上限都是按"先跑起来"写的，
  在 16 GB / 无独显的机器上仍然容易被枪包数量放大。
- 它会同时改三类行为：别人的枪换成简化几何、远处停掉枪械状态机动画、后台烘焙落盘。
  三者耦合在一个开关面里，调一个参数要同时验证三件事。
- 产品上还没有决定"枪械 LOD 是必须保持可辨识轮廓的降级表现，还是纯性能优化、允许 best-effort"。
  这个裁决没下来之前，继续加代码是在替产品做决定。
  **裁决所需的实测数据、代价与三个待答问题整理在
  [docs/future/gun-lod-decision.md](../../docs/future/gun-lod-decision.md)。**

## 移出后模组的行为

- 所有玩家（包括其他玩家）的枪械一律渲染 TaCZ 的完整模型，**不再**按距离替换几何。
- **不再**按距离停掉枪械状态机动画（换弹、拉栓、开火等在所有距离都正常播放）。
- **不再**有后台预热、LOD 进度条、以及 `gun_lod` / `other_player_gun_lod` 配置项。
- 枪 id 环形记忆与手枪判定缓存保留（它们服务于副手枪的 locator 摆放），资源重载时由
  `TACZCompat.clearGunCaches()` 清理。

旧配置文件里残留的 `gun_lod` / `gun_lod_prewarm` / `other_player_gun_lod` 段会被 Forge
忽略，不会报错。

## 目录

```
todo/gun-lod/
  README.md                      本文件
  docs/gun-lod.md                原 docs/architecture/integration/gun-lod.md（机制、数据、失败边界）
  src/main/java/...              14 个引擎类 + 预热进度条 + 进度条配置
  src/test/java/...              21 个离线单测（减面、缓存、指纹、预热策略、磁盘往返）
```

这些文件不在 Gradle 的 source set 里，`./gradlew build` 不会编译它们；改动它们不会影响 jar。

## 要恢复时

1. 把 `src/main` 与 `src/test` 按原包名搬回 `src/`。
2. 重新接回这几处（都已在移出时删除）：
   - `TacCompatInner#getGunLod` / `clearGunLodCache` / `allGunIds` / `prewarmGunLod` / `hasPendingGunLod`
   - `TACZCompat` 的同名门面
   - `CustomPlayerItemInHandLayer` 的 `renderGunLod` 分支（主手 + 副手）
   - `TacCompatInner#playGunMainAnimation` 里的 `GunAnimationLod.isAllowed` 判定
   - `ClientTickEvent` 的 `GunLodPrewarm.tick()` / `GunLodRegistry.sweepIdle()`
   - `ClientSetupEvent` 的进度条 overlay 与 `TACZCompat.clearGunCaches`
   - `ClientConfig` 的 11 个 LOD 配置项
3. 重做之前先确定产品裁决（见上），再决定是继续用这套缓存/减面，还是换成别的方案。
