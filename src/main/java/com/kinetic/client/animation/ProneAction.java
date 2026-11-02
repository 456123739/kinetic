package com.kinetic.client.animation;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 匍匐（趴下）动作的侧躺状态。
 *
 * <p>问题：趴下时打药 / 换弹 / 手雷蓄力沿用站姿动画，而站姿动画的 {@code Head} 是"相对站直的身体"
 * 写的——它会把 {@code climbing} 里那句抵消躺姿的 {@code -90} 顶掉，头就顺着躺姿扎进地里；
 * 手臂的"向前"是胸腔法线，趴下时该法线朝地，于是双手也插进地面。
 *
 * <p>解法：先平滑侧滚到侧躺（{@code roll} 0→1），动作在侧躺姿态里做完，结束后再滚回去。
 * 本类只维护这一个标量，以及"这一轮是什么动作"（用来选同一段动画做回滚，否则回滚会换姿势）。
 * 姿态本身全部在动画里：每个被改写的通道都写成
 * {@code bone_rot('X').ax * (1 - kinetic.prone_roll) + <目标> * kinetic.prone_roll}，
 * 因此 {@code roll == 0} 时逐位等于底层动画的输出（不是"接近"），进出在任意底层姿势下都连续。
 *
 * <p>动作种类不看游戏内部状态，而是看其他频道这一帧选中的动画名（见 {@code PronePredicate}），
 * 这样对任何往那些频道塞动画的枪械联动都自动成立。
 */
public final class ProneAction {
    /** 动作种类。{@code OTHER} 覆盖吃喝这类"只需要把头和腿摆正"的动作。 */
    public enum Kind {
        NONE, MEDICAL, RELOAD, GRENADE, OTHER
    }

    /** 滚入时长（秒）。0.24s 约等于一次自然的翻身；越短，中途"站姿手臂压在躺姿上"的帧越少。 */
    static final float ENTER_SECONDS = 0.24f;
    /** 动作结束时仍趴着：滚出时长（秒），比滚入略慢，回位更放松。 */
    static final float EXIT_SECONDS = 0.34f;
    /**
     * 已经不在趴姿（站起来 / 死亡 / 睡觉 / 上车 / 开始游泳）时的滚出时长（秒）。
     *
     * <p>这时底层已经换成站姿（或死亡）动画，若还按 0.34s 慢慢退，会有 0.3 秒"站着却举着一副
     * 侧躺的胳膊"的违和画面。站起来本身就是一次离散的姿态切换，快速收掉反而更自然。
     */
    static final float SNAP_SECONDS = 0.12f;
    /**
     * 动作信号短暂丢失的容忍（秒）。
     *
     * <p>换弹的开火动画是一次性的：TaCZ 的重载计数还没走完，动画可能先播完一帧空档。
     * 没有这点容忍，身体会在换弹中途滚回趴姿再滚出来。
     */
    static final float DETACH_GRACE_SECONDS = 0.12f;
    /**
     * "动作早就开始了、玩家途中才趴下"时，侧躺的起始进度。
     *
     * <p>这一帧本身就是一次离散的姿态切换（站 → 趴），所以不必再从 0 慢慢滚：直接给到 0.7，
     * 剩下的 0.07s 收尾。否则会有 0.2 秒"站姿的手臂压在躺姿身体上、整个人陷进地里"的过渡帧，
     * 看起来就是"先卡进地里再爬出来"。动作**在趴下状态里**开始时不走这条路径，仍从 0 平滑滚入。
     */
    static final float CATCH_UP_PROGRESS = 0.70f;

    /** 单帧允许推进的最大 tick 数：掉帧/暂停后不要一次跳过整段动画。 */
    static final float MAX_STEP_TICKS = 2.0f;
    /** renderTicks 是 tick，一秒 20 个。 */
    static final float TICKS_PER_SECOND = 20.0f;
    /** 小于这个 roll 视为 0，直接停止写姿态动画。 */
    public static final float EPSILON = 0.001f;

    private static final ConcurrentHashMap<Player, State> STATES = new ConcurrentHashMap<>();
    private static int observeCounter;

    private ProneAction() {
    }

    /** 玩家此刻是不是"趴着"（爬行/匍匐），排除游泳、滑铲、骑乘、死亡、睡觉、旁观。 */
    public static boolean isProne(@Nullable Player player) {
        if (player == null) {
            return false;
        }
        return player.getPose() == Pose.SWIMMING
                && !player.isSwimming()
                && player.onGround()
                && !player.isDeadOrDying()
                && !player.isSleeping()
                && !player.isSpectator()
                && player.getVehicle() == null
                && !SlideLock.isLocked(player);
    }

    /** {@link #isProne(Player)} 的实体版：非玩家一律 false。 */
    public static boolean isProne(@Nullable Entity entity) {
        return entity instanceof Player player && isProne(player);
    }

    /**
     * 用本帧观测推进状态并返回 roll（0..1）。
     *
     * @param renderTicks 渲染 tick（与动画求值同源，含插值），用于算帧间隔
     * @param kind        本帧识别到的动作种类；{@code NONE} 表示动作已经结束
     */
    public static float observe(@Nullable Player player, float renderTicks, Kind kind) {
        if (player == null) {
            return 0.0f;
        }
        if (player.isRemoved()) {
            STATES.remove(player);
            return 0.0f;
        }
        State state = STATES.get(player);
        if (state == null) {
            if (kind == Kind.NONE) {
                return 0.0f;
            }
            state = new State();
            state.lastRenderTicks = renderTicks;
            STATES.put(player, state);
        }
        boolean prone = isProne(player);
        float roll = advance(state, renderTicks, kind != Kind.NONE, prone, kind);
        if (roll <= 0.0f) {
            STATES.remove(player);
        } else if ((++observeCounter & 0xFF) == 0) {
            prune(renderTicks);
        }
        return roll;
    }

    /**
     * 纯状态推进，供测试直接驱动（没有任何客户端依赖）。
     *
     * <p>`active` 是"动作还在进行"（换弹/打药/蓄力），`prone` 是"此刻是不是趴着"。
     * 两者分开传值，是因为四种组合的期望行为完全不同：
     * <ul>
     *   <li>站着做动作：不侧躺（0）；</li>
     *   <li>站着做动作的途中趴下：<b>当场开始侧躺</b>，动作继续（这就是"无缝衔接"）；</li>
     *   <li>趴着做动作、动作结束：慢慢回位到匍匐（EXIT）；</li>
     *   <li>趴着做动作、途中站起来：快速收掉（SNAP），别让站姿顶着一副侧躺的胳膊。</li>
     * </ul>
     *
     * @return 本帧生效的 roll（已缓动）
     */
    static float advance(State state, float renderTicks, boolean active, boolean prone, Kind kind) {
        float ticks = renderTicks - state.lastRenderTicks;
        if (!(ticks > 0.0f)) {
            ticks = 0.0f;
        }
        ticks = Math.min(ticks, MAX_STEP_TICKS);
        float dt = ticks / TICKS_PER_SECOND;
        state.lastRenderTicks = renderTicks;

        if (active && kind != Kind.NONE && prone) {
            // 只记"趴着时"的动作：站着做动作不该留下任何状态。
            if (state.armedCatchUp) {
                // 动作在站着的时候就开始了：直接落到侧躺，不从"站姿叠躺姿"的坏姿势慢慢滚。
                state.progress = Math.max(state.progress, CATCH_UP_PROGRESS);
                state.armedCatchUp = false;
            }
            state.kind = kind;
            state.detach = DETACH_GRACE_SECONDS;
        } else if (active && kind != Kind.NONE) {
            // 站着做动作：记下"动作已经在进行"，等趴下那一刻用上。
            state.armedCatchUp = true;
        } else {
            state.armedCatchUp = false;
            // 容忍只对"趴着"有意义：站着的时候不该留下一条会凭空触发侧躺的尾巴。
            state.detach = !prone ? 0.0f : Math.max(0.0f, state.detach - dt);
        }

        // 只有趴着才允许侧躺；动作短暂中断时（detach）仍然保持，避免一帧抖动就回位。
        boolean want = prone && (active || state.detach > 0.0f);
        float seconds = want ? ENTER_SECONDS : (prone ? EXIT_SECONDS : SNAP_SECONDS);
        float speed = dt / seconds;
        state.progress = want ? Math.min(1.0f, state.progress + speed) : Math.max(0.0f, state.progress - speed);
        if (state.progress <= 0.0f) {
            state.kind = Kind.NONE;
            return 0.0f;
        }
        return ease(state.progress);
    }

    /** 缓入缓出：两端速度为 0，中间最快。 */
    static float ease(float progress) {
        float p = Math.max(0.0f, Math.min(1.0f, progress));
        return p * p * (3.0f - 2.0f * p);
    }

    /** 当前 roll（只读，不推进）。给 molang 变量用。 */
    public static float roll(@Nullable Entity entity) {
        if (!(entity instanceof Player player)) {
            return 0.0f;
        }
        State state = STATES.get(player);
        return state == null ? 0.0f : ease(state.progress);
    }

    /** 这一轮锁定的动作种类（动作动画停了也还留着，用于回滚时选同一段动画）。 */
    public static Kind kindOf(@Nullable Player player) {
        if (player == null) {
            return Kind.NONE;
        }
        State state = STATES.get(player);
        return state == null ? Kind.NONE : state.kind;
    }

    public static boolean isActive(@Nullable Player player) {
        return roll(player) > EPSILON;
    }

    public static void clear(@Nullable Player player) {
        if (player != null) {
            STATES.remove(player);
        }
    }

    public static void clearAll() {
        STATES.clear();
    }

    private static void prune(float renderTicks) {
        Iterator<Map.Entry<Player, State>> iterator = STATES.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().isRemoved() || renderTicks - entry.getValue().lastRenderTicks > 200.0f) {
                iterator.remove();
            }
        }
    }

    /** 单个玩家的侧躺状态；包可见是为了让状态机可以脱离 Player 实例单独测试。 */
    static final class State {
        // 动画求值在 worker 线程池上跑，同一实体的相邻帧可能落在不同线程，
        // 因此跨帧可见性用 volatile（同一帧内的读写在同一个线程上）。
        volatile float progress;
        volatile float lastRenderTicks = -1.0f;
        volatile float detach;
        volatile boolean armedCatchUp;
        volatile Kind kind = Kind.NONE;
    }
}
