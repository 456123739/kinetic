package com.kinetic.capability;

import com.kinetic.model.catalog.snapshot.ServerCatalog;
import com.kinetic.model.catalog.source.CatalogRootKind;
import com.kinetic.network.forge.PlayerStateHandler;
import com.kinetic.network.protocol.ModelReferenceCodec;
import com.kinetic.proto.network.ModelSelectionState;
import com.kinetic.proto.network.MolangVariable;
import com.kinetic.proto.network.PlayerStateUpdate;
import com.kinetic.proto.network.RoamingState;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import com.kinetic.model.catalog.builtin.BuiltinModelIndex;

public final class ModelInfoSyncAssembler {
    private ModelInfoSyncAssembler() {
    }

    public static Optional<PlayerStateUpdate> build(ServerPlayer player,
                                                                  ModelInfoCapability capability,
                                                                  ServerCatalog snapshot) {
        return ModelSelectionService.resolve(capability, snapshot).map(model -> {
            var hash = model.representation().modelId();
            var builtinDefault = model.location().rootKind() == CatalogRootKind.BUILTIN
                    && model.location().path().equals(BuiltinModelIndex.INTRINSIC_DEFAULT_PATH) ? hash : null;
            var reference = ModelReferenceCodec.create(hash, builtinDefault);
            var update = PlayerStateHandler.newFull(player)
                    .setModel(ModelSelectionState.newBuilder()
                            .setModel(reference)
                            .setTextureId(capability.getSelectTexture())
                            .setDisabled(capability.isDisabled())
                            .build());
            capability.getPropertiesTracker().populateFull(update, player);
            var variables = capability.roamingVariables().variables(hash);
            var roaming = RoamingState.newBuilder().setModelKey(hash.roamingHash());
            variables.object2FloatEntrySet().fastForEach(entry -> roaming.addVariables(
                    MolangVariable.newBuilder()
                            .setName(entry.getKey())
                            .setValue(entry.getFloatValue())
                            .build()));
            update.setRoaming(roaming.build());
            return update.build();
        });
    }
}
