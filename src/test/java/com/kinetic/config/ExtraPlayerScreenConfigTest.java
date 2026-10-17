package com.kinetic.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HUD 模型预览（extra player renderer）默认关闭。
 *
 * <p>回归点：它每帧多付一次模型求值 + 原生 extract + 绘制，而且 HUD 上下文可变、绕过了动画
 * 速率限制。默认值改成 true 还不够——旧配置文件里写死的 {@code DisablePlayerRender = false}
 * 会继续生效，所以配置键本身也改了名，旧键不再被读取。
 */
class ExtraPlayerScreenConfigTest {
    @Test
    void hudPreviewIsDisabledByDefaultUnderARenamedKey() {
        var builder = new ForgeConfigSpec.Builder();
        ExtraPlayerScreenConfig.init(builder);

        assertEquals(List.of("extra_player_render", "DisableExtraPlayerRender"),
                ExtraPlayerScreenConfig.DISABLE_PLAYER_RENDER.getPath(),
                "配置键改名后，旧配置文件里的 DisablePlayerRender = false 才不会被继续采用");
        assertEquals(Boolean.TRUE, ExtraPlayerScreenConfig.DISABLE_PLAYER_RENDER.getDefault());
    }
}
