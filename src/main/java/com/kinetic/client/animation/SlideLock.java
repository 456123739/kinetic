package com.kinetic.client.animation;

import com.kinetic.client.compat.swarfare.SWarfareCompat;
import com.kinetic.client.compat.tacz.TACZCompat;
import com.kinetic.geckolib3.core.molang.util.StringPool;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 滑铲期间的动画状态锁定。
 *
 * <p>ParCool 在滑铲期间本来就会每 tick 把疾跑置为 false（{@code Slide#onWorkingTick}），
 * 所以"开火取消疾跑"并不是滑铲被打断的原因。真正把画面变成"头在前、脚在后、身体下拱"的是
 * 持枪姿势本身：{@code tac:hold:rifle} 里有一条 {@code AllBody} 轨道，而滑铲的 {@code AllBody}
 * 是"人向后躺、脚朝前"的大角度旋转，被持枪姿势一改写，整个人就翻过来了。
 *
 * <p>滑铲动作结束后（持续时间到、松开蹲键等）ParCool 会把动画换成 {@code CrawlAnimator}
 * （它没有 KINETIC 动画名），玩家的 {@code Pose.SWIMMING} 还在；主频道于是切到
 * {@code climb}/{@code climbing}，开火频道再叠一层 {@code tac:climbing:fire:*}——整身趴地。
 * 这两条路径都必须由骨骼级的锁定拦掉。
 *
 * <p>本类只维护"这一刻该不该锁"这一个状态，真正的两份工作分别落在：
 * <ul>
 *   <li>{@code ParCoolPredicate}：锁定期间即使 ParCool 已经停了滑铲，也继续把
 *       {@code parcool:sliding} 喂给跑酷控制器（同名动画不会重播，播放头原地继续）；</li>
 *   <li>{@code AnimationProcessor}：跑酷控制器处理完之后把下肢/躯干的骨骼快照抄下来，等所有
 *       控制器（TaCZ 的 hold/fire、原版的 swing/use）都跑完再把它们写回去——这些骨骼的最终
 *       输出只可能来自滑铲。这条回写是骨骼级的，{@code AllBody} 那种"整身改写"也拦得住。</li>
 * </ul>
 *
 * <p>锁定只在"主手持枪 + 跑酷正在滑铲"时进入，滑铲结束（或玩家站起/游泳/上车/死亡）后松开，
 * 不会影响其它任何动画。
 */
public final class SlideLock {
    /** ParCool 滑铲动画名，同时也是跑酷控制器在锁定期间保持播放的动画名。 */
    public static final String SLIDE_ANIMATION = "parcool:sliding";
    private static final String SLIDE_PREFIX = "parcool:sliding";

    /**
     * 失去 ParCool 滑铲信号之后继续锁定多久（tick）。
     *
     * <p>TaCZ 取消疾跑发生在开火那一 tick，之后 ParCool 立即停滑铲；这里留 2 秒，覆盖
     * 一次连发，期间玩家仍在往前滑（速度没掉）就一直续着。取 40 而不是无限：真正的滑铲
     * 持续不到 2 秒，再长就是在撒谎了。
     */
    static final int HOLD_TICKS = 40;

    /** 速度掉到爬行/站立水平并持续这么久就松开（tick）。 */
    static final int SLOW_RELEASE_TICKS = 20;

    /** 低于这个水平速度平方（约 0.1 格/tick）视为"已经不在滑了"。 */
    static final double SLOW_SPEED_SQR = 0.01;

    /** 离地超过这么多 tick 就松开（滑铲贴地，起跳/被炸飞都与滑铲无关）。 */
    static final int MAX_AIRBORNE_TICKS = 3;

    /** 同时跟踪的玩家上限；超过就清理已经松开的条目，避免遗留。 */
    private static final int MAX_TRACKED = 256;

    /**
     * 滑铲锁定的骨骼：根、整体身体、躯干与整条腿。
     *
     * <p>手臂、前臂、手、头不在其中——它们由 TaCZ 的静止持枪姿势负责，并且是锁定躯干的
     * 子骨骼，会跟着躯干一起动。
     */
    private static final IntOpenHashSet LOCKED_BONES = new IntOpenHashSet(new int[]{
            StringPool.computeIfAbsent("Root"),
            StringPool.computeIfAbsent("MAllBody"),
            StringPool.computeIfAbsent("AllBody"),
            StringPool.computeIfAbsent("UpBody"),
            StringPool.computeIfAbsent("UpperBody"),
            StringPool.computeIfAbsent("DownBody"),
            StringPool.computeIfAbsent("LeftLeg"),
            StringPool.computeIfAbsent("LeftLowerLeg"),
            StringPool.computeIfAbsent("LeftFoot"),
            StringPool.computeIfAbsent("RightLeg"),
            StringPool.computeIfAbsent("RightLowerLeg"),
            StringPool.computeIfAbsent("RightFoot"),
    });

    private static final ConcurrentHashMap<Player, State> STATES = new ConcurrentHashMap<>();

    private SlideLock() {
    }

    /** 该骨骼的最终输出是否必须来自滑铲。 */
    public static boolean isLockedBone(int pooledName) {
        return LOCKED_BONES.contains(pooledName);
    }

    /**
     * 用本帧的观测刷新锁定状态。
     *
     * @param parcoolAnimation ParCool 当前动画名，可能为 {@code null}
     * @param modelHasSlide    当前模型里是否有 {@code parcool:sliding}
     * @param holdingFirearm   主手是否持枪（只有持枪才需要锁：没枪时滑铲本来就不会被 TaCZ 顶掉）
     * @return 锁定是否生效（供本帧后续的频道判定使用）
     */
    public static boolean observe(Player player, @Nullable String parcoolAnimation,
                                  boolean modelHasSlide, boolean holdingFirearm) {
        if (player == null) {
            return false;
        }
        boolean slidingNow = parcoolAnimation != null && parcoolAnimation.startsWith(SLIDE_PREFIX);
        State state = STATES.get(player);
        if (!slidingNow && state == null) {
            return false;
        }
        if (state == null) {
            state = new State();
            STATES.put(player, state);
        }
        boolean locked = advance(state, player.tickCount, slidingNow, parcoolAnimation != null,
                holdingFirearm, modelHasSlide, player.isDeadOrDying(), player.isSwimming(),
                player.getVehicle() != null, player.onGround(),
                player.getPose() == net.minecraft.world.entity.Pose.SWIMMING,
                player.getDeltaMovement().horizontalDistanceSqr());
        if (!locked) {
            STATES.remove(player);
        } else {
            prune(player.tickCount);
        }
        return locked;
    }

    /**
     * 纯状态推进：所有输入都是原语，逐 tick 的行为可以直接测（没有客户端也能验证）。
     *
     * @param otherParcoolAction 跑酷正在演别的动作（非滑铲），视为滑铲已结束
     */
    static boolean advance(State state, int now, boolean slidingNow, boolean otherParcoolAction,
                           boolean holdingFirearm, boolean modelHasSlide, boolean dead,
                           boolean swimming, boolean riding, boolean onGround, boolean slidingPose,
                           double horizontalSpeedSqr) {
        state.lastSeenTick = now;
        if (slidingNow) {
            state.lastSlideTick = now;
            state.slowTicks = 0;
            state.airborneTicks = 0;
            // 上一次锁定已经释放、这一次又重新滑起来了：重新进入锁定。
            state.locked = true;
        }
        if (!state.locked) {
            return false;
        }
        boolean release = false;
        if (!holdingFirearm || !modelHasSlide || dead || swimming || riding) {
            release = true;
        } else if (!onGround) {
            if (++state.airborneTicks > MAX_AIRBORNE_TICKS) {
                release = true;
            }
        } else {
            state.airborneTicks = 0;
        }
        if (!release && !slidingNow) {
            if (otherParcoolAction) {
                // 跑酷切到了别的动作（墙跑、翻滚……）：滑铲已经结束。
                release = true;
            } else if (!slidingPose) {
                // 已经站起来了（Pose 不再是 SWIMMING）：滑铲确实结束，没必要再演。
                release = true;
            } else if (horizontalSpeedSqr < SLOW_SPEED_SQR) {
                release = ++state.slowTicks >= SLOW_RELEASE_TICKS;
            } else {
                state.slowTicks = 0;
                // 还在往前滑：从最后一次滑铲信号起最多再续 HOLD_TICKS。
                release = now - state.lastSlideTick > HOLD_TICKS;
            }
        }
        if (release) {
            state.locked = false;
        }
        return state.locked;
    }

    /** 本帧该玩家的滑铲是否锁着。只读，不做观测。 */
    public static boolean isLocked(@Nullable Player player) {
        if (player == null) {
            return false;
        }
        State state = STATES.get(player);
        return state != null && state.locked;
    }

    public static boolean isLocked(@Nullable Entity entity) {
        return entity instanceof Player player && isLocked(player);
    }

    /** 主手是否持枪（TaCZ / 卓越前线）。 */
    public static boolean isGunInMainHand(@Nullable LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        return isGun(entity.getMainHandItem());
    }

    public static boolean isGun(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return TACZCompat.isGun(stack) || SWarfareCompat.isGun(stack);
    }

    /**
     * 锁定期间，开火 / 挥动 / 使用这些一次性频道是否必须让位。
     *
     * <p>只对"滑铲 + 持枪"生效：锁的作用是把滑铲姿势留住，没枪时不该改变任何既有行为。
     */
    public static boolean suppressesOneShot(@Nullable LivingEntity entity) {
        return isLocked(entity) && isGunInMainHand(entity);
    }

    /** 锁定期间的持械姿势是否必须固定在静止持枪（而不是瞄准/跑动/开火变体）。 */
    public static boolean wantsStaticHold(@Nullable LivingEntity entity) {
        return suppressesOneShot(entity);
    }

    /** 玩家离开、死亡或模型重载时清掉条目。 */
    public static void clear(@Nullable Player player) {
        if (player != null) {
            STATES.remove(player);
        }
    }

    public static void clearAll() {
        STATES.clear();
    }

    private static void prune(int now) {
        if (STATES.size() <= MAX_TRACKED) {
            return;
        }
        Iterator<Map.Entry<Player, State>> iterator = STATES.entrySet().iterator();
        while (iterator.hasNext()) {
            State state = iterator.next().getValue();
            if (!state.locked || now - state.lastSeenTick > 200) {
                iterator.remove();
            }
        }
    }

    /** 单个玩家的锁定状态；包可见是为了让状态机可以脱离 Player 实例单独测试。 */
    static final class State {
        boolean locked = true;
        int lastSlideTick;
        int lastSeenTick;
        int slowTicks;
        int airborneTicks;
    }
}
