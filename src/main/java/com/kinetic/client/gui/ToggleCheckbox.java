package com.kinetic.client.gui;

import java.util.function.Consumer;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.network.chat.Component;

/**
 * 勾选框 + 应用配置的回调。
 *
 * <p>以前这两处是就地写匿名子类覆写 {@code onPress()}。那种写法在较老的
 * JDK 17 上会报 {@code variable o is already defined in constructor <anonymous ...>}
 * （javac 对匿名类捕获变量与合成构造器参数的命名缺陷），表现就是"本机能编、CI 编不过"。
 * 换成具名子类，语义不变，任何 17 补丁版本都能编。
 */
public final class ToggleCheckbox extends Checkbox {
    private final Consumer<ToggleCheckbox> onPress;

    public ToggleCheckbox(int x, int y, int width, int height, Component message,
                          boolean selected, Consumer<ToggleCheckbox> onPress) {
        super(x, y, width, height, message, selected, true);
        this.onPress = onPress;
    }

    @Override
    public void onPress() {
        super.onPress();
        onPress.accept(this);
    }

    /** 当前勾选状态（{@code Checkbox} 的字段是私有的，这里给回调一个入口）。 */
    public boolean value() {
        return selected();
    }
}
