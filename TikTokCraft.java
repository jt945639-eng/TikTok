package com.tiktokcraft;

import com.google.gson.JsonObject;
import io.github.jwdeveloper.tiktok.TikTokLive;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Plugin principal: TikTokCraft (todo el código en un solo archivo). */
public class TikTokCraft extends JavaPlugin {

    private TikTokConnection tiktok;
    private EventRouter router;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        start();
        getLogger().info("TikTokCraft activado.");
    }

    @Override
    public void onDisable() {
        stop();
    }

    private void start() {
        stop();
        router = new EventRouter(this, new ActionExecutor(this));
        tiktok = new TikTokConnection(this, router);
        if (getConfig().getBoolean("tiktok.auto-connect", true)) {
            tiktok.connect(getConfig().getString("tiktok.username", ""));
        }
    }

    private void stop() {
        if (tiktok != null) {
            tiktok.disconnect();
            tiktok = null;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Uso: /tiktokcraft reload | gift <nombre_con_guion_bajo> [cantidad] | like <cantidad> | chat <comando> | follow | connect [usuario] | disconnect");
            return true;
        }
        String user = sender.getName();
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                reloadConfig();
                start();
                sender.sendMessage("Configuración recargada.");
            }
            case "gift" -> {
                if (args.length < 2) {
                    sender.sendMessage("Uso: /tiktokcraft gift <nombre> [cantidad]  (usa _ en vez de espacios)");
                    return true;
                }
                int count = args.length > 2 ? parse(args[2], 1) : 1;
                router.gift(user, args[1].replace('_', ' '), count);
            }
            case "like" -> router.like(user, args.length > 1 ? parse(args[1], 1) : 1);
            case "chat" -> {
                if (args.length < 2) {
                    sender.sendMessage("Uso: /tiktokcraft chat <comando>");
                    return true;
                }
                router.chat(user, getConfig().getString("chat.prefix", "!") + args[1]);
            }
            case "follow" -> router.follow(user);
            case "connect" -> {
                String name = args.length > 1 ? args[1] : getConfig().getString("tiktok.username", "");
                tiktok.connect(name);
                sender.sendMessage("Conectando con el LIVE de TikTok...");
            }
            case "disconnect" -> {
                tiktok.disconnect();
                sender.sendMessage("Desconectado de TikTok.");
            }
            default -> sender.sendMessage("Subcomando desconocido.");
        }
        return true;
    }

    private int parse(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}

/** Conexión directa con el LIVE de TikTok (librería TikTokLiveJava). No necesita Node.js. */
class TikTokConnection {

    private final TikTokCraft plugin;
    private final EventRouter router;
    private Object client;
    private boolean connecting = false;

    TikTokConnection(TikTokCraft plugin, EventRouter router) {
        this.plugin = plugin;
        this.router = router;
    }

    synchronized void connect(String username) {
        if (username == null || username.isBlank() || username.equals("tu_usuario_sin_arroba")) {
            plugin.getLogger().warning("Falta tiktok.username en config.yml. Usa /tiktokcraft connect <usuario>.");
            return;
        }
        if (connecting) {
            return;
        }
        disconnect();
        connecting = true;
        final String name = username.startsWith("@") ? username.substring(1) : username;

        // Conectar puede tardar y bloquea, así que se hace fuera del hilo principal.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Object c = TikTokLive.newClient(name)
                        .onGift((liveClient, event) -> router.gift(
                                String.valueOf(event.getUser().getName()),
                                String.valueOf(event.getGift().getName()),
                                1))
                        .onComment((liveClient, event) -> router.chat(
                                String.valueOf(event.getUser().getName()),
                                String.valueOf(event.getText())))
                        .onLike((liveClient, event) -> router.like("Alguien", 1))
                        .onFollow((liveClient, event) -> router.follow("Alguien"))
                        .onShare((liveClient, event) -> router.share("Alguien"))
                        .buildAndConnect();
                synchronized (TikTokConnection.this) {
                    client = c;
                }
                plugin.getLogger().info("Conectado al LIVE de @" + name);
            } catch (Exception e) {
                plugin.getLogger().warning("No se pudo conectar a @" + name
                        + " (¿estás en LIVE?). Reintenta con /tiktokcraft connect. Detalle: " + e);
            } finally {
                synchronized (TikTokConnection.this) {
                    connecting = false;
                }
            }
        });
    }

    synchronized void disconnect() {
        if (client == null) {
            return;
        }
        try {
            for (java.lang.reflect.Method m : client.getClass().getMethods()) {
                if (m.getName().equals("disconnect") && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    m.invoke(client);
                    break;
                }
            }
        } catch (Exception ignored) {
            // si no se puede cerrar limpiamente, se descarta la referencia
        }
        client = null;
    }
}

/** Decide qué acciones de la configuración se ejecutan para cada evento. */
class EventRouter {

    private final TikTokCraft plugin;
    private final ActionExecutor exec;
    private int likeCounter = 0;
    private long likeCooldownUntil = 0;
    private final Map<String, Long> cooldowns = new HashMap<>();

    EventRouter(TikTokCraft plugin, ActionExecutor exec) {
        this.plugin = plugin;
        this.exec = exec;
    }

    void handle(JsonObject json) {
        String type = str(json, "type", "");
        String user = str(json, "user", "anonimo");

        if (plugin.getConfig().getBoolean("debug")) {
            plugin.getLogger().info("Evento: " + json);
        }

        switch (type) {
            case "gift" -> gift(user, str(json, "giftName", ""), num(json, "count", 1));
            case "like" -> like(user, num(json, "likeCount", 1));
            case "chat" -> chat(user, str(json, "comment", ""));
            case "follow" -> follow(user);
            case "share" -> run(plugin.getConfig().getStringList("share"), 1, user, "", 1);
            default -> plugin.getLogger().warning("Tipo de evento desconocido: " + type);
        }
    }

    void gift(String user, String giftName, int count) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("gifts");
        List<String> actions = List.of();
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                if (key.equalsIgnoreCase(giftName)) {
                    actions = sec.getStringList(key);
                    break;
                }
            }
            if (actions.isEmpty()) {
                actions = sec.getStringList("_default");
            }
        }
        int max = plugin.getConfig().getInt("limits.max-gift-multiplier", 10);
        int mult = Math.max(1, Math.min(count, max));
        run(actions, mult, user, giftName, count);
    }

    synchronized void like(String user, int amount) {
        long now = System.currentTimeMillis();
        if (now < likeCooldownUntil) {
            return; // durante la espera los likes no cuentan
        }
        int every = Math.max(1, plugin.getConfig().getInt("likes.every", 100));
        likeCounter += Math.max(0, amount);
        if (likeCounter >= every) {
            likeCounter = 0;
            long cooldown = plugin.getConfig().getLong("likes.cooldown-seconds", 0) * 1000L;
            likeCooldownUntil = now + cooldown;
            run(plugin.getConfig().getStringList("likes.actions"), 1, user, "", 1);
        }
    }

    synchronized void chat(String user, String comment) {
        String prefix = plugin.getConfig().getString("chat.prefix", "!");
        if (comment == null || !comment.startsWith(prefix)) {
            return;
        }
        String name = comment.substring(prefix.length()).trim().toLowerCase();
        List<String> actions = plugin.getConfig().getStringList("chat.commands." + name);
        if (actions.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long wait = plugin.getConfig().getLong("chat.cooldown-seconds", 10) * 1000L;
        Long last = cooldowns.get(user);
        if (last != null && now - last < wait) {
            return;
        }
        cooldowns.put(user, now);
        run(actions, 1, user, "", 1);
    }

    void share(String user) {
        run(plugin.getConfig().getStringList("share"), 1, user, "", 1);
    }

    void follow(String user) {
        run(plugin.getConfig().getStringList("follow"), 1, user, "", 1);
    }

    private void run(List<String> actions, int mult, String user, String gift, int count) {
        if (actions == null || actions.isEmpty()) {
            return;
        }
        // Las acciones de Bukkit deben ejecutarse en el hilo principal.
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (String action : actions) {
                exec.run(action, mult, user, gift, count);
            }
        });
    }

    private String str(JsonObject json, String key, String def) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : def;
    }

    private int num(JsonObject json, String key, int def) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : def;
    }
}

/** Ejecuta una acción de la configuración sobre los jugadores objetivo. Siempre en el hilo principal. */
class ActionExecutor {

    private final TikTokCraft plugin;
    private final Random random = new Random();
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();

    ActionExecutor(TikTokCraft plugin) {
        this.plugin = plugin;
    }

    void run(String raw, int mult, String user, String gift, int count) {
        String text = raw
                .replace("{user}", user)
                .replace("{gift}", gift)
                .replace("{count}", String.valueOf(count));

        String[] head = text.split(":", 2);
        String type = head[0].trim().toLowerCase();
        String rest = head.length > 1 ? head[1] : "";
        String[] a = rest.isEmpty() ? new String[0] : rest.split(":");

        int max = plugin.getConfig().getInt("limits.max-entities-per-action", 20);

        switch (type) {
            case "message" -> Bukkit.broadcast(legacy.deserialize(rest));
            case "title" -> {
                Component title = legacy.deserialize(rest);
                for (Player p : targets()) {
                    p.showTitle(Title.title(title, Component.empty()));
                }
            }
            case "command" -> {
                for (Player p : targets()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rest.replace("{player}", p.getName()));
                }
            }
            case "spawn" -> spawn(a, mult, max, user);
            case "tnt" -> {
                int n = Math.min(intArg(a, 0, 1) * mult, max);
                for (Player p : targets()) {
                    for (int i = 0; i < n; i++) {
                        Location loc = near(p, 3);
                        TNTPrimed tnt = loc.getWorld().spawn(loc, TNTPrimed.class);
                        tnt.setFuseTicks(60);
                    }
                }
            }
            case "effect" -> effect(a);
            case "give" -> give(a, mult, max);
            case "lightning" -> {
                for (Player p : targets()) {
                    p.getWorld().strikeLightning(p.getLocation());
                }
            }
            case "launch" -> {
                for (Player p : targets()) {
                    p.setVelocity(new Vector(0, 1.5, 0));
                }
            }
            case "fire" -> {
                int ticks = intArg(a, 0, 5) * 20;
                for (Player p : targets()) {
                    p.setFireTicks(ticks);
                }
            }
            case "feed" -> {
                for (Player p : targets()) {
                    p.setFoodLevel(20);
                    p.setSaturation(20f);
                }
            }
            default -> plugin.getLogger().warning("Acción desconocida: " + raw);
        }
    }

    private void spawn(String[] a, int mult, int max, String user) {
        if (a.length == 0) {
            return;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(a[0].toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Mob desconocido: " + a[0]);
            return;
        }
        if (!type.isSpawnable()) {
            plugin.getLogger().warning("Este tipo no se puede spawnear: " + a[0]);
            return;
        }
        int n = Math.min(intArg(a, 1, 1) * mult, max);
        Material helmet = a.length > 2 ? Material.matchMaterial(a[2]) : null;
        for (Player p : targets()) {
            for (int i = 0; i < n; i++) {
                Location loc = near(p, 4);
                Entity e = loc.getWorld().spawnEntity(loc, type);
                if (e instanceof LivingEntity le) {
                    le.customName(Component.text(user));
                    le.setCustomNameVisible(true);
                    if (helmet != null && le.getEquipment() != null) {
                        le.getEquipment().setHelmet(new ItemStack(helmet));
                        le.getEquipment().setHelmetDropChance(0f);
                    }
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void effect(String[] a) {
        if (a.length == 0) {
            return;
        }
        PotionEffectType type = PotionEffectType.getByName(a[0].toUpperCase());
        if (type == null) {
            plugin.getLogger().warning("Efecto desconocido: " + a[0]);
            return;
        }
        int ticks = intArg(a, 1, 10) * 20;
        int amp = Math.max(0, intArg(a, 2, 1) - 1);
        for (Player p : targets()) {
            p.addPotionEffect(new PotionEffect(type, ticks, amp));
        }
    }

    private void give(String[] a, int mult, int max) {
        if (a.length == 0) {
            return;
        }
        Material m = Material.matchMaterial(a[0]);
        if (m == null || !m.isItem()) {
            plugin.getLogger().warning("Item desconocido: " + a[0]);
            return;
        }
        int n = Math.min(intArg(a, 1, 1) * mult, max * 4);
        for (Player p : targets()) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(m, n));
            for (ItemStack item : left.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), item);
            }
        }
    }

    private List<Player> targets() {
        String name = plugin.getConfig().getString("target-player", "");
        if (name != null && !name.isBlank()) {
            Player p = Bukkit.getPlayerExact(name);
            return p == null ? List.of() : List.of(p);
        }
        return new ArrayList<>(Bukkit.getOnlinePlayers());
    }

    private Location near(Player p, double radius) {
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = 2 + random.nextDouble() * radius;
        return p.getLocation().add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
    }

    private int intArg(String[] a, int i, int def) {
        if (i >= a.length) {
            return def;
        }
        try {
            return Integer.parseInt(a[i].trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
