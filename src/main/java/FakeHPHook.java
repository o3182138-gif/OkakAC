import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedWatchableObject;

import java.util.List;

public class FakeHPHook {

    public static void register(Main plugin) {
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Server.ENTITY_METADATA) {
            @Override
            public void onPacketSending(PacketEvent event) {
                // Клонируем пакет перед изменением
                event.setPacket(event.getPacket().deepClone());
                if (!((Main) plugin).isAntiCheatEnabled()) return;

                Entity entity = event.getPacket().getEntityModifier(event.getPlayer().getWorld()).read(0);
                if (!(entity instanceof Player)) return;

                Player packetPlayer = (Player) entity;
                Player receiver = event.getPlayer();

                // Мы не скрываем HP от самого себя
                if (packetPlayer.getUniqueId().equals(receiver.getUniqueId())) return;

                // Получаем метаданные (список WrappedWatchableObject)
                List<WrappedWatchableObject> watchableObjects = event.getPacket().getWatchableCollectionModifier().read(0);

                if (watchableObjects != null) {
                    for (WrappedWatchableObject watchable : watchableObjects) {
                        // В версиях 1.16 индекс здоровья у игрока равен 8.
                        if (watchable.getIndex() == 8 && watchable.getValue() instanceof Float) {
                            watchable.setValue(20.0f);
                        }
                    }
                }
            }
        });

        // Перехватываем пакет обновления атрибутов, чтобы скрыть максимальное ХП (иногда читы берут его оттуда)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Server.UPDATE_ATTRIBUTES) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (!((Main) plugin).isAntiCheatEnabled()) return;

                int entityId = event.getPacket().getIntegers().read(0);
                Entity entity = protocolManager.getEntityFromID(event.getPlayer().getWorld(), entityId);

                if (!(entity instanceof Player)) return;

                Player packetPlayer = (Player) entity;
                Player receiver = event.getPlayer();

                if (packetPlayer.getUniqueId().equals(receiver.getUniqueId())) return;

                // Клонируем пакет
                event.setPacket(event.getPacket().deepClone());

                // В ProtocolLib для 1.16 атрибуты представлены списком WrappedAttribute
                // Проще всего просто удалить этот пакет для других игроков, так как клиенту не обязательно знать атрибуты чужих игроков
                event.setCancelled(true);
            }
        });

        plugin.getLogger().info("Fake HP (ProtocolLib Hook) enabled!");
    }
}