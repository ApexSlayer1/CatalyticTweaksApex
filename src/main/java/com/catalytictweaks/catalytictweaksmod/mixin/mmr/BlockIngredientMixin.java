package com.catalytictweaks.catalytictweaksmod.mixin.mmr;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import es.degrassi.mmreborn.api.BlockIngredient;
import es.degrassi.mmreborn.api.PartialBlockState;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;

@Pseudo
@Mixin(value = BlockIngredient.class, remap = false)
public class BlockIngredientMixin
{
    @Shadow @Final
    private List<TagKey<Block>> insertedTags;
    @Shadow @Final
    private boolean not;
    @Shadow @Final
    private List<PartialBlockState> insertedStates;

    @Overwrite
    public boolean test(BlockInWorld block)
    {
        if(block == null)
        {
            return false;
        }
            
        try
        {
            BlockState state = block.getState();
            if(state == null)
            {
                return false;
            }
                
            boolean hasTags = insertedTags != null && !insertedTags.isEmpty();

            if(not)
            {
                boolean noTagsMatch = hasTags && !hasMatchingTag(state);
                return noTagsMatch || !hasMatchingState(block);
            }

            return hasMatchingTag(state) || hasMatchingState(block);
        }
        catch(Exception e)
        {
            return false;
        }
    }

    @Unique
    private boolean hasMatchingTag(BlockState state)
    {
        if(insertedTags == null)
        {
            return false;
        }

        for(int i = 0; i < insertedTags.size(); i++)
        {
            TagKey<Block> tag = insertedTags.get(i);
            if(tag != null && state.is(tag))
            {
                return true;
            }
        }
        return false;
    }

    @Unique
    private boolean hasMatchingState(BlockInWorld block)
    {
        if(insertedStates == null)
        {
            return false;
        }
            
        for(int i = 0; i < insertedStates.size(); i++)
        {
            PartialBlockState state = insertedStates.get(i);
            if(state != null && state.test(block))
            {
                return true;
            }
        }
        return false;
    }
}
