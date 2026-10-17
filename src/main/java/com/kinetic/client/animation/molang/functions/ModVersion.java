package com.kinetic.client.animation.molang.functions;

import com.kinetic.molang.runtime.ExecutionContext;
import com.kinetic.molang.runtime.Function;
import net.minecraftforge.common.util.MavenVersionStringHelper;
import net.minecraftforge.fml.ModList;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ModVersion implements Function {
    @Nullable
    @Override
    public Object evaluate(@NotNull ExecutionContext<?> context, @NotNull ArgumentCollection arguments) {
        String modId = arguments.getAsString(context, 0);
        if (modId == null) {
            return null;
        }

        return ModList.get().getModContainerById(modId)
                .map(modContainer -> MavenVersionStringHelper.artifactVersionToString((modContainer.getModInfo().getVersion())))
                .orElse(null);
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
