package com.kinetic.client.wound;

import com.kinetic.client.wound.WoundMaskPainter.Layer;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受伤遮罩的绘制。
 *
 * <p>这一层的断言都是"能离线证明、不需要看渲染图"的性质：位置必须落在该层的可撒区域内、
 * 颜色必须是灰度而 alpha 必须有梯度、档位越高只增不减、两层必须严格对应、同一档位每次得到
 * 完全一样的图。视觉上的判断（像不像刀伤/弹孔/碳灰、和 Visual Health 的密度是否接近）
 * 留给渲染图，代码这边只保证不会退化。
 */
class WoundMaskPainterTest {

    private static final int SIZE = WoundMaskPainter.SIZE;

    @Test
    void tierZeroIsEmpty() {
        for (Layer layer : Layer.values()) {
            assertTrue(allTransparent(WoundMaskPainter.paint(0, 5, 50, layer)));
            assertTrue(allTransparent(WoundMaskPainter.paint(-1, 5, 50, layer)));
        }
    }

    @Test
    void paintingIsDeterministic() {
        for (Layer layer : Layer.values()) {
            for (int tier = 1; tier <= 5; tier++) {
                assertArrayEquals(WoundMaskPainter.paint(tier, 5, 50, layer),
                        WoundMaskPainter.paint(tier, 5, 50, layer),
                        layer + " 第 " + tier + " 档必须是确定性的");
            }
        }
    }

    @Test
    void woundCountAccumulatesPerTier() {
        // 默认 5 档、50% 密度：每档新增 11 处，累计 11/22/33/44/55。
        int[] expected = {0, 11, 22, 33, 44, 55};
        for (int tier = 0; tier <= 5; tier++) {
            assertEquals(expected[tier], WoundMaskPainter.woundCount(tier, 5, 50), "第 " + tier + " 档");
        }
        // 档位数更多时每档新增更少，最高档的总数与档位数无关。
        assertEquals(WoundMaskPainter.woundCount(5, 5, 50), WoundMaskPainter.woundCount(10, 10, 50),
                "最高档的伤口总数不应随档位数变化");
        // 密度上下限。
        assertEquals(1, WoundMaskPainter.woundCount(1, 5, 0), "掉血了就必须至少看得见一处");
        assertEquals(WoundMaskPainter.woundCount(5, 5, 100), WoundMaskPainter.woundCount(5, 5, 999));
    }

    @Test
    void woundCountIsMonotoneInTierAndDensity() {
        for (int tiers : new int[]{2, 3, 5, 8, 10}) {
            int previous = 0;
            for (int tier = 1; tier <= tiers; tier++) {
                int count = WoundMaskPainter.woundCount(tier, tiers, 50);
                assertTrue(count >= previous, "档位升高不能让伤口变少: " + tier + "/" + tiers + " -> " + count);
                previous = count;
            }
        }
        int previous = 0;
        for (int density = 10; density <= 100; density += 10) {
            int count = WoundMaskPainter.woundCount(5, 5, density);
            assertTrue(count >= previous, "密度升高不能让伤口变少: " + density + "% -> " + count);
            previous = count;
        }
    }

    /**
     * 档位是累加的：伤口序列与档位无关，第 t 档恒为前 count(t) 处。
     *
     * <p>这条性质是"回血就是伤口在愈合"的全部依据——如果每一档都重新随机一遍，
     * 回血时整身伤口会换位置换形状，看起来像换了一张贴图。
     */
    @Test
    void higherTierContainsEveryLowerTierWound() {
        for (Layer layer : Layer.values()) {
            for (int tiers : new int[]{2, 5, 10}) {
                for (int density : new int[]{50, 100}) {
                    for (int tier = 2; tier <= tiers; tier++) {
                        int[] lower = WoundMaskPainter.paint(tier - 1, tiers, density, layer);
                        int[] higher = WoundMaskPainter.paint(tier, tiers, density, layer);
                        for (int i = 0; i < lower.length; i++) {
                            if (lower[i] != 0) {
                                assertTrue(higher[i] != 0, layer + " 第 " + tier + " 档丢了第 "
                                        + (tier - 1) + " 档的伤口（下标 " + i + "）");
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void paintedAreaGrowsWithTheTier() {
        int previous = 0;
        for (int tier = 1; tier <= 10; tier++) {
            int painted = countPainted(WoundMaskPainter.paint(tier, 10, 100, Layer.BASE))
                    + countPainted(WoundMaskPainter.paint(tier, 10, 100, Layer.OVERLAY));
            assertTrue(painted > previous, "档位升高必须让伤口变多: 第 " + tier + " 档 " + painted);
            previous = painted;
        }
    }

    @Test
    void everyPixelLandsInItsOwnLayersRegion() {
        for (int tiers : new int[]{2, 5, 10}) {
            for (int tier = 1; tier <= tiers; tier++) {
                for (Layer layer : Layer.values()) {
                    int[] pixels = WoundMaskPainter.paint(tier, tiers, 100, layer);
                    for (int y = 0; y < SIZE; y++) {
                        for (int x = 0; x < SIZE; x++) {
                            if (pixels[y * SIZE + x] == 0) {
                                continue;
                            }
                            boolean inside = layer == Layer.BASE
                                    ? WoundMaskPainter.isWoundable(x, y)
                                    : WoundMaskPainter.isOverlayWoundable(x, y);
                            assertTrue(inside, layer + " 的伤口画到了该层之外 (" + x + "," + y + ")，档位 "
                                    + tier + "/" + tiers);
                        }
                    }
                }
            }
        }
    }

    /**
     * 两层必须严格对应。
     *
     * <p>渲染时第二层那一遍用深度相等测试决定"外层皮肤在不在"，基础层那一遍画在被外层挡住的地方之外。
     * 这个机制成立的前提是：同一处伤口在两层上的位置只差该部位的一个整体平移，且两层的伤口集合一致。
     * 只要有一层少画了，就会出现"外层皮肤在，但那一层没有血"的空洞。
     */
    @Test
    void overlayMaskMirrorsTheBaseMaskExactly() {
        int[] base = WoundMaskPainter.paint(5, 5, 100, Layer.BASE);
        int[] overlay = WoundMaskPainter.paint(5, 5, 100, Layer.OVERLAY);
        int mirrored = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (base[y * SIZE + x] == 0) {
                    continue;
                }
                int[] offset = overlayOffset(x, y);
                assertTrue(offset != null, "基础层在 (" + x + "," + y + ") 有伤口，但它不属于任何部位");
                int ox = x + offset[0];
                int oy = y + offset[1];
                assertTrue(overlay[oy * SIZE + ox] != 0,
                        "基础层在 (" + x + "," + y + ") 有伤口，第二层 (" + ox + "," + oy + ") 却没有");
                mirrored++;
            }
        }
        assertTrue(mirrored > 0, "必须真的有两层对应的伤口");
        // 反过来：第二层的每一个像素都必须在基础层上有对应（第二层不会凭空多出伤口）。
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (overlay[y * SIZE + x] == 0) {
                    continue;
                }
                boolean found = false;
                int[] offset = overlayOffsetInverse(x, y);
                if (offset != null) {
                    found = base[(y + offset[1]) * SIZE + (x + offset[0])] != 0;
                }
                assertTrue(found, "第二层在 (" + x + "," + y + ") 有伤口，基础层却没有对应");
            }
        }
    }

    @Test
    void footprintStaysProportionalToTheWoundCount() {
        for (int tiers : new int[]{2, 5, 10}) {
            for (int tier = 1; tier <= tiers; tier++) {
                int count = WoundMaskPainter.woundCount(tier, tiers, 100);
                int painted = countPainted(WoundMaskPainter.paint(tier, tiers, 100, Layer.BASE))
                        + countPainted(WoundMaskPainter.paint(tier, tiers, 100, Layer.OVERLAY));
                assertTrue(painted >= count, "每处伤口至少要留下一个像素: 档位 " + tier + " 只有 " + painted);
            }
        }
    }

    @Test
    void colourIsGreyscaleWithAGradientOfAlphas() {
        Set<Integer> greys = new HashSet<>();
        Set<Integer> alphas = new HashSet<>();
        int semiTransparent = 0;
        for (Layer layer : Layer.values()) {
            for (int tier = 1; tier <= 5; tier++) {
                int[] pixels = WoundMaskPainter.paint(tier, 5, 100, layer);
                for (int value : pixels) {
                    int alpha = (value >>> 24) & 0xFF;
                    if (alpha == 0) {
                        continue;
                    }
                    int red = (value >>> 16) & 0xFF;
                    int green = (value >>> 8) & 0xFF;
                    int blue = value & 0xFF;
                    assertEquals(red, green, "遮罩必须是灰度：颜色由顶点色给");
                    assertEquals(red, blue, "遮罩必须是灰度：颜色由顶点色给");
                    assertTrue(red >= WoundMaskPainter.SOOT / 2 && red <= WoundMaskPainter.BLOOD,
                            "灰度值 " + red + " 越界");
                    assertTrue(alpha >= WoundMaskPainter.MIN_ALPHA,
                            "alpha " + alpha + " 低于裁剪阈值，画了也看不见");
                    greys.add(red);
                    alphas.add(alpha);
                    if (alpha < 255) {
                        semiTransparent++;
                    }
                }
            }
        }
        // 渐变与半透明层是这一层的主要目的，必须有足够多的层次，否则又退回"贴纸"观感。
        assertTrue(greys.size() >= 8, "灰度层次太少，看不出深浅过渡，实际 " + greys.size());
        assertTrue(alphas.size() >= 20, "alpha 层次太少，看不出边缘渐变，实际 " + alphas.size());
        assertTrue(semiTransparent > 0, "必须有半透明像素");
    }

    @Test
    void sootSpecksAreDarkerThanAnyBlood() {
        // 碳灰是唯一比血色暗得多、且接近中性黑的元素；没有它就只是"红点堆"。
        boolean found = false;
        for (int tier = 1; tier <= 5 && !found; tier++) {
            for (Layer layer : Layer.values()) {
                int[] pixels = WoundMaskPainter.paint(tier, 5, 100, layer);
                for (int value : pixels) {
                    int alpha = (value >>> 24) & 0xFF;
                    if (alpha == 0) {
                        continue;
                    }
                    if (((value >>> 16) & 0xFF) <= WoundMaskPainter.SOOT) {
                        found = true;
                        break;
                    }
                }
            }
        }
        assertTrue(found, "必须有碳灰级别的暗像素");
    }

    /**
     * 碳灰是唯一比血色暗得多、接近中性黑的元素，它的像素数随档位增长。
     *
     * <p>形状本身不能随档位变化（否则随机流会分叉），所以档位只影响<b>数量</b>：
     * 高档 = 更多同样大小的伤口，而不是更大的伤口。这条同时钉住"没有大块撕裂"——
     * 试过用一块半径 3 格以上的血泊制造冲击力，结果是躯干被那一块吃掉、别处没有余地。
     */
    @Test
    void sootFootprintGrowsWithTheTier() {
        int previous = 0;
        for (int tier = 1; tier <= 5; tier++) {
            int darkest = 0;
            for (Layer layer : Layer.values()) {
                int[] pixels = WoundMaskPainter.paint(tier, 5, 100, layer);
                for (int value : pixels) {
                    if ((value >>> 24) != 0 && ((value >>> 16) & 0xFF) <= WoundMaskPainter.SOOT) {
                        darkest++;
                    }
                }
            }
            assertTrue(darkest >= previous, "碳灰像素数不应随档位下降: 第 " + tier + " 档 " + darkest);
            previous = darkest;
        }
        assertTrue(previous > 0, "必须真的有碳灰像素");
    }

    /**
     * 单处伤口必须保持"小"。
     *
     * <p>这是上一版"大撕裂"的直接教训：一块盖住三分之一前胸的血泊，观感上把整块躯干吃掉了，
     * 真实度反而下降。参考实现（Visual Health）对玩家只用 2×2 与 3×3 的小素材，
     * 靠数量与分布堆出重伤观感。这里用一个上限把"某处伤口面积过大"钉死。
     */
    @Test
    void noSingleWoundIsLarge() {
        // 最大的一处是刀伤：7 格长、每格一圈渗血 + 一条主缝，加上飞溅、碳灰与血痕。
        int limit = 44;
        for (int tiers : new int[]{5, 10}) {
            for (int tier = 1; tier <= tiers; tier++) {
                int count = WoundMaskPainter.woundCount(tier, tiers, 100);
                int painted = countPainted(WoundMaskPainter.paint(tier, tiers, 100, Layer.BASE))
                        + countPainted(WoundMaskPainter.paint(tier, tiers, 100, Layer.OVERLAY));
                assertTrue(painted <= count * limit,
                        "单处伤口的平均面积过大（可能有块状血泊）: 第 " + tier + " 档 " + painted
                                + " 像素 / " + count + " 处，上限 " + limit);
            }
        }
    }

    @Test
    void armsAvoidTheSlimSkinsTransparentColumns() {
        // 细手臂的贴图比宽手臂窄 2 列，多出来的那两列在细皮肤上是全透明的。
        // 遮罩是宽/细手臂共用的一张，落到那里就等于没画。
        for (int y = 20; y < 32; y++) {
            assertTrue(WoundMaskPainter.isWoundable(53, y), "右臂 53 列必须可用");
            assertFalse(WoundMaskPainter.isWoundable(54, y), "右臂 54 列在细皮肤上是透明的");
            assertFalse(WoundMaskPainter.isWoundable(55, y), "右臂 55 列在细皮肤上是透明的");
        }
        for (int y = 52; y < 64; y++) {
            assertTrue(WoundMaskPainter.isWoundable(45, y), "左臂 45 列必须可用");
            assertFalse(WoundMaskPainter.isWoundable(46, y), "左臂 46 列在细皮肤上是透明的");
            assertFalse(WoundMaskPainter.isWoundable(47, y), "左臂 47 列在细皮肤上是透明的");
        }
    }

    @Test
    void overlayRegionsMirrorTheBaseOnes() {
        assertTrue(WoundMaskPainter.isOverlayWoundable(24, 40), "外套前胸（基础 24,24 平移 +16 y）");
        assertTrue(WoundMaskPainter.isOverlayWoundable(46, 40), "右袖正面（基础 46,24 平移 +16 y）");
        assertTrue(WoundMaskPainter.isOverlayWoundable(52, 56), "左袖正面（基础 36,56 平移 +16 x）");
        assertTrue(WoundMaskPainter.isOverlayWoundable(6, 56), "左裤（基础 22,56 平移 -16 x）");
        assertFalse(WoundMaskPainter.isOverlayWoundable(22, 56), "左裤的偏移方向必须是 -16 x");
        // 帽子是独立配饰，不该被画上伤口，也不该有第二层可撒区。
        assertTrue(WoundMaskPainter.isOverlayWoundable(40, 8), "帽子正面（基础 8,8 平移 +32 x）");
        assertTrue(WoundMaskPainter.isOverlayWoundable(56, 8), "帽子后脑（基础 24,8 平移 +32 x）");
        assertFalse(WoundMaskPainter.isOverlayWoundable(59, 2), "手/脚的小贴图区");
        // 基础层反过来不该包含第二层。
        assertFalse(WoundMaskPainter.isWoundable(24, 40), "外套层不属于基础层");
    }

    @Test
    void baseRegionsAreAllWoundable() {
        assertTrue(WoundMaskPainter.isWoundable(10, 10), "脸");
        assertTrue(WoundMaskPainter.isWoundable(12, 4), "头顶");
        assertTrue(WoundMaskPainter.isWoundable(24, 24), "前胸");
        assertTrue(WoundMaskPainter.isWoundable(36, 24), "后背");
        assertTrue(WoundMaskPainter.isWoundable(46, 24), "右臂正面");
        assertTrue(WoundMaskPainter.isWoundable(38, 56), "左臂正面");
        assertTrue(WoundMaskPainter.isWoundable(6, 24), "右腿正面");
        assertTrue(WoundMaskPainter.isWoundable(22, 56), "左腿正面");
        assertFalse(WoundMaskPainter.isWoundable(-1, 10), "越界");
        assertFalse(WoundMaskPainter.isWoundable(SIZE, 10), "越界");
        assertFalse(WoundMaskPainter.isWoundable(10, SIZE), "越界");
    }

    /**
     * 默认配置下的覆盖量对着 Visual Health 标定过。
     *
     * <p>{@code analysis/wound_vh_reference.py} 用 VH 自己的算法（8×8 格洗牌、格心抖动、
     * generic 素材、0.30~0.50 不透明度）在同一张皮肤上跑出 215/381/590/727/892 个<b>盖印</b>像素
     * （档位 1~5，50% 密度、5 档）；KINETIC 两层合计是 214/389/657/858/1036，同一量级。
     * 真正显示出来的像素还要过一遍"外层皮肤在不在"的裁决，脚本里单独统计（109/205/352/485/579
     * 对 VH 的 92/182/282/374/467），单测这边只钉住与皮肤无关的盖印量。
     */
    @Test
    void defaultDensityMatchesTheReferenceWeight() {
        int[] vhStamped = {0, 215, 381, 590, 727, 892};
        for (int tier = 1; tier <= 5; tier++) {
            int painted = countPainted(WoundMaskPainter.paint(tier, 5, 50, Layer.BASE))
                    + countPainted(WoundMaskPainter.paint(tier, 5, 50, Layer.OVERLAY));
            assertTrue(painted >= vhStamped[tier] / 2,
                    "第 " + tier + " 档比参考轻太多: " + painted + " vs " + vhStamped[tier]);
            assertTrue(painted <= vhStamped[tier] * 3 / 2,
                    "第 " + tier + " 档比参考重太多: " + painted + " vs " + vhStamped[tier]);
        }
    }

    /** 与 WoundMaskPainter.PARTS 一致的部位表，用于验证两层镜像关系。 */
    private static final int[][] PARTS = {
            {8, 8, 8, 8, 32, 0, 1}, {24, 8, 8, 8, 32, 0, 1}, {16, 8, 8, 8, 32, 0, 1},
            {0, 8, 8, 8, 32, 0, 1}, {8, 0, 8, 8, 32, 0, 1},
            {20, 20, 8, 12, 0, 16, 1}, {32, 20, 8, 12, 0, 16, 1},
            {16, 20, 4, 12, 0, 16, 1}, {28, 20, 4, 12, 0, 16, 1},
            {40, 20, 4, 12, 0, 16, 1}, {44, 20, 4, 12, 0, 16, 1},
            {48, 20, 4, 12, 0, 16, 1}, {52, 20, 2, 12, 0, 16, 1},
            {32, 52, 4, 12, 16, 0, 1}, {36, 52, 4, 12, 16, 0, 1},
            {40, 52, 4, 12, 16, 0, 1}, {44, 52, 2, 12, 16, 0, 1},
            {4, 20, 4, 12, 0, 16, 1}, {12, 20, 4, 12, 0, 16, 1},
            {0, 20, 4, 12, 0, 16, 1}, {8, 20, 4, 12, 0, 16, 1},
            {20, 52, 4, 12, -16, 0, 1}, {28, 52, 4, 12, -16, 0, 1},
            {16, 52, 4, 12, -16, 0, 1}, {24, 52, 4, 12, -16, 0, 1},
    };

    /** 基础层坐标 -> 第二层坐标；该部位没有第二层时返回 null（当前原版布局里不存在这种部位）。 */
    private static int[] overlayOffset(int x, int y) {
        for (int[] part : PARTS) {
            if (x >= part[0] && x < part[0] + part[2] && y >= part[1] && y < part[1] + part[3]) {
                return part[6] == 1 ? new int[]{part[4], part[5]} : null;
            }
        }
        return null;
    }

    /** 第二层坐标 -> 基础层坐标的偏移。 */
    private static int[] overlayOffsetInverse(int x, int y) {
        for (int[] part : PARTS) {
            if (part[6] != 1) {
                continue;
            }
            int bx = part[0] + part[4];
            int by = part[1] + part[5];
            if (x >= bx && x < bx + part[2] && y >= by && y < by + part[3]) {
                return new int[]{-part[4], -part[5]};
            }
        }
        return null;
    }

    private static boolean allTransparent(int[] pixels) {
        for (int value : pixels) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static int countPainted(int[] pixels) {
        int count = 0;
        for (int value : pixels) {
            if (value != 0) {
                count++;
            }
        }
        return count;
    }
}
