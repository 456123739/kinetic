package com.kinetic.format.schema.baked.model;

import com.kinetic.buffer.ArrayBuffer;
import com.kinetic.buffer.BufferType;
import com.kinetic.format.container.AssetContainerReader;
import com.kinetic.format.container.InlineChunkReader;
import com.kinetic.model.domain.Hash256;
import com.kinetic.natives.Blake3;
import com.kinetic.proto.mixel.asset.model.data.Bone;
import com.kinetic.proto.mixel.asset.model.data.GeoModel;
import com.kinetic.proto.mixel.asset.model.data.GeoProperties;
import com.kinetic.testutil.ProtobufJavaOracle;
import com.kinetic.util.ProtoBytes;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BakedModelWriterTest {
    @TempDir
    Path temp;

    @Test
    void writesWithoutAnExplicitSummary() throws Exception {
        var path = temp.resolve("model.geo.kinetic-cache");
        var hash = new byte[Blake3.HASH_SIZE];
        var model = GeoModel.newBuilder()
                .setProperties(properties())
                .setCubes(ByteBuffer.wrap(new byte[]{4, 5}));
        model.addBones(Bone.newBuilder().setName("root").build());
        try (var baked = ArrayBuffer.move(new byte[]{1, 2, 3});
             var writer = new BakedModelWriter();
             var output = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE)) {
            writer.setData(hash, model.build(), baked);
            writer.write(output);
        }

        try (var input = FileChannel.open(path, StandardOpenOption.READ)) {
            var view = new BakedModelView(input);
            assertEquals(new Hash256(hash), view.bakeHash());
            assertArrayEquals(new byte[]{4, 5}, ProtoBytes.copy(view.model().cubes()));
            var container = AssetContainerReader.read(input);
            var chunk = container.getChunkInfo(BakedModelConstant.MANIFEST_CHUNK_NAME);
            try (var bytes = InlineChunkReader.readPayload(input, chunk, BufferType.ARRAY)) {
                var protobufIndex = ProtobufJavaOracle.parse(
                        "mixel.asset.model.data.GeoModel",
                        ProtoBytes.copy(bytes.nio()));
                assertEquals(1, protobufIndex.getRepeatedFieldCount(
                        ProtobufJavaOracle.field(
                                protobufIndex.getDescriptorForType(), "bones")));
            }
            try (var baked = view.readModelData(input)) {
                assertEquals(3, baked.size());
            }
        }
    }

    private static GeoProperties properties() {
        return GeoProperties.newBuilder()
                .setTextureHeight(0)
                .setTextureWidth(0)
                .build();
    }
}
