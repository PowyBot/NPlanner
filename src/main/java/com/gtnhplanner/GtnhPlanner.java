package com.gtnhplanner;

import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.network.NetworkCheckHandler;
import cpw.mods.fml.relauncher.Side;

@Mod(
    modid = GtnhPlanner.MODID,
    version = Tags.VERSION,
    name = "GTNH Planner",
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "after:betterquesting")
public class GtnhPlanner {

    public static final String MODID = "gtnhplanner";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "com.gtnhplanner.ClientProxy", serverSide = "com.gtnhplanner.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(final FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    public void init(final FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    public void postInit(final FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(final FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }

    @Mod.EventHandler
    public void serverStarted(final FMLServerStartedEvent event) {
        proxy.serverStarted(event);
    }

    @Mod.EventHandler
    public void serverStopped(final FMLServerStoppedEvent event) {
        proxy.serverStopped(event);
    }

    @Mod.EventHandler
    public void serverStopping(final FMLServerStoppingEvent event) {
        proxy.serverStopping(event);
    }

    /**
     * The planner is needed on both sides (the owner, 2026-10-13): plans are kept by the server and machines are read
     * there. Any version is let in; the planner's own hello ({@code net/Proto}) tells the two apart and says so.
     */
    @NetworkCheckHandler
    public boolean checkRemote(final Map<String, String> mods, final Side remote) {
        return mods.containsKey(MODID);
    }
}
