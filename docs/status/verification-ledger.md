# 交付验证台账

> 用途：把「崩溃安全 / 内存·枪械 LOD / 性能 / 动画」四类修复与**验证证据**逐条对齐。
> 每条都给出提交、守护测试和可复现的命令，避免"改了什么"只能从对话历史里翻。

## 0. 产物与总体状态

| 项 | 值 |
| --- | --- |
| jar | `kinetic-3.0-dev-forge+mc1.20.1.jar` |
| 字节数 | 17,717,131 |
| sha256 | `bcdddc1c8ed85fe312d7c664d710533ccbf3fb02d60cc705d7859891e223bc02` |
| 测试 | 771 tests / 0 failures / 10 skipped |
| 文档自检 | `python3 docs/tools/check_docs.py` → `OK: documentation tree is self-contained` |

复核命令：

```bash
source gradle-env.sh   # 本机提供的 Gradle/JDK 环境脚本
LANG=C.UTF-8 LC_ALL=C.UTF-8 ./gradlew build --offline --console=plain
sha256sum build/libs/kinetic-3.0-dev-forge+mc1.20.1.jar
python3 docs/tools/check_docs.py
```

## 1. 崩溃安全

### 1.1 顶点缓冲预留越界（曾导致启动崩溃）

- **症状**：`BufferBuilder.kinetic$reserve` 抛 `IndexOutOfBoundsException: Range [0, 0 + 20304) out of bounds for length 1536`。
- **根因**：把**顶点数**传给了按**字节**计容的 `ensureCapacity(int)`。
- **修复**：`BufferReservation.bytesFor()` 做溢出拒绝，`fits()` 复核；放不下时返回 `null` 交给 `FallbackVertexWriter`，不再"预留后丢弃"。
- **提交**：`8b48fcf`
- **守护**：`BufferReservationTest`（含常量池扫描，防止调用点再退化）

### 1.2 控制器异常隔离

- **症状**：任一谓词抛 `RuntimeException` 会直接冒到渲染帧，整个游戏崩溃。
- **修复**：`AnimationProcessor.tickAnimation` 里 `controller.process` 包 try-catch，只跳过该控制器这一帧；**只吞 `RuntimeException`**（`Error` 继续冒泡）；日志按 1200 tick 限流。
- **提交**：`a9b1509`
- **守护**：`AnimationProcessorFailureIsolationTest`（源码级三点断言）

### 1.3 Curios 跨线程访问

- **症状**：`CuriosCompat` 的 FIXME——`CuriosApi` 的 `ItemStackHandler` 非线程安全，而 native extract / 模型烘焙在 worker 上。
- **修复**：三个 `hasAnyItemEquipped*` 先过 `onRenderThread()`；非渲染线程返回 `false`（不崩）；无 GL 的离线环境探测失败时放行以保持原行为。
- **提交**：`f0f7195`
- **守护**：`CuriosCompatThreadGuardTest`

### 1.4 片段着色前的数值安全

- `kinetic.prone_roll` 经 `ease()` 的 clamp 恒落在 `[0,1]`，所以 `math.sqrt(prone_roll)` 不会产生 NaN。
- `LinearBlendTransition` 长度 0 时 `get()` 返回 1（源码里已有 `ticks != 0` 判断），不会 0/0。
- **守护**：`LinearBlendTransitionTest`（提交 `6af5296`）

## 2. 内存 · 枪械 LOD 预热

**结论：预热已整体消除，不是"未修复"而是"移除"。**

移出的规模与完整性（可复现）：

```bash
unzip -l build/libs/kinetic-3.0-dev-forge+mc1.20.1.jar | grep -ci "gunlod\|gun_lod"   # → 0
grep -ri "gunlod\|gun_lod" src/ | wc -l                                               # → 0
find todo/gun-lod/src -name "*.java" | wc -l                                          # → 38
find todo/gun-lod/src -name "*.java" -exec cat {} + | wc -l                            # → 7215
```

| 项 | 移出前 | 移出后 |
| --- | --- | --- |
| jar 内 LOD 类 / 资源 | 38 个类 + 进度条 overlay | **0** |
| `src/` 内引用 | 接线点散布 5 处 | **0** |
| 配置项 | `gun_lod` / `gun_lod_prewarm` / `other_player_gun_lod` 等 11 个 | **0**（旧配置残留段被 Forge 忽略） |
| 预热磁盘 | 1000 把约 250 MB | **0** |
| 预热 CPU | 单核约 5%，1000 把约 17 分钟 | **0** |

因此"LOD 预热内存"这一项在当前构建里不存在可占用内存的路径。

**未做**：重做。它需要产品先回答三个问题（可辨识轮廓的底线 / 是否降级自己的枪 / 250 MB 磁盘是否可接受），
材料见 [枪械 LOD 裁决材料](../future/gun-lod-decision.md)。

## 3. 性能

`ProneAction.advance` 每个渲染帧、每个被跟踪玩家各跑一次，是动画侧最热的纯逻辑路径。
`ProneActionThroughputTest` 用 20 万次预热 + 100 万次计时量出单次成本：

```bash
LANG=C.UTF-8 LC_ALL=C.UTF-8 ./gradlew test --offline --tests "*ProneActionThroughputTest"
# 报告里的 system-out：
# [perf] ProneAction.advance = 47.8 ns/call (1000000 calls in 47 ms)
# [perf] ProneAction.ease    = 47.8 ns/call
```

断言上限 2000 ns/call，留了约 40 倍余量吸收 CI 抖动；目的是发现"这条每帧路径上出现分配或哈希查找"
级别的退化，而不是精确 benchmark。

其余性能结论（无新增逐帧分配）：

- `PronePredicate.detect` 的物品分类只在 `isUsingItem()` 为真时执行；
- `PlayerAnimatableCapability` 的皮肤变体判定已从"每帧轮询"改为"事件 + 失效集合驱动"（提交 `987d98e` 之前的工作）。

## 4. 动画

| 场景 | 修复 | 提交 | 守护 |
| --- | --- | --- | --- |
| 滑铲与趴下互斥 | `ProneAction.isProne` 显式排除 `SlideLock.isLocked` | — | `SlideLockTest` / `SlideLockWiringTest` |
| 近战（趴下挥砍） | 0.30s 手写连续弧线（原来 IK 逐帧解，相邻帧差 232°），手腕沿 −Z 前挥 | `a737504` | `ProneWiringTest`、`PronePoseDataTest` |
| 近战（站起后残留） | 挥动结束且不再趴着时 `STOP` 趴下变体 | `05c7c14` | `ProneWiringTest` |
| 打药 | 0.72s 节奏、左手贴体侧、腿伸直、最低点 −1.01 | `ed975bc` | `PronePoseDataTest` |
| 死亡 | 布娃娃捕获死亡瞬间的 KINETIC 骨骼，不退回僵直站姿 | — | `KineticRagdollMathTest`（映射/零跳变/关节范围） |
| 侧躺过渡 | 站姿动作途中趴下**当帧即完整侧躺**（`CATCH_UP_PROGRESS=1.0` + 频道过渡 0），进入 0.15s / 退出 0.24s | `ed975bc`、`0d49ab6` | `ProneActionTest` |
| 换弹 / 掐雷 | 左臂保持撑地（改体侧后最低点 −1.09 → −1.81，roll=70 时不划算） | `6af5296` | `PronePoseDataTest` |

## 5. 已知限制（明确未做）

| 项 | 原因 |
| --- | --- |
| 枪械 LOD 重做 | 需产品裁决，见上文 |
| 第一人称手臂 / 背景渲染 | `RenderArmEvent` 与背景层调用仍被注释；启用必须实机验证 |
| Curios worker 路径 | 已加线程门，但 worker 上"条件为 false"的观感没有实机验证 |
| 动画求值的事务性 | 控制器级已隔离，但被跳过的那一帧仍是"部分更新"（同帧更早的控制器写入不回滚） |
