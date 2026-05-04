import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NoSwingHook {

    private static final Map<UUID, Long> lastSwingTime = new ConcurrentHashMap<>();

    public static void register(Main plugin) {
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();

        // Отслеживаем взмах руки (Animation)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Client.ARM_ANIMATION) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (!((Main) plugin).isAntiCheatEnabled()) return;

                Player player = event.getPlayer();
                if (player != null) {
                    lastSwingTime.put(player.getUniqueId(), System.currentTimeMillis());
                }
            }
        });

        // Отслеживаем атаку (Use Entity)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (!((Main) plugin).isAntiCheatEnabled()) return;

                Player player = event.getPlayer();
                if (player == null) return;

                // Проверяем тип действия. Мы ищем только ATTACK.
                // В 1.16+ EnumEntityUseAction может быть разным, безопаснее использовать обертку или проверять наличие.
                // Для простоты используем проверку через Bukkit API или попытаемся прочитать Action
                try {
                    // Читаем тип действия.
                    com.comphenix.protocol.wrappers.EnumWrappers.EntityUseAction action = event.getPacket().getEntityUseActions().read(0);
                    if (action == com.comphenix.protocol.wrappers.EnumWrappers.EntityUseAction.ATTACK) {
                        UUID uuid = player.getUniqueId();
                        long now = System.currentTimeMillis();
                        long swingTime = lastSwingTime.getOrDefault(uuid, 0L);

                        // Пакет анимации может приходить чуть позже или одновременно из-за многопоточности Netty
                        // Поэтому мы даем допуск в 50 мс
                        if (now - swingTime > 50) {
                            // Передаем флаг в Main поток
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ((Main) plugin).processViolation(player, 30.0);
                                plugin.getLogger().info("[OkakAC] NoSwing detected for " + player.getName());
                            });
                        }
                    }
                } catch (Exception e) {
                    // Игнорируем ошибки парсинга пакета, если они возникнут
                }
            }
        });

        // Очистка при выходе игрока
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
                lastSwingTime.remove(event.getPlayer().getUniqueId());
            }
        }, plugin);

        plugin.getLogger().info("NoSwing (ProtocolLib Hook) enabled!");
    }
}
