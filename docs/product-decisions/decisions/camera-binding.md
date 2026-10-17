# camera-binding

- Requirement: [REQ.integrate-mod-content](../requirements/req-integrate-mod-content.md#reqintegrate-mod-content)
- Select: 真实相机（Real Camera）的相机绑定解算
- Needs:
  - 界定外部玩法职责: [DD.rendering-only-replacement](visual-gameplay-boundary.md#ddrendering-only-replacement)
  - 可选依赖不兼容: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
  - 状态读取与输入: [DD.client-evaluates-visible-state](animation-sampling.md#ddclient-evaluates-visible-state)
  - 联动内容组合: [DD.compose-external-models-and-animation](mod-integration.md#ddcompose-external-models-and-animation)
  - 工作负载预算: [DD.main-loop-has-priority](workload-budget.md#ddmain-loop-has-priority)
- Landing:
  - [architecture](../../architecture/integration/camera-binding.md)
  - [status](../../status/known-issues/rendering.md#相机绑定)

## DD.camera-binding-comes-from-the-model

- Constraints: [CON.visual-only-scope](../shared.md#convisual-only-scope)
- Claim: 真实相机需要的相机绑定位置与朝向由 KINETIC 直接从自身已烘焙的模型几何与骨骼姿态解算，不再由对方每帧重画模型、捕获顶点、再按 UV 反查图元。
- Rationale: 对方自带的做法每帧多出 4~8 次模型烘焙与提交，发生在关卡渲染之外、KINETIC 渲染上下文与正常帧不一致的位置；而且只要 KINETIC 那一遍渲染没有产出几何（模型正在切换、原生状态未就绪、贴图未知），捕获到的顶点数就是 0，绑定判失败，对方在连续失败若干帧后会把整个模组关掉——用户看到的是"相机绑定短暂失效、身体消失"。KINETIC 的几何与骨骼姿态本来就在手边，直接解算既准确又不引入额外绘制。反过来继续依赖顶点捕获，等于把绑定正确性押在"这一帧模型一定被画过"上，而这个前提在死亡、换模、切皮肤时并不成立。

### BC.camera-binding-matches-the-rendered-model

- Claim: 绑定结果必须与同一帧画出来的模型严格重合。模型姿态与绘制共用同一份实现，绑定不得自行复制一套姿态推导。

### BC.camera-binding-adds-no-draw-work

- Claim: 解算层不得提交任何几何，也不得触发任何原生烘焙；给出结果后，对方的顶点捕获路径与兜底重画都不得执行。

### BC.camera-binding-yields-on-missing-input

- Claim: 模型未加载、未烘焙、贴图未知、任一绑定 UV 在模型上找不到面、面法线退化——任何一种情况都必须返回空结果并让位给对方自己的兜底路径，不得把部分结果或非有限值交给相机。

### BC.camera-binding-follows-the-ragdoll-after-death

- Claim: 本地玩家死亡、且其尸体正由 KINETIC 模型绘制时，绑定来源改为该布娃娃；不得继续绑定已被提供方从渲染中摘除的玩家模型，否则绑定必然失败并触发对方自我关闭。

### BC.camera-binding-does-not-fight-the-provider-death-camera

- Claim: 布娃娃提供方自带的死亡相机仍然优先。提供方在自己的视角事件里最后写入相机位姿，本层不得改变这一顺序或与之争夺同一帧的最终位姿。

### BC.camera-binding-is-independently-disableable

- Claim: 模型绑定与死亡后改绑布娃娃各自可独立关闭；关闭后行为与没有本层时完全一致。本层缺失、关闭或解析失败都不得影响相机可用性，也不得影响其它联动。

### BC.camera-binding-is-a-clean-implementation

- Claim: 本层只使用真实相机的公开 API 与数据语义，不复制其实现代码，也不修改对方任何状态；对方缺席时不得加载其任何类型。
