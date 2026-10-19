package com.kinetic.client.compat.tacz.lod;

import com.kinetic.format.parser.pojo.model.Bone;
import com.kinetic.format.parser.pojo.model.Cube;
import com.kinetic.format.parser.pojo.model.CubeUv;
import com.kinetic.format.parser.pojo.model.FaceUv;
import com.kinetic.format.parser.pojo.model.GeoModel;
import com.kinetic.format.parser.pojo.model.Geometry;
import com.kinetic.format.parser.pojo.model.UvFaces;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * 枪械 LOD 减面。做四件事，顺序固定：
 *
 * <ol>
 *   <li><b>丢掉 TaCZ 从不绘制的标记骨骼</b>（正确性，不是优化）。</li>
 *   <li><b>把"实心"的零件整体折叠成一个盒子</b>（阶段 4，主要的省面手段）。</li>
 *   <li><b>对保留下来的零件做面级遮挡剔除</b>（决定每个零件画哪些面）。</li>
 *   <li><b>以骨骼为单位整体取舍</b>（阶段 2，自适应阈值，决定保留哪些零件）。</li>
 * </ol>
 *
 * <h2>为什么顺序是 1 → 4 → 3 → 2</h2>
 * 阶段 4 必须在阶段 3 之前：折叠后的盒子要复制"供体方块"的 UV，而面级剔除会把被挡住的面
 * 从 UV 表里删掉。若先剔除，复制过来的 UV 就带着空洞，盒子上会缺面。阶段 2 放在最后，
 * 是因为自适应阈值要用"折叠 + 剔除之后"的每骨骼面数来算预算。
 *
 * <h2>为什么整体取舍，而不是逐方块过滤</h2>
 * 逐方块按尺寸过滤会把零件打碎。实测第一版规则在 ak47 上把 21 个主要零件里的 15 个削成残片
 * （{@code gastube} 34→1、{@code group6} 18→1、{@code bolt} 12→1），后果有两类，离线渲染都复现了：
 * 零件只剩 1~2 个方块时与整枪不再相连，轮廓裂成 3 块（完整模型是 1 块）；而且没有任何零件看起来
 * 是实心的，机匣读起来像一叠松散板条。整体取舍从构造上排除这两类问题。
 *
 * <h2>为什么体量要同时看"最长边"和"表面积"</h2>
 * 只看最长边不普适：hk416d 的握把 {@code group14} 由 25 个小方块拼成，单块最长边只有 2.56，
 * 会被整体丢掉；而 ak47 的握把 {@code grip2} 单块最长边 3.94，能活下来。<b>同一个特征、不同作者
 * 的建模粒度不同</b>，所以只按最长边判定就会像 hk416d 那样把握把削没。表面积正好补上这个洞：
 * 它衡量"这个零件占多少可见表面"，与建模粒度无关。
 *
 * <h2>为什么还要面级剔除</h2>
 * 只删零件不够：像枪托这种大块实心零件，它内部相邻方块之间的面、以及被别的方块盖住的面，
 * 玩家永远看不到，却照样要顶点。Bedrock 的逐面 UV 里"没有条目 = 不生成这个面"（见
 * {@code GeoBuilder.addFace}），所以把被遮住的面从 UV 表里删掉即可，轮廓一个像素都不会变。
 *
 * <p>本类<b>不引用任何 TaCZ 类型</b>，只操作 KINETIC 自己的 Bedrock POJO，因此可以安全地在任何
 * 情况下加载，也便于离线单测。
 */
public final class GunLodDecimator {
    /**
     * 减面<b>规则版本</b>：任何会改变输出的代码常量或算法调整都必须把它加一。
     *
     * <p>枪械 LOD 会把烘焙产物写进磁盘缓存，而缓存键只由"枪 id + 贴图内容 + 减面参数"组成
     * （见 {@link GunLodCacheKey}）——阈值、网格阶梯、面级剔除算法这些改动不体现在任何输入里。
     * 不加这个版本号，改了规则之后玩家会一直命中旧缓存，表现为"改了代码但画面没变"。
     */
    public static final int RULE_REVISION = 1;

    /**
     * 默认零件体量下限，单位是模型单位（1 格 = 16 模型单位）。
     *
     * <p>体量度量是 {@code max(最长边, sqrt(表面积) / 2)}，见 {@link #boneMetric}。
     * 它现在是<b>绝对下限</b>：低于它的零件一定丢弃；真正的取舍阈值由
     * {@link #adaptiveThreshold} 自适应算出，见那里的说明。
     */
    public static final float DEFAULT_MIN_BONE_METRIC = 3.0f;

    /**
     * 自适应阈值的分位点：取本模型骨骼体量的 95 分位作为"这个模型的主要零件有多大"的标尺。
     *
     * <p>实测（非空骨骼，去标记后）：ak47 p95 = 12.0、hk416d 11.6、eos 24.9。用高分位是因为
     * 低分位几乎与模型规模无关（p50 三把枪分别是 5.1 / 3.1 / 3.0），拉不开差距。
     */
    public static final double RELATIVE_PERCENTILE = 0.95;

    /**
     * 相对阈值系数：{@code RELATIVE_FRACTION × p95}。
     *
     * <p>0.55 来自实测标定：三把枪的 p95 之比是 1 : 0.97 : 2.07，而"要落到同一档削减"
     * 所需要的阈值之比约为 1 : 1.6 : 3.3，所以单靠相对项还不够，另加面数预算上限。
     */
    public static final float RELATIVE_FRACTION = 0.55f;

    /**
     * 面数预算：保留体量最大的零件，直到累计面数达到"去标记后总面数"的这个比例。
     *
     * <p>这是把"普适"从口号变成事实的那一项——它直接按每个模型自己的面数预算取舍，
     * 所以任何枪包都会落进同一档削减幅度。相对阈值只能提供"至少削这么多"，
     * 预算提供"最多削这么多"，取更保守的那个。
     */
    public static final float KEEP_FACE_FRACTION = 0.45f;

    /**
     * 阶段 4 的"实心度"阈值：{@code 所有方块体积和 / 真实包围盒体积}。
     *
     * <p>实测分布（四个夹具里 170 根 ≥2 方块的骨骼，见 analysis/lod_stage4_measure.py）：
     * p25=0.242、p50=0.585、p75=1.009、p90=4.171。0.63 落在 0.615~0.643 这个间隔里
     * （两侧各留约 0.014 的余量；这是 0.4~0.7 区间内最宽的间隔之一）：
     * <ul>
     *   <li>会折叠：hk416d 的导轨 {@code rail}=0.811、{@code body_rail}=0.659、
     *       {@code lower_rail}=0.846，弹匣 {@code mag}=0.654、{@code mag3}=0.693、
     *       {@code mag4}=1.051，以及枪托/机匣/受弹口等一堆实心件。</li>
     *   <li>不会折叠：{@code mount}=0.128、{@code bolt}=0.167、{@code base}=0.181、
     *       {@code upper}=0.252、{@code octagon5}=0.286、{@code gas_block}=0.422、
     *       {@code grip2}=0.499、{@code cover}=0.523、{@code lower}=0.613、
     *       eos 的 {@code centre_part2}=0.615——这些是真正非凸的零件，包围盒体积远大于
     *       实际体积，折叠会明显改变轮廓。</li>
     * </ul>
     *
     * <p>ak47 的 {@code extd_mag}（0.427）与 eos 的弹匣（0.480/0.585）是弯弹匣，实心度
     * 低于阈值，因此原样保留：折叠它们会把弹匣的弧度填成方块，轮廓变化远超导轨。
     */
    public static final float FILL_THRESHOLD = 0.63f;

    /**
     * 阶段 4 的粗体素网格分辨率阶梯：{最长轴格数, 其余两轴格数}，从细到粗。
     *
     * <p>用户对"整根换成一个包围盒"的反馈是"弹夹有些简化过头了，不要只是一个方块，要带点轮廓；
     * 表面的沟壑什么的都可以抹平"。所以换成粗体素网格：每个"有料"的格子发一个盒子，
     * 格子里的沟壑/凸起全部抹平（这正是省面的来源），而凹截面/弯截面只在有料处留台阶，
     * 轮廓得以保留。越细的档位轮廓越准、面数越多。
     *
     * <p>实测（analysis/lod_outline_error.py，逐骨骼渲染、同一相机、裁剪到骨骼自身包围盒）：
     * 对 hk416d 的导轨/弹匣，{@code (5,3)} 的轮廓误差是 7.7%~26.7%（逐方块 AABB 的下界是
     * 5.2%~12.9%，单个包围盒是 13.8%~67.4%），{@code (3,2)} 明显更胖。所以从 (5,3) 起逐级退档，
     * 取"最细且满足 {@link #COLLAPSE_FACE_TARGET}"的那一档——面数不够省就退一档，
     * 而不是退回单个包围盒。
     */
    public static final int[][] GRID_LADDER = {{5, 3}, {5, 2}, {4, 2}, {3, 2}, {2, 1}, {1, 1}};

    /** 最细一档的网格：最长轴 5 格、其余轴 3 格。 */
    public static final int GRID_LONG = 5;

    public static final int GRID_SHORT = 3;

    /**
     * 换盒子后至少要省到的面数比例：{@code 新面数 <= 原面数 × 0.70}。
     *
     * <p>实测：hk416d 的 {@code mag} 原 173 面，5×3 网格合并后 60 面（35%）；
     * {@code rail} 原 136 面，合并后 12 面（9%）。0.70 让它们用上最细的档位，
     * 又拦住了那些"换成网格反而更费面"的小零件（整根只值 6 面时不如直接一个盒子）。
     */
    public static final float COLLAPSE_FACE_TARGET = 0.70f;

    /**
     * 体素格子相交 / 盒子合并的容差，按骨骼最长边长的<b>比例</b>取。
     *
     * <p>绝对值（1e-3 模型单位）在 float 与 double 下会翻边：实测 hk416d 的 {@code group14}
     * 在 1e-3 下合并出 8 / 10 个盒子，Java 与 Python 因此对不上。改成"最长边 × 1e-3"
     * （group14 约 6e-3）后，两种精度下所有夹具的所有折叠骨骼都稳定一致。
     */
    public static final float SLICE_MERGE_EPSILON = 1e-3f;

    /**
     * TaCZ 用 functional renderer <b>顶替</b>方块绘制的骨骼，它们的方块永远不会被画出来。
     *
     * <p>名单来自 TaCZ 自己的 {@code BedrockGunModel}：它对 {@code lefthand_pos} / {@code righthand_pos}
     * 注册 {@code LeftHandRender} / {@code RightHandRender}，对 {@code muzzle_flash} / {@code shell}
     * 注册 {@code MuzzleFlashRender} / {@code ShellRender}，渲染时走替换器而不是方块。
     */
    private static final Set<String> REPLACED_BY_FUNCTIONAL_RENDERER =
            Set.of("muzzle_flash", "shell");

    /**
     * TaCZ 用来挂"可安装配件"的节点名，取自 TaCZ 自己的 {@code BedrockGunModel} 字面量。
     *
     * <p>配件开关打开时，这些节点及其整棵子树都不渲染。注意 {@code mount}（机匣导轨）不在名单里
     * ——它是枪身的一部分，不是可拆卸配件。
     */
    private static final Set<String> ATTACHMENT_NODES = Set.of(
            "attachment_adapter",
            "additional_magazine",
            "mag_standard", "mag_extended_1", "mag_extended_2", "mag_extended_3",
            "handguard_default", "handguard_tactical",
            "carry", "sight", "sight_folded", "laser_beam",
            "bullet_in_mag", "bullet_in_barrel", "bullet_chain",
            "muzzle_flash", "shell");

    /** TaCZ 用来标记"枪握在手里时该对齐到哪里"的骨骼名。 */
    public static final String HAND_NODE_NAME = "thirdperson_hand";

    /** 面级遮挡判定的容差（模型单位）。 */
    private static final float FACE_EPSILON = 1e-4f;

    /**
     * 单个骨骼参与面级剔除的方块数上限。
     *
     * <p>遮挡判定是 O(n²)。附属包的单骨骼方块数可能远大于官方枪，超过上限就跳过这个骨骼的面级
     * 剔除——只损失一点优化，不会让构建时间失控。
     */
    private static final int FACE_CULL_CUBE_LIMIT = 2048;

    /**
     * 单根骨骼参与折叠（阶段 4）的方块数上限。
     *
     * <p>折叠是"网格格子 × 方块"的扫描，最细一档约 45 个格子、阶梯最多 6 级，所以工作量是
     * {@code 270 × 方块数}。官方枪最大的一根骨骼 508 个方块，附属包可能更多；给一个远高于实际
     * 需要的上限，只是为了让病态模型（几十万方块的单根骨骼）不会在预热线程上跑出秒级的停顿。
     * 超过上限只是不折叠这根骨骼——其余三个阶段照常，损失一点简化，不影响正确性。
     */
    private static final int COLLAPSE_CUBE_LIMIT = 16_384;

    private GunLodDecimator() {
    }

    /**
     * 便捷入口：自定义体量下限，不过滤配件。这就是生产路径使用的规则
     * （{@code TaczGunLodBuilder} 只是把阈值换成配置值）。
     */
    public static void decimate(@Nullable GeoModel model, float minBoneMetric) {
        decimate(model, minBoneMetric, false);
    }

    /**
     * 就地减面：标记骨骼 → 实心零件折叠（阶段 4）→ 面级剔除（阶段 3）→ 自适应整体取舍（阶段 2）。
     *
     * @param minBoneMetric 绝对体量下限（配置值）：低于它的零件一定丢弃
     */
    public static void decimate(@Nullable GeoModel model, float minBoneMetric,
                                boolean skipAttachments) {
        var bones = allBones(model);
        if (bones.isEmpty()) {
            return;
        }
        var parents = parentMap(bones);
        clearMarkerBones(bones, parents, skipAttachments);
        // 预算的分母：去标记后（还没折叠）的总面数。
        var totalFaces = faceCount(model);
        collapseBoxLikeBones(model);
        cullAllFaces(model);
        var threshold = adaptiveThreshold(model, totalFaces, minBoneMetric, skipAttachments);
        for (var bone : bones) {
            if (bone.cubes != null && !bone.cubes.isEmpty() && boneMetric(bone) < threshold) {
                bone.cubes = new ArrayList<>(0);
            }
        }
    }

    /**
     * 只做零件整体取舍（不做折叠、不做面级剔除），阈值是<b>绝对</b>的。
     *
     * <p>单独暴露出来是为了让测试能分别验证两个阶段："整体取舍不留残片"与"面级剔除只删被挡住的
     * 面"是两个独立的不变量。这里的阈值刻意保持绝对语义（{@code 0} = 全部保留），
     * 与生产路径的自适应阈值互不影响。
     *
     * @param minBoneMetric  零件体量阈值，{@code <= 0} 表示不按体量取舍
     * @param skipAttachments true 时不渲染 TaCZ 的可安装配件子树
     */
    public static void decimateBonesOnly(@Nullable GeoModel model, float minBoneMetric,
                                         boolean skipAttachments) {
        var bones = allBones(model);
        if (bones.isEmpty()) {
            return;
        }
        var parents = parentMap(bones);
        for (var bone : bones) {
            if (bone.cubes == null || bone.cubes.isEmpty()) {
                continue;
            }
            if (isReplacedByFunctionalRenderer(bone.name)
                    || (skipAttachments && isAttachmentBone(bone.name, parents))) {
                bone.cubes = new ArrayList<>(0);
                continue;
            }
            if (minBoneMetric > 0.0f && boneMetric(bone) < minBoneMetric) {
                bone.cubes = new ArrayList<>(0);
            }
        }
    }

    /**
     * 自适应体量阈值（阶段 2）。
     *
     * <p>{@code threshold = max(minBoneMetric, min(RELATIVE_FRACTION × p95, 面数预算))}。
     *
     * <ul>
     *   <li><b>相对项</b>随模型自适应，但实测单独用它在 hk416d 与 eos 之间拉不开：两者 p95 只差
     *       2 倍，而"要落到同一档"需要的阈值差 3 倍以上（hk416d 需要 ≈3 以下，eos 需要 ≈13），
     *       而且把 ak47 的阈值抬到能压住 eos 的水平会直接删掉握把 {@code grip2}。</li>
     *   <li><b>面数预算</b>直接按每个模型自己的面数来定：按体量从大到小保留零件，直到累计面数
     *       达到 {@link #KEEP_FACE_FRACTION} × 去标记总面数。取两者较小值 = 更保守的那个，
     *       于是"绝不因为相对阈值而过度削减"。</li>
     * </ul>
     *
     * <p>实测（默认参数）：ak47 46.2%、hk416d 27.3%、eos 53.5%——三把枪都落在 25~55% 区间内，
     * 而旧的绝对阈值分别是 63.9% / 59.2% / 87.2%。
     *
     * @param totalFaces 去标记后的总面数（预算分母）
     */
    public static float adaptiveThreshold(@Nullable GeoModel model, float totalFaces,
                                          float minBoneMetric, boolean skipAttachments) {
        var bones = allBones(model);
        var parents = parentMap(bones);
        var metrics = new ArrayList<Float>();
        var candidates = new ArrayList<Bone>();
        for (var bone : bones) {
            if (bone.cubes == null || bone.cubes.isEmpty()) {
                continue;
            }
            if (isReplacedByFunctionalRenderer(bone.name)) {
                continue;
            }
            if (skipAttachments && isAttachmentBone(bone.name, parents)) {
                continue;
            }
            candidates.add(bone);
            metrics.add(boneMetric(bone));
        }
        var relative = RELATIVE_FRACTION * percentile(metrics, RELATIVE_PERCENTILE);
        var need = KEEP_FACE_FRACTION * totalFaces;
        var budget = 0.0f;
        if (need > 0.0f && !candidates.isEmpty()) {
            var sorted = new ArrayList<>(candidates);
            sorted.sort((a, b) -> Float.compare(boneMetric(b), boneMetric(a)));
            var accumulated = 0.0f;
            for (var bone : sorted) {
                accumulated += boneFaceCount(bone);
                if (accumulated >= need) {
                    budget = boneMetric(bone);
                    break;
                }
            }
        }
        return Math.max(minBoneMetric, Math.min(relative, budget));
    }

    /** 对模型里所有骨骼做面级遮挡剔除。 */
    public static void cullAllFaces(@Nullable GeoModel model) {
        for (var bone : allBones(model)) {
            if (bone.cubes == null || bone.cubes.isEmpty()) {
                continue;
            }
            // 骨骼级 inflate 会作用到该骨骼所有方块上（且单位与 cube.inflate 不同），
            // 拿不准就不做这一块的面级剔除，只损失一点优化。
            if (Math.abs(bone.inflate) > 1e-6f) {
                continue;
            }
            cullOccludedFaces(bone);
        }
    }

    /**
     * 阶段 4：把"实心"的零件换成粗体素网格盒子，返回被折叠的骨骼数。
     *
     * <p>判据是"实心度" {@code fill = 所有方块体积和 / 真实包围盒体积}：
     *
     * <ul>
     *   <li>方块的真实形状要按 {@code GeoBuilder} 的方式算紧包围盒——TaCZ 的方块几乎都带旋转，
     *       而且常绕一个很远的枢轴旋转，所以 {@code origin/size} 完全不代表它画在哪。
     *       八个角逐字复刻 {@code GeoBuilder.buildCube}（取反 X、inflate、绕枢轴旋转）后取 min/max，
     *       旋转盒子的体积不变，仍是 {@code |w·h·d|}。</li>
     *   <li>把包围盒切成 {@link #GRID_LADDER} 里的粗网格，每个"有料"的格子发出一个盒子 =
     *       与该格子相交的方块真实 AABB <b>裁剪到该格子</b>后的并集。于是每个盒子都装得下它
     *       覆盖的那部分几何，所有盒子并起来一定装得下整根骨骼——轮廓只会变大、绝不会丢可见像素
     *       （离线渲染逐像素验证过）。空格子不发盒子，所以凹/弯截面不会被填平。</li>
     *   <li>UV 取自该格子里体积最大的<b>有面</b>方块，六个面都采同一小块贴图：沟壑、导轨齿、
     *       螺丝台这些表面细节就这样被抹平了。只有无面方块的格子不发盒子（那些方块在原模型里
     *       本来就生成 0 个四边形，复制它们的 UV 只会得到一个隐形盒子）。</li>
     * </ul>
     *
     * <p>不会折叠的情况：方块数 &lt; 2；实心度低于 {@link #FILL_THRESHOLD}；或者阶梯里最粗的一档
     * 也省不到 {@link #COLLAPSE_FACE_TARGET}（此时宁可保留原样）。
     */
    public static int collapseBoxLikeBones(@Nullable GeoModel model) {
        var count = 0;
        for (var bone : allBones(model)) {
            if (collapseBone(bone)) {
                count++;
            }
        }
        return count;
    }

    /** 单根骨骼的折叠，见 {@link #collapseBoxLikeBones}。 */
    public static boolean collapseBone(@Nullable Bone bone) {
        if (bone == null || bone.cubes == null || bone.cubes.size() < 2) {
            return false;
        }
        if (bone.cubes.size() > COLLAPSE_CUBE_LIMIT) {
            return false;
        }
        var bounds = boneBounds(bone, bone.inflate);
        if (bounds == null) {
            return false;
        }
        var bboxVolume = (bounds[3] - bounds[0]) * (bounds[4] - bounds[1]) * (bounds[5] - bounds[2]);
        if (!(bboxVolume > 0.0f)) {
            return false;
        }
        var volume = 0.0f;
        var totalFaces = 0;
        for (var cube : bone.cubes) {
            if (cube == null) {
                continue;
            }
            volume += cubeVolume(cube);
            totalFaces += faceCount(cube);
        }
        if (volume / bboxVolume < FILL_THRESHOLD) {
            return false;
        }
        // 容差按骨骼最长边长的比例取，避免 float/double 精度差导致合并结果翻边。
        var span = Math.max(1e-6f, Math.max(bounds[3] - bounds[0],
                Math.max(bounds[4] - bounds[1], bounds[5] - bounds[2])));
        var eps = SLICE_MERGE_EPSILON * span;
        // 从最细的档位开始试；省得不够就退一档，而不是退回单个包围盒。
        List<SliceBox> chosen = null;
        for (var level : GRID_LADDER) {
            var boxes = gridBoxes(bone.cubes, bone.inflate, bounds, level[0], level[1], eps);
            if (boxes.isEmpty()) {
                continue;
            }
            boxes = mergeBoxes(boxes, eps);
            var newFaces = 0;
            for (var box : boxes) {
                newFaces += collapsedFaceCount(collapseUv(box.donor));
            }
            if (newFaces <= totalFaces * COLLAPSE_FACE_TARGET) {
                chosen = boxes;
                break;
            }
        }
        if (chosen == null) {
            return false;
        }
        var replaced = new ArrayList<Cube>(chosen.size());
        for (var box : chosen) {
            var merged = new Cube();
            // trueAabb 返回的是 KINETIC 坐标系（X 取反）的包围盒，而 cube.origin/size 是基岩坐标系，
            // GeoBuilder 还会再取反一次 X。所以这里必须换回去：M·[lo,hi] = [-hi,-lo]。
            merged.origin = new float[]{-box.hi[0], box.lo[1], box.lo[2]};
            merged.size = new float[]{
                    box.hi[0] - box.lo[0], box.hi[1] - box.lo[1], box.hi[2] - box.lo[2]};
            // 包围盒已经把 cube.inflate 与骨骼级 inflate 都算进去了，新方块不能再被 inflate 一次。
            merged.inflate = 0.0f;
            merged.mirror = box.donor.mirror;
            merged.uv = collapseUv(box.donor);
            replaced.add(merged);
        }
        bone.cubes = replaced;
        return true;
    }

    /** 网格里一个"有料"的格子：它在 KINETIC 空间的盒子，以及复制 UV 用的供体方块。 */
    private static final class SliceBox {
        final float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        final float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        Cube donor;
        /** 供体体积；{@code -1} 表示还没有供体。避免为了比较体积而反复查表或重算。 */
        float donorVolume = -1.0f;
    }

    /**
     * 把骨骼包围盒切成粗体素网格，返回每个"有料"格子的盒子。
     *
     * <p>最长轴切 {@code longCount} 格、另外两轴各切 {@code shortCount} 格。每个格子只收
     * 与它相交的方块，并且把方块的 AABB <b>裁剪到格子边界</b>：一根贯穿整根骨骼的长条
     * （导轨底座）必须按格子切段，否则它只落在某一个格子里，那个格子就会横跨整根骨骼。
     */
    private static List<SliceBox> gridBoxes(List<Cube> cubes, float boneInflate, float[] bounds,
                                            int longCount, int shortCount, float eps) {
        var axis = 0;
        for (var k = 1; k < 3; k++) {
            if (bounds[k + 3] - bounds[k] > bounds[axis + 3] - bounds[axis]) {
                axis = k;
            }
        }
        var counts = new int[]{shortCount, shortCount, shortCount};
        counts[axis] = longCount;
        var edges = new float[3][];
        for (var k = 0; k < 3; k++) {
            var count = Math.max(1, counts[k]);
            var span = bounds[k + 3] - bounds[k];
            if (span <= 0.0f) {
                edges[k] = new float[]{bounds[k], bounds[k + 3]};
                continue;
            }
            edges[k] = new float[count + 1];
            for (var i = 0; i <= count; i++) {
                edges[k][i] = bounds[k] + span * i / count;
            }
        }
        // 每个方块的紧包围盒与体积、面数只算一次：它们只取决于方块本身，与格子无关。
        // 放在内层循环里会变成"格子数 × 方块数"次 trueAabb（每个 8 次角点变换），
        // 对 45 个格子 × 4000 个方块的骨骼是约 18 万次；提出来之后是 4000 次。
        var aabbs = new float[cubes.size()][];
        var volumes = new float[cubes.size()];
        var faceCounts = new int[cubes.size()];
        for (var c = 0; c < cubes.size(); c++) {
            var cube = cubes.get(c);
            if (cube == null) {
                continue;
            }
            aabbs[c] = trueAabb(cube, boneInflate);
            volumes[c] = cubeVolume(cube);
            faceCounts[c] = faceCount(cube);
        }
        var boxes = new ArrayList<SliceBox>();
        for (var i = 0; i + 1 < edges[0].length; i++) {
            for (var j = 0; j + 1 < edges[1].length; j++) {
                for (var m = 0; m + 1 < edges[2].length; m++) {
                    var index = new int[]{i, j, m};
                    var box = new SliceBox();
                    for (var c = 0; c < cubes.size(); c++) {
                        var aabb = aabbs[c];
                        if (aabb == null) {
                            continue;
                        }
                        var cube = cubes.get(c);
                        var outside = false;
                        for (var k = 0; k < 3; k++) {
                            if (aabb[k + 3] <= edges[k][index[k]] + eps
                                    || aabb[k] >= edges[k][index[k] + 1] - eps) {
                                outside = true;
                                break;
                            }
                        }
                        if (outside) {
                            continue;
                        }
                        for (var k = 0; k < 3; k++) {
                            box.lo[k] = Math.min(box.lo[k],
                                    Math.max(aabb[k], edges[k][index[k]]));
                            box.hi[k] = Math.max(box.hi[k],
                                    Math.min(aabb[k + 3], edges[k][index[k] + 1]));
                        }
                        // 供体必须至少有一个面。无面方块（uv == null，或逐面 UV 六个面全缺、
                        // 或逐面条目缺少 uv/uv_size）在 GeoBuilder 里生成 0 个四边形，把它的 UV
                        // 复制到盒子上 = 盒子彻底隐形（轮廓上一个洞），而不是"没贴图/白色"。
                        // 实测 eos 的 helenas_nail 就有 11 个这样的方块。
                        // 整个格子只有无面方块时干脆不发盒子：那部分几何在原模型里本来就不画，
                        // 发了反而是凭空多出可见像素。
                        if (faceCounts[c] > 0 && volumes[c] > box.donorVolume) {
                            box.donor = cube;
                            box.donorVolume = volumes[c];
                        }
                    }
                    if (box.donor != null) {
                        boxes.add(box);
                    }
                }
            }
        }
        return boxes;
    }

    /**
     * 把"截面相同且贴在一起"的相邻盒子合并。
     *
     * <p>网格本身不发合并，所以一根直的零件会被切成好几段。合并只在不改变并集的前提下进行
     * （两轴截面逐位相同、第三轴相接），因此轮廓一个像素都不会变，只是面数变少——
     * 省下来的面数预算可以拿去换更细的网格。
     */
    private static List<SliceBox> mergeBoxes(List<SliceBox> boxes, float eps) {
        var current = new ArrayList<>(boxes);
        var changed = true;
        while (changed) {
            changed = false;
            var merged = new ArrayList<SliceBox>(current.size());
            var used = new boolean[current.size()];
            for (var i = 0; i < current.size(); i++) {
                if (used[i]) {
                    continue;
                }
                var cur = current.get(i);
                for (var j = i + 1; j < current.size(); j++) {
                    if (used[j]) {
                        continue;
                    }
                    var other = current.get(j);
                    var canMerge = false;
                    for (var axis = 0; axis < 3 && !canMerge; axis++) {
                        var touching =
                                Math.abs(cur.hi[axis] - other.lo[axis]) <= eps
                                        || Math.abs(other.hi[axis] - cur.lo[axis]) <= eps;
                        if (!touching) {
                            continue;
                        }
                        canMerge = true;
                        for (var k = 0; k < 3; k++) {
                            if (k == axis) {
                                continue;
                            }
                            if (Math.abs(cur.lo[k] - other.lo[k]) > eps
                                    || Math.abs(cur.hi[k] - other.hi[k]) > eps) {
                                canMerge = false;
                                break;
                            }
                        }
                    }
                    if (!canMerge) {
                        continue;
                    }
                    for (var k = 0; k < 3; k++) {
                        cur.lo[k] = Math.min(cur.lo[k], other.lo[k]);
                        cur.hi[k] = Math.max(cur.hi[k], other.hi[k]);
                    }
                    if (other.donorVolume > cur.donorVolume) {
                        cur.donor = other.donor;
                        cur.donorVolume = other.donorVolume;
                    }
                    used[j] = true;
                    changed = true;
                }
                merged.add(cur);
            }
            current = merged;
        }
        return current;
    }

    /**
     * 折叠盒子的 UV：供体的六个面必须齐全。
     *
     * <p>原模型里很多方块只写"看得见的那几个面"（ak47 的 {@code barrel4} 供体只有 4 个面），
     * 照抄会让盒子缺面、从缺的那一侧看过去是空的——离线渲染实测 ak47 会因此丢 1232 个可见像素。
     * 缺的面用供体已有的面补齐（同一小块贴图），这也正是"没有凹槽的实心条"该有的样子。
     *
     * <p>补面优先用<b>对面</b>（west↔east、north↔south、up↔down），找不到才退回"第一个非空面"。
     * 原因是 UV 的轴对应关系：west/east 都是 {@code u=z, v=y}、north/south 都是 {@code u=x, v=y}、
     * up/down 都是 {@code u=x, v=z}。用"第一个非空面"（往往是 west，u=z/v=y）去补 up（u=x/v=z）
     * 会把 patch 的两条轴整个配错，长边被压到短边上。离线实测（analysis/gun_lod_stretch_options.py，
     * 模型单位/声明 texel 的最坏面）：ak47 p90 从 15.61 降到 4.69、hk416d p99 从 21.08 降到 9.73，
     * 且与"逐面挑最优模板"的 oracle 完全一致。对面不可用时退回旧规则，行为不会更差。
     */
    private static @Nullable CubeUv collapseUv(Cube donor) {
        var uv = donor.uv;
        if (uv == null || uv.perFaceUv == null) {
            // 盒式 UV 本来就会生成 6 个面；没有 UV 的方块保持原样（它本来也不画）。
            return uv;
        }
        var faces = uv.perFaceUv;
        var fallback = firstFace(faces);
        if (fallback == null) {
            return uv;
        }
        var filled = new UvFaces();
        filled.west = renderable(faces.west) ? faces.west : orElse(faces.east, fallback);
        filled.east = renderable(faces.east) ? faces.east : orElse(faces.west, fallback);
        filled.north = renderable(faces.north) ? faces.north : orElse(faces.south, fallback);
        filled.south = renderable(faces.south) ? faces.south : orElse(faces.north, fallback);
        filled.up = renderable(faces.up) ? faces.up : orElse(faces.down, fallback);
        filled.down = renderable(faces.down) ? faces.down : orElse(faces.up, fallback);
        return CubeUv.perFace(filled);
    }

    /**
     * 该逐面条目是否真的会生成一个四边形。
     *
     * <p>{@code GeoBuilder.addFace} 在 {@code uv} 或 {@code uv_size} 缺失/过短时<b>直接跳过</b>，
     * 所以"条目非 null"不等于"会画出来"。这个区分是必要的：一个只带 {@code "west": {}} 的方块
     * 在源模型里本来就画 0 个面，如果把它当"有面"的供体折叠，整根骨骼会变成一个隐形盒子
     * （面数从 N 直接掉到 0），而不是"没贴图"。
     */
    private static boolean renderable(@Nullable FaceUv face) {
        return face != null && face.uv != null && face.uvSize != null
                && face.uv.length >= 2 && face.uvSize.length >= 2;
    }

    /** 缺面补齐时的兜底模板：第一个<b>真的会画出来</b>的面（旧行为）。 */
    private static FaceUv firstFace(UvFaces faces) {
        return renderable(faces.west) ? faces.west
                : renderable(faces.east) ? faces.east
                : renderable(faces.north) ? faces.north
                : renderable(faces.south) ? faces.south
                : renderable(faces.up) ? faces.up
                : renderable(faces.down) ? faces.down
                : null;
    }

    private static FaceUv orElse(FaceUv preferred, FaceUv fallback) {
        return renderable(preferred) ? preferred : fallback;
    }

    /** 折叠盒子的面数：逐面 UV 补齐后是 6 面（只要有一个面），盒式 UV 也是 6 面。 */
    private static int collapsedFaceCount(@Nullable CubeUv uv) {
        if (uv == null) {
            return 0;
        }
        if (uv.perFaceUv == null) {
            return uv.boxUv != null ? 6 : 0;
        }
        return hasAnyFace(uv.perFaceUv) ? 6 : 0;
    }

    /**
     * 方块真实渲染形状的紧包围盒（KINETIC 坐标系），逐字复刻 {@code GeoBuilder.buildCube}。
     *
     * <p>返回 {@code {loX, loY, loZ, hiX, hiY, hiZ}}。没有旋转时就是普通的
     * {@code origin/size} 盒子（X 按 KINETIC 约定取反）。
     *
     * @param boneInflate 骨骼级 inflate（模型单位）；方块自带 {@code inflate} 时以方块为准
     */
    public static float[] trueAabb(@Nullable Cube cube, float boneInflate) {
        var origin = cube.origin != null && cube.origin.length >= 3
                ? cube.origin : new float[]{0.0f, 0.0f, 0.0f};
        var size = cube.size != null && cube.size.length >= 3
                ? cube.size : new float[]{1.0f, 1.0f, 1.0f};
        var inflate = cube.inflate != null ? cube.inflate : boneInflate;
        var ox = -(origin[0] + size[0]);
        var oy = origin[1];
        var oz = origin[2];
        var x0 = ox - inflate;
        var x1 = ox + size[0] + inflate;
        var y0 = oy - inflate;
        var y1 = oy + size[1] + inflate;
        var z0 = oz - inflate;
        var z1 = oz + size[2] + inflate;

        var rotation = cube.rotation;
        var rotated = false;
        if (rotation != null && rotation.length >= 3) {
            for (var angle : rotation) {
                if (Math.abs(angle) > 1e-6f) {
                    rotated = true;
                    break;
                }
            }
        }
        var px = 0.0f;
        var py = 0.0f;
        var pz = 0.0f;
        var rx = 0.0f;
        var ry = 0.0f;
        var rz = 0.0f;
        if (rotated) {
            var pivot = cube.pivot != null && cube.pivot.length >= 3
                    ? cube.pivot : new float[]{0.0f, 0.0f, 0.0f};
            px = -pivot[0];
            py = pivot[1];
            pz = pivot[2];
            rx = (float) -Math.toRadians(rotation[0]);
            ry = (float) -Math.toRadians(rotation[1]);
            rz = (float) Math.toRadians(rotation[2]);
        }

        var lo = new float[]{Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        var hi = new float[]{-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (var xi = 0; xi < 2; xi++) {
            for (var yi = 0; yi < 2; yi++) {
                for (var zi = 0; zi < 2; zi++) {
                    var x = xi == 0 ? x0 : x1;
                    var y = yi == 0 ? y0 : y1;
                    var z = zi == 0 ? z0 : z1;
                    if (rotated) {
                        var sx = x - px;
                        var sy = y - py;
                        var sz = z - pz;
                        // Rx
                        var y1r = sy * (float) Math.cos(rx) - sz * (float) Math.sin(rx);
                        var z1r = sy * (float) Math.sin(rx) + sz * (float) Math.cos(rx);
                        // Ry
                        var x2 = sx * (float) Math.cos(ry) + z1r * (float) Math.sin(ry);
                        var z2 = -sx * (float) Math.sin(ry) + z1r * (float) Math.cos(ry);
                        // Rz
                        var x3 = x2 * (float) Math.cos(rz) - y1r * (float) Math.sin(rz);
                        var y3 = x2 * (float) Math.sin(rz) + y1r * (float) Math.cos(rz);
                        x = x3 + px;
                        y = y3 + py;
                        z = z2 + pz;
                    }
                    lo[0] = Math.min(lo[0], x);
                    lo[1] = Math.min(lo[1], y);
                    lo[2] = Math.min(lo[2], z);
                    hi[0] = Math.max(hi[0], x);
                    hi[1] = Math.max(hi[1], y);
                    hi[2] = Math.max(hi[2], z);
                }
            }
        }
        return new float[]{lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]};
    }

    /** 骨骼的"实心度"：所有方块体积和 / 真实包围盒体积；空骨骼或退化盒子返回 0。 */
    public static float boneFill(@Nullable Bone bone, float boneInflate) {
        if (bone == null || bone.cubes == null || bone.cubes.isEmpty()) {
            return 0.0f;
        }
        var bounds = boneBounds(bone, boneInflate);
        if (bounds == null) {
            return 0.0f;
        }
        var bboxVolume = (bounds[3] - bounds[0]) * (bounds[4] - bounds[1]) * (bounds[5] - bounds[2]);
        if (!(bboxVolume > 0.0f)) {
            return 0.0f;
        }
        var volume = 0.0f;
        for (var cube : bone.cubes) {
            volume += cubeVolume(cube);
        }
        return volume / bboxVolume;
    }

    /** 整根骨骼所有方块真实包围盒的并集；没有任何有效方块时返回 {@code null}。 */
    private static float @Nullable [] boneBounds(Bone bone, float boneInflate) {
        var lo = new float[]{Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        var hi = new float[]{-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        var any = false;
        for (var cube : bone.cubes) {
            if (cube == null) {
                continue;
            }
            var box = trueAabb(cube, boneInflate);
            for (var axis = 0; axis < 3; axis++) {
                lo[axis] = Math.min(lo[axis], box[axis]);
                hi[axis] = Math.max(hi[axis], box[axis + 3]);
            }
            any = true;
        }
        if (!any) {
            return null;
        }
        return new float[]{lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]};
    }

    /** 方块体积（旋转不改变体积）。 */
    public static float cubeVolume(@Nullable Cube cube) {
        if (cube == null || cube.size == null || cube.size.length < 3) {
            return 0.0f;
        }
        return Math.abs(cube.size[0]) * Math.abs(cube.size[1]) * Math.abs(cube.size[2]);
    }

    /** 线性插值分位数，必须与 analysis/gun_lod_compare.py 的 _percentile 逐位一致。 */
    private static float percentile(List<Float> values, double p) {
        if (values.isEmpty()) {
            return 0.0f;
        }
        var sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        var k = (sorted.size() - 1) * p;
        var floorIndex = (int) Math.floor(k);
        var ceilIndex = Math.min(floorIndex + 1, sorted.size() - 1);
        return (float) (sorted.get(floorIndex)
                + (sorted.get(ceilIndex) - sorted.get(floorIndex)) * (k - floorIndex));
    }

    private static void clearMarkerBones(List<Bone> bones, Map<String, String> parents,
                                         boolean skipAttachments) {
        for (var bone : bones) {
            if (bone.cubes == null || bone.cubes.isEmpty()) {
                continue;
            }
            if (isReplacedByFunctionalRenderer(bone.name)
                    || (skipAttachments && isAttachmentBone(bone.name, parents))) {
                bone.cubes = new ArrayList<>(0);
            }
        }
    }

    private static Map<String, String> parentMap(List<Bone> bones) {
        var parents = new HashMap<String, String>();
        for (var bone : bones) {
            if (bone.name != null && bone.parent != null) {
                parents.put(bone.name, bone.parent);
            }
        }
        return parents;
    }

    private static int boneFaceCount(Bone bone) {
        var count = 0;
        if (bone.cubes != null) {
            for (var cube : bone.cubes) {
                count += faceCount(cube);
            }
        }
        return count;
    }

    /**
     * 零件体量：{@code max(最长边, sqrt(表面积) / 2)}。
     *
     * <p>两个量缺一不可：
     *
     * <ul>
     *   <li><b>最长边</b>：保住又细又长的零件（枪管、导气管）。只看表面积会把它们误删。</li>
     *   <li><b>sqrt(面积)/2</b>：保住由很多小方块拼成的大零件。hk416d 的握把 {@code group14}
     *       有 25 个小方块、单块最长边只有 2.56，只看最长边会把握把整个删掉（ak47 的握把
     *       单块最长边 3.94 却没事）——同一个特征、不同作者的建模粒度不同，必须有一个
     *       与粒度无关的量。{@code sqrt(面积)/2} 大致等于"等效正方形的边长"。</li>
     * </ul>
     */
    public static float boneMetric(@Nullable Bone bone) {
        var edge = boneMaxEdge(bone);
        var area = boneArea(bone);
        var equivalent = (float) (Math.sqrt(Math.max(area, 0.0f)) / 2.0);
        return Math.max(edge, equivalent);
    }

    /**
     * 该骨骼的方块是否由 TaCZ 的 functional renderer 顶替绘制（因此不该出现在 LOD 里）。
     *
     * <p>除了两个确切名字，还按 {@code _pos} 后缀兜底：TaCZ 的定位标记骨骼一律叫
     * {@code <用途>_pos}。个别枪包可能给标记骨骼带上几何，这里一并挡掉。
     */
    public static boolean isReplacedByFunctionalRenderer(@Nullable String boneName) {
        return boneName != null
                && (boneName.endsWith("_pos") || REPLACED_BY_FUNCTIONAL_RENDERER.contains(boneName));
    }

    /** 该骨骼是否属于 TaCZ 的可安装配件子树（沿 {@code parent} 链向上查节点名）。 */
    public static boolean isAttachmentBone(@Nullable String boneName, Map<String, String> parents) {
        var current = boneName;
        var guard = 0;
        while (current != null && guard++ < 256) {
            if (ATTACHMENT_NODES.contains(current)) {
                return true;
            }
            current = parents.get(current);
        }
        return false;
    }

    /**
     * 面级遮挡剔除：把被同一骨骼内其它方块完全盖住的面从逐面 UV 表里删掉。
     *
     * <p>只做<b>骨骼内</b>判定：同一骨骼的方块共享同一个局部坐标系，不需要骨骼变换就能精确比较
     * 包围盒；跨骨骼判定需要完整 FK，收益不确定而风险更高。
     *
     * <p>六个面全被盖住的方块整块删除。轮廓不会改变，因为被删掉的面本来就被挡住。
     */
    private static void cullOccludedFaces(Bone bone) {
        var cubes = bone.cubes;
        if (cubes == null || cubes.size() < 2 || cubes.size() > FACE_CULL_CUBE_LIMIT) {
            return;
        }
        var count = cubes.size();
        var lo = new float[count][];
        var hi = new float[count][];
        var mirrored = new boolean[count];
        for (var i = 0; i < count; i++) {
            var cube = cubes.get(i);
            if (cube == null) {
                continue;
            }
            if (!isAxisAlignedBox(cube)) {
                // 旋转过的方块，它的 origin/size 包围盒不再等于实际画出来的盒子；
                // 用它当被剔除方会误删可见面，用它当覆盖方会过度遮挡。一律跳过。
                continue;
            }
            var origin = cube.origin != null && cube.origin.length >= 3
                    ? cube.origin : new float[]{0, 0, 0};
            var size = cube.size != null && cube.size.length >= 3
                    ? cube.size : new float[]{1, 1, 1};
            // inflate 是<b>有符号</b>的均匀外扩（GeoBuilder 直接把它加减到八个角上，负值就是内缩），
            // 所以这里不能取绝对值：取了会把一个内缩的方块当成外扩的，进而误删旁边其实有缝的面。
            var inflate = cube.inflate != null ? cube.inflate : 0.0f;
            lo[i] = new float[]{origin[0] - inflate, origin[1] - inflate, origin[2] - inflate};
            hi[i] = new float[]{origin[0] + size[0] + inflate,
                    origin[1] + size[1] + inflate, origin[2] + size[2] + inflate};
            mirrored[i] = bone.mirror || cube.mirror;
        }

        var kept = new ArrayList<Cube>(count);
        for (var i = 0; i < count; i++) {
            var cube = cubes.get(i);
            if (cube == null) {
                continue;
            }
            if (lo[i] == null) {
                // 旋转方块：不做任何面级剔除，原样保留。
                kept.add(cube);
                continue;
            }
            var faces = cube.uv != null ? cube.uv.perFaceUv : null;
            var covered = new boolean[6];
            var coveredCount = 0;
            for (var dir = 0; dir < 6; dir++) {
                covered[dir] = isFaceCovered(lo, hi, mirrored, i, dir);
                if (covered[dir]) {
                    coveredCount++;
                }
            }
            if (coveredCount == 6) {
                // 完全被包住的方块：整块不要。
                continue;
            }
            if (faces != null && coveredCount > 0) {
                // 逐面 UV：删掉条目就等于不生成该面（见 GeoBuilder.addFace）。
                if (covered[0]) faces.west = null;
                if (covered[1]) faces.east = null;
                if (covered[2]) faces.north = null;
                if (covered[3]) faces.south = null;
                if (covered[4]) faces.up = null;
                if (covered[5]) faces.down = null;
                if (!hasAnyFace(faces)) {
                    continue;
                }
            }
            kept.add(cube);
        }
        bone.cubes = kept;
    }

    /**
     * 方向编号到坐标轴的映射。必须显式写出来：方向表是
     * {@code 0=west 1=east 2=north 3=south 4=up 5=down}（逐面 UV 的六个条目名），
     * 而 {@code dir >> 1} 会把 2/3（±Z）映射到 Y 轴、把 4/5（±Y）映射到 Z 轴，
     * 也就是六个面里有四个用错了轴去判定覆盖。
     */
    private static final int[] FACE_AXIS = {0, 0, 2, 2, 1, 1};

    /**
     * 每个逐面 UV 条目<b>实际被画在哪个半轴</b>上。X 那一对是反的，这一点很容易搞错：
     *
     * <p>{@code GeoBuilder} 解析基岩几何时会取反 X（{@code ox = -(origin.x + size.x)}），并且把
     * west/east 的贴图<b>对调</b>以保持镜像后的贴图正确。所以 {@code west} 这个条目落在
     * {@code origin.x} 的<b>最大</b>侧，{@code east} 落在最小侧。这里的覆盖判定用的是原始 JSON 坐标，
     * 于是两个 X 面的正负号与直觉相反；写反了就会"删掉外侧可见面、留下内侧看不到的面"，
     * 表现为模型上出现破洞。Z 与 Y 不参与取反，保持直觉。
     */
    private static final boolean[] FACE_POSITIVE = {true, false, false, true, true, false};

    /**
     * 镜像布局（{@code bone.mirror} 或 {@code cube.mirror}）下每个条目的半轴。
     *
     * <p>同一个 {@code GeoBuilder} 分支在镜像布局里把 west/east 与 up/down <b>再</b>对调一次，
     * 所以这四项的正负号整体翻转；north/south 不受影响。
     */
    private static final boolean[] FACE_POSITIVE_MIRRORED = {false, true, false, true, false, true};

    private static boolean isFaceCovered(float[][] lo, float[][] hi, boolean[] mirrored,
                                         int index, int dir) {
        var axis = FACE_AXIS[dir];
        var positive = (mirrored[index] ? FACE_POSITIVE_MIRRORED : FACE_POSITIVE)[dir];
        var plane = positive ? hi[index][axis] : lo[index][axis];
        var otherA = (axis + 1) % 3;
        var otherB = (axis + 2) % 3;
        for (var j = 0; j < lo.length; j++) {
            if (j == index || lo[j] == null) {
                continue;
            }
            // j 必须在外侧贴住（或越过）这个面所在的平面。
            //
            // 注意这里必须是"贴住"而不是"跨越"：两个方块面贴面相邻时（A 占 [0,2]、B 占 [2,4]，
            // 共享 x=2 这个面），B 并不跨越 x=2。早期版本要求跨越，导致最常见的相邻方块
            // 一个面都剔不掉。
            if (positive) {
                if (lo[j][axis] > plane + FACE_EPSILON || hi[j][axis] < plane + FACE_EPSILON) {
                    continue;
                }
            } else {
                if (hi[j][axis] < plane - FACE_EPSILON || lo[j][axis] > plane - FACE_EPSILON) {
                    continue;
                }
            }
            // j 在另外两个轴上的截面必须完整盖住这个面。
            if (lo[j][otherA] <= lo[index][otherA] + FACE_EPSILON
                    && hi[j][otherA] >= hi[index][otherA] - FACE_EPSILON
                    && lo[j][otherB] <= lo[index][otherB] + FACE_EPSILON
                    && hi[j][otherB] >= hi[index][otherB] - FACE_EPSILON) {
                return true;
            }
        }
        return false;
    }

    /**
     * 该方块在骨骼局部坐标系里是否是一个轴对齐盒子。
     *
     * <p>只有轴对齐时，{@code origin/size} 才等于实际绘制出来的形状。带旋转的方块必须排除：
     * 离线渲染实测过，把旋转方块的包围盒当成真实盒子去判定覆盖，会在 ak47 上误删 50 px、
     * 在 hk416d 上误删 82 px 的<b>可见</b>表面（其中 47/50 与 82/82 都来自带旋转的方块）。
     */
    private static boolean isAxisAlignedBox(Cube cube) {
        var rotation = cube.rotation;
        if (rotation == null) {
            return true;
        }
        for (var angle : rotation) {
            if (Math.abs(angle) > 1e-6f) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasAnyFace(@Nullable UvFaces faces) {
        return faces != null && (renderable(faces.west) || renderable(faces.east)
                || renderable(faces.north) || renderable(faces.south)
                || renderable(faces.up) || renderable(faces.down));
    }

    /**
     * 把模型的 {@code thirdperson_hand} 骨骼摆到手上所需的平移量，单位是模型单位（1/16 格），
     * 且已经换到 KINETIC 的坐标系（X 取反，与 {@code GeoBuilder} 的解析约定一致）。
     *
     * <p>TaCZ 的第三人称摆放是一段"原点路径"变换，作用是把 {@code positioning -> thirdperson_hand}
     * 这条链上的节点搬到姿态原点。这两个节点在两个已知模型里都没有旋转、{@code positioning} 的枢轴
     * 都是 (0,0,0)，所以核心就是一次纯平移 {@code -pivot * scale / 16}。
     *
     * <p>刻意不照抄 TaCZ 里那两个来历不明的 {@code 1.5} 常数：它们在方块空间里是整格级偏移，
     * 没有客户端就无法验证其含义，照抄反而更可能引入错位。KINETIC 的 locator 本身就在手上，
     * 所以"把模型的手节点放到 locator 原点"是自洽且可离线验证的选择。
     *
     * <p>找不到该骨骼时返回零平移，等价于"不做额外摆放"。
     */
    public static Vector3f handAnchor(@Nullable GeoModel model) {
        // 必须与 TaCZ 完全一致，否则就是"错位"。
        //
        // TaCZ 的 applyPositioningTransform 在第三人称下取 getThirdPersonHandOriginPath()，
        // 而那条路径是 getPath(modelMap.get("thirdperson_hand"))；getPath(null) 返回 null，
        // 而 applyPositioningNodeTransform 第一件事就是 `if (originPath == null) return;`。
        //
        // 也就是说：**模型没有 thirdperson_hand 时，TaCZ 不做任何摆放**，模型原点直接落在姿态原点上。
        // 实测 eos 枪包 74 把枪里有 48 把没有这个骨骼，而且那 48 把连
        // thirdperson / fixed / ground / root / positioning 都没有——所以"回退到别的节点"
        // 或"回退到几何中心"都会让这 48 把枪相对 TaCZ 偏位。这里刻意只认 thirdperson_hand，
        // 找不到就返回零平移，与 TaCZ 行为逐位一致。
        var anchor = anchorOf(allBones(model), HAND_NODE_NAME);
        return anchor != null ? anchor : new Vector3f();
    }

    private static @Nullable Vector3f anchorOf(List<Bone> bones, String name) {
        for (var bone : bones) {
            if (name.equals(bone.name) && bone.pivot != null && bone.pivot.length >= 3) {
                // KINETIC 解析时取反枢轴的 X，所以该节点在 KINETIC 坐标系下的位置是 (-x, y, z)，
                // 把它搬到原点需要平移 (x, -y, -z)。
                return new Vector3f(bone.pivot[0], -bone.pivot[1], -bone.pivot[2]);
            }
        }
        return null;
    }

    /** 骨骼的"最长边"：它所有方块里最长边最大的一条。 */
    public static float boneMaxEdge(@Nullable Bone bone) {
        if (bone == null || bone.cubes == null) {
            return 0.0f;
        }
        var edge = 0.0f;
        for (var cube : bone.cubes) {
            edge = Math.max(edge, longestEdge(cube));
        }
        return edge;
    }

    /**
     * 骨骼的表面积（所有方块六面之和，模型单位的平方）。
     *
     * <p>刻意用表面积而不是包围盒跨度：一个沿枪管铺开的骨骼（例如 {@code gastube}）包围盒很大，
     * 但每个方块都很薄，用包围盒会把它误判成主要结构；表面积与"占多少可见表面"一致。
     */
    public static float boneArea(@Nullable Bone bone) {
        if (bone == null || bone.cubes == null) {
            return 0.0f;
        }
        var area = 0.0f;
        for (var cube : bone.cubes) {
            area += cubeArea(cube);
        }
        return area;
    }

    /** 方块六面面积。 */
    public static float cubeArea(@Nullable Cube cube) {
        if (cube == null || cube.size == null || cube.size.length < 3) {
            return 0.0f;
        }
        var w = Math.abs(cube.size[0]);
        var h = Math.abs(cube.size[1]);
        var d = Math.abs(cube.size[2]);
        return 2.0f * (w * h + h * d + w * d);
    }

    /** 方块三条边里最长的一条；尺寸缺失时按 0 处理。 */
    public static float longestEdge(@Nullable Cube cube) {
        if (cube == null || cube.size == null || cube.size.length < 3) {
            return 0.0f;
        }
        return Math.max(Math.abs(cube.size[0]),
                Math.max(Math.abs(cube.size[1]), Math.abs(cube.size[2])));
    }

    /** 方块的面数：逐面 UV 按实际存在的面计，盒式 UV 固定 6 面。 */
    public static int faceCount(@Nullable Cube cube) {
        if (cube == null) {
            return 0;
        }
        CubeUv uv = cube.uv;
        if (uv == null) {
            return 0;
        }
        UvFaces faces = uv.perFaceUv;
        if (faces == null) {
            return uv.boxUv != null ? 6 : 0;
        }
        var count = 0;
        if (renderable(faces.north)) count++;
        if (renderable(faces.south)) count++;
        if (renderable(faces.east)) count++;
        if (renderable(faces.west)) count++;
        if (renderable(faces.up)) count++;
        if (renderable(faces.down)) count++;
        return count;
    }

    /** 整个模型的方块数。 */
    public static int cubeCount(@Nullable GeoModel model) {
        var count = 0;
        for (var bone : allBones(model)) {
            if (bone.cubes != null) {
                count += bone.cubes.size();
            }
        }
        return count;
    }

    /** 整个模型的面数。 */
    public static int faceCount(@Nullable GeoModel model) {
        var count = 0;
        for (var bone : allBones(model)) {
            if (bone.cubes == null) {
                continue;
            }
            for (var cube : bone.cubes) {
                count += faceCount(cube);
            }
        }
        return count;
    }

    /** 某个骨骼名在减面后是否仍有方块。 */
    public static boolean boneHasCubes(@Nullable GeoModel model, String boneName) {
        for (var bone : allBones(model)) {
            if (boneName.equals(bone.name)) {
                return bone.cubes != null && !bone.cubes.isEmpty();
            }
        }
        return false;
    }

    /**
     * 模型里<b>真正会被渲染</b>的全部骨骼。
     *
     * <p>只取第一个 geometry：{@code GeoBuilder.build} 用的是
     * {@code raw.minecraftGeometry.get(0)}，其余 geometry 根本不会被画出来。把幽灵几何也算进来
     * 会让阶段 2 的自适应阈值被它的体量抬上去（实测：一个 40×40×40 的幽灵骨骼能把阈值从 3.0
     * 抬到 25.7，于是唯一会被渲染的那根骨骼被整根删掉，LOD 直接变成空模型）。减面必须只针对
     * 会被画出来的东西。
     */
    private static List<Bone> allBones(@Nullable GeoModel model) {
        var bones = new ArrayList<Bone>();
        if (model == null || model.minecraftGeometry == null || model.minecraftGeometry.isEmpty()) {
            return bones;
        }
        Geometry geometry = model.minecraftGeometry.get(0);
        if (geometry == null || geometry.bones == null) {
            return bones;
        }
        for (Bone bone : geometry.bones) {
            if (bone != null) {
                bones.add(bone);
            }
        }
        return bones;
    }
}
