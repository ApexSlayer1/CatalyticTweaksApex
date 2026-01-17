package com.catalytictweaks.catalytictweaksmod.mixin.mmr.controller;

import es.degrassi.mmreborn.common.crafting.MachineRecipe;
import es.degrassi.mmreborn.common.entity.MachineControllerEntity;
import es.degrassi.mmreborn.common.manager.crafting.MachineProcessor;
import es.degrassi.mmreborn.common.manager.crafting.MachineProcessorCore;
import es.degrassi.mmreborn.common.manager.crafting.MachineRecipeFinder;
import es.degrassi.mmreborn.common.manager.crafting.MachineStatus;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import com.catalytictweaks.catalytictweaksmod.Config;

import java.util.List;
import java.util.Optional;

@Mixin(MachineProcessor.class)
public abstract class MachineProcessorMixin {

    @Shadow @Final private List<MachineProcessorCore> cores;
    @Shadow @Final private MachineControllerEntity tile;
    @Shadow private boolean initialized;
    @Shadow private void init() {}

    @Unique private int globalRecipeCooldown = 0;

    /**
     * @author
     * @reason
     */
    @Overwrite
    public void tick()
    {
        if(!this.initialized) this.init();

        if(globalRecipeCooldown > 0)
        {
            globalRecipeCooldown--;
        }

        if(cores.isEmpty()) return;
        MachineProcessorCore masterCore = cores.get(0);

        if(!masterCore.hasActiveRecipe() && globalRecipeCooldown <= 0)
        {
            MachineProcessorCoreAccessor masterAccess = (MachineProcessorCoreAccessor) masterCore;
            MachineRecipeFinder finder = masterAccess.getRecipeFinder();
            Optional<RecipeHolder<MachineRecipe>> found = finder.findRecipe(true);
            if(found.isPresent())
            {
                RecipeHolder<MachineRecipe> recipe = found.get();

                for(MachineProcessorCore core : cores)
                {
                    if(core.hasActiveRecipe()) continue;
                    MachineProcessorCoreAccessor coreAccess = (MachineProcessorCoreAccessor) core;
                    coreAccess.callSetRecipe(recipe);
                    coreAccess.callCheckConditions();
                    if(coreAccess.getError() != null)
                    {
                        core.reset();
                        break;
                    }
                }
            }
            else
            {
                globalRecipeCooldown = Config.timebetweentries;
            }
        }

        this.cores.forEach(MachineProcessorCore::tick);

        if(this.tile.getStatus() != MachineStatus.IDLE &&
           this.cores.stream().noneMatch(MachineProcessorCore::hasActiveRecipe) &&
           !this.tile.getStatus().isMissingStructure())
        {
            this.tile.setStatus(MachineStatus.IDLE);
        }
    }
}