package com.catalytictweaks.catalytictweaksmod.mixin.jei;

import dev.latvian.mods.rhino.NativeJavaMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;

@Pseudo
@Mixin(NativeJavaMap.class)
public class Entry
{

    @SuppressWarnings("rawtypes")
    @Redirect(
        method = "safeHas",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Map;containsKey(Ljava/lang/Object;)Z"),
            require = 0
    )
    private boolean contains(Map instance, Object o)
    {
        try
        {
            return instance.containsKey(o);
        }
        catch(Exception e)
        {
            return false;
        }
    }

    @SuppressWarnings("rawtypes")
    @Redirect(
        method = "get(Ldev/latvian/mods/rhino/Context;Ljava/lang/String;Ldev/latvian/mods/rhino/Scriptable;)Ljava/lang/Object;", 
        at = @At(value = "INVOKE",
        target = "Ljava/util/Map;containsKey(Ljava/lang/Object;)Z"), require = 0
    )
    private boolean contains2(Map instance, Object o)
    {
        try
        {
            return instance.containsKey(o);
        }
        catch(Exception e)
        {
            return false;
        }
    }
}