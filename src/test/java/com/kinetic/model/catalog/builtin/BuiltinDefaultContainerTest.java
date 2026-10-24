package com.kinetic.model.catalog.builtin;

import com.kinetic.model.catalog.source.CatalogModelLocation;
import com.kinetic.model.catalog.source.CatalogRootKind;
import com.kinetic.model.domain.ModelPath;
import com.kinetic.model.storage.ManagedContainer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 构建期生成的默认模型容器必须能被直接打开，且与内置索引里的哈希一致。
 *
 * <p>运行期优先加载这个容器（约 280 KB），不再重新解析 6 MB 动画 JSON；如果容器缺失或与
 * 索引不一致，运行期会回退到解析原始资源（见 {@code BuiltinModelCatalog}）。这个测试保证
 * 发布包里那条快路径真的可用，而不是每次都悄悄走回退。
 */
class BuiltinDefaultContainerTest {
    private static final ModelPath DEFAULT_PATH = new ModelPath("misc/2_steve");

    @Test
    void prebuiltContainerMatchesTheBuiltinIndex() throws Exception {
        var container = read("/assets/kinetic/builtin-default.kinetic");
        assertNotNull(container, "构建期容器没有被打进资源；运行期会退回解析 6 MB JSON");
        assertTrue(container.length > 0, "构建期容器为空");

        var index = BuiltinModelIndex.read(new StringReader(new String(
                read("/assets/kinetic/builtin-index.json"), StandardCharsets.UTF_8)));
        var expected = index.require(DEFAULT_PATH);

        var location = new CatalogModelLocation(CatalogRootKind.BUILTIN, DEFAULT_PATH);
        var handle = ManagedContainer.openResidentDefault(container, location);
        try {
            assertEquals(expected, handle.representation().modelId(),
                    "预构建容器的模型哈希必须与内置索引一致");
        } finally {
            handle.representation().close();
        }
    }

    private static byte[] read(String resource) throws Exception {
        try (InputStream in = BuiltinDefaultContainerTest.class.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        }
    }
}
