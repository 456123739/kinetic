package com.kinetic.model.catalog.content;

import com.kinetic.format.schema.model.ModelFileConstant;
import com.kinetic.model.storage.PreviewStore;
import com.kinetic.proto.mixel.manifest.info.PreviewSource;

import java.io.IOException;
import java.util.Objects;

/** Source policy applied only when a current container is supplied as a finished artifact. */
public final class DirectContainerAdmission {
    private DirectContainerAdmission() {
    }

    public static void requireEmbeddedPreview(ModelContent content) throws IOException {
        Objects.requireNonNull(content, "content");
        var view = content.modelFile();
        if (view.getThumbnailPreviewSource() == PreviewSource.PREVIEW_SOURCE_UNSPECIFIED) {
            throw new IOException("Direct model container has no embedded preview");
        }
        var chunk = view.getFileView().getAssetView().getChunkInfo(
                ModelFileConstant.THUMB_BUTTON_CHUNK_NAME);
        if (chunk == null) {
            throw new IOException("Direct model container preview is unavailable");
        }
        var source = view.thumbnailSource(content.chunks());
        if (source == null) {
            throw new IOException("Direct model container preview is unavailable");
        }
        PreviewStore.read(source, chunk.size());
    }
}
