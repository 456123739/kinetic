# 滑铲锁定（持枪滑铲）

> **适用问题**：ParCool 滑铲期间持枪/开火时，为什么下肢和躯干必须由滑铲独占、上半身只能出持枪姿势，
> 以及这套锁定在哪里结束；**不包含** ParCool 的滑铲动作本身和 TaCZ 的枪械机制。

## 现象与根因

滑铲时玩家向后躺、脚朝前，`parcool:sliding` 把 `Root` / `AllBody` / `UpBody` / `UpperBody` 和整条腿
都写成了这个姿态。持枪姿势 `tac:hold:rifle` 自带一条 `AllBody` 轨道（按 `kinetic.head_yaw` 微调朝向），
它会把滑铲的 `AllBody` 直接覆盖掉——于是整个人从"脚在前、头在后"翻成"头在前、脚在后、身体向下拱"。
pistol / rpg 的 hold 动画没有 `AllBody`，所以这个问题只在长枪上明显。

另一条路径发生在滑铲动作结束之后：ParCool 会换成 `CrawlAnimator`（它没有 KINETIC 动画名），
玩家的 `Pose.SWIMMING` 还在，主频道于是切到 `climb`/`climbing`，开火频道再叠
`tac:climbing:fire:*`——整身趴地。

> 注：ParCool 的 `Slide#onWorkingTick` 每 tick 都会自己 `setSprinting(false)`，所以"TaCZ 开火取消疾跑
> 导致滑铲中断"是误诊；曾经加过的两个疾跑注入已经删除。

## 机制

锁定由三部分组成，缺一不可：

1. **状态机** `SlideLock`（`client/animation/SlideLock.java`）：主手持枪 + ParCool 报出
   `parcool:sliding` 时进入锁定。它只接收"是否还在滑铲、姿态、速度、是否持枪、是否死亡/游泳/骑乘"
   这些原语，**不接收开火/挥动/使用**——开火在结构上无法影响它。
2. **跑酷频道续播**（`ParCoolPredicate`）：锁定期间即使 ParCool 停报滑铲，也继续把
   `parcool:sliding` 喂给同一个控制器。动画名与循环类型不变，`AnimationPlayer#setAnimation`
   会直接返回，播放头原地继续，不会重播入场的翻转。
3. **骨骼级回写**（`AnimationProcessor`）：`player.parcool` 控制器处理完之后，抄下
   `Root, MAllBody, AllBody, UpBody, UpperBody, DownBody, 双腿/脚, AllHead/MHead/Head` 的旋转、位移、缩放；
   等 `main / hold / fire / swing / use` 全部跑完再把它们写回，并标记为"本帧有动画"以跳过尾过渡。
   **任何**频道（包括将来新增的）都改不动这些骨骼。

头也必须一起锁：持枪姿势的 Head/AllHead 是按站直的身体写的，叠到滑铲（AllBody 约 -174°）上
之后朝向会变成"低头朝后下方"；实机表现就是趴在地上往地面开枪。锁住滑铲自己的头部后，
头部前向量是 (0.21, 0.13, -0.97)（正前方），验证脚本会断言这一条。

上半身由 hold 频道的静止持枪姿势负责：`MainhandPredicate` 在锁定期间直接返回
`tac:hold:<type>`，并且绕过"挥动/使用就暂停"；`GunFirePredicate`、`SwingPredicate`、`UsePredicate`
在锁定期间返回 `STOP`。主频道（`PlayerMainPredicate`）整帧让位，`climb/climbing` 也抢不到身体。

## 释放

| 条件 | 行为 |
|---|---|
| ParCool 仍在滑铲 | 一直锁 |
| 跑酷停报滑铲、但玩家仍以 >0.1 格/tick 前滑 | 最多再锁 40 tick（2 秒），覆盖一次连发 |
| 速度掉到爬行水平并持续 20 tick | 释放 |
| 站起（`Pose` 不是 `SWIMMING`）/ 游泳 / 上车 / 死亡 / 换成别的跑酷动作 | 立即释放 |
| 离地超过 3 tick | 释放 |

释放后跑酷控制器自然淡出，主频道重新接管；不会留下"永久滑铲"。

## 验证

- `SlideLockTest`：9 个逐 tick 场景（持续滑铲、停报后的保持窗口、站起/换动作/低速/死亡/游泳/骑乘/离地、
  重新入锁、骨骼集合）。
- `analysis/slide_lock_verify.py`：用真实模型与真实动画做 FK，断言锁定骨骼与滑铲本体逐元素相等、
  头脚朝向不翻转、上半身确实被持枪姿势替换，并带"修复前会翻转"的负向对照。
  对 2_steve / 1_alex 与 rifle/pistol/rpg 三种持枪姿态都通过。
- `docs/status/support-and-verification.md` 记录的客户端边界仍然适用：这里能证明姿态合成与状态机，
  不能替代实机手感确认。
