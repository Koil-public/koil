package com.spirit.mixin.client.nbt;

import com.spirit.Client;
import com.spirit.koil.api.dev.TooltipChanger;
import com.spirit.koil.api.stats.global.KoilPhysicalMomensCurrency;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;

@Mixin(ItemStack.class)
public abstract class Tooltip {

	@Inject(method = "getTooltip", at = @At("RETURN"), cancellable = true)
	protected void injectEditTooltipMethod(PlayerEntity player, TooltipContext context, CallbackInfoReturnable<ArrayList<Text>> info) {
		int code = InputUtil.fromTranslationKey(Client.KOIL_UTIL_NBT_KEY.getBoundKeyTranslationKey()).getCode();
		boolean isKeybindPressed = InputUtil.isKeyPressed(MinecraftClient.getInstance().getWindow().getHandle(), code);
		ItemStack itemStack = (ItemStack) (Object) this;
		ArrayList<Text> list = info.getReturnValue();

		if (KoilPhysicalMomensCurrency.isMomens(itemStack) && !koil$hasMomensPortfolio(list)) {
			list.addAll(KoilPhysicalMomensCurrency.sourceTooltip(itemStack));
		}

		if (context.isAdvanced() && isKeybindPressed) {
			if (itemStack.hasNbt()) {
				info.setReturnValue(TooltipChanger.Main(itemStack, list));
			}
		}
	}

	private boolean koil$hasMomensPortfolio(ArrayList<Text> list) {
		for (Text line : list) {
			String value = line.getString();

			if (value.contains("Momens Market Note") || value.contains("Momens Portfolio") || value.contains("MOMENS PORTFOLIO")) {
				return true;
			}
		}

		return false;
	}
}
