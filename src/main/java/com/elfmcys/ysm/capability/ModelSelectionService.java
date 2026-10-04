package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.config.ServerConfig;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelIndex;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.snapshot.ServerCatalog;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.storage.ManagedContainer;

import java.util.Optional;

/** Resolves mutable player selection against an explicit immutable server catalog. */
public final class ModelSelectionService {
    private ModelSelectionService() {
    }

    /**
     * Selects the configured default model, falling back to the builtin intrinsic default.
     *
     * <p>The intrinsic default is the wide vanilla player model ({@code misc/2_steve}). The slim
     * variant is chosen by the client through an explicit selection, because only the client can
     * read the vanilla skin model name.
     */
    public static boolean selectDefault(ModelInfoCapability capability,
                                        ServerCatalog snapshot) {
        var configured = snapshot.findPath(ServerConfig.DEFAULT_MODEL_PATH.get()).orElse(null);
        var model = configured != null ? configured : snapshot.defaultModel().orElse(null);
        if (model == null) {
            return false;
        }
        return applyModel(capability, model, ServerConfig.DEFAULT_MODEL_TEXTURE.get());
    }

    /**
     * Selects the builtin player model that matches a vanilla skin model name.
     *
     * <p>{@code "slim"} selects the slim model, anything else the intrinsic default.
     */
    public static boolean selectForSkinType(ModelInfoCapability capability,
                                            ServerCatalog snapshot,
                                            String skinModelName,
                                            String requestedTexture) {
        return selectBuiltinPath(capability, snapshot,
                BuiltinModelIndex.playerPathForSkin(skinModelName), requestedTexture);
    }

    /** Selects the builtin intrinsic default player model. */
    public static boolean selectBuiltinDefault(ModelInfoCapability capability,
                                               ServerCatalog snapshot,
                                               String requestedTexture) {
        return selectBuiltinPath(capability, snapshot,
                BuiltinModelIndex.INTRINSIC_DEFAULT_PATH, requestedTexture);
    }

    /**
     * Applies an explicit model selection, filling in the model's own default texture when the
     * client did not send a usable texture name.
     */
    public static boolean selectExplicit(ModelInfoCapability capability,
                                         ServerCatalog snapshot,
                                         Hash256 modelId,
                                         String requestedTexture) {
        var model = snapshot.find(modelId).orElse(null);
        if (model == null) {
            return false;
        }
        return applyModel(capability, model, requestedTexture);
    }

    private static boolean selectBuiltinPath(ModelInfoCapability capability,
                                             ServerCatalog snapshot,
                                             ModelPath path,
                                             String requestedTexture) {
        var model = snapshot.models().values().stream()
                .filter(handle -> handle.location().rootKind() == CatalogRootKind.BUILTIN
                        && handle.location().path().equals(path))
                .findFirst().orElse(null);
        if (model == null) {
            return false;
        }
        capability.clearIgnoreGrants();
        return applyModel(capability, model, requestedTexture);
    }

    private static boolean applyModel(ModelInfoCapability capability,
                                      ManagedContainer model,
                                      String requestedTexture) {
        var textures = model.view().getPlayer().getTextureNames();
        var configured = model.view().getMetadata().getSettings().defaultTexture().orElse("");
        var texture = requestedTexture != null && textures.contains(requestedTexture)
                ? requestedTexture
                : textures.contains(configured) ? configured
                : textures.stream().sorted().findFirst().orElse("");
        capability.setModelAndTexture(model.representation().modelId(), texture);
        return true;
    }

    public static Optional<ManagedContainer> resolve(ModelInfoCapability capability,
                                                     ServerCatalog snapshot) {
        if (capability.getModelId() == null && !selectDefault(capability, snapshot)) {
            return Optional.empty();
        }
        var model = snapshot.find(capability.getModelId()).orElse(null);
        if (model == null || !model.view().getPlayer().getTextureNames()
                .contains(capability.getSelectTexture())) {
            capability.clearIgnoreGrants();
            if (!selectDefault(capability, snapshot)) {
                capability.setModelAndTexture(null, "");
                return Optional.empty();
            }
            model = snapshot.find(capability.getModelId()).orElse(null);
        }
        if (model != null && model.location().rootKind() == CatalogRootKind.BUILTIN
                && model.location().path().equals(BuiltinModelIndex.INTRINSIC_DEFAULT_PATH)) {
            capability.clearIgnoreGrants();
        }
        return Optional.ofNullable(model);
    }

    public static String displayId(ModelInfoCapability capability,
                                   ServerCatalog snapshot) {
        var hash = capability.getModelId();
        return hash == null ? BuiltinModelIndex.INTRINSIC_DEFAULT_PATH.value() : snapshot.find(hash)
                .map(model -> model.location().path().value())
                .orElse(hash.toString());
    }
}
