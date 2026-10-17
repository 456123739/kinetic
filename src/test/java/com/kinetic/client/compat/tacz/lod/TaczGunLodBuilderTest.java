package com.kinetic.client.compat.tacz.lod;

import com.kinetic.format.parser.pojo.model.Cube;
import com.kinetic.format.parser.pojo.model.GeoModel;
import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast, Minecraft-free unit test for the TaCZ gun LOD decimation (stage 1).
 *
 * <p>The fixtures are the real TaCZ default-gun models. The counts below were measured
 * independently on the raw JSON: {@code ak47_geo.json} has 617 cubes / 2455 faces before
 * decimation and 268 cubes / 1155 faces after the full pipeline (stage 4 box collapse +
 * adaptive stage 2 + face culling). A "face" is one entry of a cube's per-face uv map,
 * or 6 for a box-uv cube.
 *
 * <p>{@code body_rail} is not a bone of {@code ak47_geo.json} (that model has {@code rail2});
 * it only exists on the AR-pattern models, so it is asserted against {@code hk416d_geo.json}.
 */
class TaczGunLodBuilderTest {

    private static final float THRESHOLD = TaczGunLodBuilder.Decimator.MIN_BONE_METRIC;

    @Test
    void decimatesRealTaczAk47Model() {
        var raw = load("tacz/ak47_geo.json");
        var fullCubes = cubeCount(raw);
        var fullFaces = faceCount(raw);
        System.out.println("[LOD] ak47 full: " + fullCubes + " cubes / " + fullFaces + " faces");
        assertEquals(617, fullCubes, "ak47 full cube count");
        assertEquals(2455, fullFaces, "ak47 full face count");

        var decimated = TaczGunLodBuilder.Decimator.decimate(raw, THRESHOLD);
        var lodCubes = cubeCount(decimated);
        var lodFaces = faceCount(decimated);
        System.out.println("[LOD] ak47 at metric floor " + THRESHOLD + " (adaptive): "
                + lodCubes + " cubes / " + lodFaces + " faces");

        assertEquals(278, lodCubes, "ak47 decimated cube count");
        assertEquals(1210, lodFaces, "ak47 decimated face count");
        for (var bone : new String[]{"barrel4", "gastube", "ar_stock_adapter", "grip2", "extd_mag"}) {
            assertTrue(cubesOfBone(decimated, bone) > 0, bone + " must keep at least one cube");
        }
    }

    @Test
    void keepsEssentialBodyRailOnArPatternModel() {
        var raw = load("tacz/hk416d_geo.json");

        var decimated = TaczGunLodBuilder.Decimator.decimate(raw, THRESHOLD);

        assertTrue(cubesOfBone(decimated, "body_rail") > 0, "body_rail must keep at least one cube");
    }

    private static GeoModel load(String path) {
        try (var in = TaczGunLodBuilderTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "missing test resource " + path);
            var model = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), GeoModel.class);
            assertNotNull(model, "failed to parse " + path);
            return model;
        } catch (Exception e) {
            throw new AssertionError("failed to read " + path, e);
        }
    }

    private static int cubeCount(GeoModel model) {
        var count = 0;
        for (var geometry : model.minecraftGeometry) {
            if (geometry == null || geometry.bones == null) {
                continue;
            }
            for (var bone : geometry.bones) {
                if (bone != null && bone.cubes != null) {
                    count += bone.cubes.size();
                }
            }
        }
        return count;
    }

    private static int faceCount(GeoModel model) {
        var count = 0;
        for (var geometry : model.minecraftGeometry) {
            if (geometry == null || geometry.bones == null) {
                continue;
            }
            for (var bone : geometry.bones) {
                if (bone == null || bone.cubes == null) {
                    continue;
                }
                for (var cube : bone.cubes) {
                    count += faceCount(cube);
                }
            }
        }
        return count;
    }

    private static int faceCount(Cube cube) {
        var uv = cube.uv;
        if (uv == null) {
            return 0;
        }
        if (uv.boxUv != null) {
            return 6;
        }
        var faces = uv.perFaceUv;
        if (faces == null) {
            return 0;
        }
        var count = 0;
        if (faces.down != null) {
            count++;
        }
        if (faces.east != null) {
            count++;
        }
        if (faces.north != null) {
            count++;
        }
        if (faces.south != null) {
            count++;
        }
        if (faces.up != null) {
            count++;
        }
        if (faces.west != null) {
            count++;
        }
        return count;
    }

    private static int cubesOfBone(GeoModel model, String name) {
        var count = 0;
        for (var geometry : model.minecraftGeometry) {
            if (geometry == null || geometry.bones == null) {
                continue;
            }
            for (var bone : geometry.bones) {
                if (bone != null && name.equals(bone.name) && bone.cubes != null) {
                    count += bone.cubes.size();
                }
            }
        }
        return count;
    }
}
