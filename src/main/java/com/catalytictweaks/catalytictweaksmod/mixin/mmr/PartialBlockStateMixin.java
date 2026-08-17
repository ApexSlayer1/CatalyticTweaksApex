package com.catalytictweaks.catalytictweaksmod.mixin.mmr;

import com.google.common.collect.Lists;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import es.degrassi.mmreborn.api.PartialBlockState;
import es.degrassi.mmreborn.common.block.BlockController;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;
import java.util.Locale;

@SuppressWarnings("null")
@Mixin(value = PartialBlockState.class, remap = false)
public abstract class PartialBlockStateMixin
{

    @Shadow
    private BlockState blockState;

    @Shadow
    private List<Property<?>> properties;

    @Shadow
    private CompoundTag nbt;

	private PartialBlockState self()
    {
        return (PartialBlockState) (Object) this;
    }

    @Overwrite
    public PartialBlockState rotate(Rotation rotation)
    {
        if(this.properties.contains(BlockStateProperties.HORIZONTAL_FACING) && this.blockState.hasProperty(BlockStateProperties.HORIZONTAL_FACING) && !(this.blockState.getBlock() instanceof BlockController))
        {

            Direction currentDir = this.blockState.getValue(BlockStateProperties.HORIZONTAL_FACING);
            Direction newDir = rotation.rotate(currentDir);

            BlockState newBlockState = this.blockState.setValue(BlockStateProperties.HORIZONTAL_FACING, newDir);
            List<Property<?>> propertiesList = Lists.newArrayList(this.properties);

            if(!propertiesList.contains(BlockStateProperties.HORIZONTAL_FACING))
            {
                propertiesList.add(BlockStateProperties.HORIZONTAL_FACING);
            }

            return new PartialBlockState(newBlockState, propertiesList, this.nbt);

        }
        else if(this.properties.contains(BlockStateProperties.FACING) && this.blockState.hasProperty(BlockStateProperties.FACING) && !(this.blockState.getBlock() instanceof BlockController))
        {

            Direction currentDir = this.blockState.getValue(BlockStateProperties.FACING);

            if(currentDir.getAxis() == Direction.Axis.Y)
            {
                return self();
            }

            Direction newDir = rotation.rotate(currentDir);

            BlockState newBlockState = this.blockState.setValue(BlockStateProperties.FACING, newDir);
            List<Property<?>> propertiesList = Lists.newArrayList(this.properties);

            if(!propertiesList.contains(BlockStateProperties.FACING))
            {
                propertiesList.add(BlockStateProperties.FACING);
            }

            return new PartialBlockState(newBlockState, propertiesList, this.nbt);
        }
        else
        {
            return self();
        }
    }

    @Overwrite
    public static PartialBlockState of(String s) throws CommandSyntaxException
    {
        s = s.replaceAll("\\+", ",");
        s = sanitizeBlockStateString(s);
        BlockStateParser.BlockResult result = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), s, true);
        return new PartialBlockState(result.blockState(), Lists.newArrayList(result.properties().keySet()), result.nbt());
    }

    @Unique
    private static String sanitizeBlockStateString(String input)
    {
        if(input == null || !input.contains("["))
        {
            return input;
        }

        int start = input.indexOf('[');
        int end = input.lastIndexOf(']');

        if(start != -1 && end > start)
        {
            String blockId = input.substring(0, start);
            String properties = input.substring(start + 1, end);
            String extra = input.substring(end + 1);

            StringBuilder fixedProps = new StringBuilder();
            String[] pairs = properties.split(",");

            for(int i = 0; i < pairs.length; i++)
            {
                String pair = pairs[i];
                int eqIndex = pair.indexOf('=');
                if(eqIndex != -1)
                {
                    String key = pair.substring(0, eqIndex);
                    String val = pair.substring(eqIndex + 1);
                    fixedProps.append(key).append('=').append(val.toLowerCase(Locale.ROOT));
                }
                else
                {
                    fixedProps.append(pair);
                }

                if(i < pairs.length - 1)
                {
                    fixedProps.append(',');
                }
            }
            return blockId + "[" + fixedProps + "]" + extra;
        }

        return input;
    }
}

/*
public PartialBlockState rotate(Rotation rotation) {
    if (this.properties.contains(BlockStateProperties.HORIZONTAL_FACING) && this.blockState.hasProperty(BlockStateProperties.HORIZONTAL_FACING) && !(this.blockState.getBlock() instanceof BlockController)) {
      AtomicReference<Direction> direction = new AtomicReference<>(this.blockState.getValue(BlockStateProperties.HORIZONTAL_FACING));
      this.blockState.getBlockHolder().unwrapKey().ifPresent(key -> { //fuera filtro
        if (!key.location().getNamespace().toLowerCase(Locale.ROOT).equals("minecraft")) {
          direction.set(rotation.rotate(direction.get()));
        }
      });
      direction.set(rotation.rotate(direction.get()));
      BlockState blockState = this.blockState.setValue(BlockStateProperties.HORIZONTAL_FACING, direction.get());
      List<Property<?>> properties = Lists.newArrayList(this.properties);
      if (!properties.contains(BlockStateProperties.HORIZONTAL_FACING))
        properties.add(BlockStateProperties.HORIZONTAL_FACING);
      return new PartialBlockState(blockState, properties, this.nbt);
    } else if (this.properties.contains(BlockStateProperties.FACING) && this.blockState.hasProperty(BlockStateProperties.FACING) && !(this.blockState.getBlock() instanceof BlockController)) {
      AtomicReference<Direction> direction = new AtomicReference<>(this.blockState.getValue(BlockStateProperties.FACING));
      if (direction.get().getAxis() == Direction.Axis.Y)
        return this;
      this.blockState.getBlockHolder().unwrapKey().ifPresent(key -> {
        if (!key.location().getNamespace().toLowerCase(Locale.ROOT).equals("minecraft")) {
          direction.set(rotation.rotate(direction.get()));
        }
      });
      direction.set(rotation.rotate(direction.get()));
      BlockState blockState = this.blockState.setValue(BlockStateProperties.FACING, direction.get());
      List<Property<?>> properties = Lists.newArrayList(this.properties);
      if (!properties.contains(BlockStateProperties.FACING))
*/