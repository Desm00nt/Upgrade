package io.github.desm00nt.kustik;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(KustikMod.MOD_ID)
public final class KustikMod {
    public static final String MOD_ID = "kustik";
    static final Logger LOGGER = LogUtils.getLogger();
    private BushConversationService service;

    public KustikMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, KustikConfig.SPEC, "kustik-common.toml");
        // No client classes or custom packets: works on dedicated and integrated servers.
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        BushCommands.register(event.getDispatcher(), () -> service);
    }

    @SubscribeEvent
    public void started(ServerStartedEvent event) {
        try {
            service = new BushConversationService(event.getServer(), KustikConfig.snapshot());
            LOGGER.info("Kustik ready. Atria credentials configured: {}", service.configured());
            if (!service.configured()) {
                LOGGER.warn("Set a key with /kustikadmin key, or use ATRIA_API_KEY / config/kustik-common.toml and restart.");
            }
        } catch (RuntimeException e) {
            // Exception messages from config/HTTP libraries might contain credentials.
            LOGGER.error("Kustik could not start ({}). Check config/kustik-common.toml; values are not logged.",
                    e.getClass().getSimpleName());
        }
    }

    @SubscribeEvent
    public void stopping(ServerStoppingEvent event) {
        if (service != null) {
            service.close();
            service = null;
        }
    }

    @SubscribeEvent
    public void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (service != null && event.getEntity() instanceof ServerPlayer player) {
            service.playerLeft(player.getUUID());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void broken(BlockEvent.BreakEvent event) {
        if (service != null && event.getLevel() instanceof ServerLevel level
                && event.getState().is(Blocks.DEAD_BUSH)) {
            service.bushBroken(level.dimension().location().toString(), event.getPos().asLong());
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (service != null && event.phase == TickEvent.Phase.END) {
            service.tick();
        }
    }
}
