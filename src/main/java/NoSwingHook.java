import org.bukkit.entity.Player;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;

public class NoSwingHook {
    public static void register(Main mainPlugin) {
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(mainPlugin, ListenerPriority.NORMAL, PacketType.Play.Client.ARM_ANIMATION) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (!mainPlugin.isAntiCheatEnabled()) return;
                Player player = event.getPlayer();
                if (player == null) return;
                Main.PlayerMovementTracker tracker = mainPlugin.getMovementTracker(player.getUniqueId());
                tracker.lastSwingTime = System.currentTimeMillis();
            }
        });
        mainPlugin.getLogger().info("NoSwing (ProtocolLib Hook) enabled!");
    }
}
