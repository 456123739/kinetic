package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.AssetPaths;
import net.minecraftforge.fml.ModList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ModelCatalogSources {
    private ModelCatalogSources() {
    }

    public static List<ModelCatalogSource> sources() {
        var result = new ArrayList<ModelCatalogSource>();
        result.add(builtin());
        result.addAll(reloadableSources());
        return List.copyOf(result);
    }

    public static ModelCatalogSource builtin() {
        return new ModelCatalogSource(CatalogRootKind.BUILTIN,
                AssetPaths.builtinModelsResource(), false);
    }

    public static Path builtinIndex() {
        return ModList.get().getModFileById(YesSteveModel.MOD_ID).getFile()
                .findResource("assets", YesSteveModel.MOD_ID, "builtin-index.json");
    }

    /**
     * 可热加载的模型来源。
     *
     * <p>模型导入入口已整体禁用：不再扫描 {@code custom/} 与 {@code auth/} 目录，因此玩家
     * 无法通过放置文件导入自定义模型。唯一可用的模型来源是模组自带的 builtin 目录
     * （当前只含 {@code misc/2_steve} 与 {@code misc/1_alex}）。
     *
     * <p>返回空列表同时会让 {@link ModelDirectoryWatcher} 不注册任何非 builtin 根目录，
     * 因此也不会再有目录监听触发的重扫描。
     */
    public static List<ModelCatalogSource> reloadableSources() {
        return List.of();
    }

    public static Path customPath() {
        return AssetPaths.customModelsRoot();
    }
}
