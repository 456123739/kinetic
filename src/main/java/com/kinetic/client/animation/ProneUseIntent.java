package com.kinetic.client.animation;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地玩家的"正在按住使用键"输入。
 *
 * <p>为什么需要它：{@code PronePredicate} 原先只用 {@code player.isUsingItem()} 判断"在打药 / 在掐雷"。
 * 但趴在（{@code Pose.SWIMMING}）状态下按右键时，这条状态并不总会成立——同样的操作站着做就会
 * 立刻进入使用状态，趴着做却拿不到，于是"趴下直接按右键"永远识别不出动作，只有先站着开始、
 * 再趴下才碰巧有效。把判定挂在 {@code isUsingItem} 上，就等于把动画绑死在一条并不总是成立的
 * 下游状态上。
 *
 * <p>这里改为观察**玩家自己的输入**：使用键被按下、按着的仍是同一件主手物品、且没有超时。
 * 不伪造任何玩法状态——不写 use 计时、不消耗物品、不发包，只回答"玩家现在是否按着使用键"。
 * 真正的投掷/消耗仍完全由原模组决定；松开按键或主手换物品都会立刻结束。
 */
public final class ProneUseIntent {
    /**
     * 一次按压最多承认多久（tick）。
     *
     * <p>掐雷蓄力一般不到 2 秒；再长就不是"按着在用"而是卡住的按键了，必须自然过期，
     * 不能让侧躺姿势永远挂在身上。
     */
    static final int MAX_HOLD_TICKS = 60;

    private static final Map<Player, Entry> ENTRIES = new ConcurrentHashMap<>();

    /** 本地玩家这一 tick 是否按着使用键。由客户端 tick 事件更新，避免在求值线程里碰 Minecraft。 */
    private static volatile boolean useKeyDown;

    private ProneUseIntent() {
    }

    private record Entry(Item item, int startTick) {
    }

    /** 使用键被按下时记录主手物品。只对本地玩家生效：输入只属于自己。 */
    public static void notePress(@Nullable Player player, ItemStack stack) {
        if (player == null || stack.isEmpty()) {
            return;
        }
        if (Minecraft.getInstance().player != player) {
            return;
        }
        // 只在趴着时记录：站着时 isUsingItem 本身可靠，不必也不该扩大这条回退的适用范围。
        // 这样"趴着右键方块"这类误触最多只是短暂的蓄力姿势，不会影响站姿行为。
        if (!ProneAction.isProne(player)) {
            return;
        }
        ENTRIES.put(player, new Entry(stack.getItem(), player.tickCount));
    }

    /** 客户端每 tick 更新按键状态。 */
    public static void updateKeyState(boolean down) {
        useKeyDown = down;
        if (!down) {
            // 松手即结束：下一次按压必须重新记录起始 tick。
            ENTRIES.clear();
        }
    }

    /**
     * 玩家此刻是否"按着使用键、且主手还是当初那件物品"。
     *
     * <p>只对本地玩家可能为真；远端玩家的输入不可见，永远返回 {@code false}。
     */
    public static boolean isHolding(@Nullable Player player) {
        if (player == null) {
            return false;
        }
        Entry entry = ENTRIES.get(player);
        if (entry == null) {
            return false;
        }
        ItemStack current = player.getMainHandItem();
        boolean sameItem = !current.isEmpty() && current.getItem() == entry.item();
        boolean holding = stillHolding(useKeyDown, sameItem, player.tickCount, entry.startTick());
        if (!holding) {
            ENTRIES.remove(player);
        }
        return holding;
    }

    /**
     * 纯判定：按键仍按着、主手还是同一件物品、且没有超过最大按压时长。
     *
     * <p>只用布尔与整数，避免单测去碰 Minecraft 的注册表。
     *
     * @param keyDown    使用键是否还按着
     * @param sameItem   主手是否仍是按下时那件物品
     * @param now        当前 tick
     * @param startTick  按下时的 tick
     */
    static boolean stillHolding(boolean keyDown, boolean sameItem, int now, int startTick) {
        if (!keyDown || !sameItem) {
            return false;
        }
        if (now < startTick) {
            // 换维度/重生会把 tickCount 归零，旧记录一律作废。
            return false;
        }
        return now - startTick <= MAX_HOLD_TICKS;
    }

    public static void clear(@Nullable Player player) {
        if (player != null) {
            ENTRIES.remove(player);
        }
    }

    public static void clearAll() {
        ENTRIES.clear();
        useKeyDown = false;
    }
}
