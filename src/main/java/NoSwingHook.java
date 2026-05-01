import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NoSwingHook implements Listener {

    // Thread-safe map for Netty async threads
    private static final Map<UUID, Long> lastSwingTicks = new ConcurrentHashMap<>();

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        lastSwingTicks.remove(event.getPlayer().getUniqueId());
    }

    public static void register(Main plugin) {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            plugin.getLogger().warning("ProtocolLib not found! NoSwingHook is disabled.");
            return;
        }

        Bukkit.getPluginManager().registerEvents(new NoSwingHook(), plugin);

        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();

        // Listen for ARM_ANIMATION (Swing)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Client.ARM_ANIMATION) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                if (player == null) return;

                // Record the time of the swing
                lastSwingTicks.put(player.getUniqueId(), System.currentTimeMillis());
            }
        });

        // Listen for USE_ENTITY (Attack)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (!((Main) plugin).isAntiCheatEnabled()) return;

                Player player = event.getPlayer();
                if (player == null) return;

                EnumWrappers.EntityUseAction action = event.getPacket().getEntityUseActions().read(0);
                if (action != EnumWrappers.EntityUseAction.ATTACK) return;

                long currentTime = System.currentTimeMillis();
                long lastSwing = lastSwingTicks.getOrDefault(player.getUniqueId(), 0L);

                // If USE_ENTITY arrives, ARM_ANIMATION should have arrived very recently (usually same tick, or max 50ms difference due to network)
                // If it's been more than 150ms since the last swing, or they never swung, it's NoSwing.
                // NOTE: Some legitimate clients send USE_ENTITY *before* ARM_ANIMATION in the same tick. We give a small leniency.
                if (currentTime - lastSwing > 150) {
                    // It's highly likely a NoSwing cheat
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        // We must call Bukkit API on the main thread
                        plugin.getLogger().info("§8[§cOkakAC§8] §c[SILENT FLAG] §e" + player.getName() + " §7flagged for §cNoSwing");
                        // We could hook this into processViolation if we made it public, but for now we warn admins directly
                        for (Player op : Bukkit.getOnlinePlayers()) {
                            if (op.isOp()) {
                                op.sendMessage("§8[§cOkakAC§8] §e" + player.getName() + " §7Flag: §cNoSwing (Attack without swing)");
                            }
                        }
                    });
                }
            }
        });

        plugin.getLogger().info("NoSwingHook (ProtocolLib Hook) enabled!");
    }
}
