package world.anhgelus.manhunt;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.serialization.DataResult;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.command.EntitySelector;
import net.minecraft.command.arguments.EntityArgumentType;
import net.minecraft.datafixer.NbtOps;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;

public class Manhunt implements ModInitializer {
	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
    public static final Logger LOGGER = LogManager.getLogger("manhunt");
    public static final String MOD_ID = "manhunt";

	private final Set<ServerPlayerEntity> hunters = new HashSet<>();
	private final Set<ServerPlayerEntity> speedrunners = new HashSet<>();

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Manhunt");

		final LiteralArgumentBuilder<ServerCommandSource> command = literal("manhunt");

		final LiteralArgumentBuilder<ServerCommandSource> track = literal("track");
		track.then(RequiredArgumentBuilder.<ServerCommandSource, EntitySelector>argument("player", EntityArgumentType.player())
				.executes(context -> {
					final ServerCommandSource source = context.getSource();
					final ServerPlayerEntity tracked = (ServerPlayerEntity) EntityArgumentType.getEntity(context, "player");
					final ServerPlayerEntity player = source.getPlayer();
					updateCompass(player, tracked);
					return Command.SINGLE_SUCCESS;
				})
		);
		final LiteralArgumentBuilder<ServerCommandSource> team = literal("team");
		final RequiredArgumentBuilder<ServerCommandSource, EntitySelector> teamP = RequiredArgumentBuilder.argument("player", EntityArgumentType.player());
		teamP.then(LiteralArgumentBuilder.<ServerCommandSource>literal("hunter").executes(context -> {
					final ServerPlayerEntity p = (ServerPlayerEntity) EntityArgumentType.getEntity(context, "player");
                    speedrunners.remove(p);
					hunters.add(p);
					return Command.SINGLE_SUCCESS;
				}))
				.then(LiteralArgumentBuilder.<ServerCommandSource>literal("speedrunner").executes(context -> {
					final ServerPlayerEntity p = (ServerPlayerEntity) EntityArgumentType.getEntity(context, "player");
					hunters.remove(p);
					speedrunners.add(p);
					return Command.SINGLE_SUCCESS;
				}));
		team.then(teamP);
		final LiteralArgumentBuilder<ServerCommandSource> start = literal("start");
		start.executes(context -> {
			for (final ServerPlayerEntity hunter : hunters) {
				hunter.giveItemStack(new ItemStack(Items.COMPASS));
				hunter.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 30, 255));
			}
			final MinecraftServer server = context.getSource().getMinecraftServer();
			for (final ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				player.kill();
			}
			return Command.SINGLE_SUCCESS;
		});

		command.then(track);
		command.then(team);
		command.then(start);

		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> dispatcher.register(command));
	}

	private void updateCompass(ServerPlayerEntity player, ServerPlayerEntity tracked) {
		final ItemStack stack = new ItemStack(Items.COMPASS);
		assert stack.getTag() != null;
		final CompoundTag tag = stack.getTag();
		tag.put("LodestonePos", NbtHelper.fromBlockPos(tracked.getBlockPos()));
		final DataResult<Tag> weird = World.CODEC.encodeStart(NbtOps.INSTANCE, player.world.getRegistryKey());
		weird.resultOrPartial(LOGGER::error).ifPresent((t) -> {
			tag.put("LodestoneDimension", t);
		});
		tag.putBoolean("LodestoneTracked", true);
		final int slot = player.inventory.getSlotWithStack(new ItemStack(Items.COMPASS));
		player.inventory.insertStack(slot, stack);
	}
}