package mc.bastion.bonero;

import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcData;
import io.papermc.paper.event.player.PlayerPurchaseEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;
import java.util.regex.*;

public final class BoneroPlugin extends JavaPlugin implements Listener, TabExecutor {
    private YamlConfiguration locations, trades, messages, state;
    private File locationsFile, tradesFile, messagesFile, stateFile;
    private final Random random = new Random();
    private NamespacedKey marker;
    private Npc npc;
    private boolean npcReady;
    private TextDisplay nameDisplay, timerDisplay;
    private Chunk chunkTicket;
    private long nextSpawn, expires;
    private int locationId = -1, lastLocation = -1;
    private boolean warningSent;
    private long lastOrphanSweep;
    private final Map<UUID, Integer> editors = new HashMap<>();
    private final Map<UUID, MerchantSession> merchantSessions = new HashMap<>();
    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static final int[] TRADE_SLOTS = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};

    @Override public void onEnable() {
        saveDefaultConfig();
        locationsFile = file("locations.yml"); tradesFile = file("trades.yml");
        messagesFile = file("messages.yml"); stateFile = file("state.yml");
        locations = YamlConfiguration.loadConfiguration(locationsFile);
        trades = YamlConfiguration.loadConfiguration(tradesFile);
        messages = YamlConfiguration.loadConfiguration(messagesFile);
        state = YamlConfiguration.loadConfiguration(stateFile);
        marker = new NamespacedKey(this, "bonero_entity");
        Objects.requireNonNull(getCommand("elbonero")).setExecutor(this);
        Objects.requireNonNull(getCommand("elbonero")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("bonero")).setExecutor(this);
        Objects.requireNonNull(getCommand("bonero")).setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        // Remove orphaned holograms left by an older version, but NEVER touch other plugins' displays.
        // ChunkLoadEvent also handles holograms in chunks that were unloaded during startup.
        cleanupOrphanDisplays();
        nextSpawn = state.getLong("next-spawn", System.currentTimeMillis() + interval());
        expires = state.getLong("expires", 0);
        locationId = state.getInt("active-location", -1);
        lastLocation = state.getInt("last-location", -1);
        long now = System.currentTimeMillis();
        if (expires <= now || locationId <= 0 || location(locationId) == null) {
            expires = 0; locationId = -1;
            if (nextSpawn <= 0) nextSpawn = now + interval();
        }
        persist();
        // FancyNpcs recomienda esperar al menos 10 segundos tras arrancar el servidor.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            npcReady = true;
            if (expires > System.currentTimeMillis() && locationId > 0) {
                if (!createNpc(location(locationId))) {
                    getLogger().warning("No se pudo restaurar El Bonero. Se reintentará en el siguiente ciclo.");
                    expires = 0; locationId = -1; nextSpawn = System.currentTimeMillis() + 30000L; persist();
                }
            }
        }, 220L);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    @Override public void onDisable() {
        clearNpc();
        persist();
    }

    private File file(String name) {
        File f = new File(getDataFolder(), name);
        if (!f.exists() && !name.equals("state.yml")) saveResource(name, false);
        return f;
    }
    private void save(YamlConfiguration yaml, File f) {
        try { yaml.save(f); } catch (Exception e) { getLogger().severe("No se pudo guardar " + f.getName() + ": " + e.getMessage()); }
    }
    private long interval() { return Math.max(1, getConfig().getLong("bonero.respawn-minutes", 120)) * 60000L; }
    private long duration() { return Math.max(1, getConfig().getLong("bonero.duration-minutes", 5)) * 60000L; }
    private void persist() {
        state.set("next-spawn", nextSpawn);
        state.set("expires", expires);
        state.set("active-location", locationId);
        state.set("last-location", lastLocation);
        save(state, stateFile);
    }
    private String legacy(String s) {
        Matcher m = HEX.matcher(s == null ? "" : s);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String h = m.group(1);
            StringBuilder converted = new StringBuilder("§x");
            for (char c : h.toCharArray()) converted.append('§').append(c);
            m.appendReplacement(b, Matcher.quoteReplacement(converted.toString()));
        }
        m.appendTail(b);
        return ChatColor.translateAlternateColorCodes('&', b.toString());
    }
    private Component component(String s) { return LegacyComponentSerializer.legacySection().deserialize(legacy(s)); }
    private String msg(String path) { return messages.getString("admin." + path, "&c" + path); }
    private void tell(CommandSender to, String s) { to.sendMessage(component(s)); }
    private String apply(String s) {
        return s.replace("{locations}", Integer.toString(ids().size()))
          .replace("{duration}", Long.toString(duration()/60000L))
          .replace("{respawn_time}", format(interval()/1000L))
          .replace("{time}", format(Math.max(0, (expires - System.currentTimeMillis()+999)/1000L)));
    }
    private String format(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return h > 0 ? String.format("%dh %02dm", h, m) : String.format("%02d:%02d", m, s);
    }
    private List<Integer> ids() {
        ConfigurationSection sec = locations.getConfigurationSection("locations");
        if (sec == null) return List.of();
        List<Integer> list = new ArrayList<>();
        for (String id : sec.getKeys(false)) try { list.add(Integer.parseInt(id)); } catch (NumberFormatException ignored) { }
        Collections.sort(list); return list;
    }
    private Location location(int id) { return locations.getLocation("locations." + id); }
    private void tick() {
        long now = System.currentTimeMillis();
        if (!npcReady) return;
        if (expires <= 0 && now - lastOrphanSweep >= 30000L) {
            cleanupOrphanDisplays();
            lastOrphanSweep = now;
        }
        if (expires > 0) {
            if (now >= expires) { despawn(true); return; }
            if (npc == null) {
                Location l = location(locationId);
                if (l == null || !createNpc(l)) {
                    getLogger().warning("El Bonero no pudo restaurarse; cancelando evento para evitar hologramas fantasmas.");
                    despawn(false);
                    return;
                }
            }
            // Use the saved expiry epoch (not a separate countdown) so it never drifts.
            updateTimer();
            if (!warningSent && expires - now <= 60000 && getConfig().getBoolean("bonero.announcements.last-minute-warning", true)) {
                warningSent = true;
                for (String line : messages.getStringList("warning")) Bukkit.broadcast(component(apply(line)));
            }
        } else if (now >= nextSpawn && !ids().isEmpty()) {
            List<Integer> eligible = new ArrayList<>(ids());
            if (eligible.size() > 1 && getConfig().getBoolean("bonero.avoid-last-location", true)) eligible.remove(Integer.valueOf(lastLocation));
            spawnAt(eligible.get(random.nextInt(eligible.size())), true);
        }
    }
    private void announce(String name, String soundKey) {
        for (String s : messages.getStringList(name)) Bukkit.broadcast(component(apply(s)));
        if (getConfig().getBoolean("bonero.announcements." + soundKey + ".enabled", true)) {
            String path = "bonero.announcements." + soundKey;
            try {
                Sound sound = Sound.valueOf(getConfig().getString(path + ".type", "ENTITY_ENDERMAN_TELEPORT"));
                float vol = (float) getConfig().getDouble(path + ".volume", 1.0);
                float pitch = (float) getConfig().getDouble(path + ".pitch", 1.0);
                for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), sound, vol, pitch);
            } catch (IllegalArgumentException e) { getLogger().warning("Sonido inválido: " + path); }
        }
    }
    private void spawnAt(int id, boolean announce) {
        Location l = location(id);
        if (l == null || l.getWorld() == null) return;
        if (!npcReady) { getLogger().warning("FancyNpcs todavía está iniciándose. Reintenta en unos segundos."); return; }
        clearNpc();
        cleanupOrphanDisplays();
        locationId = id; lastLocation = id;
        expires = System.currentTimeMillis() + duration();
        warningSent = false;
        if (!createNpc(l)) {
            expires = 0; locationId = -1;
            nextSpawn = System.currentTimeMillis() + 30000L;
            persist();
            return;
        }
        persist();
        if (announce) announce("spawn", "spawn-sound");
    }
    private boolean createNpc(Location l) {
        if (npc != null) return true;
        if (l == null || l.getWorld() == null) return false;
        try {
            Chunk ch = l.getChunk(); ch.load(); ch.addPluginChunkTicket(this); chunkTicket = ch;
            NpcData data = new NpcData("bastion_elbonero", UUID.fromString("4c6e7e9e-9fc1-4c4a-b8bd-67131d44aa24"), l);
            // El nombre lo dibujan nuestros hologramas configurables.
            data.setDisplayName("<gray> </gray>");
            data.setCollidable(false);
            data.setTurnToPlayer(getConfig().getBoolean("bonero.look-at-players", true));
            data.setOnClick(player -> Bukkit.getScheduler().runTask(this, () -> {
                if (expires > System.currentTimeMillis() && npc != null) openMerchantShop(player);
            }));
            String skin = getConfig().getString("bonero.skin.source", "file");
            if (skin.equalsIgnoreCase("file")) {
                // FancyNpcs resolves local skins by file name inside its own skins/ directory.
                // An absolute path in our plugin folder throws SkinLoadException.
                File skinsFolder = new File(getDataFolder().getParentFile(), "FancyNpcs/skins");
                if (!skinsFolder.exists() && !skinsFolder.mkdirs()) {
                    throw new java.io.IOException("No se pudo crear " + skinsFolder.getAbsolutePath());
                }
                File targetSkin = new File(skinsFolder, "bastion_elbonero.png");
                // Ensure the built-in skin exists, copying from the plugin JAR when needed.
                File localSkin = new File(getDataFolder(), "elbonero.png");
                if (!localSkin.exists()) saveResource("elbonero.png", false);
                java.nio.file.Files.copy(localSkin.toPath(), targetSkin.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                data.setSkin(targetSkin.getName());
            } else if (!skin.isBlank() && !skin.equalsIgnoreCase("steve")) {
                data.setSkin(skin); // Puede ser nombre de jugador, UUID o URL.
            }
            Npc created = FancyNpcsPlugin.get().getNpcAdapter().apply(data);
            created.setSaveToFile(false);
            FancyNpcsPlugin.get().getNpcManager().registerNpc(created);
            npc = created;
            npc.create();
            npc.spawnForAll();
            if (getConfig().getBoolean("bonero.hologram.enabled", true)) {
                nameDisplay = display(l.clone().add(0, getConfig().getDouble("bonero.hologram.height-name", 2.45), 0), getConfig().getString("bonero.name"));
                timerDisplay = display(l.clone().add(0, getConfig().getDouble("bonero.hologram.height-timer", 2.76), 0), apply(getConfig().getString("bonero.hologram.timer", "&c{time}")));
            }
            updateTimer();
            return true;
        } catch (Exception ex) {
            getLogger().log(java.util.logging.Level.SEVERE, "No se pudo crear el NPC FancyNpcs", ex);
            clearNpc();
            return false;
        }
    }
    private TextDisplay display(Location l, String text) {
        return l.getWorld().spawn(l, TextDisplay.class, e -> {
            mark(e); e.setPersistent(false); e.setBillboard(Display.Billboard.CENTER);
            String base = "bonero.hologram.";
            e.setShadowed(getConfig().getBoolean(base + "text-shadow", false));
            e.setSeeThrough(getConfig().getBoolean(base + "see-through", false));
            e.setDefaultBackground(false);
            // TextDisplay keeps its own semi-opaque rectangle unless its ARGB
            // background is explicitly made fully transparent (alpha = 0).
            int alpha = Math.max(0, Math.min(255, getConfig().getInt(base + "background-opacity", 0)));
            String hex = getConfig().getString(base + "background-color", "#000000");
            try {
                String clean = hex == null ? "000000" : hex.strip().replaceFirst("^#", "");
                if (!clean.matches("[0-9a-fA-F]{6}")) throw new IllegalArgumentException("HEX inválido");
                int rgb = Integer.parseInt(clean, 16);
                e.setBackgroundColor(Color.fromARGB(alpha, (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255));
            } catch (IllegalArgumentException error) {
                getLogger().warning("Color de fondo de holograma inválido: " + hex + "; usando transparente.");
                e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            }
            e.text(component(text));
        });
    }
    private void mark(Entity e) { e.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte)1); }

    private void updateTimer() {
        if (timerDisplay != null && timerDisplay.isValid()) {
            long seconds = Math.max(0L, (expires - System.currentTimeMillis() + 999L) / 1000L);
            String template = getConfig().getString("bonero.hologram.timer", "&c{time}");
            timerDisplay.text(component(template.replace("{time}", format(seconds))));
        }
    }

    // Only our own PDC-marked entities qualify. FancyHolograms and other NPC displays are untouched.
    private boolean isCurrentDisplay(Entity e) {
        return (nameDisplay != null && nameDisplay.getUniqueId().equals(e.getUniqueId()))
            || (timerDisplay != null && timerDisplay.getUniqueId().equals(e.getUniqueId()));
    }
    private void cleanupOrphanDisplays(Iterable<? extends Entity> entities) {
        for (Entity e : entities) {
            if (e instanceof TextDisplay && !isCurrentDisplay(e)
                    && e.getPersistentDataContainer().has(marker, PersistentDataType.BYTE)) {
                e.remove();
            }
        }
    }
    private void cleanupOrphanDisplays() {
        for (World w : Bukkit.getWorlds()) cleanupOrphanDisplays(w.getEntities());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        // Wait one tick so newly loaded entities are visible to Bukkit's entity list.
        Bukkit.getScheduler().runTask(this, () -> cleanupOrphanDisplays(Arrays.asList(event.getChunk().getEntities())));
    }

    private void clearNpc() {
        if (npc != null) {
            try {
                npc.removeForAll();
                FancyNpcsPlugin.get().getNpcManager().removeNpc(npc);
            } catch (Exception ex) { getLogger().warning("Error al retirar NPC: " + ex.getMessage()); }
        }
        if (nameDisplay != null && nameDisplay.isValid()) nameDisplay.remove();
        if (timerDisplay != null && timerDisplay.isValid()) timerDisplay.remove();
        npc = null; nameDisplay = null; timerDisplay = null;
        cleanupOrphanDisplays();
        if (chunkTicket != null) { chunkTicket.removePluginChunkTicket(this); chunkTicket = null; }
    }
    private void despawn(boolean announce) {
        clearNpc(); expires = 0; locationId = -1;
        nextSpawn = System.currentTimeMillis() + interval();
        persist();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (merchantSessions.containsKey(p.getUniqueId()) && p.getOpenInventory().getTopInventory().getType() == InventoryType.MERCHANT) {
                p.closeInventory();
            }
        }
        merchantSessions.clear();
        if (announce) announce("despawn", "despawn-sound");
    }
    private void reloadAll() {
        reloadConfig();
        locations = YamlConfiguration.loadConfiguration(locationsFile);
        trades = YamlConfiguration.loadConfiguration(tradesFile);
        messages = YamlConfiguration.loadConfiguration(messagesFile);
        if (expires > 0 && npcReady) {
            clearNpc();
            Location l = location(locationId);
            if (l == null || !createNpc(l)) despawn(false);
        } else cleanupOrphanDisplays();
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("elbonero.admin")) { tell(sender, "&cSin permiso."); return true; }
        if (label.equalsIgnoreCase("bonero")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) { reloadAll(); tell(sender, msg("reloaded")); }
            else tell(sender, "&e/bonero reload");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            tell(sender, "&6&lEL BONERO &7- Comandos");
            for (String s : List.of("/elbonero set location <id>", "/elbonero spawn location <id>", "/elbonero remove location <id>", "/elbonero locations", "/elbonero edit trade", "/elbonero status", "/elbonero despawn", "/bonero reload")) tell(sender, "&e" + s);
            return true;
        }
        String a = args[0].toLowerCase(Locale.ROOT);
        if (a.equals("locations")) { tell(sender, "&6Ubicaciones: &f" + ids()); return true; }
        if (a.equals("status")) {
            tell(sender, expires > 0 ? "&aActivo en ubicación " + locationId + " | Restan " + format((expires-System.currentTimeMillis()+999)/1000) : "&eSiguiente aparición en " + format(Math.max(0,(nextSpawn-System.currentTimeMillis()+999)/1000)));
            return true;
        }
        if (a.equals("despawn")) {
            if (expires > 0) { despawn(true); tell(sender, msg("removed")); }
            else { clearNpc(); cleanupOrphanDisplays(); tell(sender, "&eSe revisaron y limpiaron los hologramas huérfanos del plugin."); }
            return true;
        }
        if (a.equals("edit") && args.length >= 2 && args[1].equalsIgnoreCase("trade") && sender instanceof Player p) { editorList(p); return true; }
        if (List.of("set", "spawn", "remove").contains(a) && args.length >= 3 && args[1].equalsIgnoreCase("location")) {
            int id;
            try { id = Integer.parseInt(args[2]); if (id < 1 || id > 1000) throw new NumberFormatException(); }
            catch (NumberFormatException e) { tell(sender, "&cID inválido (1-1000)."); return true; }
            if (a.equals("set")) {
                if (!(sender instanceof Player p)) { tell(sender, "&cSolo jugadores."); return true; }
                locations.set("locations." + id, p.getLocation()); save(locations, locationsFile);
                tell(sender, msg("location-set").replace("{id}", String.valueOf(id)));
            } else if (a.equals("remove")) {
                if (location(id) == null) { tell(sender, msg("invalid-location")); return true; }
                if (locationId == id) despawn(true);
                locations.set("locations." + id, null); save(locations, locationsFile);
                tell(sender, msg("location-removed").replace("{id}", String.valueOf(id)));
            } else {
                if (location(id) == null) { tell(sender, msg("invalid-location")); return true; }
                if (expires > 0) { tell(sender, msg("already-active")); return true; }
                spawnAt(id, getConfig().getBoolean("bonero.announcements.manual-spawn", true));
                if (expires > 0) tell(sender, msg("spawned").replace("{id}", String.valueOf(id)));
                else tell(sender, "&cNo se pudo generar EL BONERO. Revisa la consola.");
            }
            return true;
        }
        tell(sender, msg("bad-command")); return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] a) {
        if (!sender.hasPermission("elbonero.admin")) return List.of();
        if (cmd.getName().equalsIgnoreCase("bonero")) return a.length == 1 ? prefix(List.of("reload"), a[0]) : List.of();
        if (a.length == 1) return prefix(List.of("help","set","spawn","remove","locations","edit","status","despawn"), a[0]);
        if (a.length == 2 && List.of("set","spawn","remove").contains(a[0].toLowerCase(Locale.ROOT))) return prefix(List.of("location"), a[1]);
        if (a.length == 2 && a[0].equalsIgnoreCase("edit")) return prefix(List.of("trade"), a[1]);
        if (a.length == 3 && a[1].equalsIgnoreCase("location")) return prefix(a[0].equalsIgnoreCase("set") ? List.of("1","2","3","4","5","6","7") : ids().stream().map(String::valueOf).toList(), a[2]);
        return List.of();
    }
    private List<String> prefix(List<String> values, String p) { return values.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p.toLowerCase(Locale.ROOT))).toList(); }

    private enum Kind { LIST, EDIT }
    private static final class BoneroHolder implements InventoryHolder {
        final Kind kind; final int tradeId; Inventory inventory;
        BoneroHolder(Kind kind, int tradeId) { this.kind = kind; this.tradeId = tradeId; }
        @Override public Inventory getInventory() { return inventory; }
    }
    private static final class MerchantSession {
        final Merchant merchant;
        final List<Integer> tradeIds;
        MerchantSession(Merchant merchant, List<Integer> tradeIds) {
            this.merchant = merchant;
            this.tradeIds = tradeIds;
        }
    }
    private Inventory inventory(Kind kind, int tradeId, int size, String title) {
        BoneroHolder h = new BoneroHolder(kind, tradeId);
        h.inventory = Bukkit.createInventory(h, size, component(title)); return h.inventory;
    }
    private List<Integer> tradeIds() {
        ConfigurationSection sec = trades.getConfigurationSection("trades");
        if (sec == null) return List.of();
        List<Integer> all = new ArrayList<>();
        for (String k : sec.getKeys(false)) try { all.add(Integer.parseInt(k)); } catch (NumberFormatException ignored) { }
        Collections.sort(all); return all;
    }
    private ItemStack tradeItem(int id, String k) { return trades.getItemStack("trades."+id+"."+k); }
    private int stock(int id) { return trades.getInt("trades."+id+".stock", -1); }
    private ItemStack button(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat); ItemMeta meta = item.getItemMeta();
        meta.displayName(component(name)); meta.lore(lore.stream().map(this::component).toList()); item.setItemMeta(meta);
        return item;
    }
    private void editorList(Player p) { list(p, true); }
    private void list(Player p, boolean edit) {
        List<Integer> all = tradeIds();
        Inventory inv = inventory(Kind.LIST, -1, 54, messages.getString("gui."+(edit?"editor-title":"trade-title")));
        int i = 0;
        for (int id : all) {
            if (i >= TRADE_SLOTS.length) break;
            ItemStack reward = tradeItem(id, "reward"); if (reward == null) continue;
            ItemStack display = reward.clone(); ItemMeta meta = display.getItemMeta();
            List<Component> lore = new ArrayList<>();
            for (String s : messages.getStringList(edit?"gui.edit-lore":"gui.trade-lore")) lore.add(component(s));
            for (String key : List.of("price1","price2")) {
                ItemStack cost = tradeItem(id,key);
                if (cost != null) lore.add(component("&7Precio: &f" + cost.getAmount() + "x " + cost.getType()));
            }
            if (stock(id) >= 0) lore.add(component("&7Stock: &e" + stock(id)));
            meta.lore(lore); display.setItemMeta(meta);
            inv.setItem(TRADE_SLOTS[i++], display);
        }
        inv.setItem(49, button(Material.EMERALD_BLOCK, messages.getString("gui.add"), List.of("&7Crear un intercambio")));
        p.openInventory(inv);
    }
    private void openMerchantShop(Player p) {
        if (expires <= System.currentTimeMillis()) {
            tell(p, "&cEl Bonero no está activo en este momento.");
            return;
        }
        List<MerchantRecipe> recipes = new ArrayList<>();
        List<Integer> shownTradeIds = new ArrayList<>();
        for (int id : tradeIds()) {
            ItemStack reward = tradeItem(id, "reward");
            if (reward == null || reward.getType().isAir()) continue;
            int stock = stock(id);
            if (stock == 0) continue;
            MerchantRecipe recipe = new MerchantRecipe(reward.clone(), 0, stock > 0 ? stock : Integer.MAX_VALUE, false, 0, 0f);
            recipe.setExperienceReward(false);
            recipe.setVillagerExperience(0);
            recipe.setPriceMultiplier(0f);
            recipe.setIgnoreDiscounts(true);
            List<ItemStack> ingredients = new ArrayList<>();
            ItemStack a = tradeItem(id, "price1"), b = tradeItem(id, "price2");
            if (a != null && !a.getType().isAir()) ingredients.add(a.clone());
            if (b != null && !b.getType().isAir()) ingredients.add(b.clone());
            if (ingredients.isEmpty()) continue;
            recipe.setIngredients(ingredients);
            recipes.add(recipe);
            shownTradeIds.add(id);
        }
        if (recipes.isEmpty()) {
            tell(p, msg("sold-out"));
            return;
        }
        Merchant merchant = Bukkit.createMerchant(component(messages.getString("gui.trade-title", "EL BONERO | Intercambios")));
        merchant.setRecipes(recipes);
        merchantSessions.put(p.getUniqueId(), new MerchantSession(merchant, shownTradeIds));
        p.openMerchant(merchant, true);
    }

    private void edit(Player p, int id) {
        Inventory inv = inventory(Kind.EDIT, id, 27, messages.getString("gui.edit-trade-title"));
        for (int slot = 0; slot < 27; slot++) inv.setItem(slot, button(Material.GRAY_STAINED_GLASS_PANE, "&7", List.of()));
        for (int slot : List.of(11,12,15)) inv.setItem(slot, null);
        if (id > 0) {
            inv.setItem(11, cloneOrNull(tradeItem(id,"price1")));
            inv.setItem(12, cloneOrNull(tradeItem(id,"price2")));
            inv.setItem(15, cloneOrNull(tradeItem(id,"reward")));
        }
        inv.setItem(22, button(Material.LIME_DYE, messages.getString("gui.save"), List.of("&7Guarda el intercambio")));
        inv.setItem(26, button(Material.BARRIER, messages.getString("gui.cancel"), List.of("&7Regresar sin guardar")));
        p.openInventory(inv);
    }
    private ItemStack cloneOrNull(ItemStack i) { return i == null ? null : i.clone(); }
    @EventHandler public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof BoneroHolder h)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int raw = e.getRawSlot();
        if (h.kind == Kind.EDIT) {
            if (e.isShiftClick() || e.getClick().isKeyboardClick() || e.getClick() == ClickType.DOUBLE_CLICK) { e.setCancelled(true); return; }
            if (raw >= e.getView().getTopInventory().getSize()) return;
            e.setCancelled(true);
            if (raw == 11 || raw == 12 || raw == 15) {
                if (e.isRightClick()) e.getInventory().setItem(raw, null);
                else if (e.getCursor() != null && !e.getCursor().getType().isAir()) e.getInventory().setItem(raw, e.getCursor().clone());
                return;
            }
            if (raw != 11 && raw != 12 && raw != 15) {
                e.setCancelled(true);
                if (raw == 22) {
                    ItemStack a = e.getInventory().getItem(11), b = e.getInventory().getItem(12), c = e.getInventory().getItem(15);
                    if (c == null || c.getType().isAir() || (a == null && b == null)) { tell(p,msg("incomplete-trade")); return; }
                    int id = h.tradeId > 0 ? h.tradeId : (tradeIds().stream().mapToInt(Integer::intValue).max().orElse(0)+1);
                    for (String key : List.of("price1","price2","reward")) trades.set("trades."+id+"."+key, key.equals("price1") ? a : key.equals("price2") ? b : c);
                    if (h.tradeId <= 0) trades.set("trades."+id+".stock", -1);
                    save(trades, tradesFile);
                    // Clear items before closing: the GUI is a temporary editor, not player storage.
                    for (int slot : List.of(11,12,15)) e.getInventory().setItem(slot, null);
                    tell(p,msg("saved-trade")); Bukkit.getScheduler().runTask(this, () -> editorList(p));
                } else if (raw == 26) Bukkit.getScheduler().runTask(this, () -> editorList(p));
            }
            return;
        }
        e.setCancelled(true);
        if (raw < 0 || raw >= e.getView().getTopInventory().getSize()) return;
        if (h.kind == Kind.LIST && raw == 49) { Bukkit.getScheduler().runTask(this, () -> edit(p,-1)); return; }
        int idx = -1; for (int j=0;j<TRADE_SLOTS.length;j++) if (TRADE_SLOTS[j]==raw) {idx=j;break;}
        List<Integer> all = tradeIds(); if (idx < 0 || idx >= all.size()) return;
        int id = all.get(idx);
        if (h.kind == Kind.LIST) {
            if (e.isShiftClick() && e.isRightClick()) {
                trades.set("trades."+id, null); save(trades,tradesFile);
                Bukkit.getScheduler().runTask(this, () -> editorList(p));
            } else Bukkit.getScheduler().runTask(this, () -> edit(p,id));
        }
    }
    @EventHandler public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof BoneroHolder h)) return;
        for (int slot : e.getRawSlots()) {
            if (slot < e.getView().getTopInventory().getSize()) { e.setCancelled(true); return; }
        }
    }
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p && e.getView().getTopInventory().getType() == InventoryType.MERCHANT) {
            merchantSessions.remove(p.getUniqueId());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerPurchase(PlayerPurchaseEvent e) {
        MerchantSession session = merchantSessions.get(e.getPlayer().getUniqueId());
        if (session == null || session.merchant != e.getMerchant()) return;
        int index = session.merchant.getRecipes().indexOf(e.getTrade());
        if (index < 0 || index >= session.tradeIds.size()) return;
        int id = session.tradeIds.get(index);
        if (stock(id) == 0) {
            e.setCancelled(true);
            tell(e.getPlayer(), msg("sold-out"));
            return;
        }
        if (stock(id) > 0) {
            trades.set("trades." + id + ".stock", stock(id) - 1);
            save(trades, tradesFile);
        }
        tell(e.getPlayer(), msg("bought"));
        Bukkit.getScheduler().runTask(this, () -> {
            Player p = e.getPlayer();
            if (!p.isOnline()) return;
            MerchantSession current = merchantSessions.get(p.getUniqueId());
            if (current == null || current.merchant != e.getMerchant()) return;
            if (expires > System.currentTimeMillis()) openMerchantShop(p);
        });
    }
}
