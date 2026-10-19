package com.kinetic.client.event;

import com.kinetic.Kinetic;
import com.kinetic.client.animation.AnimationRegister;
import com.kinetic.client.compat.ARCompat;
import com.kinetic.client.compat.CosmeticArmorCompat;
import com.kinetic.client.compat.ElytraSlotCompat;
import com.kinetic.client.compat.FirstPersonCompat;
import com.kinetic.client.compat.ImmersiveAircraftCompat;
import com.kinetic.client.compat.IrisCompat;
import com.kinetic.client.compat.OptifineCompat;
import com.kinetic.client.compat.PlayerAnimatorCompat;
import com.kinetic.client.compat.SimplePlaneCompat;
import com.kinetic.client.compat.backpack.sophisticated.SophisticatedCompat;
import com.kinetic.client.compat.bettercombat.BetterCombatCompat;
import com.kinetic.client.compat.carryon.CarryOnCompat;
import com.kinetic.client.compat.create.CreateCompat;
import com.kinetic.client.compat.curios.CuriosCompat;
import com.kinetic.client.compat.immersivemelodies.ImmersiveMelodiesCompat;
import com.kinetic.client.compat.ironsspellbooks.IronsSpellBooksCompat;
import com.kinetic.client.compat.parcool.ParCoolCompat;
import com.kinetic.client.compat.quickskin.QuickSkinCompat;
import com.kinetic.client.compat.ragdollified.RagdollifiedCompat;
import com.kinetic.client.compat.realcamera.RealCameraCompat;
import com.kinetic.client.compat.simplehat.SimpleHatsCompat;
import com.kinetic.client.compat.slashblade.SlashBladeCompat;
import com.kinetic.client.compat.swarfare.SWarfareCompat;
import com.kinetic.client.compat.swem.SwemCompat;
import com.kinetic.client.compat.tacz.TACZCompat;
import com.kinetic.client.compat.touhoulittlemaid.client.TlmClientCompat;
import com.kinetic.client.gui.overlay.DebugAnimationScreen;
import com.kinetic.client.gui.overlay.ExtraPlayerScreen;
import com.kinetic.client.gui.overlay.GunLodPrewarmScreen;
import com.kinetic.client.gui.overlay.LoadingStateScreen;
import com.kinetic.client.input.DebugAnimationKey;
import com.kinetic.client.input.ExtraPlayerConfigKey;
import com.kinetic.client.model.locator.FirstPersonLocator;
import com.kinetic.client.model.locator.PlayerLocator;
import com.kinetic.model.service.ClientModelService;
import com.kinetic.client.model.locator.ProjectileLocator;
import com.kinetic.client.model.locator.VehicleLocator;
import com.kinetic.config.ClientConfig;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.ModLoadingStage;
import net.minecraftforge.fml.ModLoadingWarning;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.LoadingModList;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Optional;

import static net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.DEBUG_TEXT;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ClientSetupEvent {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        if (!Kinetic.isAvailable()) {
            return;
        }

        AnimationRegister.registerAnimationState();

        event.enqueueWork(() -> {
            CuriosCompat.init();
            FirstPersonCompat.init();
            RealCameraCompat.init();
            PlayerAnimatorCompat.init();
            BetterCombatCompat.init();
            IrisCompat.init();
            ARCompat.init();
            OptifineCompat.init();
            CosmeticArmorCompat.init();
            ElytraSlotCompat.init();
            TACZCompat.init();
            SWarfareCompat.init();
            TlmClientCompat.init();
            CarryOnCompat.init();
            ParCoolCompat.init();
            RagdollifiedCompat.initRuntime();
            // Quick Skin 存在时订阅其外观更新事件；缺失时是空操作。
            QuickSkinCompat.init();
            SlashBladeCompat.init();
            SwemCompat.init();
            CreateCompat.init();
            SophisticatedCompat.init();
            SimpleHatsCompat.init();
            ImmersiveMelodiesCompat.init();
            IronsSpellBooksCompat.init();
            SimplePlaneCompat.init();
            ImmersiveAircraftCompat.init();

            checkCompatibility(ParCoolCompat.getCompatibilityWarning());
            checkCompatibility(SophisticatedCompat.getCompatibilityWarning());
            if (ClientConfig.DISABLE_SELF_MODEL.get() &&
                    ClientConfig.DISABLE_OTHER_MODEL.get() &&
                    ClientConfig.DISABLE_SELF_HANDS.get()) {
                informIncompatible("epicfight", "Epic Fight");
            }

            // Model render target data is now owned by the Java model service.
            PlayerLocator.init();
            FirstPersonLocator.init();
            ProjectileLocator.init();
            VehicleLocator.init();

            ClientModelService.start();
        });
    }

    private static void checkCompatibility(Optional<Pair<String, String>> infoHolder) {
        infoHolder.ifPresent(info -> {
            ModLoader.get().addWarning(new ModLoadingWarning(
                    LoadingModList.get().getModFileById(Kinetic.MOD_ID).getMods().get(0), ModLoadingStage.SIDED_SETUP,
                    "error.kinetic.incompatible_mod_version", info.getKey(), info.getValue()));
        });
    }

    private static void informIncompatible(String modId, String modName) {
        if (LoadingModList.get().getModFileById(modId) != null) {
            ModLoader.get().addWarning(new ModLoadingWarning(
                    LoadingModList.get().getModFileById(Kinetic.MOD_ID).getMods().get(0), ModLoadingStage.SIDED_SETUP,
                    "error.kinetic.incompatible_mod", modName));
        }
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        // 模型选择界面（Alt+Y）与动作轮盘（Z / Alt+L）的入口已按产品要求禁用：
        // Screen 类保留，但不再注册任何打开它们的快捷键。
        if (!Kinetic.isAvailable()) {
            return;
        }

        event.register(DebugAnimationKey.DEBUG_ANIMATION_KEY);
        event.register(ExtraPlayerConfigKey.EXTRA_PLAYER_RENDER_KEY);
    }

    @SubscribeEvent
    public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
        if (!Kinetic.isAvailable()) {
            return;
        }
        event.registerAbove(DEBUG_TEXT.id(), "kinetic_debug_info", DebugAnimationScreen.getGuiOverlay());
        event.registerAbove(DEBUG_TEXT.id(), "kinetic_extra_player", new ExtraPlayerScreen());
        event.registerAbove(DEBUG_TEXT.id(), "kinetic_loading_state", new LoadingStateScreen());
        event.registerAbove(DEBUG_TEXT.id(), "kinetic_gun_lod_prewarm", new GunLodPrewarmScreen());
    }

    /**
     * 资源重载后丢弃已烘焙的枪械 LOD：枪包换了模型/贴图，缓存里的几何必须重算。
     * 未安装 TaCZ 时这是空操作。
     */
    @SubscribeEvent
    public static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        if (!Kinetic.isAvailable()) {
            return;
        }
        event.registerReloadListener(
                (ResourceManagerReloadListener) resourceManager -> TACZCompat.clearGunLodCache());
    }

}
