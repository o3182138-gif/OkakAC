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
                UUID uuid = player.getUniqueId();

                Main.PlayerMovementTracker tracker = ((Main) plugin).movementTrackers.get(uuid);
                if (tracker != null) {
                    tracker.swungArm = true;
                }
            }
        });
        plugin.getLogger().info("NoSwing (ProtocolLib Hook) enabled!");
    }
}
