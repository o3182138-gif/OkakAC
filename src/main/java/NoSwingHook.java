import org.bukkit.Bukkit;
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

    private static final Map<UUID, Long> lastSwingTime = new ConcurrentHashMap<>();

    public static void register(Main plugin) {
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();

        // Отслеживание пакета анимации (Arm Swing)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Client.ARM_ANIMATION) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (!((Main) plugin).isAntiCheatEnabled()) return;
                lastSwingTime.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
            }
        });

        // Отслеживание пакета взаимодействия (Attack)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (!((Main) plugin).isAntiCheatEnabled()) return;

                Player player = event.getPlayer();
                UUID uuid = player.getUniqueId();

                // Действие может быть ATTACK, INTERACT, INTERACT_AT. Проверяем только атаку
                try {
                    Object action = event.getPacket().getEntityUseActions().read(0);
                    if (action == null || !action.toString().contains("ATTACK")) return;
                } catch (Exception e) {
                    // Игнорируем в случае несоответствия версий ProtocolLib, но продолжаем проверку
                }

                // Захватываем время начала проверки ДО задержки
                long checkStartTime = System.currentTimeMillis();
                // Задержка проверки, так как пакеты могут приходить в разном порядке (USE_ENTITY перед ARM_ANIMATION)
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                    long swingTime = lastSwingTime.getOrDefault(uuid, 0L);

                    // Если последняя анимация была больше чем 100 мс назад относительно момента удара, это NoSwing
                    if (checkStartTime - swingTime > 100) {
                        // Также дополнительно проверяем, не пришел ли пакет анимации за эти 3 тика
                        if (swingTime < checkStartTime) {
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ((Main) plugin).processViolationPublic(player, 60, "NoSwing (Missing Arm Animation)");
                            });
                        }
                    }
                }, 3L); // Ждем 3 тика (около 150мс)
            }
        });

        plugin.getLogger().info("NoSwing detection (ProtocolLib Hook) enabled!");
    }
}
