# 第三方素材声明

本分支（`resources`）用于离线归档开发过程产物，其中包含少量**第三方**内容。
它们不属于 Kinetic 模组本体、不随模组 jar 分发、也不在本项目许可证覆盖范围内；
仅作为分析输入/参考资料存放，版权归各自作者所有。

| 位置 | 内容 | 来源 | 处理 |
| --- | --- | --- | --- |
| `bundles/gunpacks-eos.tar.zst` | TACZ「eos」枪包（模型、贴图、动画、lua、音效） | 上游枪包发布页（含 `eos.zip`，15,677,704 字节） | 仅作 LOD/动画分析样本；如需移除请告知 |
| `bundles/ragdollified-src.tar.zst` | Ragdollified 1.1.1 / 1.2.2 的 Vineflower 反编译源码与 1.2.2 原 jar | Modrinth/CurseForge 发布件 | 仅用于确认兼容层依赖的 API 行为 |

其余归档（渲染图、分析图、崩溃报告、日志、`analysis/` 工具链）均为本项目自身产物。

仓库为私有；若将来转为公开，上述两项第三方内容应先剥离。
