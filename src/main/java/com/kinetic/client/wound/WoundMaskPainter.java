package com.kinetic.client.wound;

import java.util.Random;

/**
 * 受伤遮罩的程序化绘制：在 64×64 的原版皮肤布局上，按档位撒下刀伤、弹孔、撕裂与碳灰。
 *
 * <p><b>为什么程序生成而不是带素材</b>：现成的伤口素材（Visual Health）是 GPL-3.0，KINETIC 是 Apache-2.0，
 * 直接复用会把许可传染进来，所以这里自己算。程序生成还有第二个好处：形状、位置、数量都能在离线单测里
 * 断言，不会出现「素材里多了一个像素」这种只能靠眼睛发现的问题。
 *
 * <p><b>为什么是 64×64</b>：遮罩的贴图密度必须和皮肤一致。做成 128/256 会让伤口比皮肤本身细 2~4 倍，
 * 而方盒模型在远处本来就靠贴图自身的纹素混叠，遮罩比它更细只会闪得更厉害。原版皮肤布局就是 64×64，
 * HD 皮肤是它的整数倍放大、UV 按比例对应，所以这一张遮罩同时适配 64/128/256 的皮肤。
 *
 * <p><b>像素语义：RGB 是颜色系数，A 是不透明度。</b> 灰度值乘顶点色（血色）得到这一像素的血色深浅
 * （越接近 0 越黑，对应弹孔中心与碳灰；越接近 255 越接近纯血色，对应渗血外圈）；alpha 是这个像素
 * 覆盖皮肤的程度。渲染时开启混合，GPU 得到 {@code skin·(1-A) + 血色·灰度/255·A}，也就是
 * 「按 A 把血色混进皮肤」——这正是这类外观需要的半透明叠加与渐变，而不是非黑即白的裁剪。
 *
 * <p><b>为什么同一处伤口要在两张遮罩上各画一份</b>：原版皮肤的第二层（外套、袖子、裤子）与基础层是
 * 同一批方盒、向外偏移 0.25 像素。渲染时分成两遍：第二层那一遍用深度相等测试，只有第二层皮肤真的
 * 不透明（即真的往深度缓冲写了值）的地方才会画出来；基础层那一遍画在被第二层挡住的地方之外。
 * 于是「外层皮肤存在就贴在外层、不存在才贴在内层」这件事完全由深度缓冲裁决，不需要知道玩家用的是
 * 哪张皮肤，也不会出现血浮在半空——两张遮罩因此都按全强度绘制，内层那一份在外层存在时是被
 * 不透明几何整个遮住的，不是混合掉的，所以不需要减淡。
 */
public final class WoundMaskPainter {
    public static final int SIZE = 64;

    /** 遮罩分两层：基础层与皮肤第二层。两张遮罩由同一串伤口生成，只是落在不同的层上。 */
    public enum Layer {
        BASE,
        OVERLAY
    }

    /**
     * 100% 密度、5 档时<b>每一档新增</b>的伤口数。
     *
     * <p>按档位累加而不是按档位给总数：档位越高伤口越多，且第 t 档恒为第 t-1 档加上新的一批，
     * 所以回血一档只会少掉最新那批，已有的伤口位置与形状都不变。这与 Visual Health 的
     * 增量盖印（`findPreviousTierCopy`）是同一个行为。
     *
     * <p>档位数更多时每档新增更少（乘 {@code 5/tiers}），最高档的总数因此与档位数无关。
     */
    static final int WOUNDS_PER_TIER_AT_FULL_DENSITY = 22;
    /**
     * 形状由一次 0..99 的掷点决定：{@code <45} 刀伤、{@code <75} 弹孔、其余小血点。
     *
     * <p><b>掷点必须是档位的纯函数</b>：如果形状随档位变化（例如"某种形状只在高档出现"），
     * 同一个掷点在两档会走不同分支、消耗不同数量的随机数，于是整条伤口序列分叉，
     * "高档包含低档全部伤口"与"两层严格对应"两条性质同时失效。
     *
     * <p><b>为什么没有"大撕裂"。</b> 试过在躯干上加一块半径 3 格以上的血泊来制造冲击力，
     * 结果是那块血泊把整个躯干吃掉了：一处伤口盖住三分之一的前胸，剩下的地方没有余地再放
     * 细碎的刀伤与血点，手臂上也几乎分不到。参考实现（Visual Health）对玩家用的是 2×2 与 3×3
     * 的小素材，靠"数量多、分布均匀、中心深边缘浅"堆出重伤观感，而不是靠单块面积。
     * 所以这里只有三种小尺寸，冲击力来自数量与对比度。
     */
    static final int SLASH_PERCENT = 45;
    static final int HOLE_PERCENT = 30;

    /**
     * 头部部位在贴图上的 y 上界（0..16 是头的六个面）。
     *
     * <p>头部要单独压一档：它面积小、又是玩家辨认对方的第一眼，同样密度下会比躯干糊得多。
     * 所以头部尺寸乘 {@link #HEAD_SCALE}、权重减半、血痕概率也更低。
     * 目标是"看得出挨了打"而不是"看不出是谁"。
     */
    static final int HEAD_Y_LIMIT = 16;
    /** 头部伤口的尺寸系数。 */
    static final float HEAD_SCALE = 0.75f;

    /** 弹孔核心：几乎全黑，看着是个洞。 */
    static final int HOLE_CORE = 40;
    /** 弹孔外圈：暗红。 */
    static final int HOLE_RIM = 130;
    /** 刀缝最深处。 */
    static final int SLASH_CORE = 60;
    /** 刀缝边缘：比核心浅，形成缝内的深浅过渡。 */
    static final int SLASH_EDGE = 185;
    /** 渗血与血痕：接近纯血色。 */
    static final int BLOOD = 235;
    /** 碳灰：火药烧灼与灰烬，比任何血色都暗，几乎是中性黑。 */
    static final int SOOT = 30;

    /** 裁剪渲染的丢弃阈值是 0.1，低于它的像素会被丢掉，所以最淡的外圈也要高过这个值。 */
    static final int MIN_ALPHA = 34;

    /**
     * {@link #blob} 的固定遍历半径。
     *
     * <p>必须大于最大半径（撕裂全尺寸时约 3.3，加轮廓抖动约 4.4），并且<b>不随半径变化</b>，
     * 否则随机数消耗数量会跟着半径变，整条伤口序列分叉。
     */
    static final int BLOB_EXTENT = 6;

    /**
     * 可撒伤口的一个部位。
     *
     * <p>{@code x/y/w/h} 是基础层的贴图矩形，{@code overlayDx/overlayDy} 是同一部位第二层相对基础层的
     * 平移（原版布局里第二层恒为整体平移、尺寸一致）。{@code overlay} 为 false 表示该部位没有第二层；
     * 当前原版布局的每个部位都有第二层（头对应帽子、躯干对应外套、手臂对应袖子、腿对应裤子），
     * 所以这个标记只在理论上留着一个出口。
     */
    private record Part(int x, int y, int w, int h, int weight, int overlayDx, int overlayDy,
                        boolean overlay) {
    }

    /**
     * 部位表。{@code weight} 是相对权重：躯干面积最大、也最常被子弹打中，权重最高；脸虽然显眼，
     * 权重压到最低，避免几张脸全被糊住。
     *
     * <p>手臂只取 {@code x=40..54}（右）与 {@code x=32..46}（左）：细手臂的贴图比宽手臂窄 2 列，
     * 多出来的列在细皮肤上是全透明的，取并集就会在细手臂上把伤口画进透明区。
     */
    private static final Part[] PARTS = {
            // 头（第二层是帽子，整体 +32 x）。权重压得比躯干低：脸 1、其余 2，避免"几张脸全被糊住"。
            new Part(8, 8, 8, 8, 1, 32, 0, true),    // 脸
            new Part(24, 8, 8, 8, 2, 32, 0, true),   // 后脑
            new Part(16, 8, 8, 8, 2, 32, 0, true),   // 左侧
            new Part(0, 8, 8, 8, 2, 32, 0, true),    // 右侧
            new Part(8, 0, 8, 8, 1, 32, 0, true),    // 头顶
            // 躯干（第二层是外套，整体 +16 y）
            new Part(20, 20, 8, 12, 5, 0, 16, true), // 前胸/腹部
            new Part(32, 20, 8, 12, 4, 0, 16, true), // 后背
            new Part(16, 20, 4, 12, 3, 0, 16, true), // 右侧
            new Part(28, 20, 4, 12, 3, 0, 16, true), // 左侧
            // 右臂（第二层是袖子，整体 +16 y）
            new Part(40, 20, 4, 12, 3, 0, 16, true), // 外侧
            new Part(44, 20, 4, 12, 4, 0, 16, true), // 正面
            new Part(48, 20, 4, 12, 2, 0, 16, true), // 内侧
            new Part(52, 20, 2, 12, 2, 0, 16, true), // 背面
            // 左臂（第二层是袖子，整体 +16 x）
            new Part(32, 52, 4, 12, 3, 16, 0, true),
            new Part(36, 52, 4, 12, 4, 16, 0, true),
            new Part(40, 52, 4, 12, 2, 16, 0, true),
            new Part(44, 52, 2, 12, 2, 16, 0, true),
            // 右腿（第二层是裤子，整体 +16 y）
            new Part(4, 20, 4, 12, 3, 0, 16, true),
            new Part(12, 20, 4, 12, 2, 0, 16, true),
            new Part(0, 20, 4, 12, 2, 0, 16, true),
            new Part(8, 20, 4, 12, 1, 0, 16, true),
            // 左腿（第二层是裤子，整体 -16 x）
            new Part(20, 52, 4, 12, 3, -16, 0, true),
            new Part(28, 52, 4, 12, 2, -16, 0, true),
            new Part(16, 52, 4, 12, 2, -16, 0, true),
            new Part(24, 52, 4, 12, 1, -16, 0, true),
    };

    /** 基础层可撒位图。 */
    private static final boolean[] BASE = buildLayer(false);
    /** 第二层可撒位图。 */
    private static final boolean[] OVERLAY = buildLayer(true);
    /** 候选点（按权重重复），静态构建一次。 */
    private static final int[] CANDIDATES = buildCandidates();

    private WoundMaskPainter() {
    }

    /** 基础层遮罩。 */
    public static int[] paint(int tier, int tiers, int densityPercent) {
        return paint(tier, tiers, densityPercent, Layer.BASE);
    }

    /** 第二层遮罩。 */
    public static int[] paintOverlay(int tier, int tiers, int densityPercent) {
        return paint(tier, tiers, densityPercent, Layer.OVERLAY);
    }

    /**
     * 生成某一层、某一档位的遮罩像素（ARGB，RGB 为灰度系数、A 为不透明度）。
     *
     * <p>结果完全由 {@code (tier, tiers, density, layer)} 决定：同一档位在任何机器、任何一次运行里
     * 都得到同一张遮罩，因此离线单测可以断言具体像素，渲染图也可以按同一套规则复现。
     */
    public static int[] paint(int tier, int tiers, int densityPercent, Layer layer) {
        int[] canvas = new int[SIZE * SIZE];
        int count = woundCount(tier, tiers, densityPercent);
        if (count <= 0) {
            return canvas;
        }
        // 种子与档位无关：第 t 档的遮罩恒等于同一串伤口的<b>前 count(t) 处</b>。
        // 于是档位升高只会往上加伤口，不会让已有的伤口换位置、换形状——回血一档就少掉最新的一处，
        // 视觉上是"伤口在愈合"，而不是"整身伤口重新随机了一遍"。像素面积也因此天然单调不减。
        Random random = new Random(SEED);
        int[] candidates = CANDIDATES.clone();
        shuffle(candidates, random);
        int placed = 0;
        for (int candidate : candidates) {
            if (placed >= count) {
                break;
            }
            Part part = PARTS[candidate >>> 16];
            int x = (candidate >>> 8) & 0xFF;
            int y = candidate & 0xFF;
            if (paintWound(canvas, part, x, y, random, layer)) {
                placed++;
            }
        }
        return canvas;
    }

    /** 某一档位累计要画几处伤口（两层合计，每处只会落在其中一层上）。 */
    public static int woundCount(int tier, int tiers, int densityPercent) {
        int count = WoundTier.clampTiers(tiers);
        if (tier <= 0) {
            return 0;
        }
        int clamped = Math.min(tier, count);
        int density = Math.max(0, Math.min(100, densityPercent));
        // 每档新增 = 满密度每档数 × 密度% × 5/档位数；累计 = 新增 × 已过的档数。
        double perTier = WOUNDS_PER_TIER_AT_FULL_DENSITY * (density / 100.0) * 5.0 / count;
        long total = Math.round(perTier * clamped);
        return (int) Math.max(1L, total);
    }

    /** 该贴图坐标是否落在基础层可撒区域内。 */
    public static boolean isWoundable(int x, int y) {
        if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) {
            return false;
        }
        return BASE[y * SIZE + x];
    }

    /** 该贴图坐标是否落在第二层可撒区域内。 */
    public static boolean isOverlayWoundable(int x, int y) {
        if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) {
            return false;
        }
        return OVERLAY[y * SIZE + x];
    }

    // ------------------------------------------------------------------ 形状

    private static boolean paintWound(int[] canvas, Part part, int x, int y, Random random,
                                      Layer layer) {
        boolean head = part.y() < HEAD_Y_LIMIT;
        float scale = head ? HEAD_SCALE : 1f;
        int roll = random.nextInt(100);
        boolean painted;
        if (roll < SLASH_PERCENT) {
            painted = paintSlash(canvas, part, x, y, random, layer, scale);
        } else if (roll < SLASH_PERCENT + HOLE_PERCENT) {
            painted = paintHole(canvas, part, x, y, random, layer, scale);
        } else {
            painted = paintSpeck(canvas, part, x, y, random, layer, scale);
        }
        if (!painted) {
            return false;
        }
        // 飞溅：伤口周围散落的几点血。这一层是"看起来像流血"而不是"贴了个形状"的关键。
        paintSpatter(canvas, part, x, y, random, layer, scale);
        // 碳灰：火药烧灼与灰烬，只压在伤口附近，颜色比血色暗得多，用来打散规则的形状。
        paintSoot(canvas, part, x, y, random, layer, scale);
        // 一部分伤口拖出血痕：顺着贴图向下走，越来越淡。
        if (random.nextInt(100) < (head ? 30 : 55)) {
            paintDrip(canvas, part, x, y, random, layer, scale);
        }
        return true;
    }

    /**
     * 刀伤：一条会拐弯、宽度逐格变化的裂缝，外面裹一层低透明度的渗血。
     *
     * <p>裂缝不是直线：每走一格都有机会沿垂直方向漂移，因此会拐弯、会分叉出粗细变化；
     * 两端收细、中段最宽，宽度再乘一个随机系数。先把整条路径算出来，再依次画渗血层与主缝，
     * 这样主缝一定盖在渗血之上，而不是按格子交错。
     */
    private static boolean paintSlash(int[] canvas, Part part, int x, int y, Random random, Layer layer,
                                     float scale) {
        // 长度也按系数缩：头部的矩形只有 8 格宽，不缩的话一刀就能横穿整张脸。
        int length = Math.max(2, Math.round((3 + random.nextInt(5)) * scale));
        boolean horizontal = random.nextBoolean();
        int[] pathX = new int[length];
        int[] pathY = new int[length];
        int drawn = 0;
        float drift = 0f;
        for (int i = 0; i < length; i++) {
            if (i > 0 && random.nextInt(100) < 60) {
                drift += (random.nextFloat() - 0.5f) * 1.7f;
            }
            int along = Math.round(drift);
            int px = horizontal ? x + i : x + along;
            int py = horizontal ? y + along : y + i;
            if (!woundable(part, px, py)) {
                break;
            }
            pathX[drawn] = px;
            pathY[drawn] = py;
            drawn++;
        }
        if (drawn == 0) {
            return false;
        }
        // 渗血层：半径略大、透明度更低、轮廓更毛。只比主缝大一圈，否则会糊成一团雾。
        for (int i = 0; i < drawn; i++) {
            blob(canvas, part, pathX[i], pathY[i], taper(drawn, i, 0.55f, 0.45f) * scale, BLOOD, BLOOD, 96,
                    random, 0.8f, layer);
        }
        // 主缝：窄、深、宽度逐格抖动。
        for (int i = 0; i < drawn; i++) {
            float radius = taper(drawn, i, 0.35f, 0.6f) * (0.7f + random.nextFloat() * 0.7f) * scale;
            blob(canvas, part, pathX[i], pathY[i], radius, SLASH_CORE, SLASH_EDGE, 240, random, 0.6f,
                    layer);
        }
        return true;
    }

    /**
     * 弹孔：近黑的核心、一圈暗红、一层低透明度的血雾。
     *
     * <p>三层半径递进，因此从中心到边缘有明确的由黑到血色的渐变，而不是一个方块；
     * 每层轮廓都带抖动，所以边缘是不平整的。
     */
    private static boolean paintHole(int[] canvas, Part part, int x, int y, Random random, Layer layer,
                                    float scale) {
        float core = (0.65f + random.nextFloat() * 0.5f) * scale;
        blob(canvas, part, x, y, core + 1.1f * scale, HOLE_RIM, BLOOD, 104, random, 0.8f, layer);
        blob(canvas, part, x, y, core + 0.6f * scale, HOLE_RIM, HOLE_RIM, 190, random, 0.6f, layer);
        blob(canvas, part, x, y, core, HOLE_CORE, HOLE_RIM, 248, random, 0.45f, layer);
        return true;
    }

    /**
     * 小血点：2×2 上下的一小块，带一圈更淡的渗血。
     *
     * <p>对应参考实现里最常见的 2×2 素材。它是"数量多、分布均匀"的主力——重伤观感靠很多这样的
     * 小点堆出来，而不是靠少数几块大面积。
     */
    private static boolean paintSpeck(int[] canvas, Part part, int x, int y, Random random, Layer layer,
                                      float scale) {
        float core = (0.45f + random.nextFloat() * 0.35f) * scale;
        blob(canvas, part, x, y, core + 0.7f * scale, HOLE_RIM, BLOOD, 124, random, 0.85f, layer);
        blob(canvas, part, x, y, core, HOLE_CORE, HOLE_RIM, 242, random, 0.5f, layer);
        return true;
    }

    /** 伤口周围散落的几点血：单个像素，透明度与灰度都随机，把规则形状打散。 */
    private static void paintSpatter(int[] canvas, Part part, int x, int y, Random random, Layer layer,
                                    float scale) {
        int count = 1 + random.nextInt(4);
        for (int i = 0; i < count; i++) {
            int spread = Math.max(1, Math.round(3 * scale));
            int px = x + random.nextInt(spread * 2 + 1) - spread;
            int py = y + random.nextInt(spread * 2 + 1) - spread;
            if (!woundable(part, px, py)) {
                continue;
            }
            int alpha = 70 + random.nextInt(120);
            int grey = HOLE_RIM + random.nextInt(BLOOD - HOLE_RIM + 1);
            blend(canvas, part, px, py, grey, alpha, layer);
        }
    }

    /**
     * 碳灰：伤口周围的火药烧灼与灰烬，比血色暗得多、几乎是中性黑。
     *
     * <p>散落的黑灰点会让伤口看起来是"被打了"而不是"画了个红点"，
     * 也是这一层里唯一不偏红的元素，视觉上把整片伤口打散。
     */
    private static void paintSoot(int[] canvas, Part part, int x, int y, Random random, Layer layer,
                                 float scale) {
        // 近处：贴着伤口的烧灼，稍浓。
        int nearSpread = Math.max(1, Math.round(3 * scale));
        int near = 2 + random.nextInt(4);
        for (int i = 0; i < near; i++) {
            int px = x + random.nextInt(nearSpread * 2 + 1) - nearSpread;
            int py = y + random.nextInt(nearSpread * 2 + 1) - nearSpread;
            if (!woundable(part, px, py)) {
                continue;
            }
            blend(canvas, part, px, py, SOOT + random.nextInt(SOOT), 70 + random.nextInt(70), layer);
        }
        // 远处：飞散的火药灰。透明度压得很低，于是它是"把皮肤压暗"而不是"盖一层红"，
        // 在浅色皮肤上就表现为灰色斑点——这正是碳灰该有的样子。
        int farSpread = Math.max(2, Math.round(6 * scale));
        int far = 1 + random.nextInt(3);
        for (int i = 0; i < far; i++) {
            int px = x + random.nextInt(farSpread * 2 + 1) - farSpread;
            int py = y + random.nextInt(farSpread * 2 + 1) - farSpread;
            if (!woundable(part, px, py)) {
                continue;
            }
            blend(canvas, part, px, py, SOOT / 2 + random.nextInt(SOOT), 40 + random.nextInt(70), layer);
        }
    }

    /** 血痕：从伤口往下拖几格，越往下越淡，同时左右随机摆动。 */
    private static void paintDrip(int[] canvas, Part part, int x, int y, Random random, Layer layer,
                                  float scale) {
        int length = Math.max(1, Math.round((2 + random.nextInt(4)) * scale));
        int drift = 0;
        for (int i = 1; i <= length; i++) {
            if (random.nextInt(100) < 40) {
                drift += random.nextInt(3) - 1;
            }
            int alpha = Math.max(MIN_ALPHA, 150 - i * 26);
            int grey = Math.max(HOLE_RIM, BLOOD - i * 12);
            int px = x + drift;
            int py = y + i;
            if (!woundable(part, px, py)) {
                break;
            }
            blend(canvas, part, px, py, grey, alpha, layer);
        }
    }

    /** 两端收细、中段最宽的包络。 */
    private static float taper(int length, int index, float min, float span) {
        float t = length <= 1 ? 0.5f : (float) index / (length - 1);
        return min + span * (float) Math.sin(Math.PI * (0.12f + 0.76f * t));
    }

    /**
     * 一团有柔和边缘与内部渐变的血迹。
     *
     * <p>半径逐像素抖动，因此轮廓是不平整的；而内部的透明度与灰度按到中心的距离平滑衰减，
     * 所以边缘柔和、中心最深。抖动只作用在半径判定上，不影响内部的渐变，否则整块都会变成噪点。
     */
    private static void blob(int[] canvas, Part part, int cx, int cy, float radius,
                             int coreGrey, int edgeGrey, int coreAlpha, Random random, float ragged,
                             Layer layer) {
        // 遍历范围固定，不随半径变化：每个候选像素都要消耗一次 nextFloat 来取轮廓抖动，
        // 如果范围跟着半径走，"半径变了"就会改变随机数的消耗数量，整条伤口序列随之分叉，
        // "高档包含低档全部伤口"与"两层严格对应"两条性质都会失效。
        int extent = BLOB_EXTENT;
        for (int dy = -extent; dy <= extent; dy++) {
            for (int dx = -extent; dx <= extent; dx++) {
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                float local = radius + (random.nextFloat() - 0.5f) * 2f * ragged;
                if (local <= 0f || distance > local) {
                    continue;
                }
                float t = Math.min(1f, distance / local);
                // 二次衰减：中心接近全不透明，边缘快速变淡。
                int alpha = Math.round(coreAlpha * (1f - t * t));
                if (alpha < MIN_ALPHA) {
                    continue;
                }
                int grey = Math.round(coreGrey + (edgeGrey - coreGrey) * t);
                blend(canvas, part, cx + dx, cy + dy, grey, alpha, layer);
            }
        }
    }

    // ------------------------------------------------------------------ 像素

    /**
     * 把一点血迹按 source-over 混到画布上。
     *
     * <p>灰度取「加权结果」与「已有的更深的那个」的较小值：重叠处应该更黑（血更多），
     * 单纯加权平均会让叠了两处伤口的地方反而变浅。
     */
    private static void blend(int[] canvas, Part part, int x, int y, int grey, int alpha, Layer layer) {
        // 越出种子部位矩形的像素一律丢掉。团块的半径可能伸到相邻部位上，而相邻部位的第二层偏移
        // 与本体不同，一旦让它写出去，两层就不再是"同一个矩形加一个平移"，深度裁决会失去确定的含义。
        if (!woundable(part, x, y)) {
            return;
        }
        if (layer == Layer.OVERLAY) {
            if (!part.overlay()) {
                // 这个部位没有第二层（帽子），这一处伤口只存在于基础层。
                return;
            }
            // 传入的一律是基础层坐标；第二层是同一部位的整体平移。
            plot(canvas, OVERLAY, x + part.overlayDx(), y + part.overlayDy(), grey, alpha);
            return;
        }
        plot(canvas, BASE, x, y, grey, alpha);
    }

    private static void plot(int[] canvas, boolean[] allowed, int x, int y, int grey, int alpha) {
        if (x < 0 || y < 0 || x >= SIZE || y >= SIZE || !allowed[y * SIZE + x]) {
            return;
        }
        int index = y * SIZE + x;
        int existing = canvas[index];
        if (existing == 0) {
            canvas[index] = argb(grey, alpha);
            return;
        }
        int dstAlpha = (existing >>> 24) & 0xFF;
        int outAlpha = alpha + dstAlpha * (255 - alpha) / 255;
        if (outAlpha <= 0) {
            return;
        }
        int dstGrey = (existing >>> 16) & 0xFF;
        int weighted = (grey * alpha + dstGrey * dstAlpha * (255 - alpha) / 255) / outAlpha;
        int outGrey = Math.min(weighted, Math.min(grey, dstGrey));
        canvas[index] = argb(outGrey, outAlpha);
    }

    private static int argb(int grey, int alpha) {
        int value = Math.max(0, Math.min(255, grey));
        int a = Math.max(0, Math.min(255, alpha));
        return (a << 24) | (value << 16) | (value << 8) | value;
    }

    /**
     * 该像素是否属于这个部位在这一层上的区域。
     *
     * <p>没有第二层的部位（帽子）永远只允许落在基础层，否则会画到帽子上。
     */
    /**
     * 该像素是否还在这个部位的贴图矩形内。
     *
     * <p><b>必须裁剪到种子部位自己的矩形</b>，不能只判断"是否落在任意可撒区域"：两个相邻部位
     * （例如左腿背面与左臂外侧）的贴图是挨着的，伤口一旦越界，它的基础层副本落在腿上、
     * 第二层副本却按手臂的偏移落到了袖子上。渲染时两层是分开画的，那处伤口就会在腿和袖子之间
     * 断开——外层皮肤不透明时只看得见袖子那一半。裁剪之后两层严格是同一个矩形加一个平移，
     * 深度裁决才有确定的含义。
     */
    private static boolean woundable(Part part, int x, int y) {
        return x >= part.x() && x < part.x() + part.w()
                && y >= part.y() && y < part.y() + part.h();
    }

    /** 固定种子：伤口序列与档位无关，因此第 t 档恒为前 count(t) 处的子集。 */
    private static final long SEED = 0x5EED0000L;

    private static void shuffle(int[] values, Random random) {
        for (int i = values.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = values[i];
            values[i] = values[j];
            values[j] = tmp;
        }
    }

    private static boolean[] buildLayer(boolean overlay) {
        boolean[] layer = new boolean[SIZE * SIZE];
        for (Part part : PARTS) {
            if (overlay && !part.overlay()) {
                continue;
            }
            int offsetX = overlay ? part.overlayDx() : 0;
            int offsetY = overlay ? part.overlayDy() : 0;
            for (int y = part.y(); y < part.y() + part.h(); y++) {
                for (int x = part.x(); x < part.x() + part.w(); x++) {
                    int px = x + offsetX;
                    int py = y + offsetY;
                    if (px < 0 || py < 0 || px >= SIZE || py >= SIZE) {
                        continue;
                    }
                    layer[py * SIZE + px] = true;
                }
            }
        }
        return layer;
    }

    private static int[] buildCandidates() {
        int total = 0;
        for (Part part : PARTS) {
            total += part.w() * part.h() * part.weight();
        }
        int[] candidates = new int[total];
        int cursor = 0;
        for (int index = 0; index < PARTS.length; index++) {
            Part part = PARTS[index];
            for (int y = part.y(); y < part.y() + part.h(); y++) {
                for (int x = part.x(); x < part.x() + part.w(); x++) {
                    for (int repeat = 0; repeat < part.weight(); repeat++) {
                        candidates[cursor++] = (index << 16) | (x << 8) | y;
                    }
                }
            }
        }
        return candidates;
    }
}
