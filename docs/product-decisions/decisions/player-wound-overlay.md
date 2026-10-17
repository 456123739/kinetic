# player-wound-overlay

- Requirement: [REQ.render-model-consistently](../requirements/req-render-model-consistently.md#reqrender-model-consistently)
- Select: 受伤外观（按血量叠加在玩家模型上的伤口）
- Needs:
  - 状态输入来源: [DD.client-evaluates-visible-state](animation-sampling.md#ddclient-evaluates-visible-state)
  - 非视觉玩法边界: [DD.rendering-only-replacement](visual-gameplay-boundary.md#ddrendering-only-replacement)
  - 保留原版视觉反馈: [DD.preserve-game-visual-signals](game-visual-signals.md#ddpreserve-game-visual-signals)
  - 本地可控呈现: [DD.local-player-controls-presentation](local-presentation-control.md#ddlocal-player-controls-presentation)
  - 可选依赖不兼容: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
  - 复用外部作品的实现或素材: [BC.model-use-does-not-relicense-either-work](content-rights.md#bcmodel-use-does-not-relicense-either-work)
- Landing:
  - [architecture](../../architecture/rendering/wound-overlay.md)
  - [status](../../status/known-issues/rendering.md#受伤外观)

## DD.player-wound-overlay

- Constraints: [CON.visual-only-scope](../shared.md#convisual-only-scope)
- Claim: KINETIC 可以在玩家模型上叠加一层由当前血量决定的受伤外观（伤口），把它作为受击反馈的一部分；该外观只由客户端已知状态算出，不新增玩法输入、不改变任何玩法裁决，且必须能被本地关闭。
- Rationale: 换模已经接管了玩家外观，原版那条"受击变红"的反馈在模型上只剩下一瞬间的红色叠加；血量与受击持续状态在模型上没有承载物。补上伤口可以让玩家在不看血条的情况下判断对手的伤势，这与保留发光、着火、受击反馈是同一条理由——视觉替换不应让玩家丢失理解战斗状态的信息。代价是引入一份新的绘制与一份新的配置面，且必须避免与专门做同一件事的模组重复叠加。

### BC.wounds-come-only-from-client-known-health

- Claim: 伤口档位只能由客户端已经持有的实体状态（当前血量、最大血量、受击与死亡状态）算出；不得为了呈现伤口而请求、推断或同步任何新的服务端数据，也不得因为伤口而改变客户端对其他实体的任何判断。

### BC.wounds-are-local-and-disableable

- Claim: 受伤外观是纯本地呈现：不影响服务端、不影响其他玩家的画面、不写入共享状态；本地配置必须能整体关闭它，关闭后玩家模型回到与未启用时完全一致的外观。

### BC.wounds-do-not-displace-vanilla-damage-signals

- Claim: 受伤外观是叠加在原有反馈之上的附加层。受击红色覆盖层、发光轮廓、着火与环境明暗必须仍然按原版语义作用于模型；不得用伤口替换、削弱或改写这些信号。

### BC.wounds-attach-to-the-outermost-skin-layer

- Claim: 伤口必须附着在该处<b>实际存在的最外层皮肤</b>上：外层皮肤存在时贴在外层，只有外层不存在时才贴在基础层。不得出现"悬在身体外面的一层血"，也不得出现"外层皮肤在、那一层却没有血"的空洞。

### BC.wounds-preserve-recognisability

- Claim: 受伤外观是冲击力与可辨识度的平衡。它必须让对手一眼看出"这人伤得很重"，同时不得重到看不出对方原本的样子；头部（尤其是脸）是约束最强的地方，必须比躯干更克制。冲击力应当来自伤口的数量与分布，而不是少数几处大面积：单块伤口大到占满一个部位时，该部位就只剩那一块，真实度反而下降。

### BC.wounds-yield-to-a-dedicated-wound-mod

- Claim: 当存在专门实现受伤外观的外部模组时，KINETIC 的同一功能必须整体让位（默认关闭并说明原因），不得与之叠加出两套血迹。

### BC.wounds-default-to-the-builtin-player-models

- Claim: 受伤外观默认只作用于使用原版皮肤布局的内置玩家模型；其它模型的 UV 布局不保证与遮罩对齐，因此默认不画，并必须提供显式开启手段与一次性说明，避免"看起来没生效"。

### BC.wounds-are-clean-room

- Claim: 受伤外观的实现与素材必须独立产生：可以对照外部模组的可观察行为，但不得复制其代码、贴图或其它受保护内容，也不得让本模组的许可因复用而改变。
