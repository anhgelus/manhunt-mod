package world.anhgelus.manhunt;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.command.EntitySelector;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.FoodComponents;
import net.minecraft.component.type.LodestoneTrackerComponent;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.PiglinBruteEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.GameMode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;

public class Manhunt implements ModInitializer {
	public static final String MOD_ID = "manhunt";
	public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

	private final Set<UUID> hunters = new HashSet<>();
	private final Set<UUID> speedrunners = new HashSet<>();
	private final Map<UUID, UUID> map = new HashMap<>();
	private final Map<UUID, ItemStack> compassMap = new HashMap<>();

	private final Timer timer = new Timer();

	private enum GameState {
		ON, OFF
	}

	private GameState state = GameState.OFF;

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Manhunt");

		final LiteralArgumentBuilder<ServerCommandSource> command = literal("manhunt");

		final LiteralArgumentBuilder<ServerCommandSource> track = literal("track");
		track.then(RequiredArgumentBuilder.<ServerCommandSource, EntitySelector>argument("player", EntityArgumentType.player())
				.executes(context -> {
					final var source = context.getSource();
					final var tracked = (ServerPlayerEntity) EntityArgumentType.getEntity(context, "player");
					final var player = source.getPlayer();
					if (player == null) return 2;
					map.put(source.getPlayer().getUuid(), tracked.getUuid());
					updateCompass(player, tracked);
					assert tracked.getDisplayName() != null;
					context.getSource().sendFeedback(() -> Text.literal("Tracking "+tracked.getDisplayName().getString()), false);
					return Command.SINGLE_SUCCESS;
				})
		);
		final LiteralArgumentBuilder<ServerCommandSource> team = literal("team");
		final RequiredArgumentBuilder<ServerCommandSource, EntitySelector> teamP = RequiredArgumentBuilder.argument("player", EntityArgumentType.player());
		teamP.then(LiteralArgumentBuilder.<ServerCommandSource>literal("hunter").executes(context -> {
					final var p = (ServerPlayerEntity) EntityArgumentType.getEntity(context, "player");
                    speedrunners.remove(p.getUuid());
					hunters.add(p.getUuid());
					assert p.getDisplayName() != null;
					context.getSource().sendFeedback(() -> Text.literal(p.getDisplayName().getString()+" added to hunter"), true);
					return Command.SINGLE_SUCCESS;
				}))
				.then(LiteralArgumentBuilder.<ServerCommandSource>literal("speedrunner").executes(context -> {
					final var p = (ServerPlayerEntity) EntityArgumentType.getEntity(context, "player");
					hunters.remove(p.getUuid());
					speedrunners.add(p.getUuid());
					assert p.getDisplayName() != null;
					context.getSource().sendFeedback(() -> Text.literal(p.getDisplayName().getString()+" added to speedrunner"), true);
					return Command.SINGLE_SUCCESS;
				}));
		team.then(teamP);
		final LiteralArgumentBuilder<ServerCommandSource> start = literal("start");
		start.executes(context -> {
			if (state == GameState.ON) {
				context.getSource().sendFeedback(() -> Text.literal("Cannot start a manhunt if one is already started!"), false);
				return Command.SINGLE_SUCCESS;
			}
			final PlayerManager pm = context.getSource().getServer().getPlayerManager();
			for (final ServerPlayerEntity player : pm.getPlayerList()) {
				player.setHealth(player.getMaxHealth());
				player.setExperienceLevel(0);
				player.getInventory().clear();
				player.getHungerManager().eat(
						new FoodComponent(20, 20.0f, true, 0f, Optional.empty(), new ArrayList<>())
				);
				final var spawn = player.getServerWorld().getSpawnPos();
				player.teleport(player.getServerWorld(), spawn.getX(), spawn.getY(), spawn.getZ(), 0f, 0f);
			}
			state = GameState.ON;
			for (final UUID uuid : hunters) {
				final var hunter = pm.getPlayer(uuid);
				assert hunter != null;
				final var isACompass = new ItemStack(Items.COMPASS);
				compassMap.put(hunter.getUuid(), isACompass);
				hunter.giveItemStack(isACompass);
				hunter.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 30, 255));
				hunter.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 30, 255));
			}
			timer.schedule(new TimerTask() {
				@Override
				public void run() {
					for (final UUID uuid : hunters) {
						final ServerPlayerEntity hunter = pm.getPlayer(uuid);
						if (hunter == null) continue;
						final ServerPlayerEntity tracked = pm.getPlayer(map.get(uuid));
						if (tracked == null) continue;
						updateCompass(hunter, tracked);
					}
				}
			}, 60*1000, 60*1000);
			context.getSource().sendFeedback(() -> Text.literal("Game started!"), true);
			return Command.SINGLE_SUCCESS;
		});

		command.then(track);
		command.then(team);
		command.then(start);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(command));

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (state == GameState.OFF) return;
			final var uuid = oldPlayer.getUuid();
			if (hunters.contains(uuid)) {
				newPlayer.giveItemStack(new ItemStack(Items.COMPASS));
				return;
			}
			speedrunners.remove(uuid);
			newPlayer.changeGameMode(GameMode.SPECTATOR);
			if (!speedrunners.isEmpty()) return;
			for (final ServerPlayerEntity player : newPlayer.server.getPlayerManager().getPlayerList()) {
				player.changeGameMode(GameMode.SPECTATOR);
				hunters.remove(player.getUuid());
				speedrunners.remove(player.getUuid());
				state = GameState.OFF;
			}
		});

		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof PiglinBruteEntity) entity.discard();
		});
	}

	private void updateCompass(ServerPlayerEntity player, ServerPlayerEntity tracked) {
		final var trackerCpnt = new LodestoneTrackerComponent(Optional.of(GlobalPos.create(tracked.getWorld().getRegistryKey(), tracked.getBlockPos())), true);
		final var is = compassMap.get(player.getUuid());
		if (is == null) {
			LOGGER.warn("Compass item is null");
			return;
		}
		final int slot = player.getInventory().getSlotWithStack(is);
		if (slot == -1) {
			LOGGER.warn("ItemStack not found");
			return;
		}
		is.set(DataComponentTypes.LODESTONE_TRACKER, trackerCpnt);
		player.getInventory().setStack(slot, is);
	}
}