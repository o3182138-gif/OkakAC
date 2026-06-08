import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class NoSwingHook {

    public static void register(Plugin plugin) {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(plugin, PacketType.Play.Client.ARM_ANIMATION) {
                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        Player player = event.getPlayer();
                        if (player != null && ((Main) plugin).isAntiCheatEnabled()) {
                            ((Main) plugin).setLastSwingTime(player.getUniqueId(), System.currentTimeMillis());
                        }
                    }
                }
        );
    }
}
