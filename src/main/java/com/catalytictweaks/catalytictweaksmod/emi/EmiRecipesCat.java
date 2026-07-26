package com.catalytictweaks.catalytictweaksmod.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.recipe.EmiRecipeSorting;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.data.EmiData;
import dev.emi.emi.data.EmiRecipeCategoryProperties;
import dev.emi.emi.registry.EmiRecipes;
import dev.emi.emi.runtime.EmiHidden;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenCustomHashMap;
import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;

@SuppressWarnings({"unchecked", "null", "rawtypes"})
public class EmiRecipesCat
{
    private static final Logger LOGGER = LoggerFactory.getLogger("CatalyticTweaks/EmiRecipesCat");

    private static final Function<StrategyWrapper, Set<EmiRecipe>> NEW_KEY_SET_FUNC = k -> ConcurrentHashMap.newKeySet();

    private static Constructor<?> MANAGER_CONSTRUCTOR;
    private static Constructor<?> HASH_STRATEGY_CONSTRUCTOR;
    private static Method SET_WORKER_METHOD;

    private static Field RECIPES_FIELD;
    private static Field WORKSTATIONS_FIELD;
    private static Field DISABLED_FILTER_LOOKUP_FIELD;

    private static Field MANAGER_CATEGORIES;
    private static Field MANAGER_WORKSTATIONS;
    private static Field MANAGER_RECIPES;
    private static Field MANAGER_BY_INPUT;
    private static Field MANAGER_BY_OUTPUT;
    private static Field MANAGER_BY_CATEGORY;
    private static Field MANAGER_BY_ID;

    static
    {
        Configurator.setLevel("CatalyticTweaks/EmiRecipesCat", Level.INFO);
        try
        {
            Class<?> managerClass = Class.forName("dev.emi.emi.registry.EmiRecipes$Manager");
            MANAGER_CONSTRUCTOR = managerClass.getDeclaredConstructor();
            MANAGER_CONSTRUCTOR.setAccessible(true);

            Class<?> hashStrategyClass = Class.forName("dev.emi.emi.registry.EmiStackList$ComparisonHashStrategy");
            HASH_STRATEGY_CONSTRUCTOR = hashStrategyClass.getDeclaredConstructor();
            HASH_STRATEGY_CONSTRUCTOR.setAccessible(true);

            SET_WORKER_METHOD = EmiRecipes.class.getDeclaredMethod("setWorker", Class.forName("dev.emi.emi.registry.EmiRecipes$Worker"));
            SET_WORKER_METHOD.setAccessible(true);

            RECIPES_FIELD = EmiRecipes.class.getDeclaredField("recipes");
            RECIPES_FIELD.setAccessible(true);

            WORKSTATIONS_FIELD = EmiRecipes.class.getDeclaredField("workstations");
            WORKSTATIONS_FIELD.setAccessible(true);

            DISABLED_FILTER_LOOKUP_FIELD = EmiHidden.class.getDeclaredField("disabledFilterLookup");
            DISABLED_FILTER_LOOKUP_FIELD.setAccessible(true);

            MANAGER_CATEGORIES = managerClass.getDeclaredField("categories");
            MANAGER_CATEGORIES.setAccessible(true);

            MANAGER_WORKSTATIONS = managerClass.getDeclaredField("workstations");
            MANAGER_WORKSTATIONS.setAccessible(true);

            MANAGER_RECIPES = managerClass.getDeclaredField("recipes");
            MANAGER_RECIPES.setAccessible(true);

            MANAGER_BY_INPUT = managerClass.getDeclaredField("byInput");
            MANAGER_BY_INPUT.setAccessible(true);

            MANAGER_BY_OUTPUT = managerClass.getDeclaredField("byOutput");
            MANAGER_BY_OUTPUT.setAccessible(true);

            MANAGER_BY_CATEGORY = managerClass.getDeclaredField("byCategory");
            MANAGER_BY_CATEGORY.setAccessible(true);

            MANAGER_BY_ID = managerClass.getDeclaredField("byId");
            MANAGER_BY_ID.setAccessible(true);
        }
        catch(Exception e)
        {
            LOGGER.error("Failed to initialize reflection cache for EmiRecipesCat", e);
        }
    }

    private static class StrategyWrapper
    {
        final EmiStack stack;
        final Hash.Strategy strategy;

        StrategyWrapper(EmiStack stack, Hash.Strategy strategy)
        {
            this.stack = stack;
            this.strategy = strategy;
        }

        @Override
        public int hashCode()
        {
            return strategy.hashCode(stack);
        }

        @Override
        public boolean equals(Object obj)
        {
            if(this == obj)
                return true;
            if(!(obj instanceof StrategyWrapper other))
                return false;
            return strategy.equals(this.stack, other.stack);
        }
    }

    public static void bake()
    {
        long start = System.currentTimeMillis();

        ClassLoader modClassLoader = Thread.currentThread().getContextClassLoader();
        ForkJoinPool customPool = createCustomThreadPool(modClassLoader);

        try
        {
            customPool.submit(() -> {
                try
                {
                    executeBaking(start);
                }
                catch(Exception e)
                {
                    throw new RuntimeException(e);
                }
            })
            .get();
        }
        catch(Exception e)
        {
            LOGGER.error("Error, using EMI implementation:", e);
            EmiRecipes.bake();
        }
        finally
        {
            customPool.shutdown();
            customPool.close();
        }
    }

    private static ForkJoinPool createCustomThreadPool(ClassLoader modClassLoader)
    {
        return new ForkJoinPool(
            Runtime.getRuntime().availableProcessors(),
            pool -> {
                var worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                if(worker != null)
                {
                    worker.setContextClassLoader(modClassLoader);
                }
                return worker;
            },
            null, true);
    }

    private static void executeBaking(long start) throws Exception
    {
        if(EmiRecipes.byWorkstation != null)
        {
            EmiRecipes.byWorkstation.clear();
        }

        setupSafeLookup();

        List<EmiRecipe> recipes = (List<EmiRecipe>)RECIPES_FIELD.get(null);

        Map<EmiRecipeCategory, List<EmiIngredient>> workstations = (Map<EmiRecipeCategory, List<EmiIngredient>>)WORKSTATIONS_FIELD.get(null);

        List<EmiRecipeCategory> categories = EmiRecipes.categories;
        List<Predicate<EmiRecipe>> invalidators = EmiRecipes.invalidators;

        if(recipes != null)
        {
            for(var supplier : EmiData.recipes)
            {
                try
                {
                    EmiRecipe recipe = (EmiRecipe) supplier.get();
                    if(recipe != null)
                    {
                        recipes.add(recipe);
                    }
                }
                catch(Throwable t)
                {
                    LOGGER.error("Error loading EMI data recipe", t);
                }
            }
        }
        else
        {
            recipes = new ArrayList<>();
        }

        categories.sort((a, b) -> EmiRecipeCategoryProperties.getOrder(a) - EmiRecipeCategoryProperties.getOrder(b));
        invalidators.addAll(EmiData.recipeFilters);
        invalidators.add(EmiRecipesCat::isRecipeDisabled);

        List<EmiRecipe> filteredRecipes = recipes.parallelStream()
                                              .filter(EmiRecipesCat::isValidRecipe)
                                              .toList();

        Map<EmiRecipeCategory, List<EmiIngredient>> filteredWorkstations = filterWorkstations(workstations, categories);

        Map<EmiRecipeCategory, List<EmiRecipe>> byCategoryRaw = filteredRecipes.parallelStream()
            .filter(recipe -> recipe.getCategory() != null)
            .collect(Collectors.groupingBy(EmiRecipe::getCategory));

        Map<ResourceLocation, EmiRecipe> byId = filteredRecipes.parallelStream()
            .filter(recipe -> recipe.getId() != null)
            .collect(Collectors.toMap(
                EmiRecipe::getId,
                recipe -> recipe, (existing, replacement) -> existing));

        Map<EmiRecipeCategory, List<EmiRecipe>> byCategory = sortAndFreezeCategories(byCategoryRaw, categories);

        Hash.Strategy strategy = (Hash.Strategy)HASH_STRATEGY_CONSTRUCTOR.newInstance();

        Map<StrategyWrapper, Set<EmiRecipe>> inputAccumulator = new ConcurrentHashMap<>(32768);
        Map<StrategyWrapper, Set<EmiRecipe>> outputAccumulator = new ConcurrentHashMap<>(32768);

        filteredRecipes.parallelStream().forEach(recipe -> {
            List<EmiIngredient> inputs = recipe.getInputs();
            if(inputs != null)
            {
                for(int i = 0; i < inputs.size(); i++)
                {
                    EmiIngredient ingredient = inputs.get(i);
                    if(ingredient != null)
                    {
                        List<EmiStack> stacks = ingredient.getEmiStacks();
                        if(stacks != null)
                        {
                            for(int s = 0; s < stacks.size(); s++)
                            {
                                EmiStack stack = stacks.get(s);
                                if(stack != null && !stack.isEmpty())
                                {
                                    StrategyWrapper wrapper = new StrategyWrapper(stack, strategy);
                                    inputAccumulator.computeIfAbsent(wrapper, NEW_KEY_SET_FUNC).add(recipe);
                                }
                            }
                        }
                    }
                }
            }

            List<EmiIngredient> catalysts = recipe.getCatalysts();
            if(catalysts != null)
            {
                for(int i = 0; i < catalysts.size(); i++)
                {
                    EmiIngredient catalyst = catalysts.get(i);
                    if(catalyst != null)
                    {
                        List<EmiStack> stacks = catalyst.getEmiStacks();
                        if(stacks != null)
                        {
                            for(int s = 0; s < stacks.size(); s++)
                            {
                                EmiStack stack = stacks.get(s);
                                if(stack != null && !stack.isEmpty())
                                {
                                    StrategyWrapper wrapper = new StrategyWrapper(stack, strategy);
                                    inputAccumulator.computeIfAbsent(wrapper, NEW_KEY_SET_FUNC).add(recipe);
                                }
                            }
                        }
                    }
                }
            }

            List<EmiStack> outputs = recipe.getOutputs();
            if(outputs != null)
            {
                for(int i = 0; i < outputs.size(); i++)
                {
                    EmiStack stack = outputs.get(i);
                    if(stack != null && !stack.isEmpty())
                    {
                        StrategyWrapper wrapper = new StrategyWrapper(stack, strategy);
                        outputAccumulator.computeIfAbsent(wrapper, NEW_KEY_SET_FUNC).add(recipe);
                    }
                }
            }
        });

        Map<List<EmiRecipe>, List<EmiRecipe>> listInterner = new ConcurrentHashMap<>(16384);
        Function<Set<EmiRecipe>, List<EmiRecipe>> listDeduplicator = set -> {
            List<EmiRecipe> list = List.copyOf(set);
            return listInterner.computeIfAbsent(list, k -> k);
        };

        Map<EmiStack, List<EmiRecipe>> finalByInput = new Object2ObjectOpenCustomHashMap<>(inputAccumulator.size(), strategy);
        inputAccumulator.forEach((wrapper, set) -> finalByInput.put(wrapper.stack, listDeduplicator.apply(set)));

        Map<EmiStack, List<EmiRecipe>> finalByOutput = new Object2ObjectOpenCustomHashMap<>(outputAccumulator.size(), strategy);
        outputAccumulator.forEach((wrapper, set) -> finalByOutput.put(wrapper.stack, listDeduplicator.apply(set)));

        listInterner.clear();

        populateWorkstationMap(byCategory, filteredWorkstations);

        EmiRecipeManager customManager = (EmiRecipeManager)MANAGER_CONSTRUCTOR.newInstance();

        MANAGER_CATEGORIES.set(customManager, categories.stream().distinct().toList());
        MANAGER_WORKSTATIONS.set(customManager, filteredWorkstations);
        MANAGER_RECIPES.set(customManager, List.copyOf(filteredRecipes));
        MANAGER_BY_INPUT.set(customManager, finalByInput);
        MANAGER_BY_OUTPUT.set(customManager, finalByOutput);
        MANAGER_BY_CATEGORY.set(customManager, byCategory);
        MANAGER_BY_ID.set(customManager, byId);

        EmiRecipes.manager = customManager;
        if(recipes instanceof ArrayList<?> arr) arr.trimToSize();
        if(EmiRecipes.categories instanceof ArrayList<?> arr) arr.trimToSize();
        if(EmiRecipes.invalidators instanceof ArrayList<?> arr) arr.trimToSize();

        SET_WORKER_METHOD.invoke(null, (Object)null);

        LOGGER.info("Processed " + filteredRecipes.size() + " recipes in " + (System.currentTimeMillis() - start) + "ms!");
    }

    private static void setupSafeLookup() throws Exception
    {
        Map<String, Boolean> originalLookup = (Map<String, Boolean>)DISABLED_FILTER_LOOKUP_FIELD.get(null);
        if(originalLookup != null)
        {
            ConcurrentHashMap<String, Boolean> safeLookup = new ConcurrentHashMap<>(originalLookup);
            DISABLED_FILTER_LOOKUP_FIELD.set(null, safeLookup);
        }
    }

    private static boolean isRecipeDisabled(EmiRecipe r)
    {
        if(r == null) return true;

        List<EmiIngredient> inputs = r.getInputs();
        if(inputs != null)
        {
            for(EmiIngredient i : inputs)
            {
                if(i != null && EmiHidden.isDisabled(i))
                {
                    return true; 
                }  
            }
        }

        List<EmiStack> outputs = r.getOutputs();
        if(outputs != null)
        {
            for(EmiIngredient i : outputs)
            {
                if(i != null && EmiHidden.isDisabled(i))
                {
                    return true; 
                }  
            }
        }

        List<EmiIngredient> catalysts = r.getCatalysts();
        if(catalysts != null)
        {
            for(EmiIngredient i : catalysts)
            {
                if(i != null && EmiHidden.isDisabled(i))
                {
                    return true; 
                }  
            }
        }

        return false;
    }

    private static boolean isValidRecipe(EmiRecipe r)
    {
        if(r == null) return false;
        
        for(Predicate<EmiRecipe> predicate : EmiRecipes.invalidators)
        {
            if(predicate.test(r))
            {
                return false;
            }
        }
        return true;
    }

    private static Map<EmiRecipeCategory, List<EmiIngredient>> filterWorkstations(
        Map<EmiRecipeCategory, List<EmiIngredient>> workstations,
        List<EmiRecipeCategory> categories)
    {
        if(workstations == null || workstations.isEmpty())
        {
            return Collections.emptyMap();
        }

        Map<EmiRecipeCategory, List<EmiIngredient>> temp = new ConcurrentHashMap<>(workstations.size());

        workstations.entrySet().parallelStream().forEach(entry -> {
            if(entry.getKey() != null && entry.getValue() != null)
            {
                List<EmiIngredient> w = entry.getValue().stream()
                    .filter(s -> s != null && !EmiHidden.isDisabled(s))
                    .toList();
                if(!w.isEmpty())
                {
                    temp.put(entry.getKey(), w);
                }
            }
        });

        // Ensamblado en LinkedHashMap manteniendo el orden definido por 'categories'
        Map<EmiRecipeCategory, List<EmiIngredient>> filteredWorkstations = new LinkedHashMap<>();
        for(EmiRecipeCategory cat : categories)
        {
            List<EmiIngredient> ingredients = temp.get(cat);
            if(ingredients != null)
            {
                filteredWorkstations.put(cat, ingredients);
            }
        }

        temp.forEach((cat, ing) -> filteredWorkstations.putIfAbsent(cat, ing));

        return Collections.unmodifiableMap(filteredWorkstations);
    }

    private static Map<EmiRecipeCategory, List<EmiRecipe>> sortAndFreezeCategories(
        Map<EmiRecipeCategory, List<EmiRecipe>> byCategoryRaw,
        List<EmiRecipeCategory> categories)
    {
        Map<EmiRecipeCategory, List<EmiRecipe>> sortedPerCategory = new ConcurrentHashMap<>(byCategoryRaw.size());

        byCategoryRaw.entrySet().parallelStream().forEach(entry -> {
            EmiRecipeCategory cat = entry.getKey();
            List<EmiRecipe> cRecipes = entry.getValue();
            Comparator<EmiRecipe> sort = EmiRecipeCategoryProperties.getSort(cat);
            if(sort != EmiRecipeSorting.none())
            {
                cRecipes = cRecipes.stream().sorted(sort).toList();
            }
            sortedPerCategory.put(cat, List.copyOf(cRecipes));
        });

        // Ensamblado en LinkedHashMap garantizando la secuencia de pestañas de EMI
        Map<EmiRecipeCategory, List<EmiRecipe>> byCategory = new LinkedHashMap<>();
        for(EmiRecipeCategory cat : categories)
        {
            List<EmiRecipe> cRecipes = sortedPerCategory.get(cat);
            if(cRecipes != null)
            {
                byCategory.put(cat, cRecipes);
            }
        }

        sortedPerCategory.forEach((cat, list) -> byCategory.putIfAbsent(cat, list));

        return Collections.unmodifiableMap(byCategory);
    }

    private static void populateWorkstationMap(
        Map<EmiRecipeCategory, List<EmiRecipe>> byCategory,
        Map<EmiRecipeCategory, List<EmiIngredient>> filteredWorkstations)
    {
        Map<EmiStack, List<EmiRecipe>> workstationMap = EmiRecipes.byWorkstation;
        if (workstationMap == null) return;

        Map<List<EmiRecipe>, List<EmiRecipe>> listInterner = new HashMap<>();

        for(Map.Entry<EmiRecipeCategory, List<EmiRecipe>> entry : byCategory.entrySet())
        {
            List<EmiIngredient> ingredients = filteredWorkstations.get(entry.getKey());
            if(ingredients == null)
            {
                continue;
            }

            List<EmiRecipe> deduplicatedList = listInterner.computeIfAbsent(
                List.copyOf(entry.getValue()), k -> k
            );

            for(EmiIngredient ingredient : ingredients)
            {
                if (ingredient == null) continue;
                List<EmiStack> stacks = ingredient.getEmiStacks();
                if (stacks == null) continue;
                for(EmiStack stack : stacks)
                {
                    if(stack != null && !stack.isEmpty())
                    {
                        workstationMap.put(stack, deduplicatedList);
                    }
                }
            }
        }
    }
}