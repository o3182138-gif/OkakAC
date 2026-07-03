import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedWatchableObject;

import java.util.*;

public class Main extends JavaPlugin implements Listener, CommandExecutor {

    private boolean isAntiCheatEnabled = true;

    public boolean isAntiCheatEnabled() {
        return isAntiCheatEnabled;
    }

    private final Map<UUID, TrainingSession> sessions = new HashMap<>();
    private final Map<UUID, Double> playerProbability = new HashMap<>();
    private final Map<UUID, Integer> violations = new HashMap<>();
    private final Map<UUID, PlayerMovementTracker> movementTrackers = new HashMap<>();
    private final Map<UUID, UUID> activeVisions = new HashMap<>();
    private final Map<UUID, Location> lastSafeLocation = new HashMap<>();
    private final Map<UUID, Integer> cpsTracker = new HashMap<>();

    // Модели (Легит/Чит)
    private double cReach = 4.0, cAngle = 25.0, cSnap = 45.0, cJitter = 4.0;
    private double lReach = 3.0, lAngle = 12.0, lSnap = 15.0, lJitter = 1.0, lPrecision = 3.0;
    private double lHitVariance = 0.02;
    private double lAngleVariance = 5.0;
    private double lDeltaYawVariance = 10.0;
    private boolean modelReady = false;

    // Dynamic Speed Limits (обученные)
    private double baseSpeedLimitH = 0.35;

    // Punishment Mode (kick, warn, flag)
    private String punishmentMode = "kick";

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("ac").setExecutor(this);
        getCommand("acabuch").setExecutor(this);

        // Очистка CPS каждую секунду
        Bukkit.getScheduler().runTaskTimer(this, cpsTracker::clear, 20L, 20L);

        setupFakeHP();

        getLogger().info("OkakAC Vision 7.4 (Full & Dynamic) Loaded.");
    }

    private void setupFakeHP() {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().warning("ProtocolLib not found! Fake HP feature is disabled.");
            return;
        }

        FakeHPHook.register(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("ac")) {
            if (args.length > 1 && args[0].equalsIgnoreCase("vision")) {
                Player target = Bukkit.getPlayer(args[1]);
                if (target != null && sender instanceof Player) {
                    UUID adminId = ((Player) sender).getUniqueId();
                    if (activeVisions.containsKey(adminId)) activeVisions.remove(adminId);
                    else activeVisions.put(adminId, target.getUniqueId());
                    sender.sendMessage("§8[AC] §7Vision для §f" + target.getName() + " §7изменен.");
                }
                return true;
            }
            if (args.length > 0 && (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off"))) {
                isAntiCheatEnabled = args[0].equalsIgnoreCase("on");
                sender.sendMessage("§8[AC] §7Статус защиты: " + (isAntiCheatEnabled ? "§aВКЛ" : "§cВЫКЛ"));
                return true;
            }
            if (args.length > 1 && args[0].equalsIgnoreCase("mode")) {
                String mode = args[1].toLowerCase();
                if (mode.equals("kick") || mode.equals("warn") || mode.equals("flag")) {
                    punishmentMode = mode;
                    sender.sendMessage("§8[AC] §7Режим наказания изменен на: §e" + mode.toUpperCase());
                } else {
                    sender.sendMessage("§cИспользование: /ac mode <kick/warn/flag>");
                }
                return true;
            }
        }

        if (command.getName().equalsIgnoreCase("acabuch") && args.length >= 4) {
            // Usage: /acabuch <cheat/legit> <killaura/speed> <player> <on/off>
            Player target = Bukkit.getPlayer(args[2]);
            if (target == null) return false;

            String modeType = args[1].toLowerCase(); // killaura или speed

            if (args[3].equalsIgnoreCase("on")) {
                sessions.put(target.getUniqueId(), new TrainingSession(args[0].equalsIgnoreCase("cheat"), modeType));
                sender.sendMessage("§e[Training] §7Запись §a" + modeType.toUpperCase() + " §7в режиме: §f" + args[0].toUpperCase());
            } else {
                finishTraining(target.getUniqueId(), sender);
            }
            return true;
        }
        return false;
    }

    private void finishTraining(UUID uuid, CommandSender sender) {
        TrainingSession s = sessions.get(uuid);
        if (s == null) return;

        if (s.type.equals("speed")) {
            if (s.maxSpeedH > 0) {
                // Если обучали на legit скорость
                if (!s.isCheat) {
                    baseSpeedLimitH = s.maxSpeedH + 0.02; // Добавляем небольшую погрешность
                    sender.sendMessage("§a[AC] Модель ЛЕГИТА (Speed) обновлена. Базовый лимит: " + String.format("%.3f", baseSpeedLimitH));
                } else {
                    sender.sendMessage("§c[AC] Запись ЧИТ-скорости завершена (макс: " + String.format("%.3f", s.maxSpeedH) + ")");
                }
            } else {
                sender.sendMessage("§e[Training] Недостаточно данных о скорости.");
            }
        } else {
            if (s.hits == 0) return;
            if (s.isCheat) {
                cReach = s.maxDist; cAngle = s.totalAngle / s.hits;
                sender.sendMessage("§c[AC] Модель ЧИТА (KillAura) обновлена.");
            } else {
                lReach = s.maxDist; lAngle = s.totalAngle / s.hits;
                lHitVariance = s.getHitVariance();
                lAngleVariance = s.getAngleVariance();
                lDeltaYawVariance = s.getDeltaYawVariance();
                sender.sendMessage("§a[AC] Модель ЛЕГИТА (KillAura) обновлена. (HitVar: " + String.format("%.3f", lHitVariance) + ", AngleVar: " + String.format("%.3f", lAngleVariance) + ")");
            }
            modelReady = true;
        }

        sessions.remove(uuid);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (!isAntiCheatEnabled) return;
        if (e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK) {
            Player p = e.getPlayer();
            UUID id = p.getUniqueId();
            PlayerMovementTracker t = movementTrackers.computeIfAbsent(id, k -> new PlayerMovementTracker());
            t.recordClick(System.currentTimeMillis());

            double clickVar = t.getClickVariance();
            if (clickVar < 15.0 && t.clickDelays.size() >= 15) {
                processViolation(p, 40, "AutoClicker (var: " + String.format("%.2f", clickVar) + ")");
                t.clickDelays.clear(); // Reset to avoid spam
            }
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!isAntiCheatEnabled) return;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        Location from = e.getFrom();
        Location to = e.getTo();

        if (to == null) return;

        PlayerMovementTracker t = movementTrackers.computeIfAbsent(id, k -> new PlayerMovementTracker());
        t.update(from, to);
        t.recordRotation(to.getYaw(), to.getPitch());

        if (p.isFlying() || p.getAllowFlight() || p.isInsideVehicle() || p.isGliding() || p.isRiptiding()) {
            if (p.isOnGround()) lastSafeLocation.put(id, from);
            return;
        }

        double distX = to.getX() - from.getX();
        double distZ = to.getZ() - from.getZ();

        double speedH = Math.sqrt(distX * distX + distZ * distZ);
        double distY = to.getY() - from.getY();

        // Если включено обучение скорости
        if (sessions.containsKey(id)) {
            TrainingSession s = sessions.get(id);
            if (s.type.equals("speed")) {
                s.recordSpeed(speedH);
            }
        }

        // Speed Check
        double limitH = baseSpeedLimitH; // Изначально 0.35 или обученное значение
        if (p.isSprinting()) limitH += 0.28;
        if (p.hasPotionEffect(PotionEffectType.SPEED)) {
            limitH += 0.18 * (p.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1);
        }

        // Ice, slime, etc. can increase speed, so add a bit of leniency
        if (!p.isOnGround()) {
            limitH += 0.35; // Jumping allows more horizontal movement per tick
        }

        boolean flagged = false;
        String flagReason = "";

        if (speedH > limitH && p.getNoDamageTicks() == 0) {
            flagged = true;
            flagReason = "Speed (" + String.format("%.2f", speedH) + " > " + String.format("%.2f", limitH) + ")";
        }

        // Fly / Hover Check
        // Gravity usually pulls player down. If they are in air and moving up without jump, or hovering (distY == 0), it's sus.
        // For simplicity, we check if they are in air for too long without falling properly.
        if (!p.isOnGround() && !from.getBlock().isLiquid() && !to.getBlock().isLiquid()) {
            Material blockUnder = to.clone().subtract(0, 0.1, 0).getBlock().getType();
            if (blockUnder == Material.AIR) {
                t.airTicks++;
                if (t.airTicks > 15 && distY >= 0) {
                    flagged = true;
                    flagReason = "Fly/Hover (airTicks=" + t.airTicks + ", dy=" + String.format("%.2f", distY) + ")";
                }
            } else {
                t.airTicks = 0;
            }
        } else {
            t.airTicks = 0;
        }

        // NoFall Check
        if (p.isOnGround() && distY < -0.1 && p.getFallDistance() == 0.0f) {
            boolean allAir = true;
            for (int dx = -3; dx <= 3; dx += 3) {
                for (int dz = -3; dz <= 3; dz += 3) {
                    Location check = to.clone().add(dx / 10.0, -0.1, dz / 10.0);
                    if (check.getBlock().getType() != Material.AIR) {
                        allAir = false;
                        break;
                    }
                }
            }
            if (allAir) {
                flagged = true;
                flagReason = "NoFall";
            }
        }

        if (flagged) {
            e.setTo(lastSafeLocation.getOrDefault(id, from)); // Rubberband

            // Increment violation and notify
            int vl = violations.getOrDefault(id, 0) + 1;
            violations.put(id, vl);
            notifyAdmins("§8[§cOkakAC§8] §e" + p.getName() + " §7Flag: §c" + flagReason + " §8(VL: " + vl + ")");

            if (vl >= 20) {
                handlePunishment(p, "Suspicious Movement (" + flagReason + ")");
                violations.put(id, 0); // Reset after punishing
            }
        } else {
            if (p.isOnGround()) {
                lastSafeLocation.put(id, from);
            }
        }
    }

    @EventHandler
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!isAntiCheatEnabled || !(event.getDamager() instanceof Player)) return;

        Player player = (Player) event.getDamager();
        Entity target = event.getEntity();
        UUID uuid = player.getUniqueId();

        Location eye = player.getEyeLocation();
        Location tarBase = target.getLocation();
        Location tarCenter = tarBase.clone().add(0, target.getHeight() * 0.5, 0);
        double dist = eye.distance(tarCenter);

        if (dist > 4.2 || hasBlockBetween(eye, tarCenter)) {
            event.setCancelled(true);
            return;
        }

        // RayTracing
        double hitHeightRatio = 0.5;
        RayTraceResult rayResult = target.getBoundingBox().rayTrace(eye.toVector(), eye.getDirection(), dist + 1.0);
        if (rayResult != null) {
            hitHeightRatio = (rayResult.getHitPosition().getY() - tarBase.getY()) / target.getHeight();
        }

        Vector look = eye.getDirection();
        Vector toT = tarCenter.toVector().subtract(eye.toVector()).normalize();
        double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, look.dot(toT)))));

        PlayerMovementTracker tracker = movementTrackers.computeIfAbsent(uuid, k -> new PlayerMovementTracker());

        boolean targetMoving = target.getVelocity().length() > 0.05;
        boolean playerMoving = player.getVelocity().length() > 0.05;

        tracker.recordHit(dist, hitHeightRatio);
        tracker.recordAngle(angle);

        double hitVar = tracker.getHitVariance();
        double distVar = tracker.getDistVariance();
        double angleVar = tracker.getAngleVariance();
        double deltaYawVar = tracker.getDeltaYawVariance();

        if (sessions.containsKey(uuid)) {
            TrainingSession s = sessions.get(uuid);
            if (s.type.equals("killaura")) {
                s.record(dist, tracker.jitterScore, angle, tracker.currentDeltaYaw, eye.getPitch(), hitHeightRatio);
                return;
            }
        }

        if (!modelReady) return;

        double chance = 0;
        StringBuilder reasons = new StringBuilder();

        // 1. Static Hitpoint / Target Box Checking
        if (Math.abs(hitHeightRatio - tracker.lastHitRatio) < 0.0001) {
            tracker.stableHits++;
        } else {
            tracker.stableHits = 0;
        }
        tracker.lastHitRatio = hitHeightRatio;

        if (tracker.stableHits >= 3 && playerMoving) {
            chance += 65;
            reasons.append("Static HitBox ");
        }

        // 2. Reach Consistency
        if (distVar < 0.001 && tracker.hitDistances.size() >= 5 && playerMoving) {
            chance += 40;
            reasons.append("Reach Consistency ");
        }

        // 3. Aim & Tracking (Прилипание)
        if (angle > lAngle * 1.5) {
            chance += 30;
            reasons.append("Angle Hit ");
        }
        if (angle < 0.8 && (targetMoving || playerMoving)) {
            chance += 50;
            reasons.append("Aim Lock ");
        }

        // 3.5 Constant Aim Tracking
        if (angle < 5.0 && (targetMoving || playerMoving)) {
            tracker.trackingTicks++;
            if (tracker.trackingTicks > 8) {
                chance += 50;
                reasons.append("Constant Tracking ");
            }
        } else {
            tracker.trackingTicks = 0;
        }

        // 4. CPS
        int cps = cpsTracker.getOrDefault(uuid, 0) + 1;
        cpsTracker.put(uuid, cps);
        if (cps > 15) {
            chance += 25;
            reasons.append("High CPS ");
        }

        // 5. GCD (Greatest Common Divisor) Flaw
        float deltaPitch = Math.abs(player.getLocation().getPitch() - tracker.lastPitch);

        if (deltaPitch > 0 && deltaPitch < 0.01) {
            tracker.gcdFlaws++;
            if (tracker.gcdFlaws > 5) {
                chance += 20;
                reasons.append("GCD Flaw ");
            }
        } else {
            tracker.gcdFlaws = 0;
        }

        // 6. Snap Detection using acceleration
        if (tracker.lastAccelYaw > 20.0f && angle < 5.0) {
            chance += 35;
            reasons.append("Snap Aim ");
        }

        // 7. Head Randomizer Detection (High yaw variance, very low angle variance)
        if (deltaYawVar > 20.0 && angleVar < 1.0 && tracker.angles.size() > 5) {
            chance += 45;
            reasons.append("Head Randomizer ");
        }

        // Vision Stats для админа
        if (activeVisions.containsValue(uuid)) {
            sendVisionStats(uuid, player.getName(), dist, angle, hitVar, (double) tracker.stableHits);
        }

        // Вердикт
        if (chance > 85) {
            event.setCancelled(true);
            processViolation(player, chance, reasons.toString().trim());
        } else if (chance > 40) {
            processViolation(player, chance * 0.3, reasons.toString().trim());
        }
    }

    private void processViolation(Player p, double chance) {
        processViolation(p, chance, "Suspicious Combat (KillAura/Aim)");
    }

    private void processViolation(Player p, double chance, String reason) {
        UUID id = p.getUniqueId();
        double currentProb = playerProbability.getOrDefault(id, 0.0);
        double newProb = (currentProb * 0.7) + (chance * 0.3);
        playerProbability.put(id, newProb);

        if (newProb > 80) {
            int vl = violations.getOrDefault(id, 0) + 1;
            violations.put(id, vl);
            notifyAdmins("§8[§cOkakAC§8] §e" + p.getName() + " §7Обнаружено! §c" + String.format("%.0f", newProb) + "% §8(VL: " + vl + ") §7Причина: " + reason);
            if (vl >= 10) {
                handlePunishment(p, reason);
                violations.put(id, 0);
                playerProbability.put(id, 0.0);
            }
        }
    }

    private void handlePunishment(Player player, String reason) {
        Bukkit.getScheduler().runTask(this, () -> {
            switch (punishmentMode.toLowerCase()) {
                case "kick":
                    player.kickPlayer("§cVision: Вы были кикнуты за " + reason);
                    break;
                case "warn":
                    player.sendMessage("§c§l[ВНИМАНИЕ] §eАнтичит обнаружил подозрительные действия: §f" + reason);
                    notifyAdmins("§8[§cOkakAC§8] §e" + player.getName() + " §7получил предупреждение за §c" + reason);
                    break;
                case "flag":
                    notifyAdmins("§8[§cOkakAC§8] §c[SILENT FLAG] §e" + player.getName() + " §7должен был быть кикнут за §c" + reason);
                    break;
            }
        });
    }

    private boolean hasBlockBetween(Location s, Location e) {
        Vector d = e.toVector().subtract(s.toVector());
        double dist = s.distance(e); d.normalize();
        for (double i = 0.2; i < dist; i += 0.1) {
            Location check = s.clone().add(d.clone().multiply(i));
            Material m = check.getBlock().getType();
            if (m.isSolid()) {
                String n = m.name();
                if (n.contains("GLASS") || n.contains("FENCE") || n.contains("DOOR") || n.contains("SLAB") || n.contains("STAIRS")) continue;
                return true;
            }
        }
        return false;
    }

    private void sendVisionStats(UUID id, String n, double d, double a, double v, double s) {
        activeVisions.forEach((ai, ti) -> {
            if (ti.equals(id)) {
                Player adm = Bukkit.getPlayer(ai);
                if (adm != null) adm.sendMessage(String.format("§8[V] §e%s §7D:%.1f A:%.1f V:%.3f S:%.0f", n, d, a, v, s));
            }
        });
    }

    private void notifyAdmins(String m) {
        Bukkit.getOnlinePlayers().stream().filter(Player::isOp).forEach(p -> p.sendMessage(m));
    }

    // Вспомогательные классы
    private static class PlayerMovementTracker {
        double lastHitRatio = -1;
        int stableHits = 0;
        double jitterScore = 0;
        int gcdFlaws = 0;
        float lastPitch = 0.0f;
        float lastYaw = 0.0f;
        float lastDeltaYaw = 0.0f;
        float currentDeltaYaw = 0.0f;
        float lastAccelYaw = 0.0f;
        int airTicks = 0;
        int trackingTicks = 0;
        long lastClickTime = 0;
        LinkedList<Double> hitRatios = new LinkedList<>();
        LinkedList<Double> hitDistances = new LinkedList<>();
        LinkedList<Float> deltaYaws = new LinkedList<>();
        LinkedList<Double> angles = new LinkedList<>();
        LinkedList<Long> clickDelays = new LinkedList<>();

        void recordRotation(float yaw, float pitch) {
            float deltaPitch = Math.abs(pitch - lastPitch);
            float dYaw = yaw - lastYaw;

            while (dYaw > 180f) { dYaw -= 360f; }
            while (dYaw < -180f) { dYaw += 360f; }

            currentDeltaYaw = Math.abs(dYaw);
            lastAccelYaw = Math.abs(currentDeltaYaw - lastDeltaYaw);

            deltaYaws.add(currentDeltaYaw);
            if (deltaYaws.size() > 20) deltaYaws.removeFirst();

            lastDeltaYaw = currentDeltaYaw;
            lastPitch = pitch;
            lastYaw = yaw;
        }

        void recordAngle(double angle) {
            angles.add(angle);
            if (angles.size() > 10) angles.removeFirst();
        }

        double getDeltaYawVariance() {
            if (deltaYaws.size() < 3) return 0;
            double avg = deltaYaws.stream().mapToDouble(d -> d).average().orElse(0);
            return deltaYaws.stream().mapToDouble(d -> Math.pow(d - avg, 2)).sum() / deltaYaws.size();
        }

        double getAngleVariance() {
            if (angles.size() < 3) return 0;
            double avg = angles.stream().mapToDouble(d -> d).average().orElse(0);
            return angles.stream().mapToDouble(d -> Math.pow(d - avg, 2)).sum() / angles.size();
        }

        void recordClick(long time) {
            if (lastClickTime != 0) {
                long delay = time - lastClickTime;
                clickDelays.add(delay);
                if (clickDelays.size() > 20) clickDelays.removeFirst();
            }
            lastClickTime = time;
        }

        double getClickVariance() {
            if (clickDelays.size() < 5) return 100.0;
            double avg = clickDelays.stream().mapToDouble(d -> d).average().orElse(0);
            return clickDelays.stream().mapToDouble(d -> Math.pow(d - avg, 2)).sum() / clickDelays.size();
        }

        void recordHit(double dist, double ratio) {
            hitRatios.add(ratio);
            hitDistances.add(dist);
            if (hitRatios.size() > 10) hitRatios.removeFirst();
            if (hitDistances.size() > 10) hitDistances.removeFirst();
        }

        double getHitVariance() {
            if (hitRatios.size() < 3) return 0.1;
            double avg = hitRatios.stream().mapToDouble(d -> d).average().orElse(0);
            return hitRatios.stream().mapToDouble(d -> Math.pow(d - avg, 2)).sum() / hitRatios.size();
        }

        double getDistVariance() {
            if (hitDistances.size() < 3) return 0.1;
            double avg = hitDistances.stream().mapToDouble(d -> d).average().orElse(0);
            return hitDistances.stream().mapToDouble(d -> Math.pow(d - avg, 2)).sum() / hitDistances.size();
        }

        void update(Location f, Location t) {
            double dy = Math.abs(t.getYaw() - f.getYaw());
            if (dy > 0.1 && dy < 10) jitterScore = Math.min(5, jitterScore + 0.5);
            else jitterScore = Math.max(0, jitterScore - 0.1);
        }
    }

    private static class TrainingSession {
        boolean isCheat;
        String type; // "killaura" или "speed"
        int hits = 0;
        double totalDist = 0, totalJitter = 0, totalAngle = 0, maxDist = 0;
        double maxSpeedH = 0; // Максимальная записанная скорость по X/Z
        List<Double> hitHeights = new ArrayList<>();
        List<Double> angles = new ArrayList<>();
        List<Double> deltaYaws = new ArrayList<>();

        TrainingSession(boolean c, String t) { isCheat = c; type = t; }
        void record(double d, double j, double a, double s, float p, double h) {
            hits++; totalDist += d; totalAngle += a; hitHeights.add(h); angles.add(a); deltaYaws.add(s);
            if (d > maxDist) maxDist = d;
        }
        void recordSpeed(double speedH) {
            if (speedH > maxSpeedH) maxSpeedH = speedH;
        }
        double getHitVariance() {
            if (hitHeights.size() < 2) return 0;
            double avg = hitHeights.stream().mapToDouble(d -> d).average().orElse(0);
            return hitHeights.stream().mapToDouble(d -> Math.pow(d - avg, 2)).sum() / hitHeights.size();
        }
        double getAngleVariance() {
            if (angles.size() < 2) return 0;
            double avg = angles.stream().mapToDouble(d -> d).average().orElse(0);
            return angles.stream().mapToDouble(d -> Math.pow(d - avg, 2)).sum() / angles.size();
        }
        double getDeltaYawVariance() {
            if (deltaYaws.size() < 2) return 0;
            double avg = deltaYaws.stream().mapToDouble(d -> d).average().orElse(0);
            return deltaYaws.stream().mapToDouble(d -> Math.pow(d - avg, 2)).sum() / deltaYaws.size();
        }
    }
}