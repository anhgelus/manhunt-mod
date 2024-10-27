package world.anhgelus.manhunt;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import eu.midnightdust.lib.config.MidnightConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.command.EntitySelector;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.LodestoneTrackerComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PiglinBruteEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
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
	private final Map<UUID, UUID> trackedMap = new HashMap<>();

	private final Timer timer = new Timer();

	private enum State {
		ON, OFF
	}

	private State state = State.OFF;

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Manhunt");
		MidnightConfig.init(MOD_ID, Config.class);
		LOGGER.info(Config.secondsBeforeRelease);
		LOGGER.info(Config.updateCompassEach);

		final LiteralArgumentBuilder<ServerCommandSource> command = literal("manhunt");

		final LiteralArgumentBuilder<ServerCommandSource> track = literal("track");
		track.then(RequiredArgumentBuilder.<ServerCommandSource, EntitySelector>argument("player", EntityArgumentType.player())
				.executes(context -> {
					final var source = context.getSource();
					final var tracked = (ServerPlayerEntity) EntityArgumentType.getEntity(context, "player");
					assert tracked.getDisplayName() != null;
					final var player = source.getPlayer();
					if (player == null) return 2;
					final var uuid = player.getUuid();
					if (trackedMap.get(uuid) != null && trackedMap.get(uuid) == tracked.getUuid()) {
						source.sendFeedback(() -> Text.literal("Already tracking "+tracked.getDisplayName().getString()), false);
						return Command.SINGLE_SUCCESS;
					}
					trackedMap.put(player.getUuid(), tracked.getUuid());
					updateCompass(player, tracked);
					source.sendFeedback(() -> Text.literal("Tracking "+tracked.getDisplayName().getString()), false);
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
		start.requires(source -> source.hasPermissionLevel(2));
		start.executes(context -> {
			if (state == State.ON) {
				context.getSource().sendFeedback(() -> Text.literal("Cannot start a manhunt if one is already started!"), false);
				return Command.SINGLE_SUCCESS;
			}
			final PlayerManager pm = context.getSource().getServer().getPlayerManager();
			for (final ServerPlayerEntity player : pm.getPlayerList()) {
				player.setHealth(player.getMaxHealth());
				player.setExperienceLevel(0);
				player.getInventory().clear();
				player.getHungerManager().eat(
						new FoodComponent(20, 20.0f, true)
				);
				final var spawn = player.getServerWorld().getSpawnPos();
				player.teleport(spawn.getX(), spawn.getY(), spawn.getZ(), false);
			}
			state = State.ON;
			for (final UUID uuid : hunters) {
				final var hunter = pm.getPlayer(uuid);
				assert hunter != null;
				final var isACompass = new ItemStack(Items.COMPASS);
				hunter.giveItemStack(isACompass);
				hunter.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, Config.secondsBeforeRelease*20, 255, false, false));
				var attr = hunter.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
				if (attr != null) {
					final var modifier = new EntityAttributeModifier(
							Identifier.of("manhunt.speed"),
							-1,
							EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE
					);
					attr.addTemporaryModifier(modifier);
				}
				attr = hunter.getAttributeInstance(EntityAttributes.GRAVITY);
				if (attr != null) {
					final var modifier = new EntityAttributeModifier(
							Identifier.of("manhunt.gravity"),
							5,
							EntityAttributeModifier.Operation.ADD_VALUE
					);
					attr.addTemporaryModifier(modifier);
				}
                LOGGER.info("Added modifiers to {}", hunter.getDisplayName());
			}
			timer.schedule(new TimerTask() {
				@Override
				public void run() {
					LOGGER.info("Removing modifier to hunters");
					for (final UUID uuid : hunters) {
						final var hunter = pm.getPlayer(uuid);
						assert hunter != null;
						var attr = hunter.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
						if (attr != null) {
							attr.removeModifier(Identifier.of("manhunt.speed"));
						}
						attr = hunter.getAttributeInstance(EntityAttributes.GRAVITY);
						if (attr != null) {
							attr.removeModifier(Identifier.of("manhunt.gravity"));
						}
					}
				}
			}, Config.secondsBeforeRelease*1000L);
			setTimer(pm);
			context.getSource().sendFeedback(() -> Text.literal("Game started!"), true);
			return Command.SINGLE_SUCCESS;
		});

		final LiteralArgumentBuilder<ServerCommandSource> resetTimer = literal("reset-timer");
		resetTimer.requires(source -> source.hasPermissionLevel(2));
		resetTimer.executes(context -> {
			state = State.ON;
			setTimer(context.getSource().getServer().getPlayerManager());
			context.getSource().sendFeedback(() -> Text.literal("Timer reset"), true);
			return Command.SINGLE_SUCCESS;
		});

		command.then(track);
		command.then(team);
		command.then(start);
		command.then(resetTimer);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(command));

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (state == State.OFF) return;
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
			}
			state = State.OFF;
			timer.cancel();
		});

		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (!(entity instanceof PiglinBruteEntity)) return;
			EntityType.PIGLIN.spawn(world, entity.getBlockPos(), SpawnReason.MOB_SUMMONED);
			entity.discard();
		});
	}

	private void setTimer(PlayerManager pm) {
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				for (final UUID uuid : hunters) {
					final ServerPlayerEntity hunter = pm.getPlayer(uuid);
					if (hunter == null) continue;
					final ServerPlayerEntity tracked = pm.getPlayer(trackedMap.get(uuid));
					if (tracked == null) continue;
					updateCompass(hunter, tracked);
				}
			}
		}, Config.secondsBeforeRelease*1000L, Config.updateCompassEach*1000L);
	}

	private void updateCompass(ServerPlayerEntity player, ServerPlayerEntity tracked) {
		final var trackerCpnt = new LodestoneTrackerComponent(Optional.of(GlobalPos.create(tracked.getWorld().getRegistryKey(), tracked.getBlockPos())), true);
		LOGGER.info(tracked.getWorld().getRegistryKey().toString());
		LOGGER.info(tracked.getBlockPos().toString());
		LOGGER.info(trackerCpnt.toString());
		ItemStack is = null;
		int slot = PlayerInventory.NOT_FOUND;
		final var inv = player.getInventory();
		if (inv.getMainHandStack().isOf(Items.COMPASS)) {
			is = inv.getMainHandStack();
			slot = inv.getSlotWithStack(is);
		} else if (inv.getStack(PlayerInventory.OFF_HAND_SLOT).isOf(Items.COMPASS)) {
			is = inv.getStack(PlayerInventory.OFF_HAND_SLOT);
			slot = PlayerInventory.OFF_HAND_SLOT;
		} else {
			for (int i = 0; i < PlayerInventory.MAIN_SIZE && is == null; i++) {
				final var stack = inv.getStack(i);
				if (stack.isOf(Items.COMPASS)) {
					is = stack;
					slot = i;
				}
			}
		}
		if (is == null) {
			LOGGER.warn("Compass item is null");
			is = new ItemStack(Items.COMPASS);
		}
		is.set(DataComponentTypes.LODESTONE_TRACKER, trackerCpnt);
		if (slot == PlayerInventory.NOT_FOUND) {
			player.giveItemStack(is);
			return;
		}
		inv.setStack(slot, is);
	}
}