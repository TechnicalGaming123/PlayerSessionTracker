package com.playtime_rem;

import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Mod("playersessiontracker")
public class PlaytimeRem {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FOLDER_NAME = "playersessiontracker";
    private static final String SESSIONS_FOLDER_NAME = "sessions";
    private static final ZoneId SERVER_ZONE = ZoneId.of("America/Los_Angeles");
    private static final long SEVEN_DAYS_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    private static final int TICKS_PER_MINUTE = 20 * 60;
    private static final int SESSIONS_PER_PAGE = 10;

    private int tickCounter = 0;

    private final Map<UUID, Long> joinTimestamps = new HashMap<>();
    private final Map<UUID, Long> liveSessionSeconds = new HashMap<>();
    private final Map<UUID, String> joinDateDisplay = new HashMap<>();

    private static class SessionEntry {
        long join;
        long leave;
        long durationSeconds;
        String displayDate;
        String serverTime;
    }

    private static class PlayerSessionsFile {
        String playerName;
        List<SessionEntry> sessions = new ArrayList<>();
    }

    public PlaytimeRem() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    // ---------- Events ----------

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        UUID uuid = player.getUUID();
        long now = System.currentTimeMillis();

        joinTimestamps.put(uuid, now);
        liveSessionSeconds.put(uuid, 0L);

        LocalDate date = Instant.ofEpochMilli(now).atZone(SERVER_ZONE).toLocalDate();
        String dateStr = date.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH));
        joinDateDisplay.put(uuid, dateStr);
    }

    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        UUID uuid = player.getUUID();
        long now = System.currentTimeMillis();

        Long join = joinTimestamps.remove(uuid);
        liveSessionSeconds.remove(uuid);
        String dateStr = joinDateDisplay.remove(uuid);

        if (join == null || dateStr == null) return;

        long durationSeconds = Math.max(0L, (now - join) / 1000L);

        LocalTime serverTime = Instant.ofEpochMilli(join).atZone(SERVER_ZONE).toLocalTime();
        String serverTimeStr = serverTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)) + " (PT)";

        SessionEntry entry = new SessionEntry();
        entry.join = join;
        entry.leave = now;
        entry.durationSeconds = durationSeconds;
        entry.displayDate = dateStr;
        entry.serverTime = serverTimeStr;

        MinecraftServer server = player.getServer();
        if (server != null) {
            saveSession(server, uuid, player.getGameProfile().getName(), entry);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        tickCounter++;
        if (tickCounter < TICKS_PER_MINUTE) return;
        tickCounter = 0;

        MinecraftServer server = event.getServer();
        if (server == null) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            liveSessionSeconds.merge(uuid, 60L, Long::sum);
        }
    }

    // ---------- Command registration ----------

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("session")

                        // /session -> same as /session check <self>
                        .executes(ctx -> {
                            ServerPlayer self = ctx.getSource().getPlayer();
                            if (self == null) {
                                ctx.getSource().sendFailure(Component.literal("This command can only be run by a player."));
                                return 0;
                            }
                            return executeOfflineCheck(ctx.getSource(), self.getGameProfile().getName(), 1);
                        })

                        .then(Commands.literal("check")
                                .executes(ctx -> {
                                    ServerPlayer self = ctx.getSource().getPlayer();
                                    if (self == null) {
                                        ctx.getSource().sendFailure(Component.literal("This command can only be run by a player."));
                                        return 0;
                                    }
                                    return executeOfflineCheck(ctx.getSource(), self.getGameProfile().getName(), 1);
                                })

                                .then(Commands.argument("player", StringArgumentType.string())
                                        .suggests(this::suggestOfflinePlayers)
                                        .executes(ctx -> {
                                            String playerName = StringArgumentType.getString(ctx, "player");
                                            return executeOfflineCheck(ctx.getSource(), playerName, 1);
                                        })
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> {
                                                    String playerName = StringArgumentType.getString(ctx, "player");
                                                    int page = IntegerArgumentType.getInteger(ctx, "page");
                                                    return executeOfflineCheck(ctx.getSource(), playerName, page);
                                                })
                                        )
                                )
                        )

                        // /session checkonline
                        .then(Commands.literal("checkonline")
                                // No argument → show your own live session
                                .executes(ctx -> executeOnlineCheck(ctx.getSource(), ""))

                                // With argument → show another player's live session
                                .then(Commands.argument("player", StringArgumentType.string())
                                        .suggests(this::suggestOnlinePlayers)
                                        .executes(ctx -> {
                                            String playerName = StringArgumentType.getString(ctx, "player");
                                            return executeOnlineCheck(ctx.getSource(), playerName);
                                        })
                                )
                        )
        );
    }

    // ---------- Suggestions ----------

    private CompletableFuture<Suggestions> suggestOnlinePlayers(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        MinecraftServer server = ctx.getSource().getServer();
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                String name = player.getGameProfile().getName();
                if (name.toLowerCase(Locale.ROOT).startsWith(builder.getRemaining().toLowerCase(Locale.ROOT))) {
                    builder.suggest(name);
                }
            }
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestOfflinePlayers(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        MinecraftServer server = ctx.getSource().getServer();
        if (server == null) return builder.buildFuture();

        Path sessionsFolder = getSessionsFolder(server);
        if (!Files.exists(sessionsFolder)) return builder.buildFuture();

        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionsFolder, "*.json")) {
            for (Path path : stream) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    PlayerSessionsFile psf = GSON.fromJson(reader, PlayerSessionsFile.class);
                    if (psf != null && psf.playerName != null && !psf.playerName.isEmpty()) {
                        names.add(psf.playerName);
                    }
                } catch (IOException | JsonParseException ignored) {}
            }
        } catch (IOException ignored) {}

        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String name : names) {
            if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(name);
            }
        }

        return builder.buildFuture();
    }

    // ---------- Per-player message sender ----------

    private void sendToPlayer(CommandSourceStack source, Component msg) {
        if (source.getEntity() instanceof ServerPlayer player) {
            // Send the actual message
            player.sendSystemMessage(msg);
        } else {
            source.sendSuccess(() -> msg, false);
        }
    }


    // ---------- Command logic ----------

    private int executeOfflineCheck(CommandSourceStack source, String playerNameInput, int page) {
        MinecraftServer server = source.getServer();
        if (server == null) return 0;

        UUID targetUuid = resolvePlayerUUID(server, playerNameInput);
        String resolvedName = playerNameInput;

        if (targetUuid == null) {
            // Try online players first
            ServerPlayer online = server.getPlayerList().getPlayerByName(playerNameInput);
            if (online != null) {
                targetUuid = online.getUUID();
                resolvedName = online.getGameProfile().getName();
            } else {
                // Try offline session files
                targetUuid = findUUIDFromSessionFiles(server, playerNameInput);
                if (targetUuid != null) {
                    resolvedName = playerNameInput;
                }
            }
        }

        if (targetUuid == null) {
            sendToPlayer(source, Component.literal("Player not found: " + playerNameInput).withStyle(ChatFormatting.RED));
            return 0;
        }

        PlayerSessionsFile data = loadSessions(server, targetUuid, resolvedName);
        data.sessions.sort(Comparator.comparingLong(s -> s.join));

        int totalSessions = data.sessions.size();
        int totalPages = (int) Math.ceil(totalSessions / (double) SESSIONS_PER_PAGE);
        if (totalPages == 0) totalPages = 1;
        if (page > totalPages) page = totalPages;
        if (page < 1) page = 1;

        final int pageFinal = page;
        final int totalPagesFinal = totalPages;
        final String resolvedNameFinal = resolvedName;

        sendToPlayer(source, Component.literal("Play sessions for " + resolvedNameFinal).withStyle(ChatFormatting.GOLD));
        sendToPlayer(source, Component.literal("Page " + pageFinal + "/" + totalPagesFinal).withStyle(ChatFormatting.YELLOW));
        sendToPlayer(source, Component.literal(String.format("%-3s %-10s %-15s %-12s %-12s", "#", "Date", "Duration", "Joined", "Left")).withStyle(ChatFormatting.AQUA));

        if (totalSessions == 0) {
            sendToPlayer(source, Component.literal("No sessions recorded in the last 7 days.").withStyle(ChatFormatting.GRAY));
        } else {
            int startIndex = (pageFinal - 1) * SESSIONS_PER_PAGE;
            int endIndex = Math.min(startIndex + SESSIONS_PER_PAGE, totalSessions);

            for (int i = startIndex; i < endIndex; i++) {
                SessionEntry s = data.sessions.get(i);
                String durationStr = formatDuration(s.durationSeconds);

                String leftAt = Instant.ofEpochMilli(s.leave)
                        .atZone(SERVER_ZONE)
                        .toLocalTime()
                        .format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)) + " (PT)";

                String line = String.format(
                        "%-3d %-10s %-15s %-12s %-12s",
                        (i + 1),
                        s.displayDate,
                        durationStr,
                        s.serverTime,
                        leftAt
                );

                sendToPlayer(source, Component.literal(line).withStyle(ChatFormatting.WHITE));
            }
        }

        sendToPlayer(source, buildPaginationButtons(resolvedNameFinal, pageFinal, totalPagesFinal));

        return 1;
    }

    private int executeOnlineCheck(CommandSourceStack source, String playerNameInput) {
        MinecraftServer server = source.getServer();
        if (server == null) return 0;

        ServerPlayer targetOnline;

        if (playerNameInput == null || playerNameInput.isEmpty()) {
            targetOnline = source.getPlayer();
            if (targetOnline == null) {
                sendToPlayer(source, Component.literal("This command can only be run by a player.").withStyle(ChatFormatting.RED));
                return 0;
            }
        } else {
            targetOnline = server.getPlayerList().getPlayerByName(playerNameInput);
        }

        if (targetOnline == null) {
            sendToPlayer(source, Component.literal("Player " + playerNameInput + " is not online.").withStyle(ChatFormatting.RED));
            return 0;
        }

        UUID uuid = targetOnline.getUUID();
        Long join = joinTimestamps.get(uuid);
        if (join == null) {
            sendToPlayer(source, Component.literal("No live session found for " + targetOnline.getGameProfile().getName() + ".").withStyle(ChatFormatting.GRAY));
            return 0;
        }

        long now = System.currentTimeMillis();
        long durationSeconds = Math.max(0L, (now - join) / 1000L);

        LocalDate date = Instant.ofEpochMilli(join).atZone(SERVER_ZONE).toLocalDate();
        String dateStr = date.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH));
        LocalTime serverTime = Instant.ofEpochMilli(join).atZone(SERVER_ZONE).toLocalTime();
        String serverTimeStr = serverTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)) + " (PT)";

        sendToPlayer(source, Component.literal("Current Session (LIVE) for " + targetOnline.getGameProfile().getName() + ":").withStyle(ChatFormatting.GOLD));
        sendToPlayer(source, Component.literal("Started: " + dateStr).withStyle(ChatFormatting.GREEN));
        sendToPlayer(source, Component.literal("Server Time: " + serverTimeStr).withStyle(ChatFormatting.GREEN));
        sendToPlayer(source, Component.literal("Duration so far: " + formatDuration(durationSeconds)).withStyle(ChatFormatting.GREEN));

        return 1;
    }

    private Component buildPaginationButtons(String playerName, int currentPage, int totalPages) {
        Component prev;
        if (currentPage > 1) {
            int prevPage = currentPage - 1;
            prev = Component.literal("[<< Prev]")
                    .setStyle(
                            Style.EMPTY
                                    .withColor(ChatFormatting.GREEN)
                                    .withClickEvent(new ClickEvent(
                                            ClickEvent.Action.RUN_COMMAND,
                                            "/session check \"" + playerName + "\" " + prevPage
                                    ))
                    );
        } else {
            prev = Component.literal("[<< Prev]").withStyle(ChatFormatting.YELLOW);
        }

        Component next;
        if (currentPage < totalPages) {
            int nextPage = currentPage + 1;
            next = Component.literal("[Next >>]")
                    .setStyle(
                            Style.EMPTY
                                    .withColor(ChatFormatting.GREEN)
                                    .withClickEvent(new ClickEvent(
                                            ClickEvent.Action.RUN_COMMAND,
                                            "/session check \"" + playerName + "\" " + nextPage
                                    ))
                    );
        } else {
            next = Component.literal("[Next >>]").withStyle(ChatFormatting.YELLOW);
        }

        return Component.empty().append(prev).append(next);
    }


    // ---------- Storage helpers ----------

    private Path getWorldRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT);
    }

    private Path getSessionsFolder(MinecraftServer server) {
        Path base = getWorldRoot(server).resolve(FOLDER_NAME);
        Path sessions = base.resolve(SESSIONS_FOLDER_NAME);
        try {
            Files.createDirectories(sessions);
        } catch (IOException ignored) {}
        return sessions;
    }

    private Path getPlayerFile(MinecraftServer server, UUID uuid) {
        return getSessionsFolder(server).resolve(uuid.toString() + ".json");
    }

    private PlayerSessionsFile loadSessions(MinecraftServer server, UUID uuid, String fallbackName) {
        Path path = getPlayerFile(server, uuid);
        if (!Files.exists(path)) {
            PlayerSessionsFile psf = new PlayerSessionsFile();
            psf.playerName = fallbackName;
            return psf;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            PlayerSessionsFile psf = GSON.fromJson(reader, PlayerSessionsFile.class);
            if (psf == null) psf = new PlayerSessionsFile();
            if (psf.sessions == null) psf.sessions = new ArrayList<>();
            if (psf.playerName == null || psf.playerName.isEmpty()) psf.playerName = fallbackName;

            long now = System.currentTimeMillis();
            psf.sessions = psf.sessions.stream()
                    .filter(s -> s.leave >= now - SEVEN_DAYS_MILLIS)
                    .collect(Collectors.toList());

            return psf;
        } catch (IOException | JsonParseException e) {
            PlayerSessionsFile psf = new PlayerSessionsFile();
            psf.playerName = fallbackName;
            return psf;
        }
    }

    private void saveSession(MinecraftServer server, UUID uuid, String playerName, SessionEntry newEntry) {
        PlayerSessionsFile psf = loadSessions(server, uuid, playerName);
        psf.playerName = playerName;

        long now = System.currentTimeMillis();
        psf.sessions = psf.sessions.stream()
                .filter(s -> s.leave >= now - SEVEN_DAYS_MILLIS)
                .collect(Collectors.toList());

        psf.sessions.add(newEntry);

        Path path = getPlayerFile(server, uuid);
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(psf, writer);
        } catch (IOException ignored) {}
    }

    private UUID findUUIDFromSessionFiles(MinecraftServer server, String name) {
        Path folder = getSessionsFolder(server);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.json")) {
            for (Path path : stream) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    PlayerSessionsFile psf = GSON.fromJson(reader, PlayerSessionsFile.class);

                    if (psf != null && psf.playerName != null &&
                            psf.playerName.equalsIgnoreCase(name)) {

                        String fileName = path.getFileName().toString();
                        return UUID.fromString(fileName.substring(0, fileName.length() - 5));
                    }
                } catch (Exception ignored) {}
            }
        } catch (IOException ignored) {}

        return null;
    }

    // ---------- Utils ----------

    private String formatDuration(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return String.format("%dh %02dm %02ds", h, m, s);
    }

    private UUID resolvePlayerUUID(MinecraftServer server, String input) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(input);
        if (online != null) return online.getUUID();

        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ignored) {}

        return null;
    }
}
