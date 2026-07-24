package com.catalytictweaks.catalytictweaksmod.mixin.mmr;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import com.catalytictweaks.catalytictweaksmod.mmr.IMachineRecipeFinder;
import com.catalytictweaks.catalytictweaksmod.mmr.RecipeFinder.Context;
import com.google.common.collect.Lists;
import com.catalytictweaks.catalytictweaksmod.mmr.RecipeFinder;
import com.mojang.datafixers.util.Pair;

import es.degrassi.mmreborn.api.crafting.CraftingContext;
import es.degrassi.mmreborn.common.crafting.MachineRecipe;
import es.degrassi.mmreborn.common.entity.MachineControllerEntity;
import es.degrassi.mmreborn.common.manager.crafting.MachineProcessorCore;
import es.degrassi.mmreborn.common.manager.crafting.MachineRecipeFinder;
import es.degrassi.mmreborn.common.manager.crafting.RecipeChecker;
import es.degrassi.mmreborn.common.registration.RecipeRegistration;
import es.degrassi.mmreborn.common.util.Comparators;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

@Pseudo
@SuppressWarnings("null")
@Mixin(MachineRecipeFinder.class)
public abstract class MachineRecipeFinderMixin implements IMachineRecipeFinder, Context
{
    @Shadow
    protected @Final MachineControllerEntity tile;
    @Shadow
    protected @Final int baseCooldown;
    @Shadow
    protected @Final CraftingContext.Mutable mutableCraftingContext;
    @Shadow
    protected List<RecipeChecker<MachineRecipe>> recipes;
    @Shadow
    protected List<RecipeChecker<MachineRecipe>> okToCheck;
    @Shadow
    protected boolean componentChanged = true;
    @Shadow
    protected int recipeCheckCooldown;
    @Shadow
    protected @Final MachineProcessorCore core;

    @Unique
    private static final Map<RecipeManager, Map<ResourceLocation, List<RecipeChecker<MachineRecipe>>>> CACHE_BY_MANAGER =
        Collections.synchronizedMap(new WeakHashMap<>());

    @Overwrite
    public void init()
    {
        if(this.tile.getLevel() == null)
        {
            throw new IllegalStateException("Broken machine " + String.valueOf(this.tile.getId()) + "doesn't have a world");
        }
        else
        {
            RecipeManager currentManager = this.tile.getLevel().getRecipeManager();

            Map<ResourceLocation, List<RecipeChecker<MachineRecipe>>> worldCache = CACHE_BY_MANAGER.computeIfAbsent(
                currentManager,
                k -> new ConcurrentHashMap<>());

            this.recipes = worldCache.computeIfAbsent(this.tile.getId(), machineId -> 
                            currentManager.getAllRecipesFor((RecipeType<MachineRecipe>)RecipeRegistration.RECIPE_TYPE.get())
                                          .parallelStream()
                                          .filter(recipe -> recipe.value().getOwningMachineIdentifier().equals(machineId))
                                          .sorted(Comparators::compare)
                                          .map(RecipeChecker::new)
                                          .toList());

            this.okToCheck = Lists.newArrayList();
            this.recipeCheckCooldown = this.tile.getLevel().random.nextInt(this.baseCooldown);
        }
    }

    @Override
    public Optional<Pair<RecipeHolder<MachineRecipe>, Integer>> findRecipe(boolean immediately)
    {
        return RecipeFinder.findRecipe(this, immediately);
    }

    @Override
    public MachineControllerEntity getTile() { return this.tile; }
    @Override
    public int getBaseCooldown() { return this.baseCooldown; }

    @Override
    public List<RecipeChecker<MachineRecipe>> getRecipes() { return this.recipes; }
    @Override
    public void setRecipes(List<RecipeChecker<MachineRecipe>> recipes) { this.recipes = recipes; }

    @Override
    public List<RecipeChecker<MachineRecipe>> getOkToCheck() { return this.okToCheck; }
    @Override
    public void setOkToCheck(List<RecipeChecker<MachineRecipe>> okToCheck) { this.okToCheck = okToCheck; }

    @Override
    public boolean isComponentChanged() { return this.componentChanged; }
    @Override
    public void setComponentChanged(boolean changed) { this.componentChanged = changed; }

    @Override
    public int getRecipeCheckCooldown() { return this.recipeCheckCooldown; }
    @Override
    public void setRecipeCheckCooldown(int cooldown) { this.recipeCheckCooldown = cooldown; }

    @Override
    public MachineProcessorCore getCore() { return this.core; }
}