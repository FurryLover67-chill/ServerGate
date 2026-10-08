package dev.servergate;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerGatePlugin extends JavaPlugin {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Игроки, которые ещё не ввели пароль. */
    private final Set<UUID> locked = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lockedAt = new ConcurrentHashMap<>();
    private final Map<UUID, Component> pendingJoin = new ConcurrentHashMap<>();
    private NamespacedKey markKey;
    private final Set<UUID> verifying = ConcurrentHashMap.newKeySet();
    private final AttemptTracker tracker = new AttemptTracker();

    private volatile String salt = "";
    private volatile String hash = "";
    private volatile int maxAttempts = 3;
    private volatile int timeoutSeconds = 60;
    private volatile long lockoutMillis = 300_000L;
    private volatile boolean bypassOps = false;
    private volatile Set<String> allowedCommands = new HashSet<>();
    private boolean logFilterInstalled = false;

    @Override
    public void onEnable() {
        markKey = new NamespacedKey(this, "locked");
        saveDefaultConfig();
        loadSettings();

        if (getConfig().getBoolean("hide-password-from-log", true)) {
            logFilterInstalled = CommandLogFilter.install();
            if (!logFilterInstalled) {
                getLogger().warning("Не удалось установить фильтр лога: пароль может попасть в latest.log. Проверьте лог вручную.");
            }
        }

        getServer().getPluginManager().registerEvents(new GateListener(this), this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);

        // На случай /reload: заблокировать всех, кто уже на сервере.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isBypass(p)) lock(p);
        }
    }

    @Override
    public void onDisable() {
        for (UUID id : Set.copyOf(locked)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) release(p, false);
        }
    }

    // ------------------------------------------------------------------ настройки

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();

        String plain = c.getString("password", "");
        if (plain != null && !plain.isEmpty()) {
            storePassword(plain);
            getLogger().info("Пароль преобразован в хеш, открытый текст удалён из config.yml.");
        }

        salt = c.getString("password-salt", "");
        hash = c.getString("password-hash", "");
        maxAttempts = Math.max(1, c.getInt("max-attempts", 3));
        timeoutSeconds = Math.max(10, c.getInt("timeout-seconds", 60));
        lockoutMillis = Math.max(1, c.getInt("ip-lockout-seconds", 300)) * 1000L;
        bypassOps = c.getBoolean("bypass-ops", false);

        Set<String> cmds = new HashSet<>();
        for (String s : c.getStringList("allowed-commands")) cmds.add(s.toLowerCase());
        allowedCommands = cmds;

        if (!isConfigured()) {
            getLogger().severe("Пароль НЕ задан! Вход на сервер запрещён всем, пока вы не зададите его в config.yml.");
        }
    }

    private void storePassword(String plain) {
        String newSalt = PasswordHasher.newSalt();
        String newHash = PasswordHasher.hash(plain, newSalt);
        FileConfiguration c = getConfig();
        c.set("password", "");
        c.set("password-salt", newSalt);
        c.set("password-hash", newHash);
        saveConfig();
    }

    public boolean isConfigured() {
        return !salt.isEmpty() && !hash.isEmpty();
    }

    // ------------------------------------------------------------------ команды

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("gate")) {
            // Запасной путь: обычно команду перехватывает GateListener ещё до диспетчера.
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Команда только для игроков.");
                return true;
            }
            if (!isLocked(p)) {
                p.sendMessage(msg("already"));
                return true;
            }
            submit(p, String.join(" ", args));
            return true;
        }

        if (args.length == 0) return false;
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                loadSettings();
                sender.sendMessage("ServerGate: конфиг перезагружен.");
            }
            case "setpassword" -> {
                if (!(sender instanceof ConsoleCommandSender)) {
                    sender.sendMessage("Задавать пароль командой можно только из консоли "
                            + "(чтобы он не попал в чат). Или впишите его в config.yml.");
                    return true;
                }
                if (args.length < 2) return false;
                String pw = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                storePassword(pw);
                loadSettings();
                sender.sendMessage("ServerGate: пароль обновлён.");
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ сообщения

    Component msg(String key, String... pairs) {
        String raw = getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            raw = raw.replace("<" + pairs[i] + ">", pairs[i + 1]);
        }
        return MM.deserialize(raw);
    }

    // ------------------------------------------------------------------ состояние

    public boolean isLocked(Player p) {
        return locked.contains(p.getUniqueId());
    }

    public boolean isBypass(Player p) {
        return p.hasPermission("servergate.bypass") || (bypassOps && p.isOp());
    }

    public AttemptTracker tracker() {
        return tracker;
    }

    /** Имя команды без "/" и без пространства имён: gate или gpass. */
    public boolean isGateCommand(String name) {
        String n = name.toLowerCase();
        int colon = n.indexOf(':');
        if (colon >= 0) n = n.substring(colon + 1);
        return n.equals("gate") || n.equals("gpass");
    }

    public boolean isCommandAllowed(String name) {
        String n = name.toLowerCase();
        int colon = n.indexOf(':');
        if (colon >= 0) n = n.substring(colon + 1);
        return isGateCommand(n) || allowedCommands.contains(n);
    }

    public void rememberJoinMessage(Player p, Component message) {
        if (message != null) pendingJoin.put(p.getUniqueId(), message);
    }

    static String ipOf(Player p) {
        InetSocketAddress a = p.getAddress();
        return (a == null || a.getAddress() == null) ? "unknown" : a.getAddress().getHostAddress();
    }

    // ------------------------------------------------------------------ блокировка / разблокировка

    public void lock(Player p) {
        UUID id = p.getUniqueId();
        locked.add(id);
        lockedAt.put(id, System.currentTimeMillis());

        // Скрываем мир. Метим игрока, чтобы эффект гарантированно снялся потом
        // (даже если игрока кикнуло и слепота сохранилась в его данных).
        p.getPersistentDataContainer().set(markKey, PersistentDataType.BYTE, (byte) 1);
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                PotionEffect.INFINITE_DURATION, 0, false, false, false));

        // Взаимная невидимость: заблокированный не видит других, другие не видят его.
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(p)) continue;
            p.hidePlayer(this, o);
            o.hidePlayer(this, p);
        }

        p.leaveVehicle();
        p.closeInventory();
        p.updateCommands(); // обновит список команд (останется только /gate)

        p.showTitle(Title.title(msg("title"), msg("subtitle"),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(timeoutSeconds + 5L), Duration.ZERO)));
        p.sendMessage(msg("prompt"));
    }

    /** Снимает блокировку. announce = показать всем сообщение о входе. */
    public void release(Player p, boolean announce) {
        UUID id = p.getUniqueId();
        locked.remove(id);
        lockedAt.remove(id);
        verifying.remove(id);

        clearMark(p);
        p.clearTitle();
        p.sendActionBar(Component.empty());
        p.updateCommands(); // вернёт полный список команд

        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(p) || locked.contains(o.getUniqueId())) continue;
            p.showPlayer(this, o);
            o.showPlayer(this, p);
        }

        Component join = pendingJoin.remove(id);
        if (announce && join != null) {
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (!locked.contains(o.getUniqueId())) o.sendMessage(join);
            }
            Bukkit.getConsoleSender().sendMessage(join);
        }
    }

    /** Полная очистка состояния при выходе игрока. */
    public void cleanup(UUID id) {
        locked.remove(id);
        lockedAt.remove(id);
        pendingJoin.remove(id);
        verifying.remove(id);
    }

    /** Снимает нашу слепоту и метку (в т.ч. «залипшую» с прошлого сеанса). */
    public void clearMark(Player p) {
        if (p.getPersistentDataContainer().has(markKey, PersistentDataType.BYTE)) {
            p.removePotionEffect(PotionEffectType.BLINDNESS);
            p.getPersistentDataContainer().remove(markKey);
        }
    }

    // ------------------------------------------------------------------ проверка пароля

    /** Только из основного потока. */
    void submit(Player p, String input) {
        UUID id = p.getUniqueId();
        if (!locked.contains(id)) return;
        if (input == null || input.isEmpty()) {
            p.sendMessage(msg("empty"));
            return;
        }
        if (!verifying.add(id)) return; // уже идёт проверка

        final String s = salt;
        final String h = hash;
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            boolean ok;
            try {
                ok = isConfigured() && PasswordHasher.verify(input, s, h);
            } catch (Exception e) {
                ok = false;
            }
            final boolean result = ok;
            getServer().getScheduler().runTask(this, () -> {
                verifying.remove(id);
                Player q = Bukkit.getPlayer(id);
                if (q == null || !locked.contains(id)) return;
                if (result) success(q);
                else failure(q);
            });
        });
    }

    private void success(Player p) {
        tracker.clear(ipOf(p));
        release(p, true);
        p.sendMessage(msg("success"));
        getLogger().info(p.getName() + " прошёл проверку общего пароля.");
    }

    private void failure(Player p) {
        String ip = ipOf(p);
        int left = tracker.registerFailure(ip, maxAttempts, lockoutMillis);
        // Сам введённый пароль никогда не логируется.
        getLogger().warning("Неверный общий пароль: " + p.getName() + " (" + ip + ")");
        if (left <= 0) {
            p.kick(msg("kick-too-many"));
            return;
        }
        p.sendMessage(msg("wrong", "attempts", String.valueOf(left)));
    }

    // ------------------------------------------------------------------ периодическая задача

    private void tick() {
        long now = System.currentTimeMillis();
        for (UUID id : Set.copyOf(locked)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                cleanup(id);
                continue;
            }
            Long since = lockedAt.get(id);
            long elapsed = since == null ? 0 : now - since;
            if (elapsed > timeoutSeconds * 1000L) {
                p.kick(msg("kick-timeout"));
                continue;
            }
            // Если у игрока каким-то образом открыто окно (сундук и т.п.) - закрываем.
            if (p.getOpenInventory().getType() != InventoryType.CRAFTING) {
                p.closeInventory();
            }
            long left = Math.max(0, timeoutSeconds - elapsed / 1000L);
            p.sendActionBar(msg("actionbar", "seconds", String.valueOf(left)));
        }
    }
}
