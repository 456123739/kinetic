package com.kinetic.client.compat.touhoulittlemaid.client.event;

/*
 * 本文件出现的 Ysm / YSM 拼写属于 TouhouLittleMaid 的公开 API
 * （com.github.tartaricacid.touhoulittlemaid.* 下的类型、字段与方法），
 * 不是本模组自己的命名。引用第三方 API 必须逐字保留其拼写，改成 Kinetic
 * 会导致 NoClassDefFoundError / NoSuchFieldError / NoSuchMethodError。
 */

import com.github.tartaricacid.touhoulittlemaid.compat.ysm.event.OpenYsmMaidScreenEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 女仆模型选择界面的打开入口（Touhou Little Maid 兼容）。
 *
 * <p>产品要求禁止用户通过 UI 切换模型，{@code MaidModelScreen}（{@code PlayerModelScreen}
 * 的子类）因此不再由本事件打开，本事件也不再在
 * {@code TlmClientCompatInner#registerEvent} 注册。界面类保留；恢复入口只需重新注册本类并
 * 恢复打开逻辑。
 */
@OnlyIn(Dist.CLIENT)
public final class KineticMaidScreenEvent {
    @SuppressWarnings("unused")
    public void onOpenKineticMaidScreen(OpenYsmMaidScreenEvent event) {
        // 入口已禁用：不再打开 MaidModelScreen。
    }
}
