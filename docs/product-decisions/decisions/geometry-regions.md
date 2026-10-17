# geometry-regions

- Requirement: [REQ.render-model-consistently](../requirements/req-render-model-consistently.md#reqrender-model-consistently)
- Select: 受支持表面语义、艺术技巧例外与输出等价
- Needs:
  - 维护既有作品: [DD.explicit-support-is-stable](model-compatibility.md#ddexplicit-support-is-stable)
  - 骨骼姿态输入: [DD.hierarchical-bone-animation](bone-and-controller.md#ddhierarchical-bone-animation)
  - 保守默认与显式启用: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
- Landing:
  - [architecture](../../architecture/rendering/bake-and-partition.md#四逻辑分区)
  - [architecture](../../architecture/rendering/vertex-output.md#变换与面语义)
  - [architecture](../../architecture/rendering/vertex-output.md#java-侧背面裁剪开关)
  - [architecture](../../architecture/rendering/vertex-output.md)
  - [status](../../status/known-issues/rendering.md#正确性与失败处理)
  - [status](../../status/known-issues/rendering.md#背面裁剪与枪械-lod)
  - [standard](../../standards/conformance.md#视觉一致性验收)

## DD.authoring-semantics-before-optimization

- Claim: 优化和输出路径变化必须保持受支持的几何、表面、材质与姿态语义，并维护明确接纳的艺术技巧例外。
- Rationale: 创作者依赖 Blockbench 的受支持效果，不能为内部优化重做作品。负尺寸描边借助剔除优化反向形成艺术效果，属于已明确接纳的典型例外，必须保留其背面外壳；这不把所有类似技巧提升为无条件支持。完整正尺寸不透明块可省去被遮挡的背面，不完整块和透明正尺寸块则须保留可见背面。不同输出路径不能变成需要分别适配的作品格式。

### BC.authoring-surfaces-remain-equivalent

- Claim: 受支持模型的形状、骨骼姿态、UV、正反面和透明语义应与创作结果一致；Minecraft 的环境光照、游戏特效与 shader 差异不自动构成逐像素相同的承诺。

### BC.negative-size-keeps-outline-semantics

- Claim: 三轴均为负的有效 Cube（含三轴镜像）保留通过正面剔除形成背面外壳的视觉语义，不能仅因尺寸为负而拒绝、取绝对值或改变可见面。

### BC.culling-uses-original-cube-structure

- Claim: 完整性按 Cube 的原始六面结构判断，不随纹理透明洞或全透明面省略而重定义；完整块的正尺寸资格要求三轴均正，负尺寸资格要求三轴均负，零厚度不获得这两类完整块剔除资格。

### BC.culling-has-explicit-observation-scope

- Claim: 一轴、二轴镜像及从模型内部观察不属于上述可见面保证范围；三轴镜像属于明确维护的负尺寸正面剔除表现。

### BC.opaque-positive-cube-culls-back

- Claim: 完整、完全不透明的正尺寸块使用背面剔除。

### BC.complete-negative-cube-culls-front

- Claim: 完整负尺寸块维持其正面剔除语义，无论它属于不透明还是透明区域。

### BC.visible-backfaces-are-double-sided

- Claim: 不完整的块和透明的正尺寸块不剔除其可见背面，以免观察角度改变时作品缺面。

### BC.output-paths-are-visually-equivalent

- Claim: 在相同模型、姿态和宿主绘制条件下，不同受支持输出路径保持几何、正反面、光照方向、透明及材质输入语义等价。

### BC.bake-pruning-must-match-the-draw-texture

- Claim: 「整面全透明就删面」只允许在烘焙贴图与绘制贴图相同的前提下进行；绘制贴图可被替换的模型（玩家模型绘制时使用玩家皮肤，模型自带贴图只是回退值）必须按不删面的语义烘焙——几何由烘焙决定，透明性由绘制期的 alpha 裁剪按实际绘制贴图决定。否则模型自带贴图里恰好透明、而实际绘制贴图里不透明的部位会被永久删除。
- Rationale: 玩家模型的外观由玩家皮肤决定，模型自带贴图不代表最终可见性。按自带贴图删面曾使内置宽/细手臂模型的手臂叠加层（以及手、眼皮、瞳孔、眉毛）在部分皮肤下整块消失——那是把「优化的前提」当成了无条件规则，而不是 [BC.authoring-surfaces-remain-equivalent](#bcauthoring-surfaces-remain-equivalent) 的自然结果。
- Consequence: 玩家模型不能再靠「把 UV 指向全透明 texel」隐藏几何，隐藏必须走骨骼/方块的显式隐藏通道；透明区域较大的玩家模型顶点数会上升。

## DD.gpu-backface-culling-is-explicit-opt-in

- Claim: KINETIC 默认不在 GPU 侧对模型做全局背面裁剪；`CullBackFaces` 默认关闭，只有玩家显式开启后才对 KINETIC 模型生效。
- Rationale: native 烘焙已经对完整正尺寸不透明块做过内部背面剔除（封闭六面块只发 3 个 quad），GPU 侧再剔除对这类几何没有收益；而 TaCZ 枪包大量使用刻意单面或空心的块（ak47 617 个方块里 533 个不足 6 面，`eos_helenas_nail` 3053 个里 3013 个不足 6 面、其中 1504 个只有单面），它们的背面是有意绘制的，全局裁剪会删掉这些面、在模型上开洞。因此默认关闭是实测结论而非预防性保守：离线几何剔除测试（四个 TaCZ 夹具）显示完整封闭的 `eos_bayonet`（399/399 六面块）逐像素相同，但 ak47 丢 169 px、hk416d 丢 26 px、`eos_helenas_nail` 丢 2083 px（LOD 2550 px）。这与 [DD.animation-presentation-rate-is-bounded](animation-sampling.md#ddanimation-presentation-rate-is-bounded) 遵循同一条原则：默认值取安全、普适的一侧，会改变外观或可能破坏第三方内容的能力默认关闭，由用户主动选择。

### BC.gpu-backface-culling-defaults-off

- Claim: GPU 背面裁剪默认关闭；显式开启只影响本机呈现，开启后不保证单面或不完整块的背面可见，也不改变其他客户端看到的模型。默认行为仍满足 [BC.visible-backfaces-are-double-sided](#bcvisible-backfaces-are-double-sided)，显式开启是对该保证的本地主动放弃。
