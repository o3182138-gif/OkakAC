import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NoSwingHook {

    // Store timestamps of the last ARM_ANIMATION packet received per player
    private static final Map<UUID, Long> lastSwingMap = new ConcurrentHashMap<>();

    public static void register(Main plugin) {
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();

        // Listen for ARM_ANIMATION packets (when the player swings their arm)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Client.ARM_ANIMATION) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (!((Main) plugin).isAntiCheatEnabled()) return;
                lastSwingMap.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
            }
        });

        // Listen for USE_ENTITY packets (when the player attacks an entity)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (!((Main) plugin).isAntiCheatEnabled()) return;

                // Only care about attack action, not interact
                try {
                    // In 1.16+ ProtocolLib, the action is usually an EnumWrappers.EntityUseAction
                    Object actionObj = event.getPacket().getEntityUseActions().read(0);
                    if (actionObj == null || !actionObj.toString().contains("ATTACK")) return;
                } catch (Exception e) {
                    // Fallback or ignore if the field structure is different in older versions
                }

                Player player = event.getPlayer();
                UUID uuid = player.getUniqueId();
                long attackTime = System.currentTimeMillis();

                // Schedule an asynchronous check slightly after to allow ARM_ANIMATION to arrive
                // because packets can arrive slightly out of order or simultaneously
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                    if (!player.isOnline()) {
                        lastSwingMap.remove(uuid);
                        return;
                    }

                    Long lastSwing = lastSwingMap.get(uuid);
                    if (lastSwing == null || (attackTime - lastSwing > 100)) {
                        // Flag as NoSwing
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            ((Main) plugin).processViolation(player, 40, "NoSwing");
                        });
                    }
                }, 2L); // Wait 2 ticks
            }
        });

        plugin.getLogger().info("NoSwing (ProtocolLib Hook) enabled!");
    }

    public static void removePlayer(UUID uuid) {
        lastSwingMap.remove(uuid);
    }
}