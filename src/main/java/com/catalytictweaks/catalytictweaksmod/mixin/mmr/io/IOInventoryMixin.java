package com.catalytictweaks.catalytictweaksmod.mixin.mmr.io;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import net.minecraft.core.Direction;

import es.degrassi.mmreborn.common.util.IOInventory;
import es.degrassi.mmreborn.common.util.IOInventory.IOInventoryChangedListener;
import es.degrassi.mmreborn.common.util.ItemSlot;

@Mixin(IOInventory.class)
public abstract class IOInventoryMixin {

     /**
      * @author
      * @reason
      */
     @Overwrite
     public static IOInventory mergeBuild(IOInventory... inventories)
     {
         int totalSlotsCount = 0;
         int totalInputsCount = 0;
         int totalOutputsCount = 0;

         int totalInSize = 0;
         int totalOutSize = 0;
         int totalMiscSize = 0;
         int totalStackLimit = 0;

         for(IOInventory inv : inventories)
         {
             IOInventoryAccessor access = (IOInventoryAccessor) inv;
             totalSlotsCount += inv.getInventory().size();
             totalInputsCount += inv.getInputs().size();
             totalOutputsCount += inv.getOutputs().size();
             totalInSize += access.getInSlots().length;
             totalOutSize += access.getOutSlots().length;
             totalMiscSize += access.getMiscSlots().length;
             totalStackLimit += inv.getSlotLimit();
         }

         IOInventory merged = IOInventoryAccessor.create(totalSlotsCount);
         IOInventoryAccessor mergedAccess = (IOInventoryAccessor) merged;

         List<ItemSlot> mergedInventory = merged.getInventory();
         List<ItemSlot> mergedInputs = merged.getInputs();
         List<ItemSlot> mergedOutputs = merged.getOutputs();

         if(mergedInventory instanceof ArrayList) {
             ((ArrayList<?>) mergedInventory).ensureCapacity(totalSlotsCount);
         }
         if (mergedInputs instanceof ArrayList) {
             ((ArrayList<?>) mergedInputs).ensureCapacity(totalInputsCount);
         }
         if (mergedOutputs instanceof ArrayList) {
             ((ArrayList<?>) mergedOutputs).ensureCapacity(totalOutputsCount);
         }

         int[] mergedInSlots = new int[totalInSize];
         int[] mergedOutSlots = new int[totalOutSize];
         int[] mergedMiscSlots = new int[totalMiscSize];

         int[] slotOwnerIndex = new int[totalSlotsCount];
         int[] offsets = new int[inventories.length];
         int commonSidesMask = 0x3F;

         int currentSlotOffset = 0;
         int inIndex = 0;
         int outIndex = 0;
         int miscIndex = 0;

         for(int i = 0; i < inventories.length; i++)
         {
             IOInventory inv = inventories[i];
             IOInventoryAccessor access = (IOInventoryAccessor) inv;
             int invSize = inv.getInventory().size();

             offsets[i] = currentSlotOffset;
             Arrays.fill(slotOwnerIndex, currentSlotOffset, currentSlotOffset + invSize, i);

             mergedInventory.addAll(inv.getInventory());
             mergedInputs.addAll(inv.getInputs());
             mergedOutputs.addAll(inv.getOutputs());

             int[] srcIn = access.getInSlots();
             for(int val : srcIn) mergedInSlots[inIndex++] = val + currentSlotOffset;

             int[] srcOut = access.getOutSlots();
             for(int val : srcOut) mergedOutSlots[outIndex++] = val + currentSlotOffset;

             int[] srcMisc = access.getMiscSlots();
             for(int val : srcMisc) mergedMiscSlots[miscIndex++] = val + currentSlotOffset;

             int currentInvMask = 0;
             for(Direction d : access.getAccessibleSides())
             {
                 currentInvMask |= (1 << d.ordinal());
             }
             commonSidesMask &= currentInvMask;

             currentSlotOffset += invSize;
         }

         List<Direction> finalSides = new ArrayList<>(6);
         for(Direction d : Direction.values())
         {
             if((commonSidesMask & (1 << d.ordinal())) != 0)
             {
                 finalSides.add(d);
             }
         }

         mergedAccess.setInSlots(mergedInSlots);
         mergedAccess.setOutSlots(mergedOutSlots);
         mergedAccess.setMiscSlots(mergedMiscSlots);
         mergedAccess.setAccessibleSides(finalSides);
         mergedAccess.setSlotLimitField(totalStackLimit);

         merged.setListener((slot, stack) -> {
             if (slot < 0 || slot >= slotOwnerIndex.length) return;
             int ownerInvIndex = slotOwnerIndex[slot];
             IOInventory targetInv = inventories[ownerInvIndex];
             IOInventoryChangedListener subListener = ((IOInventoryAccessor) targetInv).getListener();
             if(subListener != null)
             {
                 subListener.onChange(slot - offsets[ownerInvIndex], stack);
             }
         });

         return merged;
     }
}