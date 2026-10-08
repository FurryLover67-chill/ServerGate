package dev.servergate;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;

public final class GateListener implements Listener {

    private final ServerGatePlugin plugin;

    public GateListener(ServerGatePlugin plugin) {
        this.plugin = plugin;
    }

    private boolean locked(Object o) {
        return o instanceof Player p && plugin.isLocked(p);
    }

    // ------------------------------------------------------------ вход / выход

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (!plugin.isConfigured()) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.msg("not-configured"));
            return;
        }
        long left = plugin.tracker().blockedMillisLeft(e.getAddress().getHostAddress());
        if (left > 0) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.msg("blocked", "seconds", String.valueOf((left + 999) / 1000)));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (plugin.isBypass(p)) return;
        // Сообщение о входе покажем остальным только после ввода пароля.
        plugin.rememberJoinMessage(p, e.joinMessage());
        e.joinMessage(null);
        plugin.lock(p);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (plugin.isLocked(p)) e.quitMessage(null);
        plugin.cleanup(p.getUniqueId());
    }

    // ------------------------------------------------------------ чат и команды

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (plugin.isLocked(p)) {
            // Писать в чат нельзя вообще. Сообщение никому не уходит и не логируется.
            e.setCancelled(true);
            p.sendMessage(plugin.msg("chat-blocked"));
            return;
        }
        // Заблокированные игроки не видят чат.
        e.viewers().removeIf(a -> a instanceof Player v && plugin.isLocked(v));
    }

    /**
     * Перехватываем /gate на самом раннем приоритете и отменяем событие:
     * пароль не доходит ни до AuthMe, ни до других плагинов, ни до диспетчера команд.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!plugin.isLocked(p)) return;

        String line = e.getMessage();
        String body = line.startsWith("/") ? line.substring(1) : line;
        int space = body.indexOf(' ');
        String name = space < 0 ? body : body.substring(0, space);

        if (plugin.isGateCommand(name)) {
            e.setCancelled(true);
            String password = space < 0 ? "" : body.substring(space + 1);
            plugin.submit(p, password);
            return;
        }
        if (!plugin.isCommandAllowed(name)) {
            e.setCancelled(true);
            p.sendMessage(plugin.msg("only-gate"));
        }
    }

    /** Заблокированный игрок видит в подсказках только /gate. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandSend(PlayerCommandSendEvent e) {
        if (!plugin.isLocked(e.getPlayer())) return;
        e.getCommands().removeIf(c -> !plugin.isCommandAllowed(c));
    }

    // ------------------------------------------------------------ "овощ": полная заморозка

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent e) {
        if (!plugin.isLocked(e.getPlayer())) return;
        if (e.hasChangedPosition() || e.hasChangedOrientation()) {
            e.setTo(e.getFrom().clone()); // ни шагов, ни поворота головы
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        cancelIfLocked(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        cancelIfLocked(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        cancelIfLocked(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockDamage(BlockDamageEvent e) {
        cancelIfLocked(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        cancelIfLocked(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) {
        cancelIfLocked(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        cancelIfLocked(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketFill(PlayerBucketFillEvent e) {
        cancelIfLocked(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent e) {
        cancelIfLocked(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        cancelIfLocked(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHeld(PlayerItemHeldEvent e) {
        cancelIfLocked(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsume(PlayerItemConsumeEvent e) {
        cancelIfLocked(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onShear(PlayerShearEntityEvent e) {
        cancelIfLocked(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFish(PlayerFishEvent e) {
        cancelIfLocked(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBook(PlayerEditBookEvent e) {
        cancelIfLocked(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBed(PlayerBedEnterEvent e) {
        cancelIfLocked(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFlight(PlayerToggleFlightEvent e) {
        cancelIfLocked(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSprint(PlayerToggleSprintEvent e) {
        cancelIfLocked(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwing(PlayerAnimationEvent e) {
        cancelIfLocked(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGlide(EntityToggleGlideEvent e) {
        if (locked(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVehicle(VehicleEnterEvent e) {
        if (locked(e.getEntered())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent e) {
        if (locked(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFood(FoodLevelChangeEvent e) {
        if (locked(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onShoot(EntityShootBowEvent e) {
        if (locked(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProjectile(ProjectileLaunchEvent e) {
        if (locked(e.getEntity().getShooter())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        if (locked(e.getRemover())) e.setCancelled(true);
    }

    // --- урон: ни получать, ни наносить

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (locked(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamageByEntity(EntityDamageByEntityEvent e) {
        Object damager = e.getDamager();
        if (damager instanceof Projectile proj) damager = proj.getShooter();
        if (locked(damager)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTarget(EntityTargetEvent e) {
        if (locked(e.getTarget())) e.setCancelled(true);
    }

    // --- инвентарь: ни открыть, ни двигать предметы

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInvOpen(InventoryOpenEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInvClick(InventoryClickEvent e) {
        if (locked(e.getWhoClicked())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInvDrag(InventoryDragEvent e) {
        if (locked(e.getWhoClicked())) e.setCancelled(true);
    }

    // ------------------------------------------------------------ helper

    private void cancelIfLocked(Player p, Event e) {
        if (plugin.isLocked(p) && e instanceof org.bukkit.event.Cancellable c) {
            c.setCancelled(true);
        }
    }
}
