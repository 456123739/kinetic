package com.kinetic.client.animation;

/**
 * 内置动画优先级档位。
 *
 * <p>{@link #HIGHEST}..{@link #LOWEST} 是注册时使用的档位（数值越小越重要），决定
 * “未配置覆盖时”的判断顺序；{@link #weightOf(int)} 把档位换算成与服务端优先级配置
 * 同一量纲的数值（数值越大越优先），使配置值与内置顺序可以放在一起排序。
 */
public final class Priority {
    public static final int HIGHEST = 0;
    public static final int HIGH = 1;
    public static final int NORMAL = 2;
    public static final int LOW = 3;
    public static final int LOWEST = 4;

    /** 各档位在优先级配置量纲下的权重。 */
    public static final int WEIGHT_HIGHEST = 1000;
    public static final int WEIGHT_HIGH = 800;
    public static final int WEIGHT_NORMAL = 600;
    public static final int WEIGHT_LOW = 400;
    public static final int WEIGHT_LOWEST = 200;

    private Priority() {
    }

    /** 把内置档位换算成配置量纲的权重。 */
    public static int weightOf(int builtinPriority) {
        return switch (builtinPriority) {
            case HIGHEST -> WEIGHT_HIGHEST;
            case HIGH -> WEIGHT_HIGH;
            case NORMAL -> WEIGHT_NORMAL;
            case LOW -> WEIGHT_LOW;
            default -> WEIGHT_LOWEST;
        };
    }
}
