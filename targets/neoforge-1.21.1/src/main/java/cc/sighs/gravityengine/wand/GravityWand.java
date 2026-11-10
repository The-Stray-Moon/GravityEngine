package cc.sighs.gravityengine.wand;

import cc.sighs.gravityengine.GravityEngine;
import cc.sighs.gravityengine.api.GravityEngineApi;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

public final class GravityWand {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(GravityEngine.MOD_ID);
    public static final DeferredItem<Item> ITEM = ITEMS.registerSimpleItem("gravity_wand",
            new Item.Properties().stacksTo(1).component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true));
    public static void register(IEventBus bus) {
        ITEMS.register(bus);
        bus.addListener((net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent e) -> {
            if(e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) e.accept(ITEM);
        });
        GravityEngineApi.registerFieldProvider(ResourceLocation.parse("gravityengine:wand"), WandFields::provider);
        bus.addListener(WandPackets::register);
    }
}
