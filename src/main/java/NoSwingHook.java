import org.bukkit.entity.Entity;
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

    // Thread-safe map for storing the last time a player swung their arm
    private static final Map<UUID, Long> lastSwingTicks = new ConcurrentHashMap<>();

    public static void register(Main plugin) {
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();

        // Listen for arm animation packets (swing)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Client.ARM_ANIMATION) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (!((Main) plugin).isAntiCheatEnabled()) return;

                Player player = event.getPlayer();
                if (player != null) {
                    lastSwingTicks.put(player.getUniqueId(), System.currentTimeMillis());
                }
            }
        });

        // Listen for use entity packets (attack)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (!((Main) plugin).isAntiCheatEnabled()) return;

                Player player = event.getPlayer();
                if (player == null) return;

                // Check if it's an attack action
                // In ProtocolLib for 1.16, we can read the EnumEntityUseAction
                try {
                    Object useAction = event.getPacket().getEnumEntityUseActions().readSafely(0);
                    // "ATTACK" action
                    if (useAction != null && useAction.toString().contains("ATTACK")) {
                        long currentTime = System.currentTimeMillis();
                        Long lastSwing = lastSwingTicks.get(player.getUniqueId());

                        // If no swing recorded or the swing happened more than 1 second ago (1000ms),
                        // or it arrived slightly after (due to packet order), we check if difference is too large.
                        // USE_ENTITY and ARM_ANIMATION can arrive out of order, give it a small grace period or
                        // check if there's *no* recent swing.
                        if (lastSwing == null || (currentTime - lastSwing) > 500) {
                            // If swing is missing for too long, flag for NoSwing
                            // Execute on main thread
                            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                                ((Main) plugin).processViolation(player, 50.0, "NoSwing (Отсутствует анимация удара)");
                            });
                        }
                    }
                } catch (Exception e) {
                    // Fail safely if structure changed or fields are not read correctly
                }
            }
        });

        plugin.getLogger().info("NoSwing (ProtocolLib Hook) включен!");
    }
}
