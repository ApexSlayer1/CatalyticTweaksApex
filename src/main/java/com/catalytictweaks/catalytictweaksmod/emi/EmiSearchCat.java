package com.catalytictweaks.catalytictweaksmod.emi;

import dev.emi.emi.EmiPort;
import dev.emi.emi.EmiUtil;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.config.EmiConfig;
import dev.emi.emi.data.EmiAlias;
import dev.emi.emi.data.EmiData;
import dev.emi.emi.registry.EmiStackList;
import dev.emi.emi.search.EmiSearch;
import dev.emi.emi.search.NameQuery;
import dev.emi.emi.search.SearchStack;
import dev.emi.emi.runtime.EmiReloadLog;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.searchtree.SuffixArray;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;

@SuppressWarnings("null")
public class EmiSearchCat
{
    private static final Logger LOGGER = LoggerFactory.getLogger("CatalyticTweaks/EmiSearchCat");
    private static final Map<String, String> MOD_NAME_CACHE = new ConcurrentHashMap<>(512);
    // Caché crítico para deducir palabras repetitivas en tooltips y salvar RAM/GC
    private static final Map<String, String> TOKEN_CACHE = new ConcurrentHashMap<>(65536);

    static
    {
        Configurator.setLevel("CatalyticTweaks/EmiSearchCat", Level.INFO);
    }

    private static class SearchData
    {
        final EmiStack rawStack;
        final SearchStack searchStack;
        final String name;
        final Set<String> tooltipTokens;
        final Set<String> modNames;
        final String path;

        SearchData(EmiStack rawStack, SearchStack searchStack, String name, Set<String> tooltipTokens,
            Set<String> modNames, String path)
        {
            this.rawStack = rawStack;
            this.searchStack = searchStack;
            this.name = name;
            this.tooltipTokens = tooltipTokens;
            this.modNames = modNames;
            this.path = path;
        }
    }

    private static class AliasData
    {
        final EmiStack stack;
        final String text;

        AliasData(EmiStack stack, String text)
        {
            this.stack = stack;
            this.text = text;
        }
    }

    public static void bake()
    {
        long start = System.currentTimeMillis();

        List<EmiStack> stacks = EmiStackList.stacks;
        if(stacks == null || stacks.isEmpty())
        {
            LOGGER.warn("EmiStackList is empty or null. Omitting!");
            return;
        }

        boolean old = EmiConfig.appendItemModId;
        EmiConfig.appendItemModId = false;

        // Limpiamos el caché en cada bake para evitar fugas de memoria
        TOKEN_CACHE.clear();

        ClassLoader modClassLoader = Thread.currentThread().getContextClassLoader();
        ForkJoinPool customPool = createCustomThreadPool(modClassLoader);

        ExecutorService bakingExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r);
            t.setName("EMI-Search-Baker-" + t.threadId());
            t.setDaemon(true);
            t.setContextClassLoader(modClassLoader);
            return t;
        });

        try
        {
            List<SearchData> gatheredData = customPool.submit(() -> 
                stacks.parallelStream().map(EmiSearchCat::extractSearchData)
                .filter(Objects::nonNull)
                .toList()).get();

            long gatherTime = System.currentTimeMillis() - start;
            LOGGER.info("Parallel info gathered of " + gatheredData.size() + " items in " + gatherTime + "ms");

            SuffixArray<SearchStack> names = new SuffixArray<>();
            SuffixArray<SearchStack> tooltips = new SuffixArray<>();
            SuffixArray<SearchStack> mods = new SuffixArray<>();
            SuffixArray<EmiStack> aliases = new SuffixArray<>();
            Set<EmiStack> bakedStacks = new ObjectOpenHashSet<>(gatheredData.size());

            CompletableFuture<Void> namesFuture = CompletableFuture.runAsync(() -> {
                for(int i = 0; i < gatheredData.size(); i++)
                {
                    SearchData data = gatheredData.get(i);
                    bakedStacks.add(data.rawStack);
                    if(data.name != null)
                    {
                        names.add(data.searchStack, data.name);
                    }
                    if(data.path != null)
                    {
                        names.add(data.searchStack, data.path);
                    }
                }
                names.generate();
            }, bakingExecutor);

            CompletableFuture<Void> tooltipsFuture = CompletableFuture.runAsync(() -> {
                for(int i = 0; i < gatheredData.size(); i++)
                {
                    SearchData data = gatheredData.get(i);
                    for(String token : data.tooltipTokens)
                    {
                        tooltips.add(data.searchStack, token);
                    }
                }
                tooltips.generate();
            }, bakingExecutor);

            CompletableFuture<Void> modsFuture = CompletableFuture.runAsync(() -> {
                for(int i = 0; i < gatheredData.size(); i++)
                {
                    SearchData data = gatheredData.get(i);
                    for(String modName : data.modNames)
                    {
                        mods.add(data.searchStack, modName);
                    }
                }
                mods.generate();
            }, bakingExecutor);

            CompletableFuture<Void> aliasesFuture = CompletableFuture.runAsync(() -> {
                List<AliasData> gatheredAliases = gatherAliases();
                for(int i = 0; i < gatheredAliases.size(); i++)
                {
                    AliasData aliasData = gatheredAliases.get(i);
                    if(aliasData.stack != null)
                    {
                        aliases.add(aliasData.stack.copy().comparison(EmiPort.compareStrict()), aliasData.text);
                    }
                }
                aliases.generate();
            }, bakingExecutor);

            CompletableFuture<Void> allFutures = CompletableFuture.allOf(namesFuture, tooltipsFuture, modsFuture, aliasesFuture);
            allFutures.get();

            EmiConfig.appendItemModId = old;

            EmiSearch.names = names;
            EmiSearch.tooltips = tooltips;
            EmiSearch.mods = mods;
            EmiSearch.aliases = aliases;
            EmiSearch.bakedStacks = bakedStacks;

            LOGGER.info("Completed in " + (System.currentTimeMillis() - start) + "ms!");
        }
        catch(Exception e)
        {
            LOGGER.error("Error, using EMI default:", e);
            EmiConfig.appendItemModId = old;
            EmiSearch.bake();
        }
        finally
        {
            TOKEN_CACHE.clear();
            MOD_NAME_CACHE.clear();
            
            customPool.shutdown();
            customPool.close();
            bakingExecutor.shutdown();
        }
    }

    private static ForkJoinPool createCustomThreadPool(ClassLoader modClassLoader)
    {
        return new ForkJoinPool(Runtime.getRuntime().availableProcessors(), pool -> {
            var worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            if(worker != null)
            {
                worker.setContextClassLoader(modClassLoader);
            }
            return worker;
        }, null, true);
    }

    private static SearchData extractSearchData(EmiStack stack)
    {
        if(stack == null || stack.isEmpty())
        {
            return null;
        }

        try
        {
            SearchStack searchStack = new SearchStack(stack);

            Component nameComp = NameQuery.getText(stack);
            String nameStr = null;
            if (nameComp != null)
            {
                String rawName = nameComp.getString().toLowerCase(Locale.ROOT);
                nameStr = TOKEN_CACHE.computeIfAbsent(rawName, k -> k);
            }

            Set<String> tooltipTokens = extractTooltipTokens(stack);
            Set<String> modNamesSet = new ObjectOpenHashSet<>(4);
            String pathStr = null;

            ResourceLocation id = stack.getId();
            if(id != null)
            {
                String namespace = id.getNamespace();
                String modName = MOD_NAME_CACHE.computeIfAbsent(namespace, k -> EmiUtil.getModName(k).toLowerCase(Locale.ROOT));
                modNamesSet.add(modName);
                modNamesSet.add(namespace);
                
                pathStr = id.getPath();
            }

            extractEnchantmentMods(stack, modNamesSet);

            return new SearchData(stack, searchStack, nameStr, tooltipTokens, modNamesSet, pathStr);
        }
        catch(Exception e)
        {
            return null;
        }
    }

    private static Set<String> extractTooltipTokens(EmiStack stack)
    {
        List<Component> tooltip = stack.getTooltipText();
        if(tooltip == null || tooltip.size() <= 1)
        {
            return Set.of();
        }

        Set<String> tokensSet = new ObjectOpenHashSet<>(8);
        for(int i = 1; i < tooltip.size(); ++i)
        {
            Component text = tooltip.get(i);
            if(text != null)
            {
                String line = text.getString();
                tokenize(line, tokensSet);
            }
        }

        return tokensSet;
    }

    private static void tokenize(String line, Set<String> tokensSet)
    {
        int len = line.length();
        int start = -1;
        for (int i = 0; i < len; i++)
        {
            char c = line.charAt(i);
            if (isSeparator(c))
            {
                if (start != -1)
                {
                    addToken(line, start, i, tokensSet);
                    start = -1;
                }
            }
            else
            {
                if (start == -1)
                {
                    start = i;
                }
            }
        }
        if (start != -1)
        {
            addToken(line, start, len, tokensSet);
        }
    }

    private static boolean isSeparator(char c)
    {
        return Character.isWhitespace(c) || 
               c == ',' || c == '.' || c == '|' || c == '#' || c == ':' ||
               c == '(' || c == ')' || c == '{' || c == '}' || c == '[' || c == ']' ||
               c == '+' || c == '_' || c == '-';
    }

    private static void addToken(String line, int start, int end, Set<String> tokensSet)
    {
        int len = end - start;
        if (len >= 4)
        {
            boolean isNumber = true;
            for (int i = start; i < end; i++)
            {
                char c = line.charAt(i);
                if (c < '0' || c > '9')
                {
                    isNumber = false;
                    break;
                }
            }
            if (!isNumber)
            {
                String rawToken = line.substring(start, end).toLowerCase(Locale.ROOT);
                if(!rawToken.startsWith("§")) 
                {
                    tokensSet.add(TOKEN_CACHE.computeIfAbsent(rawToken, k -> k));
                }
            }
        }
    }

    private static void extractEnchantmentMods(EmiStack stack, Set<String> modNamesSet)
    {
        if(stack.getItemStack().getItem() != Items.ENCHANTED_BOOK)
        {
            return;
        }

        ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);

        if(enchantments == null)
        {
            return;
        }

        for(Holder<Enchantment> e : enchantments.keySet())
        { 
            ResourceLocation eid = EmiPort.getEnchantmentRegistry().getKey(e.value());
            if(eid != null && !eid.getNamespace().equals("minecraft"))
            {
                String modName = MOD_NAME_CACHE.computeIfAbsent(eid.getNamespace(), k -> EmiUtil.getModName(k).toLowerCase(Locale.ROOT));
                modNamesSet.add(modName);
            }
        }
    }

    private static List<AliasData> gatherAliases()
    {
        List<AliasData> gatheredAliases = new ArrayList<>();
        Map<String, String> translationCache = new HashMap<>(128);

        for(Supplier<EmiAlias> supplier : EmiData.aliases)
        {
            EmiAlias alias = supplier.get();
            if(alias == null)
            {
                continue;
            }

            for(String key : alias.keys())
            {
                if(!I18n.exists(key))
                {
                    EmiReloadLog.warn("Untranslated alias " + key);
                }

                String text = translationCache.computeIfAbsent(key, k -> I18n.get(k).toLowerCase(Locale.ROOT));

                for(EmiIngredient ing : alias.stacks())
                {
                    List<EmiStack> emiStacks = ing.getEmiStacks();
                    for(int i = 0; i < emiStacks.size(); i++)
                    {
                        gatheredAliases.add(new AliasData(emiStacks.get(i), text));
                    }
                }
            }
        }

        for(EmiAlias.Baked alias : EmiStackList.registryAliases)
        {
            if(alias == null)
            {
                continue;
            }
            for(Component text : alias.text())
            {
                if(text == null)
                {
                    continue;
                }

                String textStr = text.getString().toLowerCase(Locale.ROOT);

                for(EmiIngredient ing : alias.stacks())
                {
                    List<EmiStack> emiStacks = ing.getEmiStacks();
                    for(int i = 0; i < emiStacks.size(); i++)
                    {
                        gatheredAliases.add(new AliasData(emiStacks.get(i), textStr));
                    }
                }
            }
        }

        return gatheredAliases;
    }
}