package com.debugbridge.fabric262snapshot3;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DebugBridgeMod implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("DebugBridge");

    @Override
    public void onInitializeClient() {
        LOG.info("[DebugBridge] Initializing 26.2 snapshot shell module");
    }
}
