package world.anhgelus.manhunt;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.serialization.DataResult;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.command.EntitySelector;
import net.minecraft.command.arguments.EntityArgumentType;
import net.minecraft.datafixer.NbtOps;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.Tag;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;

public class Manhunt implements ModInitializer {
	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
    public static final Logger LOGGER = LogManager.getLogger("manhunt");
    public static final String MOD_ID = "manhunt";

	private final Set<UUID> hunters = new HashSet<>();
	private final Set<UUID> speedrunners = new HashSet<>();

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
                    speedrunners.remove(p.getUuid());
					hunters.add(p.getUuid());
					return Command.SINGLE_SUCCESS;
				}))
				.then(LiteralArgumentBuilder.<ServerCommandSource>literal("speedrunner").executes(context -> {
					final ServerPlayerEntity p = (ServerPlayerEntity) EntityArgumentType.getEntity(context, "player");
					hunters.remove(p.getUuid());
					speedrunners.add(p.getUuid());
					return Command.SINGLE_SUCCESS;
				}));
		team.then(teamP);
		final LiteralArgumentBuilder<ServerCommandSource> start = literal("start");
		start.executes(context -> {
			final PlayerManager pm = context.getSource().getMinecraftServer().getPlayerManager();
			for (final UUID uuid : hunters) {
				final ServerPlayerEntity hunter = pm.getPlayer(uuid);
                assert hunter != null;
                hunter.giveItemStack(new ItemStack(Items.COMPASS));
				hunter.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 30, 255));
			}
			for (final ServerPlayerEntity player : pm.getPlayerList()) {
				player.kill();
			}
			return Command.SINGLE_SUCCESS;
		});

		command.then(track);
		command.then(team);
		command.then(start);

		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> dispatcher.register(command));

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (alive) return;
			final UUID uuid = oldPlayer.getUuid();
			if (!speedrunners.contains(uuid)) return;
			speedrunners.remove(uuid);
			if (!speedrunners.isEmpty()) return;
			for (final ServerPlayerEntity player : newPlayer.server.getPlayerManager().getPlayerList()) {
				player.setGameMode(GameMode.SPECTATOR);
				hunters.remove(player.getUuid());
				speedrunners.remove(player.getUuid());
			}
		});
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