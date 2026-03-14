import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedWatchableObject;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.EnumWrappers.NativeGameMode;

import java.util.List;
import java.util.Random;

public class FakeHPHook {
    private static final Random RANDOM = new Random();

    public static void broadcastFakeHP(Player player) {
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
        com.comphenix.protocol.events.PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA);

        packet.getIntegers().write(0, player.getEntityId());

        // Создаем фальшивые метаданные здоровья
        float fakeHp = 0.5f + RANDOM.nextFloat() * 34.5f;

        WrappedWatchableObject watchableHealth = new WrappedWatchableObject(8, fakeHp);
        WrappedWatchableObject watchableAbsorption = new WrappedWatchableObject(14, 0.0f);

        packet.getWatchableCollectionModifier().write(0, java.util.Arrays.asList(watchableHealth, watchableAbsorption));

        // Отправляем всем, кроме самого себя
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.getUniqueId().equals(player.getUniqueId())) {
                try {
                    protocolManager.sendServerPacket(online, packet);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

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
                    float fakeHp = 0.5f + RANDOM.nextFloat() * 34.5f;
                    for (WrappedWatchableObject watchable : watchableObjects) {
                        // В версиях 1.16 индекс здоровья у игрока равен 8.
                        if (watchable.getIndex() == 8 && watchable.getValue() instanceof Float) {
                            watchable.setValue(fakeHp);
                        }
                        // Индекс абсорбции (золотые яблоки, тотемы) - 14
                        if (watchable.getIndex() == 14 && watchable.getValue() instanceof Float) {
                            watchable.setValue(0.0f);
                        }
                    }
                }
            }
        });

        // Hook PlayerInfo to hide Gamemode
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Server.PLAYER_INFO) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (!((Main) plugin).isAntiCheatEnabled()) return;

                event.setPacket(event.getPacket().deepClone());
                List<PlayerInfoData> infoDataList = event.getPacket().getPlayerInfoDataLists().read(0);
                boolean modified = false;

                for (int i = 0; i < infoDataList.size(); i++) {
                    PlayerInfoData infoData = infoDataList.get(i);
                    // Don't modify for the player themselves receiving the packet
                    if (infoData.getProfile().getId().equals(event.getPlayer().getUniqueId())) continue;

                    PlayerInfoData newInfoData = new PlayerInfoData(
                            infoData.getProfile(),
                            infoData.getLatency(),
                            NativeGameMode.CREATIVE, // Fake gamemode 1
                            infoData.getDisplayName()
                    );
                    infoDataList.set(i, newInfoData);
                    modified = true;
                }

                if (modified) {
                    event.getPacket().getPlayerInfoDataLists().write(0, infoDataList);
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