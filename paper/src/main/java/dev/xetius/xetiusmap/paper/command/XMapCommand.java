package dev.xetius.xetiusmap.paper.command;

import dev.xetius.xetiusmap.common.model.Waypoint;
import dev.xetius.xetiusmap.common.model.WaypointIcon;
import dev.xetius.xetiusmap.paper.Permissions;
import dev.xetius.xetiusmap.paper.PluginConfig;
import dev.xetius.xetiusmap.paper.XetiusMapPlugin;
import dev.xetius.xetiusmap.paper.net.MessageBus;
import dev.xetius.xetiusmap.paper.service.TeleportService;
import dev.xetius.xetiusmap.paper.service.TileService;
import dev.xetius.xetiusmap.paper.service.WaypointService;
import dev.xetius.xetiusmap.paper.session.PlayerSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** {@code /xmap} — waypoint management, teleporting, and the operator's view of the shared map. */
public final class XMapCommand implements CommandExecutor, TabCompleter {

    private static final int PAGE_SIZE = 10;

    private final XetiusMapPlugin plugin;
    private final Supplier<PluginConfig> config;
    private final MessageBus bus;
    private final WaypointService waypoints;
    private final TeleportService teleports;
    private final TileService tiles;

    public XMapCommand(XetiusMapPlugin plugin,
                       Supplier<PluginConfig> config,
                       MessageBus bus,
                       WaypointService waypoints,
                       TeleportService teleports,
                       TileService tiles) {
        this.plugin = plugin;
        this.config = config;
        this.bus = bus;
        this.waypoints = waypoints;
        this.teleports = teleports;
        this.tiles = tiles;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "waypoint", "wp" -> handleWaypoint(sender, label, args);
            case "tp", "teleport" -> handleTeleport(sender, args);
            case "hide" -> handleHide(sender, args);
            case "list" -> listWaypoints(sender, args.length > 1 ? args[1] : "1");
            case "stats" -> handleStats(sender);
            case "purge" -> handlePurge(sender, args);
            case "reload" -> handleReload(sender);
            default -> sendHelp(sender, label);
        }
        return true;
    }

    // --- waypoint ----------------------------------------------------------------------------

    private void handleWaypoint(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sendHelp(sender, label);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "add", "create" -> addWaypoint(sender, args);
            case "remove", "delete", "del" -> removeWaypoint(sender, args);
            case "list" -> listWaypoints(sender, args.length > 2 ? args[2] : "1");
            case "edit", "set" -> editWaypoint(sender, args);
            default -> sendHelp(sender, label);
        }
    }

    private void addWaypoint(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only a player can add a waypoint at their own position.");
            return;
        }
        if (!player.hasPermission(Permissions.WAYPOINT_CREATE)) {
            error(sender, "You do not have permission to create waypoints.");
            return;
        }
        if (args.length < 3) {
            error(sender, "Usage: /xmap waypoint add <name> [icon] [#rrggbb]");
            return;
        }

        String name = args[2];
        String icon = args.length > 3 ? WaypointIcon.normalise(args[3]) : WaypointIcon.DEFAULT;
        int color = args.length > 4 ? parseColor(args[4]).orElse(0xFFAA00) : 0xFFAA00;

        Location location = player.getLocation();
        Waypoint waypoint = new Waypoint(
                UUID.randomUUID(),
                name,
                player.getWorld().getKey().toString(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ(),
                color,
                icon,
                player.getUniqueId(),
                player.getName(),
                System.currentTimeMillis()
        );

        Optional<String> failure = waypoints.create(waypoint, config.get());
        if (failure.isPresent()) {
            error(sender, failure.get());
        } else {
            success(sender, "Created waypoint '" + waypoint.name() + "' at "
                    + waypoint.x() + ", " + waypoint.y() + ", " + waypoint.z() + ".");
        }
    }

    private void removeWaypoint(CommandSender sender, String[] args) {
        if (args.length < 3) {
            error(sender, "Usage: /xmap waypoint remove <name>");
            return;
        }
        Optional<Waypoint> found = waypoints.byName(args[2]);
        if (found.isEmpty()) {
            error(sender, "No waypoint named '" + args[2] + "'.");
            return;
        }
        Waypoint waypoint = found.get();
        if (!mayModify(sender, waypoint.owner(),
                Permissions.WAYPOINT_DELETE_OWN, Permissions.WAYPOINT_DELETE_OTHER)) {
            error(sender, "You may only delete waypoints you created.");
            return;
        }
        waypoints.delete(waypoint.id());
        success(sender, "Deleted waypoint '" + waypoint.name() + "'.");
    }

    private void editWaypoint(CommandSender sender, String[] args) {
        if (args.length < 5) {
            error(sender, "Usage: /xmap waypoint edit <name> <name|icon|color|here> <value>");
            return;
        }
        Optional<Waypoint> found = waypoints.byName(args[2]);
        if (found.isEmpty()) {
            error(sender, "No waypoint named '" + args[2] + "'.");
            return;
        }
        Waypoint existing = found.get();
        if (!mayModify(sender, existing.owner(), Permissions.WAYPOINT_EDIT_OWN, Permissions.WAYPOINT_EDIT_OTHER)) {
            error(sender, "You may only edit waypoints you created.");
            return;
        }

        Waypoint edited;
        String field = args[3].toLowerCase(Locale.ROOT);
        String value = args[4];
        switch (field) {
            case "name" -> edited = withName(existing, value);
            case "icon" -> edited = withIcon(existing, WaypointIcon.normalise(value));
            case "color", "colour" -> {
                Optional<Integer> color = parseColor(value);
                if (color.isEmpty()) {
                    error(sender, "Colour must look like #RRGGBB.");
                    return;
                }
                edited = withColor(existing, color.get());
            }
            default -> {
                error(sender, "Unknown field '" + field + "'. Use name, icon or color.");
                return;
            }
        }

        Optional<String> failure = waypoints.update(edited);
        if (failure.isPresent()) {
            error(sender, failure.get());
        } else {
            success(sender, "Updated waypoint '" + edited.name() + "'.");
        }
    }

    private void listWaypoints(CommandSender sender, String pageArg) {
        List<Waypoint> all = waypoints.all();
        if (all.isEmpty()) {
            info(sender, "No waypoints have been created yet.");
            return;
        }
        int pages = (all.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = clamp(parseInt(pageArg, 1), 1, pages);

        info(sender, "Waypoints (page " + page + " of " + pages + ", " + all.size() + " total):");
        int from = (page - 1) * PAGE_SIZE;
        for (Waypoint waypoint : all.subList(from, Math.min(all.size(), from + PAGE_SIZE))) {
            sender.sendMessage(Component.text("  " + waypoint.name(), NamedTextColor.YELLOW)
                    .append(Component.text(
                            "  " + waypoint.x() + ", " + waypoint.y() + ", " + waypoint.z()
                                    + "  [" + shortDimension(waypoint.dimension()) + "]"
                                    + "  by " + waypoint.ownerName(),
                            NamedTextColor.GRAY)));
        }
    }

    // --- teleport ----------------------------------------------------------------------------

    private void handleTeleport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only a player can teleport.");
            return;
        }
        if (args.length < 2) {
            error(sender, "Usage: /xmap tp <waypoint>");
            return;
        }
        Optional<Waypoint> found = waypoints.byName(args[1]);
        if (found.isEmpty()) {
            error(sender, "No waypoint named '" + args[1] + "'.");
            return;
        }
        teleports.request(player, bus.session(player.getUniqueId()), found.get().id());
    }

    private void handleHide(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only a player can hide from the map.");
            return;
        }
        PlayerSession session = bus.session(player.getUniqueId());
        if (session == null) {
            error(sender, "You are not connected with the XetiusMap client mod.");
            return;
        }
        boolean hidden = args.length > 1
                ? args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true")
                : !session.hidden();
        session.setHidden(hidden);
        success(sender, hidden
                ? "You are now hidden from other players' maps."
                : "You are visible on other players' maps again.");
    }

    // --- admin -------------------------------------------------------------------------------

    private void handleStats(CommandSender sender) {
        TileService.Stats stats = tiles.stats();
        info(sender, "XetiusMap statistics:");
        sender.sendMessage(Component.text(
                "  tiles accepted " + stats.accepted()
                        + ", duplicates " + stats.duplicates()
                        + ", rejected " + stats.rejected(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text(
                "  tiles served " + stats.served() + ", current revision " + stats.revision(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text(
                "  waypoints " + waypoints.size() + ", connected clients " + bus.activeSessions().size(),
                NamedTextColor.GRAY));

        for (String folder : tiles.storedDimensionFolders()) {
            sender.sendMessage(Component.text("  " + folder + ": "
                    + regionCountForFolder(folder) + " region file(s)", NamedTextColor.GRAY));
        }

        for (PlayerSession session : bus.activeSessions()) {
            sender.sendMessage(Component.text(
                    "  " + session.playerName() + " — mod " + session.modVersion()
                            + ", uploaded " + session.tilesUploaded().get()
                            + ", received " + session.tilesSent().get()
                            + ", " + (session.bytesSent().get() / 1024) + " KiB sent"
                            + (session.pendingTileCount() > 0
                                    ? ", " + session.pendingTileCount() + " queued" : ""),
                    NamedTextColor.DARK_GRAY));
        }
    }

    private int regionCountForFolder(String folder) {
        // listRegions keys off the dimension id, and the folder name is its sanitised form; for the
        // vanilla dimensions the two coincide, which is all this summary line needs.
        return tiles.regionCount(folder.replace('.', ':'));
    }

    private void handlePurge(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Permissions.ADMIN)) {
            error(sender, "You do not have permission to purge map data.");
            return;
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            error(sender, "This permanently deletes stored map data. "
                    + "Run: /xmap purge <dimension> confirm");
            return;
        }
        String dimension = args[1];
        tiles.purge(dimension, removed -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (removed < 0) {
                error(sender, "Purge failed; see the server log.");
            } else {
                success(sender, "Purged " + removed + " region file(s) for " + dimension + ".");
            }
        }));
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission(Permissions.ADMIN)) {
            error(sender, "You do not have permission to reload XetiusMap.");
            return;
        }
        plugin.reload();
        success(sender, "XetiusMap configuration reloaded.");
    }

    // --- tab completion ------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            addMatching(out, args[0], List.of("waypoint", "tp", "hide", "list", "stats", "purge", "reload"));
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "waypoint", "wp" -> addMatching(out, args[1], List.of("add", "remove", "list", "edit"));
                case "tp", "teleport" -> out.addAll(waypoints.completeNames(args[1]));
                case "hide" -> addMatching(out, args[1], List.of("on", "off"));
                case "purge" -> addMatching(out, args[1], plugin.getServer().getWorlds().stream()
                        .map(world -> world.getKey().toString()).toList());
                default -> {
                    // No further suggestions for this subcommand.
                }
            }
        } else if (args.length == 3 && isWaypointSubcommand(args[0])) {
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "remove", "delete", "del", "edit", "set" -> out.addAll(waypoints.completeNames(args[2]));
                default -> {
                    // "add" takes a free-text name.
                }
            }
        } else if (args.length == 4 && isWaypointSubcommand(args[0])) {
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "add", "create" -> addMatching(out, args[3], WaypointIcon.BUILT_IN);
                case "edit", "set" -> addMatching(out, args[3], List.of("name", "icon", "color"));
                default -> {
                    // Nothing to suggest.
                }
            }
        } else if (args.length == 5 && isWaypointSubcommand(args[0])
                && (args[1].equalsIgnoreCase("edit") || args[1].equalsIgnoreCase("set"))
                && args[3].equalsIgnoreCase("icon")) {
            addMatching(out, args[4], WaypointIcon.BUILT_IN);
        }
        return out;
    }

    private static boolean isWaypointSubcommand(String arg) {
        return arg.equalsIgnoreCase("waypoint") || arg.equalsIgnoreCase("wp");
    }

    private static void addMatching(List<String> out, String prefix, List<String> candidates) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(candidate);
            }
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    private void sendHelp(CommandSender sender, String label) {
        info(sender, "XetiusMap commands:");
        for (String line : List.of(
                "/" + label + " waypoint add <name> [icon] [#rrggbb]",
                "/" + label + " waypoint remove <name>",
                "/" + label + " waypoint edit <name> <name|icon|color> <value>",
                "/" + label + " list [page]",
                "/" + label + " tp <name>",
                "/" + label + " hide [on|off]",
                "/" + label + " stats",
                "/" + label + " purge <dimension> confirm",
                "/" + label + " reload")) {
            sender.sendMessage(Component.text("  " + line, NamedTextColor.GRAY));
        }
    }

    private boolean mayModify(CommandSender sender, UUID owner, String ownPermission, String otherPermission) {
        if (sender.hasPermission(otherPermission)) {
            return true;
        }
        return sender instanceof Player player
                && player.getUniqueId().equals(owner)
                && player.hasPermission(ownPermission);
    }

    private static Waypoint withName(Waypoint w, String name) {
        return new Waypoint(w.id(), name, w.dimension(), w.x(), w.y(), w.z(), w.color(), w.icon(),
                w.owner(), w.ownerName(), w.createdAt());
    }

    private static Waypoint withIcon(Waypoint w, String icon) {
        return new Waypoint(w.id(), w.name(), w.dimension(), w.x(), w.y(), w.z(), w.color(), icon,
                w.owner(), w.ownerName(), w.createdAt());
    }

    private static Waypoint withColor(Waypoint w, int color) {
        return new Waypoint(w.id(), w.name(), w.dimension(), w.x(), w.y(), w.z(), color, w.icon(),
                w.owner(), w.ownerName(), w.createdAt());
    }

    private static Optional<Integer> parseColor(String raw) {
        String hex = raw.startsWith("#") ? raw.substring(1) : raw;
        if (hex.length() != 6) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(hex, 16) & 0xFFFFFF);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String shortDimension(String dimension) {
        int colon = dimension.indexOf(':');
        return colon >= 0 ? dimension.substring(colon + 1) : dimension;
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void info(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.AQUA));
    }

    private static void success(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.GREEN));
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
    }
}
