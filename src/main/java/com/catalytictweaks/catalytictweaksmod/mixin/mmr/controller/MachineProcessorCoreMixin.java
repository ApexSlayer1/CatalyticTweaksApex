package com.catalytictweaks.catalytictweaksmod.mixin.mmr.controller;

import es.degrassi.mmreborn.api.crafting.CraftingContext;
import es.degrassi.mmreborn.common.crafting.MachineRecipe;
import es.degrassi.mmreborn.common.entity.MachineControllerEntity;
import es.degrassi.mmreborn.common.manager.crafting.MachineProcessorCore;
import es.degrassi.mmreborn.common.manager.crafting.MachineRecipeFinder;
import es.degrassi.mmreborn.common.manager.crafting.Phase;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.crafting.RecipeHolder;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MachineProcessorCore.class)
public abstract class MachineProcessorCoreMixin implements MachineProcessorCoreAccessor
{

    @Shadow private boolean active;
    @Shadow private RecipeHolder<MachineRecipe> currentRecipe;
    @Shadow private Phase phase;
    @Shadow private Component error;
    @Shadow private float recipeProgressTime;
    @Shadow private int recipeTotalTime;
    @Shadow private CraftingContext context;
    @Shadow private boolean isLastRecipeTick;
    @Shadow @Final private MachineRecipeFinder recipeFinder;
    @Shadow @Final private MachineControllerEntity tile;
    @Shadow private boolean searchImmediately;
    @Shadow private boolean componentChanged;

    @Shadow public abstract boolean isActive();
    @Shadow protected abstract void checkConditions();
    @Shadow protected abstract void processRequirements();
    @Shadow protected abstract void processTickRequirements();
    @Shadow public abstract void reset();
    @Shadow public abstract void setRecipe(RecipeHolder<MachineRecipe> recipe);


    /**
     * @author
     * @reason
     */
    @Overwrite
    public void tick()
    {
        if(!isActive()) return;

        if (this.currentRecipe != null)
        {
            if (this.phase == Phase.CONDITIONS) this.checkConditions();

            if (this.phase == Phase.PROCESS) this.processRequirements();

            if (this.phase == Phase.PROCESS_TICK) this.processTickRequirements();

            if(this.currentRecipe != null && this.error == null && this.recipeProgressTime >= this.recipeTotalTime - this.context.getModifiedSpeed()) {
                if(this.isLastRecipeTick)
                {
                    this.isLastRecipeTick = false;
                    this.reset();
                }
                else this.isLastRecipeTick = true;
                
            }
        }
    }

    @Inject(method = "setRecipe", at = @At("RETURN"))
    private void onSetRecipe(RecipeHolder<MachineRecipe> recipe, CallbackInfo ci) {
        this.componentChanged = true; 
    }
}