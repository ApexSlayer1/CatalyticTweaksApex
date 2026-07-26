package com.catalytictweaks.catalytictweaksmod.emi;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.EmiExclusionArea;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.EmiStackProvider;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeDecorator;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.emi.emi.api.stack.Comparison;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.serializer.EmiIngredientSerializer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.RecipeManager;

public class ConcurrentEmiRegistry implements EmiRegistry
{
    private final EmiRegistry delegate;
    private final ConcurrentLinkedQueue<Consumer<EmiRegistry>> actions = new ConcurrentLinkedQueue<>();
    private volatile boolean buffering = true;

    public ConcurrentEmiRegistry(EmiRegistry delegate)
    {
        this.delegate = delegate;
    }

    public void flushTo(EmiRegistry target)
    {
        this.buffering = false;
        Consumer<EmiRegistry> action;
        while((action = actions.poll()) != null)
        {
            action.accept(target);
        }
    }

    public EmiRegistry getRegistry()
    {
        return this;
    }

    @Override
    public void addCategory(EmiRecipeCategory category)
    {
        if(buffering)
        {
            actions.add(r -> r.addCategory(category));
        }
        else
        {
            delegate.addCategory(category);
        }
    }

    @Override
    public void addWorkstation(EmiRecipeCategory category, EmiIngredient workstation)
    {
        if(buffering)
        {
            actions.add(r -> r.addWorkstation(category, workstation));
        }
        else
        {
            delegate.addWorkstation(category, workstation);
        }
    }

    @Override
    public void addRecipe(EmiRecipe recipe)
    {
        if(buffering)
        {
            actions.add(r -> r.addRecipe(recipe));
        }
        else
        {
            delegate.addRecipe(recipe);
        }
    }

    @Override
    public void addAlias(EmiIngredient ingredient, Component alias)
    {
        if(buffering)
        {
            actions.add(r -> r.addAlias(ingredient, alias));
        }
        else
        {
            delegate.addAlias(ingredient, alias);
        }
    }

    @Override
    public void addDeferredRecipes(Consumer<Consumer<EmiRecipe>> recipes)
    {
        if(buffering)
        {
            actions.add(r -> r.addDeferredRecipes(recipes));
        }
        else
        {
            delegate.addDeferredRecipes(recipes);
        }
    }

    @Override
    public <T extends Screen> void addDragDropHandler(Class<T> screenClass, EmiDragDropHandler<T> handler)
    {
        if(buffering)
        {
            actions.add(r -> r.addDragDropHandler(screenClass, handler));
        }
        else
        {
            delegate.addDragDropHandler(screenClass, handler);
        }
    }

    @Override
    public void addEmiStack(EmiStack stack)
    {
        if(buffering)
        {
            actions.add(r -> r.addEmiStack(stack));
        }
        else
        {
            delegate.addEmiStack(stack);
        }
    }

    @Override
    public void addEmiStackAfter(EmiStack stack, Predicate<EmiStack> predicate)
    {
        if(buffering)
        {
            actions.add(r -> r.addEmiStackAfter(stack, predicate));
        }
        else
        {
            delegate.addEmiStackAfter(stack, predicate);
        }
    }

    @Override
    public <T extends Screen> void addExclusionArea(Class<T> screenClass, EmiExclusionArea<T> exclusionArea)
    {
        if(buffering)
        {
            actions.add(r -> r.addExclusionArea(screenClass, exclusionArea));
        }
        else
        {
            delegate.addExclusionArea(screenClass, exclusionArea);
        }
    }

    @Override
    public void addGenericDragDropHandler(EmiDragDropHandler<Screen> handler)
    {
        if(buffering)
        {
            actions.add(r -> r.addGenericDragDropHandler(handler));
        }
        else
        {
            delegate.addGenericDragDropHandler(handler);
        }
    }

    @Override
    public void addGenericExclusionArea(EmiExclusionArea<Screen> exclusionArea)
    {
        if(buffering)
        {
            actions.add(r -> r.addGenericExclusionArea(exclusionArea));
        }
        else
        {
            delegate.addGenericExclusionArea(exclusionArea);
        }
    }

    @Override
    public void addGenericStackProvider(EmiStackProvider<Screen> provider)
    {
        if(buffering)
        {
            actions.add(r -> r.addGenericStackProvider(provider));
        }
        else
        {
            delegate.addGenericStackProvider(provider);
        }
    }

    @Override
    @Deprecated
    public <T extends EmiIngredient> void addIngredientSerializer(Class<T> clazz, EmiIngredientSerializer<T> serializer)
    {
        if(buffering)
        {
            actions.add(r -> r.addIngredientSerializer(clazz, serializer));
        }
        else
        {
            delegate.addIngredientSerializer(clazz, serializer);
        }
    }

    @Override
    public void addRecipeDecorator(EmiRecipeDecorator decorator)
    {
        if(buffering)
        {
            actions.add(r -> r.addRecipeDecorator(decorator));
        }
        else
        {
            delegate.addRecipeDecorator(decorator);
        }
    }

    @Override
    public <T extends AbstractContainerMenu> void addRecipeHandler(MenuType<T> type, EmiRecipeHandler<T> handler)
    {
        if(buffering)
        {
            actions.add(r -> r.addRecipeHandler(type, handler));
        }
        else
        {
            delegate.addRecipeHandler(type, handler);
        }
    }

    @Override
    public <T extends Screen> void addStackProvider(Class<T> screenClass, EmiStackProvider<T> provider)
    {
        if(buffering)
        {
            actions.add(r -> r.addStackProvider(screenClass, provider));
        }
        else
        {
            delegate.addStackProvider(screenClass, provider);
        }
    }

    @Override
    public RecipeManager getRecipeManager()
    {
        return delegate.getRecipeManager();
    }

    @Override
    public boolean isStackDisabled(EmiIngredient stack)
    {
        return delegate.isStackDisabled(stack);
    }

    @Override
    public void removeEmiStacks(Predicate<EmiStack> predicate)
    {
        if(buffering)
        {
            actions.add(r -> r.removeEmiStacks(predicate));
        }
        else
        {
            delegate.removeEmiStacks(predicate);
        }
    }

    @Override
    public void removeRecipes(Predicate<EmiRecipe> predicate)
    {
        if(buffering)
        {
            actions.add(r -> r.removeRecipes(predicate));
        }
        else
        {
            delegate.removeRecipes(predicate);
        }
    }

    @Override
    public void setDefaultComparison(Object stack, Function<Comparison, Comparison> comparison)
    {
        if(buffering)
        {
            actions.add(r -> r.setDefaultComparison(stack, comparison));
        }
        else
        {
            delegate.setDefaultComparison(stack, comparison);
        }
    }
}