package com.kinetic.format.schema.baked.model;

import com.kinetic.buffer.ArrayBuffer;
import com.kinetic.buffer.UniBuffer;
import com.kinetic.format.schema.file.AssetFileWriter;
import com.kinetic.natives.Blake3;
import com.kinetic.proto.mixel.asset.model.data.GeoModel;
import java.io.IOException;

public class BakedModelWriter extends AssetFileWriter {
    public BakedModelWriter() {
        setSchemaId(BakedModelConstant.SCHEMA_ID);
        setProperty(BakedModelConstant.PROP_VERSION,
                BakedModelConstant.CURRENT_VERSION.toString());
    }

    public void setData(byte[] bakeHash,
                        GeoModel sourceModel,
                        UniBuffer bakedData) throws IOException {
        if (bakeHash.length != Blake3.HASH_SIZE) {
            throw new IllegalArgumentException("Invalid bake hash");
        }
        addProtoChunk(BakedModelConstant.MANIFEST_CHUNK_NAME, sourceModel, 0);
        addRawChunk(BakedModelConstant.BAKE_HASH_CHUNK_NAME,
                ArrayBuffer.borrow(bakeHash), 0);
        addRawChunk(BakedModelConstant.MODEL_CHUNK_NAME, bakedData, 9);
    }

}
