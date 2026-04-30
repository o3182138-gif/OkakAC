import org.bukkit.entity.Player;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NoSwingHook {

    private static final Map<UUID, Long> lastSwingTime = new ConcurrentHashMap<>();

    public static void register(Main plugin) {
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();

        // Listen for ARM_ANIMATION (swing)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Client.ARM_ANIMATION) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (!((Main) plugin).isAntiCheatEnabled()) return;
                lastSwingTime.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
            }
        });

        // Listen for USE_ENTITY (attack)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (!((Main) plugin).isAntiCheatEnabled()) return;
                Player player = event.getPlayer();

                // EntityUseAction is required to determine if it's an attack, not just interaction
                // For simplicity across versions, if there's no swing recently, we check
                Long lastSwing = lastSwingTime.getOrDefault(player.getUniqueId(), 0L);
                long timeSinceSwing = System.currentTimeMillis() - lastSwing;

                // Packets can arrive in different orders, or simultaneously.
                // 50ms window should be enough to account for network jitter.
                if (timeSinceSwing > 50) {
                    // It's possible the swing arrived *after* the use entity in the same tick or next tick
                    // But if it's a huge delay (e.g., > 1000ms), they haven't swung at all.
                    if (timeSinceSwing > 100) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            ((Main) plugin).processViolation(player, 50, "NoSwing");
                        });
                    }
                }
            }
        });

        plugin.getLogger().info("NoSwing (ProtocolLib Hook) enabled!");
    }
}