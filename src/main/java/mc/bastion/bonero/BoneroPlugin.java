package mc.bastion.bonero;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
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
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.io.File;
import java.net.URL;
import java.util.*;
import java.util.regex.*;

public final class BoneroPlugin extends JavaPlugin implements Listener, TabExecutor {
    private YamlConfiguration locations, trades, messages, state;
    private File locationsFile, tradesFile, messagesFile, stateFile;
    private final Random random = new Random();
    private NamespacedKey marker;
    private Mannequin npc;
    private TextDisplay nameDisplay, timerDisplay;
    private Chunk chunkTicket;
    private long nextSpawn, expires;
    private int locationId = -1, lastLocation = -1;
    private boolean warningSent;
    private final Map<UUID, Integer> editors = new HashMap<>();
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
        // The old NPC is never reused across reloads; only plugin-marked entities are removed.
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities())
            if (e.getPersistentDataContainer().has(marker, PersistentDataType.BYTE)) e.remove();
        nextSpawn = state.getLong("next-spawn", System.currentTimeMillis() + interval());
        expires = state.getLong("expires", 0);
        locationId = state.getInt("active-location", -1);
        lastLocation = state.getInt("last-location", -1);
        long now = System.currentTimeMillis();
        if (expires > now && locationId > 0 && location(locationId) != null) {
            createNpc(location(locationId));
        } else {
            expires = 0; locationId = -1;
            if (nextSpawn <= 0) nextSpawn = now + interval();
        }
        persist();
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
        if (expires > 0) {
            if (now >= expires) { despawn(true); return; }
            if (npc == null || !npc.isValid()) {
                Location l = location(locationId);
                if (l != null) createNpc(l);
            }
            if (timerDisplay != null && timerDisplay.isValid()) timerDisplay.text(component(apply(getConfig().getString("bonero.hologram.timer", "&c{time}"))));
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
        clearNpc();
        locationId = id; lastLocation = id;
        expires = System.currentTimeMillis() + duration();
        warningSent = false;
        createNpc(l);
        persist();
        if (announce) announce("spawn", "spawn-sound");
    }
    private void createNpc(Location l) {
        if (npc != null && npc.isValid()) return;
        Chunk ch = l.getChunk(); ch.load(); ch.addPluginChunkTicket(this); chunkTicket = ch;
        npc = l.getWorld().spawn(l, Mannequin.class, m -> {
            mark(m);
            m.setImmovable(true);
            m.setInvulnerable(true);
            m.setSilent(true);
            m.setPersistent(false);
            m.setRemoveWhenFarAway(false);
            m.customName(component(getConfig().getString("bonero.name", "&6EL BONERO")));
            m.setCustomNameVisible(false);
            m.setDescription(Component.empty());
            String skin = getConfig().getString("bonero.skin.texture-url", "").trim();
            if (!skin.isEmpty()) {
                try {
                    URL url = new URL(skin);
                    if (!url.getHost().equalsIgnoreCase("textures.minecraft.net")) throw new IllegalArgumentException("Solo textures.minecraft.net");
                    PlayerProfile p = Bukkit.createProfile(UUID.randomUUID(), "ElBonero");
                    PlayerTextures t = p.getTextures(); t.setSkin(url); p.setTextures(t);
                    m.setProfile(ResolvableProfile.resolvableProfile(p));
                } catch (Exception e) { getLogger().warning("Skin no válida: " + e.getMessage()); }
            }
        });
        if (getConfig().getBoolean("bonero.hologram.enabled", true)) {
            nameDisplay = display(l.clone().add(0, getConfig().getDouble("bonero.hologram.height-name", 2.45), 0), getConfig().getString("bonero.name"));
            timerDisplay = display(l.clone().add(0, getConfig().getDouble("bonero.hologram.height-timer", 2.76), 0), apply(getConfig().getString("bonero.hologram.timer", "&c{time}")));
        }
    }
    private TextDisplay display(Location l, String text) {
        return l.getWorld().spawn(l, TextDisplay.class, e -> {
            mark(e); e.setPersistent(false); e.setBillboard(Display.Billboard.CENTER);
            e.setShadowed(true); e.setSeeThrough(false); e.setDefaultBackground(false);
            e.text(component(text));
        });
    }
    private void mark(Entity e) { e.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte)1); }
    private void clearNpc() {
        if (npc != null) npc.remove();
        if (nameDisplay != null) nameDisplay.remove();
        if (timerDisplay != null) timerDisplay.remove();
        npc = null; nameDisplay = null; timerDisplay = null;
        if (chunkTicket != null) { chunkTicket.removePluginChunkTicket(this); chunkTicket = null; }
    }
    private void despawn(boolean announce) {
        clearNpc(); expires = 0; locationId = -1;
        nextSpawn = System.currentTimeMillis() + interval();
        persist();
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getOpenInventory().getTopInventory().getHolder() instanceof BoneroHolder h && h.kind == Kind.SHOP) p.closeInventory();
        if (announce) announce("despawn", "despawn-sound");
    }
    private void reloadAll() {
        reloadConfig();
        locations = YamlConfiguration.loadConfiguration(locationsFile);
        trades = YamlConfiguration.loadConfiguration(tradesFile);
        messages = YamlConfiguration.loadConfiguration(messagesFile);
        if (expires > 0) { clearNpc(); Location l = location(locationId); if (l != null) createNpc(l); else despawn(false); }
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
        if (a.equals("despawn")) { if (expires > 0) { despawn(true); tell(sender, msg("removed")); } else tell(sender, "&cNo está activo."); return true; }
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
                tell(sender, msg("spawned").replace("{id}", String.valueOf(id)));
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

    private enum Kind { SHOP, LIST, EDIT }
    private static final class BoneroHolder implements InventoryHolder {
        final Kind kind; final int tradeId; Inventory inventory;
        BoneroHolder(Kind kind, int tradeId) { this.kind = kind; this.tradeId = tradeId; }
        @Override public Inventory getInventory() { return inventory; }
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
        Inventory inv = inventory(edit ? Kind.LIST : Kind.SHOP, -1, 54, messages.getString("gui."+(edit?"editor-title":"trade-title")));
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
        if (edit) inv.setItem(49, button(Material.EMERALD_BLOCK, messages.getString("gui.add"), List.of("&7Crear un intercambio")));
        p.openInventory(inv);
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
    @EventHandler(ignoreCancelled = true) public void onClickNpc(PlayerInteractEntityEvent e) {
        if (e.getHand() == EquipmentSlot.HAND && npc != null && e.getRightClicked().getUniqueId().equals(npc.getUniqueId())) {
            e.setCancelled(true); if (expires > System.currentTimeMillis()) list(e.getPlayer(), false);
        }
    }
    @EventHandler(ignoreCancelled = true) public void onDamage(EntityDamageEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(marker, PersistentDataType.BYTE)) e.setCancelled(true);
    }
    @EventHandler public void onDeath(EntityDeathEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(marker, PersistentDataType.BYTE)) { e.getDrops().clear(); e.setDroppedExp(0); }
    }
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
        } else if (h.kind == Kind.SHOP && expires > System.currentTimeMillis()) purchase(p,id);
    }
    @EventHandler public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof BoneroHolder h)) return;
        for (int slot : e.getRawSlots()) {
            if (slot < e.getView().getTopInventory().getSize()) { e.setCancelled(true); return; }
        }
    }
    private boolean same(ItemStack a, ItemStack b) {
        return a != null && b != null && a.isSimilar(b);
    }
    private int count(Player p, ItemStack item) {
        int total=0; for (ItemStack slot : p.getInventory().getStorageContents()) if (same(slot,item)) total += slot.getAmount();
        return total;
    }
    private void deduct(Player p, ItemStack item) {
        if (item == null) return;
        int remaining = item.getAmount();
        ItemStack[] contents = p.getInventory().getStorageContents();
        for (int i=0;i<contents.length && remaining>0;i++) {
            ItemStack slot=contents[i]; if (!same(slot,item)) continue;
            int take = Math.min(remaining,slot.getAmount()); remaining-=take;
            if (take==slot.getAmount()) contents[i]=null;
            else { slot=slot.clone();slot.setAmount(slot.getAmount()-take);contents[i]=slot; }
        }
        p.getInventory().setStorageContents(contents);
    }
    private void purchase(Player p, int id) {
        if (stock(id)==0) { tell(p,msg("sold-out")); return; }
        ItemStack a=tradeItem(id,"price1"), b=tradeItem(id,"price2"), reward=tradeItem(id,"reward");
        if (reward==null) return;
        // If both prices refer to the same item, their quantities are summed.
        int needA = a == null ? 0 : a.getAmount() + (same(a,b)?b.getAmount():0);
        if ((a!=null && count(p,a)<needA) || (b!=null && !same(a,b) && count(p,b)<b.getAmount())) { tell(p,msg("not-enough")); return; }
        // Simulate removing the price before testing capacity, so exchanges can free slots.
        ItemStack[] original = p.getInventory().getStorageContents();
        deduct(p,a); deduct(p,b);
        HashMap<Integer,ItemStack> leftover = p.getInventory().addItem(reward.clone());
        if (!leftover.isEmpty()) {
            p.getInventory().setStorageContents(original);
            tell(p,msg("inventory-full")); return;
        }
        if (stock(id)>0) { trades.set("trades."+id+".stock", stock(id)-1);save(trades,tradesFile); }
        tell(p,msg("bought"));
        Bukkit.getScheduler().runTask(this, () -> { if (p.isOnline() && expires > 0) list(p,false); });
    }
}
