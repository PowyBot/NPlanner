package com.gtnhplanner;

import com.gtnhplanner.annotation.VersionedInjector;
import com.gtnhplanner.client.ImportCommand;
import com.gtnhplanner.config.ConfigMain;
import com.gtnhplanner.net.PlannerNetwork;
import com.gtnhplanner.quest.PlannerQuests;
import com.gtnhplanner.server.PlannerCommand;
import com.gtnhplanner.server.ServerPlanner;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

public class CommonProxy {

    public void preInit(final FMLPreInitializationEvent event) {
        VersionedInjector.injectAll(event.getAsmData());

        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());
        ConfigMain.registerConfigs();

        // The planner's channel and its server side, on both sides: a single-player world has a server too.
        PlannerNetwork.init();
        ServerPlanner.init();
    }

    public void init(final FMLInitializationEvent event) {
        PlannerQuests.init();
    }

    public void postInit(final FMLPostInitializationEvent event) {

    }

    public void serverStarting(final FMLServerStartingEvent event) {
        ServerPlanner.INSTANCE.start(event.getServer());
        event.registerServerCommand(new PlannerCommand());
        if (!event.getServer()
            .isDedicatedServer()) {
            event.registerServerCommand(new ImportCommand());
        }
    }

    /** The world's quests are loaded by now: the questline goes into the book. */
    public void serverStarted(final FMLServerStartedEvent event) {
        PlannerQuests.backend()
            .serverStarted();
    }

    public void serverStopping(final FMLServerStoppingEvent event) {
        ServerPlanner.INSTANCE.stop();
    }

    public void serverStopped(final FMLServerStoppedEvent event) {
        PlannerQuests.backend()
            .serverStopped();
    }
}
