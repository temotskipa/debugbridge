package com.debugbridge.fabric12111;

import com.debugbridge.core.BridgeConfig;
import com.debugbridge.core.lua.ThreadDispatcher;
import com.debugbridge.core.mapping.MappingCache;
import com.debugbridge.core.mapping.MappingDownloader;
import com.debugbridge.core.mapping.ProGuardParser;
import com.debugbridge.core.mapping.ParsedMappings;
import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.screenshot.ScreenshotProvider;
import com.debugbridge.core.server.BridgeServer;
import com.debugbridge.core.snapshot.GameStateProvider;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class DebugBridgeMod implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("DebugBridge");
    private static final String MC_VERSION = "1.21.11";

    private static DebugBridgeMod INSTANCE;

    private BridgeConfig config;
    private BridgeServer server;
    private final AtomicBoolean warningShown = new AtomicBoolean(false);
    private final AtomicBoolean serverStarted = new AtomicBoolean(false);
    private boolean needsWarning = false;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        LOG.info("[DebugBridge] Initializing for Minecraft {}...", MC_VERSION);

        Path configDir = FabricLoader.getInstance().getConfigDir();
        config = BridgeConfig.load(configDir);

        if (config.developerModeAccepted) {
            // Already accepted, start server immediately
            startServer();
        } else {
            // Need to show warning screen - will be triggered by mixin tick
            LOG.info("[DebugBridge] Developer mode not yet accepted, will show warning screen");
            needsWarning = true;
        }
    }

    /**
     * Called by MinecraftClientMixin on each client tick.
     */
    public static void onClientTick(Minecraft mc) {
        if (INSTANCE != null) {
            INSTANCE.handleTick(mc);
        }
    }

    private void handleTick(Minecraft mc) {
        if (!needsWarning) return;

        // Only show once, and only when no screen is open (game is ready)
        if (!warningShown.get() && mc.screen == null && mc.getOverlay() == null) {
            warningShown.set(true);
            mc.setScreen(new DeveloperWarningScreen(config, accepted -> {
                mc.setScreen(null);
                if (accepted) {
                    LOG.info("[DebugBridge] Developer mode accepted by user");
                    startServer();
                } else {
                    LOG.info("[DebugBridge] Developer mode declined, mod disabled");
                }
                needsWarning = false;
            }));
        }
    }

    private void startServer() {
        if (serverStarted.getAndSet(true)) {
            return; // Already started
        }

        MappingResolver resolver = buildResolver();

        Minecraft mc = Minecraft.getInstance();
        ThreadDispatcher dispatcher = new ThreadDispatcher() {
            @Override
            public <T> T executeOnGameThread(Callable<T> task, long timeout) throws Exception {
                CompletableFuture<T> future = new CompletableFuture<>();
                mc.execute(() -> {
                    try {
                        future.complete(task.call());
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });
                return future.get(timeout, TimeUnit.MILLISECONDS);
            }
        };

        GameStateProvider stateProvider = new Minecraft12111StateProvider();
        ScreenshotProvider screenshotProvider = new Minecraft12111ScreenshotProvider();

        server = new BridgeServer(config.port, resolver, dispatcher,
            stateProvider, screenshotProvider);
        server.setGameDir(FabricLoader.getInstance().getGameDir());
        server.start();
        LOG.info("[DebugBridge] Server started on port {}", config.port);
    }

    private MappingResolver buildResolver() {
        try {
            MappingCache cache = new MappingCache();
            String proguardContent;

            if (cache.has(MC_VERSION)) {
                LOG.info("[DebugBridge] Loading cached {} mappings...", MC_VERSION);
                proguardContent = cache.load(MC_VERSION);
            } else {
                LOG.info("[DebugBridge] Downloading {} mappings from Mojang...", MC_VERSION);
                MappingDownloader downloader = new MappingDownloader();
                proguardContent = downloader.download(MC_VERSION);
                cache.save(MC_VERSION, proguardContent);
                LOG.info("[DebugBridge] Mappings downloaded and cached.");
            }

            ParsedMappings mappings = ProGuardParser.parse(proguardContent);
            LOG.info("[DebugBridge] Parsed {} classes from mappings.", mappings.classes.size());
            return new FabricMojangResolver(MC_VERSION, mappings);
        } catch (Exception e) {
            LOG.error("[DebugBridge] Failed to load mappings, falling back to passthrough", e);
            return new com.debugbridge.core.mapping.PassthroughResolver(MC_VERSION);
        }
    }

    /**
     * Captures game state for the snapshot endpoint.
     */
    private static class Minecraft12111StateProvider implements GameStateProvider {
        @Override
        public JsonObject captureSnapshot() {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            JsonObject snap = new JsonObject();

            if (player != null) {
                JsonObject playerObj = new JsonObject();
                playerObj.addProperty("name", player.getName().getString());
                playerObj.addProperty("x", player.getX());
                playerObj.addProperty("y", player.getY());
                playerObj.addProperty("z", player.getZ());
                playerObj.addProperty("health", player.getHealth());
                playerObj.addProperty("maxHealth", player.getMaxHealth());
                playerObj.addProperty("food", player.getFoodData().getFoodLevel());
                playerObj.addProperty("saturation", player.getFoodData().getSaturationLevel());
                playerObj.addProperty("dimension",
                    player.level().dimension().identifier().toString());
                playerObj.addProperty("biome", "");
                snap.add("player", playerObj);
            } else {
                snap.addProperty("player", "not in world");
            }

            if (mc.level != null) {
                JsonObject world = new JsonObject();
                world.addProperty("dayTime", mc.level.getDayTime());
                world.addProperty("isRaining", mc.level.isRaining());
                world.addProperty("isThundering", mc.level.isThundering());
                snap.add("world", world);
            }

            snap.addProperty("fps", mc.getFps());
            snap.addProperty("version", MC_VERSION);

            return snap;
        }
    }
}
