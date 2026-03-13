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
                        // В версиях 1.16 индекс здоровья у игрока равен 9.
                        if (watchable.getIndex() == 9 && watchable.getValue() instanceof Float) {
                            // Так как пакет уже глубоко клонирован, мы можем безопасно менять значение
                            watchable.setValue(20.0f);
                        }
                    }
                }
            }
        });
        plugin.getLogger().info("Fake HP (ProtocolLib Hook) enabled!");
    }
}