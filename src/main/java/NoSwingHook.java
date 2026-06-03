import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;

import java.util.UUID;

public class NoSwingHook {

    public static void register(Main plugin) {
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();

        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Client.ARM_ANIMATION) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (!((Main) plugin).isAntiCheatEnabled()) return;
                Player player = event.getPlayer();
                if (player == null) return;
                ((Main) plugin).lastSwing.put(player.getUniqueId(), System.currentTimeMillis());
            }
        });

        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (!((Main) plugin).isAntiCheatEnabled()) return;

                Player player = event.getPlayer();
                if (player == null) return;

                // USE_ENTITY can be interact, attack, interact_at
                // Let's check for attack if possible, but any USE_ENTITY without an animation is suspicious for combat

                UUID uuid = player.getUniqueId();

                // Schedule a check slightly later, because USE_ENTITY can arrive before ARM_ANIMATION
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                    if (!player.isOnline()) return;
                    long swingTime = ((Main) plugin).lastSwing.getOrDefault(uuid, 0L);
                    long currentTime = System.currentTimeMillis();
                    // If no swing in the last 150ms
                    if (currentTime - swingTime > 150) {
                        // Flag as NoSwing
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            // Process violation on main thread
                            ((Main) plugin).processViolation(player, 25.0, "Отсутствие анимации удара (NoSwing)");
                        });
                    }
                }, 2L); // 2 ticks later
            }
        });

        plugin.getLogger().info("NoSwing Hook (ProtocolLib) enabled!");
    }
}