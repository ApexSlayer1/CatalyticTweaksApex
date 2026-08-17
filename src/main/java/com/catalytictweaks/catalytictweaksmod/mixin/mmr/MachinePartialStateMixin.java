package com.catalytictweaks.catalytictweaksmod.mixin.mmr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import es.degrassi.mmreborn.common.block.BlockController;
import es.degrassi.mmreborn.common.entity.MachineControllerEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;

@Mixin(targets = "es.degrassi.mmreborn.api.PartialBlockState$2", remap = false)
public class MachinePartialStateMixin
{

    @Overwrite
    public boolean test(BlockInWorld cachedBlockInfo)
    {
        try
        {
            if(cachedBlockInfo == null) return false;

            BlockState state = cachedBlockInfo.getState();
            if(state != null && state.getBlock() instanceof BlockController) return true;

            BlockEntity entity = cachedBlockInfo.getEntity();
            return entity instanceof MachineControllerEntity;
        }
        catch(Exception e)
        {
            return false;
        }
    }
}
