package com.kinetic.format.parser;


import com.kinetic.proto.mixel.asset.model.data.AnimationFile;
import com.kinetic.proto.mixel.manifest.asset.RenderTargetKind;
import java.io.IOException;
import java.util.Collection;

/** Immutable conversion rule for removing animations supplied by the builtin contract. */
@FunctionalInterface
public interface DefaultAnimationFilter {
    AnimationFile apply(
            RenderTargetKind kind,
            Collection<String> matches,
            String animationSet,
            AnimationFile source) throws IOException;

    static DefaultAnimationFilter keepAll() {
        return (kind, matches, animationSet, source) -> source;
    }
}
