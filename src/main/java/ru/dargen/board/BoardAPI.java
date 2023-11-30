package ru.dargen.board;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.util.internal.ConcurrentSet;
import net.minecraft.server.v1_12_R1.EnumDirection;
import net.minecraft.server.v1_12_R1.Packet;
import net.minecraft.server.v1_12_R1.PacketListenerPlayIn;
import net.minecraft.server.v1_12_R1.PacketPlayInUseEntity;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_12_R1.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import ru.dargen.board.impl.PrivateBannerBoard;
import ru.dargen.board.impl.SimpleBannerBoard;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.bukkit.event.EventPriority.LOWEST;

public class BoardAPI extends BukkitRunnable implements Listener {

    private static BoardAPI api;

    private static Field entityIdField;

    static {
        try {
            entityIdField = PacketPlayInUseEntity.class.getDeclaredField("a");
            entityIdField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
    }

    private final JavaPlugin plugin;
    private final Set<BannerBoard> boardsCache;

    BoardAPI(JavaPlugin plugin) {
        this.plugin = plugin;
        boardsCache = new ConcurrentSet<>();
        runTaskTimerAsynchronously(plugin, 10, 20);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void register(BannerBoard board) {
        boardsCache.add(board);
    }

    public void unregister(BannerBoard board) {
        board.broadcastDestroy();
        boardsCache.remove(board);
    }

    void handleClick(Player player, int entityId, PacketPlayInUseEntity.EnumEntityUseAction action) {
        boardsCache.forEach(board -> {
            if (board.getFragmentsEntityIds().stream().anyMatch(id -> id == entityId))
                if (board.getInteractListener() != null)
                    board.getInteractListener().accept(action, player);
        });
    }

    @EventHandler(priority = LOWEST)
    void teleport(PlayerTeleportEvent e) {
        boardsCache.forEach(board -> board.destroy(e.getPlayer()));
    }

    @EventHandler(priority = LOWEST)
    void worldChange(PlayerChangedWorldEvent e) {
        Player player = e.getPlayer();
        for (BannerBoard board: boardsCache) {
            if (!player.getWorld().getName().equals(board.getLocation().getWorld().getName())) {
                board.destroy(player);
            } else {
                board.update(player);
            }
        }
    }

    @EventHandler(priority = LOWEST)
    void quit(PlayerQuitEvent e) {
        boardsCache.forEach(board -> board.destroy(e.getPlayer()));
    }

    @EventHandler(priority = LOWEST)
    public void kick(PlayerKickEvent e) {
        boardsCache.forEach(board -> board.destroy(e.getPlayer()));
    }

    @EventHandler(priority = LOWEST)
    public void respawn(PlayerRespawnEvent e) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(BoardAPI.api.getPlugin(), () -> {
            boardsCache.forEach(board -> board.destroy(e.getPlayer()));
        }, 2);
    }

    @EventHandler(priority = LOWEST)
    public void join(PlayerJoinEvent e) {
        boardsCache.forEach(board -> board.update(e.getPlayer()));
        ((CraftPlayer) e.getPlayer()).getHandle().playerConnection.networkManager.channel.pipeline().addAfter("decoder", "inListener", new MessageToMessageDecoder<Packet<PacketListenerPlayIn>>() {
            protected void decode(ChannelHandlerContext ctx, Packet<PacketListenerPlayIn> packet, List<Object> out) throws Exception {
                out.add(packet);
                if (!(packet instanceof PacketPlayInUseEntity)) return;
                PacketPlayInUseEntity usePacket = ((PacketPlayInUseEntity) packet);
                handleClick(e.getPlayer(), (Integer) entityIdField.get(usePacket), usePacket.a());
            }
        });
    }

    void stop0() {
        cancel();
        boardsCache.removeIf(b -> {
            b.broadcastDestroy();
            return true;
        });
        HandlerList.unregisterAll(this);
    }

    public void run() {
        Bukkit.getOnlinePlayers().forEach(player -> boardsCache.forEach(board -> {
            if (!player.getLocation().getWorld().getName().equals(board.getLocation().getWorld().getName()))
                board.destroy(player);
            else if (player.getLocation().distance(board.getLocation()) > 60 && board.getRecipients().contains(player))
                board.destroy(player);
            else if (!board.getRecipients().contains(player) && board.canReceive(player))
                board.spawn(player);
        }));
    }

    public static BannerBoard createBanner(Location location, EnumDirection direction, int columns, int rows) {
        BoardAPI api = getApi();
        BannerBoard banner = new SimpleBannerBoard(location, direction, columns, rows);
        api.register(banner);
        return banner;
    }

    public static BannerBoard createPrivateBanner(Location location, EnumDirection direction, int columns, int rows, Player viewer) {
        BoardAPI api = getApi();
        BannerBoard banner = new PrivateBannerBoard(location, direction, columns, rows, viewer);
        api.register(banner);
        return banner;
    }

    public static BoardAPI getApi() {
        if (api == null)
            throw new IllegalStateException("api not initialized");
        return api;
    }

    public static BoardAPI init(JavaPlugin plugin) {
        if (api != null)
            throw new IllegalStateException("api already initialized");
        api = new BoardAPI(plugin);
        return api;
    }

    public static void stop() {
        getApi().stop0();
        api = null;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }
}
