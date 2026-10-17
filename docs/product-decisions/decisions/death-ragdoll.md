# death-ragdoll

- Requirement: [REQ.integrate-mod-content](../requirements/req-integrate-mod-content.md#reqintegrate-mod-content)
- Select: 玩家死亡布娃娃（Ragdollified）的可见身体
- Needs:
  - 界定外部玩法职责: [DD.rendering-only-replacement](visual-gameplay-boundary.md#ddrendering-only-replacement)
  - 处理高风险侵入或例外: [DD.intrusive-change-needs-adjudication](integration-risk.md#ddintrusive-change-needs-adjudication)
  - 可选依赖不兼容: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
  - 外部模型附着: [DD.preserve-vanilla-attachments](attachments.md#ddpreserve-vanilla-attachments)
- Landing:
  - [architecture](../../architecture/integration/ragdoll.md)
  - [status](../../status/known-issues/rendering.md#布娃娃身体)

## DD.ragdoll-uses-kinetic-skeleton

- Constraints: [CON.visual-only-scope](../shared.md#convisual-only-scope)
- Claim: 玩家死亡后由布娃娃提供方负责刚体物理，KINETIC 用自身骨骼模型承接其可见身体：死亡瞬间的骨骼姿态原样保留为初始姿态，细分关节此后继续受物理驱动，且始终不超出各自关节范围。
- Rationale: 玩家模型被替换后，提供方的人形尸体与玩家实际外观不一致；而提供方的骨架只表达有限的刚体，无法承载作品模型的完整关节。由 KINETIC 提供骨架与细分子骨骼运动，可以让尸体继续呈现玩家自己的模型，同时不改变提供方的物理裁决。反过来让 KINETIC 自行实现刚体物理，会重复维护碰撞、冲量、联机同步与生命周期，代价高于收益。

### BC.ragdoll-body-substitution-scope

- Claim: 只有当布娃娃属于玩家、且该玩家当前模型能映射到 KINETIC 骨架时，KINETIC 才取代提供方绘制的原版人形身体；护甲、饰品与血迹等其它图层仍按提供方原有方式绘制，其它实体（怪物、断肢）不进入该取代范围。

### BC.ragdoll-joint-motion-is-continuous-and-bounded

- Claim: 变成布娃娃的瞬间不得出现关节突变：细分关节的初始角度等于死亡当刻正在绘制的角度，此后由物理驱动继续运动，而不是被拉直或冻结；任何时刻关节角度都不超出其活动范围，四肢不得转入躯干内部。

### BC.ragdoll-physics-authority-stays-with-provider

- Claim: 刚体数量、形状、碰撞、冲量、击退与布娃娃生命周期仍由提供方决定；KINETIC 只读取其结果，不改变伤害、死亡、掉落或任何玩法状态。

### BC.ragdoll-distance-and-lifetime-follow-the-provider

- Claim: 布娃娃的绘制距离与存活时间只有提供方一个权威，KINETIC 不得为同一具尸体再定义一套距离或生命周期；KINETIC 侧的诊断与资源保留必须跟随提供方的配置取值，使两侧对"这具尸体是否仍在被 KINETIC 绘制"的判断始终一致。

### BC.ragdoll-keeps-the-death-wound-appearance

- Claim: 尸体必须保留死亡时的受伤外观。玩家实体在死亡后会被移除，因此该外观所依赖的模型身份与血量状态必须在死亡瞬间随姿态一并保存，不能在实体消失后回退成"无伤"。

### BC.ragdoll-substitution-degrades-locally

- Claim: 提供方缺失、骨架无法映射、姿态提取失败或注入点失配时，受影响的布娃娃回退到提供方原版人形绘制；不得出现同一具尸体绘制两份身体，也不得阻断启动或影响其它布娃娃。

### BC.ragdoll-remains-bindable-for-camera-mods

- Claim: 尸体由 KINETIC 绘制期间，相机类联动（真实相机）必须能继续绑定到这具尸体，而不是已被提供方从渲染中摘除的玩家模型；提供方自带的死亡相机仍然优先，KINETIC 不得与之争夺同一帧的最终相机位姿。相机正处在尸体头部内时，头骨内壁不得遮挡视线。

## 侵入例外记录

按 [BC.intrusion-exception-is-specific](integration-risk.md#bcintrusion-exception-is-specific) 记录本次取代外部模组绘制内容的依据。

- 触发条件：布娃娃提供方存在、玩家死亡生成布娃娃、该玩家有可映射的 KINETIC 模型，且本帧 KINETIC 已准备好替代绘制。
- 侵入点：仅取消提供方玩家身体路径中绘制原版人形基础部件的私有渲染方法；不修改其物理、网络、生命周期或其它渲染路径。
- 受影响对象：该玩家布娃娃的原版人形身体与其第二层皮肤；护甲、饰品、血迹、怪物布娃娃、断肢不受影响。
- 风险传播：提供方版本变化导致注入点失配时，KINETIC 侧拿不到接管状态，因此不会绘制自己的模型，也不会跳过提供方身体；最坏结果是本功能静默失效，而不会出现双份身体或崩溃。
- 既有防护：注入点全部声明为可缺失；接管状态逐帧建立；单个布娃娃绘制异常被隔离并回退。
- 降级可行性：具备真实可用的降级路径（回退提供方原版人形），因此不属于无法降级的侵入。
- 真实需求与理由：玩家模型被替换后，提供方的人形尸体不再代表玩家外观；作品模型的关节也无法由有限刚体表达，需要 KINETIC 侧骨架承接。
- 证据：`KineticRagdollMathTest` 覆盖初始姿态的零跳变与关节范围语义；其余为设计推导，实机视觉验收见[状态](../../status/known-issues/rendering.md#布娃娃身体)。
- 人工接受范围：仅限上述玩家布娃娃可见身体的原版人形部件绘制，不扩展到提供方其它行为。
