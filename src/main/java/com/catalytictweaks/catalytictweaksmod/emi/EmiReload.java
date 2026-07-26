package com.catalytictweaks.catalytictweaksmod.emi;

import com.google.common.collect.Lists;
import dev.emi.emi.EmiPort;
import dev.emi.emi.api.EmiInitRegistry;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.jemi.JemiPlugin;
import dev.emi.emi.platform.EmiAgnos;
import dev.emi.emi.registry.EmiComparisonDefaults;
import dev.emi.emi.registry.EmiDragDropHandlers;
import dev.emi.emi.registry.EmiExclusionAreas;
import dev.emi.emi.registry.EmiIngredientSerializers;
import dev.emi.emi.registry.EmiInitRegistryImpl;
import dev.emi.emi.registry.EmiPluginContainer;
import dev.emi.emi.registry.EmiRecipeFiller;
import dev.emi.emi.registry.EmiRecipes;
import dev.emi.emi.registry.EmiRegistryImpl;
import dev.emi.emi.registry.EmiStackList;
import dev.emi.emi.registry.EmiStackProviders;
import dev.emi.emi.registry.EmiTags;
import dev.emi.emi.runtime.EmiHidden;
import dev.emi.emi.runtime.EmiPersistentData;
import dev.emi.emi.runtime.EmiReloadLog;
import dev.emi.emi.runtime.EmiReloadManager;
import dev.emi.emi.screen.EmiScreenManager;
import net.minecraft.client.Minecraft;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class EmiReload
{
    private static final Logger LOGGER = LoggerFactory.getLogger("CatalyticTweaks/EmiReloadMixin");
    private static volatile boolean clear;
    private static volatile boolean restart;
    private static volatile int status;
    private static Thread thread;

    static
    {
        Configurator.setLevel("CatalyticTweaks/EmiReloadMixin", Level.INFO);
        syncFromManager();
    }

    public static void run()
    {
        syncFromManager();

        Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
        Thread.currentThread().setName("EmiReloader");

        int retries = 3;
        Map<String, Long> timings = new LinkedHashMap<>();
        int totalPlugins = 0;
        int failedInit = 0;
        int failedReg = 0;

        do
        {
            boolean shouldRestart = false;
            timings.clear();
            totalPlugins = 0;
            failedInit = 0;
            failedReg = 0;

            try
            {
                if(!clear)
                {
                    LOGGER.info("Starting EMI reload...");
                }

                long reloadStart = System.currentTimeMillis();

                long phaseStart = System.currentTimeMillis();
                EmiReloadManager.step(EmiPort.literal("Clearing data"), 1000L);
                EmiRecipes.clear();
                EmiStackList.clear();
                EmiIngredientSerializers.clear();
                EmiExclusionAreas.clear();
                EmiDragDropHandlers.clear();
                EmiStackProviders.clear();
                EmiRecipeFiller.clear();
                EmiHidden.clear();
                EmiTags.ADAPTERS_BY_CLASS.map().clear();
                EmiTags.ADAPTERS_BY_REGISTRY.clear();
                Thread.yield();
                timings.put("Clear data", System.currentTimeMillis() - phaseStart);

                if(clear)
                {
                    clear = false;
                    syncToManager();
                    String summary = "EMI cleared in " + (System.currentTimeMillis() - reloadStart) + "ms";
                    LOGGER.info(summary);
                    break;
                }

                Minecraft client = Minecraft.getInstance();
                if(client.level == null)
                {
                    EmiReloadLog.warn("World is null");
                    break;
                }

                if(client.level.getRecipeManager() == null)
                {
                    EmiReloadLog.warn("Recipe Manager is null");
                    break;
                }

                phaseStart = System.currentTimeMillis();
                List<EmiPluginContainer> plugins = Lists.newArrayList();
                plugins.addAll(EmiAgnos.getPlugins().stream().sorted((a, b) -> Integer.compare(entrypointPriority(a), entrypointPriority(b))).toList());
                
                if(EmiAgnos.isModLoaded("jei"))
                {
                    plugins.add(new EmiPluginContainer(new JemiPlugin(), "jemi"));
                }

                timings.put("Plugin collection", System.currentTimeMillis() - phaseStart);
                totalPlugins = plugins.size();

                EmiInitRegistry rawInitRegistry = new EmiInitRegistryImpl();
                EmiInitRegistry initRegistry = (EmiInitRegistry) Proxy.newProxyInstance(
                    EmiInitRegistry.class.getClassLoader(),
                    new Class<?>[]{EmiInitRegistry.class},
                    (proxy, method, args) -> {
                        synchronized(rawInitRegistry)
                        {
                            return method.invoke(rawInitRegistry, args);
                        }
                    }
                );

                long initPhaseStart = System.currentTimeMillis();
                AtomicInteger failedInitCount = new AtomicInteger(0);

                ExecutorService initExecutor = Executors.newFixedThreadPool(
                    Runtime.getRuntime().availableProcessors(),
                    r -> {
                        Thread t = new Thread(r);
                        t.setName("EMI-Plugin-Initializer-" + t.threadId());
                        t.setDaemon(true);
                        t.setContextClassLoader(Thread.currentThread().getContextClassLoader());
                        return t;
                    }
                );

                List<CompletableFuture<Void>> initFutures = new ArrayList<>();
                for(EmiPluginContainer container : plugins)
                {
                    initFutures.add(CompletableFuture.runAsync(() -> {
                        long pStart = System.currentTimeMillis();
                        EmiReloadManager.step(EmiPort.literal("Initializing plugin from " + container.id()), 1000L);
                        try
                        {
                            container.plugin().initialize(initRegistry);
                        }
                        catch(Throwable e)
                        {
                            failedInitCount.incrementAndGet();
                            EmiReloadLog.warn("Exception initializing plugin provided by " + container.id(), e);
                        }
                        LOGGER.info("Initialized plugin " + container.id() + " in " + (System.currentTimeMillis() - pStart) + "ms");
                        Thread.yield();
                    }, initExecutor));
                }

                CompletableFuture.allOf(initFutures.toArray(new CompletableFuture[0])).join();
                initExecutor.shutdown();

                failedInit = failedInitCount.get();
                timings.put("Plugin initialization", System.currentTimeMillis() - initPhaseStart);

                if(shouldRestart || restart)
                {
                    if(restart)
                        shouldRestart = true;
                    syncToManager();
                    continue;
                }

                phaseStart = System.currentTimeMillis();
                EmiHidden.reload();
                EmiReloadManager.step(EmiPort.literal("Processing tags"), 1000L);
                EmiTags.reload();
                timings.put("Tag processing", System.currentTimeMillis() - phaseStart);
                Thread.yield();
                
                phaseStart = System.currentTimeMillis();
                EmiReloadManager.step(EmiPort.literal("Constructing index"), 1000L);
                EmiComparisonDefaults.comparisons = new HashMap<>();
                EmiStackList.reload();
                timings.put("Index construction", System.currentTimeMillis() - phaseStart);
                Thread.yield();

                if(restart)
                {
                    shouldRestart = true;
                    syncToManager();
                    continue;
                }

                EmiRegistryImpl realRegistry = new EmiRegistryImpl();

                // List<EmiPluginContainer> phase1Core = new ArrayList<>();
                List<EmiPluginContainer> phase2Parallel = new ArrayList<>();
                // List<EmiPluginContainer> phase3Sequential = new ArrayList<>();

                for(EmiPluginContainer container : plugins)
                {
                    phase2Parallel.add(container);
                    // String id = container.id();
                    // if(id.equals("kubejs") || id.equals("modular_machinery_reborn"))
                    // {
                    //     phase3Sequential.add(container);
                    // }
                    // else
                    // {
                    //     phase2Parallel.add(container);
                    // }
                }

                ConcurrentEmiRegistry[] pluginRegistries = new ConcurrentEmiRegistry[phase2Parallel.size()];
                for(int i = 0; i < phase2Parallel.size(); i++)
                {
                    pluginRegistries[i] = new ConcurrentEmiRegistry(realRegistry);
                }

                AtomicInteger failedCount = new AtomicInteger(0);
                AtomicBoolean threadRestart = new AtomicBoolean(false);

                // for(EmiPluginContainer container : phase1Core)
                // {
                //     loadPlugin.accept(container);
                //     if(threadRestart.get() || restart)
                //     {
                //         break;
                //     }
                // }

                long regPhaseStart = System.currentTimeMillis();
                if(!threadRestart.get() && !restart)
                {
                    ExecutorService executor = Executors.newFixedThreadPool(
                        Runtime.getRuntime().availableProcessors(),
                        r -> {
                            Thread t = new Thread(r);
                            t.setName("EMI-Plugin-Loader-" + t.threadId());
                            t.setDaemon(true);
                            t.setContextClassLoader(Thread.currentThread().getContextClassLoader());
                            return t;
                        }
                    );
                    List<CompletableFuture<Void>> futures = new ArrayList<>();

                    for(int i = 0; i < phase2Parallel.size(); i++)
                    {
                        final int index = i;
                        EmiPluginContainer container = phase2Parallel.get(index);
                        futures.add(CompletableFuture.runAsync(() -> {
                            long phaseStartInner = System.currentTimeMillis();
                            EmiReloadManager.step(EmiPort.literal("Loading plugin from " + container.id()), 2000L);
                            
                            try
                            {
                                container.plugin().register(pluginRegistries[index]);
                            }
                            catch(Throwable e)
                            {
                                failedCount.incrementAndGet();
                                LOGGER.error("Exception loading plugin provided by {}: {}", container.id(), e.getMessage(), e);
                                if(restart)
                                {
                                    threadRestart.set(true);
                                }
                            }

                            long elapsed = System.currentTimeMillis() - phaseStartInner;
                            LOGGER.info("Loaded plugin {} in {}ms", container.id(), elapsed);
                            Thread.yield();
                        }, executor));
                    }

                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                    executor.shutdown();
                }

                for(int i = 0; i < phase2Parallel.size(); i++)
                {
                    pluginRegistries[i].flushTo(realRegistry);
                }

                // if(!threadRestart.get() && !restart)
                // {
                //     for(EmiPluginContainer container : phase3Sequential)
                //     {
                //         loadPlugin.accept(container);
                //         if(threadRestart.get() || restart)
                //         {
                //             break;
                //         }
                //     }
                // }
                
                timings.put("Plugin registration", System.currentTimeMillis() - regPhaseStart);
                failedReg = failedCount.get();

                if(threadRestart.get())
                {
                    shouldRestart = true;
                }


                if(shouldRestart || restart)
                {
                    if(restart)
                    {
                        shouldRestart = true;
                    }

                    syncToManager();
                    continue;
                }

                phaseStart = System.currentTimeMillis();
                EmiReloadManager.step(EmiPort.literal("Baking index"), 2000L);
                EmiStackList.bake();
                timings.put("Index baking", System.currentTimeMillis() - phaseStart);
                Thread.yield();

                phaseStart = System.currentTimeMillis();
                EmiReloadManager.step(EmiPort.literal("Registering late recipes"), 2000L);
                Consumer<EmiRecipe> registerLateRecipe = realRegistry::addRecipe;
                
                for(Consumer<Consumer<EmiRecipe>> consumer : EmiRecipes.lateRecipes)
                {
                    try
                    {
                        consumer.accept(registerLateRecipe);
                    }
                    catch(Exception e)
                    {
                        EmiReloadLog.warn("Exception loading late recipes for plugins:", e);
                        if(restart)
                        {
                            shouldRestart = true;
                            break;
                        }
                    }
                }

                timings.put("Late recipes", System.currentTimeMillis() - phaseStart);
                Thread.yield();

                if(shouldRestart || restart)
                {
                    if(restart)
                    {
                        shouldRestart = true;
                    }

                    syncToManager();
                    continue;
                }

                phaseStart = System.currentTimeMillis();
                EmiReloadManager.step(EmiPort.literal("Baking recipes"), 3000L);
                EmiRecipesCat.bake();
                BoM.reload();
                EmiPersistentData.load();
                timings.put("Recipe baking", System.currentTimeMillis() - phaseStart);
                Thread.yield();

                phaseStart = System.currentTimeMillis();
                EmiReloadManager.step(EmiPort.literal("Baking search"), 3000L);
                EmiSearchCat.bake();
                timings.put("Search baking", System.currentTimeMillis() - phaseStart);
                Thread.yield();

                EmiReloadManager.step(EmiPort.literal("Finishing up"));
                Minecraft.getInstance().execute(() -> {
                    EmiScreenManager.search.update();
                    EmiScreenManager.forceRecalculate();
                });

                EmiReloadLog.bake();

                long totalTime = System.currentTimeMillis() - reloadStart;
                String summary = "EMI reload summary:\n" +
                        "  Clear data: " + timings.get("Clear data") + "ms\n" +
                        "  Plugin collection: " + timings.get("Plugin collection") + "ms\n" +
                        "  Plugin initialization: " + timings.get("Plugin initialization") + "ms (" + totalPlugins + " plugins, " + failedInit + " init failures)\n" +
                        "  Tag processing: " + timings.get("Tag processing") + "ms\n" +
                        "  Index construction: " + timings.get("Index construction") + "ms\n" +
                        "  Plugin registration: " + timings.get("Plugin registration") + "ms (" + (totalPlugins - failedReg) + " loaded, " + failedReg + " failures)\n" +
                        "  Index baking: " + timings.get("Index baking") + "ms\n" +
                        "  Late recipes: " + timings.get("Late recipes") + "ms\n" +
                        "  Recipe baking: " + timings.get("Recipe baking") + "ms\n" +
                        "  Search baking: " + timings.get("Search baking") + "ms\n" +
                        "  Total: " + totalTime + "ms";
                LOGGER.info(summary);
                status = 2;
                syncToManager();
                break;
            }
            catch(Throwable e)
            {
                EmiReloadLog.warn("Critical error occurred during reload:", e);
                status = -1;
                syncToManager();
                if(retries-- == 0)
                {
                    restart = false;
                    syncToManager();
                }
                else
                {
                    break;
                }
            }

        } while(restart);

        thread = null;
        syncToManager();
    }

    private static void syncFromManager()
    {
        try
        {
            Field clearField = EmiReloadManager.class.getDeclaredField("clear");
            clearField.setAccessible(true);
            clear = (boolean)clearField.get(null);
            Field restartField = EmiReloadManager.class.getDeclaredField("restart");
            restartField.setAccessible(true);
            restart = (boolean)restartField.get(null);
            Field statusField = EmiReloadManager.class.getDeclaredField("status");
            statusField.setAccessible(true);
            status = (int)statusField.get(null);
            Field threadField = EmiReloadManager.class.getDeclaredField("thread");
            threadField.setAccessible(true);
            thread = (Thread)threadField.get(null);
        }
        catch(Exception e)
        {
            EmiReloadLog.warn("Failed to sync from EmiReloadManager", e);
        }
    }

    private static void syncToManager()
    {
        try
        {
            Field clearField = EmiReloadManager.class.getDeclaredField("clear");
            clearField.setAccessible(true);
            clearField.set(null, clear);
            Field restartField = EmiReloadManager.class.getDeclaredField("restart");
            restartField.setAccessible(true);
            restartField.set(null, restart);
            Field statusField = EmiReloadManager.class.getDeclaredField("status");
            statusField.setAccessible(true);
            statusField.set(null, status);
            Field threadField = EmiReloadManager.class.getDeclaredField("thread");
            threadField.setAccessible(true);
            threadField.set(null, thread);
        }
        catch(Exception e)
        {
            EmiReloadLog.warn("Failed to sync to EmiReloadManager", e);
        }
    }

    private static int entrypointPriority(EmiPluginContainer container)
    {
        return container.id().equals("emi") ? 0 : 1;
    }
}