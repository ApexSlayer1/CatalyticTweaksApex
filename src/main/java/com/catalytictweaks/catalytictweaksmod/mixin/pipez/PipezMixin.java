package com.catalytictweaks.catalytictweaksmod.mixin;

import com.catalytictweaks.catalytictweaksmod.Config;
import com.mojang.logging.LogUtils;
import de.maxhenkel.pipez.Upgrade;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.lang.reflect.Field;

@Mixin(Upgrade.class)
public class PipezMixin {
    @Unique
    private static final Logger catalytic_Tweaks$LOGGER = LogUtils.getLogger();

    static {
        // Initial status, indicating configuration not loaded yet
        Config.setConfigChangeCallback(PipezMixin::catalytic_Tweaks$modifyEnum);
    }

    // Method to modify enums after confirming configuration is loaded
    @Unique
    private static void catalytic_Tweaks$modifyEnum() {
        catalytic_Tweaks$LOGGER.info("Modifying enum values after configuration is loaded.");
// makes the pipez config adjustable
        for (Upgrade upgrade : Upgrade.values()) {
            try {
                Field canChangeRedstoneModeField = Upgrade.class.getDeclaredField("canChangeRedstoneMode");
                Field canChangeFilterField = Upgrade.class.getDeclaredField("canChangeFilter");
                Field canChangeDistributionModeField = Upgrade.class.getDeclaredField("canChangeDistributionMode");

                canChangeRedstoneModeField.setAccessible(true);
                canChangeFilterField.setAccessible(true);
                canChangeDistributionModeField.setAccessible(true);
// changes the values with values stated in toml
                switch (upgrade) {
                    case BASIC:
                        canChangeRedstoneModeField.set(upgrade, Config.basicCanChangeRedstoneMode);
                        canChangeFilterField.set(upgrade, Config.basicCanChangeFilter);
                        canChangeDistributionModeField.set(upgrade, Config.basicCanChangeDistribution);
                        break;
                    case IMPROVED:
                        canChangeRedstoneModeField.set(upgrade, Config.improvedCanChangeRedstoneMode);
                        canChangeFilterField.set(upgrade, Config.improvedCanChangeFilter);
                        canChangeDistributionModeField.set(upgrade, Config.improvedCanChangeDistribution);
                        break;
                    case ADVANCED:
                        canChangeRedstoneModeField.set(upgrade, Config.advancedCanChangeRedstoneMode);
                        canChangeFilterField.set(upgrade, Config.advancedCanChangeFilter);
                        canChangeDistributionModeField.set(upgrade, Config.advancedCanChangeDistribution);
                        break;
                    case ULTIMATE:
                        canChangeRedstoneModeField.set(upgrade, Config.ultimateCanChangeRedstoneMode);
                        canChangeFilterField.set(upgrade, Config.ultimateCanChangeFilter);
                        canChangeDistributionModeField.set(upgrade, Config.ultimateCanChangeDistribution);
                        break;
                    case INFINITY:
                        canChangeRedstoneModeField.set(upgrade, Config.infinityCanChangeRedstoneMode);
                        canChangeFilterField.set(upgrade, Config.infinityCanChangeFilter);
                        canChangeDistributionModeField.set(upgrade, Config.infinityCanChangeDistribution);
                        break;
                }

                // Log the new values of fields
                catalytic_Tweaks$LOGGER.info("After modification - {}: RedstoneMode: {}, Filter: {}, Distribution: {}",
                        upgrade,
                        canChangeRedstoneModeField.get(upgrade),
                        canChangeFilterField.get(upgrade),
                        canChangeDistributionModeField.get(upgrade));

            } catch (NoSuchFieldException | IllegalAccessException e) {
                e.printStackTrace();
            }
        }
    }
}