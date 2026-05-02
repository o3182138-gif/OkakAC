import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NoSwingHook {

    private static final ConcurrentHashMap<UUID, Long> lastSwingTime = new ConcurrentHashMap<>();

    public static void register(Main plugin) {
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();

        // Listen for arm animation (swing)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Client.ARM_ANIMATION) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (!((Main) plugin).isAntiCheatEnabled()) return;
                lastSwingTime.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
            }
        });

        // Listen for use entity (attack)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (!((Main) plugin).isAntiCheatEnabled()) return;

                Player player = event.getPlayer();
                UUID uuid = player.getUniqueId();

                // Extract action type, we only care about ATTACK.
                // In ProtocolLib, getEntityUseActions() returns an enum modifier.
                // Depending on the exact ProtocolLib version and MC version, this might vary.
                // For simplicity and general compatibility, we will assume if it's an interaction, we check swing.
                // We will wrap this in a try-catch just in case the wrapper structure is different.
                try {
                    com.comphenix.protocol.wrappers.EnumWrappers.EntityUseAction action = event.getPacket().getEntityUseActions().readSafely(0);
                    if (action != null && action != com.comphenix.protocol.wrappers.EnumWrappers.EntityUseAction.ATTACK) {
                        return;
                    }
                } catch (Exception ignored) {
                    // Fallback if read fails, proceed with check
                }

                long currentTime = System.currentTimeMillis();
                long lastSwing = lastSwingTime.getOrDefault(uuid, 0L);

                // Packets might arrive simultaneously or ARM_ANIMATION slightly after due to async handling or client differences.
                // We check if there was NO swing in the last 200ms, and NO swing happens within the next few ms (which we can't easily peek).
                // However, an attack without *any* recent swing is highly suspicious. We use a 100ms window to be safe.
                long diff = currentTime - lastSwing;

                // But wait, the swing packet can arrive AFTER the attack packet.
                // So checking `currentTime - lastSwing > 100` might flag false positives.
                // To do this robustly, we could schedule a check on the main thread for a few ticks later.
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    long finalLastSwing = lastSwingTime.getOrDefault(uuid, 0L);
                    long finalDiff = System.currentTimeMillis() - finalLastSwing;

                    // If even after 2 ticks (100ms) there was no swing that occurred near the attack time
                    if (Math.abs(currentTime - finalLastSwing) > 150) {
                        ((Main) plugin).processViolation(player, 60.0, "NoSwing");
                    }
                }, 2L);
            }
        });

        plugin.getLogger().info("NoSwing (ProtocolLib Hook) enabled!");
    }
}
