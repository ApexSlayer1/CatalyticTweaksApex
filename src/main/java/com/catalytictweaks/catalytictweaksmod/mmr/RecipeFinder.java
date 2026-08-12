package com.catalytictweaks.catalytictweaksmod.mmr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.catalytictweaks.catalytictweaksmod.mixin.mmr.accessors.RecipeCheckerAccessor;
import com.google.common.collect.Lists;
import com.mojang.datafixers.util.Pair;

import es.degrassi.mmreborn.api.crafting.requirement.IRequirement;
import es.degrassi.mmreborn.api.crafting.requirement.RecipeRequirement;
import es.degrassi.mmreborn.common.crafting.MachineRecipe;
import es.degrassi.mmreborn.common.entity.MachineControllerEntity;
import es.degrassi.mmreborn.common.machine.IOType;
import es.degrassi.mmreborn.common.manager.crafting.MachineProcessorCore;
import es.degrassi.mmreborn.common.manager.crafting.RecipeChecker;
import es.degrassi.mmreborn.common.registration.RecipeRegistration;
import es.degrassi.mmreborn.common.util.Comparators;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

public class RecipeFinder
{

    private static Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> recipe_cache = null;
    private static RecipeManager last_recipe_manager = null;

    /**
     * Interface to allow as bridge, so you can use the debugger to
     * change and access the mixin status as clear as possible
     */
    public interface Context
    {
        MachineControllerEntity getTile();
        int getBaseCooldown();
        List<RecipeChecker<MachineRecipe>> getRecipes();
        void setRecipes(List<RecipeChecker<MachineRecipe>> recipes);
        List<RecipeChecker<MachineRecipe>> getOkToCheck();
        void setOkToCheck(List<RecipeChecker<MachineRecipe>> okToCheck);
        boolean isComponentChanged();
        void setComponentChanged(boolean changed);
        int getRecipeCheckCooldown();
        void setRecipeCheckCooldown(int cooldown);
        MachineProcessorCore getCore();
    }

    @SuppressWarnings({"null"})
    public static void init(Context ctx)
    {
        var tile = ctx.getTile();
        var manager = tile.getLevel().getRecipeManager();

        if(recipe_cache == null || last_recipe_manager != manager)
        {
            recipe_cache = new HashMap<>();
            last_recipe_manager = manager;

            var allRecipes = manager.getAllRecipesFor(RecipeRegistration.RECIPE_TYPE.get());

            for(RecipeHolder<MachineRecipe> recipe : allRecipes)
            {
                var machineId = recipe.value().getOwningMachineIdentifier();
                recipe_cache.computeIfAbsent(machineId, k -> new ArrayList<>()).add(recipe);
            }

            recipe_cache.values().forEach(list -> list.sort(Comparators::compare));
        }

        var targetId = tile.getId();
        List<RecipeHolder<MachineRecipe>> filteredRecipes = recipe_cache.getOrDefault(targetId, List.of());

        List<RecipeChecker<MachineRecipe>> finalRecipes = new ArrayList<>(filteredRecipes.size());

        for(int i = filteredRecipes.size() - 1; i >= 0; i--)
        {
            finalRecipes.add(new RecipeChecker<>(filteredRecipes.get(i)));
        }
        ctx.setRecipes(finalRecipes);

        ctx.setOkToCheck(Lists.newArrayList());
        ctx.setRecipeCheckCooldown(tile.getLevel().random.nextInt(ctx.getBaseCooldown()));
    }

    public static Optional<Pair<RecipeHolder<MachineRecipe>, Integer>> findRecipe(Context ctx, boolean immediately)
    {
        var tile = ctx.getTile();
        if(tile.getLevel() == null || !ctx.getCore().isActive())
        {
            return Optional.empty();
        }

        int cooldown = ctx.getRecipeCheckCooldown();
        boolean shouldCheck = immediately;

        if(!shouldCheck)
        {
            shouldCheck = (cooldown-- <= 0);
            ctx.setRecipeCheckCooldown(cooldown);
        }

        if(shouldCheck)
        {
            ctx.setRecipeCheckCooldown(ctx.getBaseCooldown());

            ctx.getOkToCheck().clear();
            ctx.getOkToCheck().addAll(ctx.getRecipes());

            InputSnapshot snapshot = new InputSnapshot((IComponentManager)tile.getComponentManager());
            Pair<RecipeHolder<MachineRecipe>, Integer> bestMatch = null;
            Iterator<RecipeChecker<MachineRecipe>> iterator = ctx.getOkToCheck().iterator();

            while(iterator.hasNext())
            {
                RecipeChecker<MachineRecipe> checker = iterator.next();
                if(!ctx.isComponentChanged() && checker.isInventoryRequirementsOnly() && !immediately)
                {
                    continue;
                }

                MachineRecipe recipe = checker.getRecipe().value();
                boolean logicCheck = true;
                RecipeCheckerAccessor checkerAccess = (RecipeCheckerAccessor)checker;

                for(var recipeRequirement : checkerAccess.getInventoryRequirements())
                {
                    IRequirement<?, ?> rawReq = recipeRequirement.requirement();
                    if(!snapshot.contains(rawReq))
                    {
                        logicCheck = false;
                        break;
                    }
                }

                if(logicCheck)
                {
                    int maxCrafts = Integer.MAX_VALUE;
                    boolean inputsSufficient = true;

                    for(RecipeRequirement<?, ?, ?> req : recipe.getRequirements())
                    {
                        if(req.requirement().getMode() != IOType.INPUT)
                            continue;
                        int matches = snapshot.calculateMatches(req.requirement());

                        if(matches == 0)
                        {
                            inputsSufficient = false;
                            break;
                        }

                        maxCrafts = Math.min(maxCrafts, matches);
                    }

                    if(inputsSufficient)
                    {
                        if(maxCrafts == Integer.MAX_VALUE)
                            maxCrafts = 1;

                        if(bestMatch == null)
                        {
                            bestMatch = Pair.of(checker.getRecipe(), maxCrafts);
                        }
                        else
                        {
                            int currentPriority = recipe.getConfiguredPriority();
                            int bestPriority = bestMatch.getFirst().value().getConfiguredPriority();

                            if(currentPriority > bestPriority)
                            {
                                bestMatch = Pair.of(checker.getRecipe(), maxCrafts);
                            }
                            else if(currentPriority == bestPriority)
                            {
                                if(maxCrafts > bestMatch.getSecond())
                                {
                                    bestMatch = Pair.of(checker.getRecipe(), maxCrafts);
                                }
                            }
                        }
                        continue;
                    }
                }

                if(!logicCheck)
                {
                    iterator.remove();
                }
            }

            ctx.setComponentChanged(false);
            return Optional.ofNullable(bestMatch);
        }

        return Optional.empty();
    }
}
