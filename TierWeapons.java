package cz.tierweapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import io.papermc.paper.event.player.PlayerPostRespawnEvent; // pokud neexistuje, pouzij PlayerRespawnEvent + runTask
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class TierWeapons extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    // label, barva, nasobek damage macem, material kopi, nasobek damage kopim
    enum Tier {
        WOODEN("Dřevěný", "<#8b5a2b>", 0.30, "WOODEN_SPEAR", 1.0),
        STONE("Kamenný", "<gray>", 0.45, "STONE_SPEAR", 1.0),
        COPPER("Měděný", "<#b87333>", 0.60, "COPPER_SPEAR", 1.0),
        IRON("Železný", "<white>", 0.75, "IRON_SPEAR", 1.0),
        DIAMOND("Diamantový", "<aqua>", 0.90, "DIAMOND_SPEAR", 1.0),
        NETHERITE("Netheritový", "<dark_gray>", 1.00, "NETHERITE_SPEAR", 1.0),
        EMERALD("Smaragdový", "<green>", 1.25, "NETHERITE_SPEAR", 1.2),
        GOD("GOD", "<gold><bold>", 2.00, "NETHERITE_SPEAR", 1.6);

        final String label, color, spearMat;
        final double maceMult, spearMult;

        Tier(String label, String color, double maceMult, String spearMat, double spearMult) {
            this.label = label;
            this.color = color;
            this.maceMult = maceMult;
            this.spearMat = spearMat;
            this.spearMult = spearMult;
        }
    }

    private NamespacedKey weaponKey, levelKey, restoreKey;
    private int killsPerLevel;
    private boolean countMobs;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        killsPerLevel = Math.max(1, getConfig().getInt("kills-per-level", 1));
        countMobs = getConfig().getBoolean("count-mobs", false);
        weaponKey = new NamespacedKey(this, "weapon");
        levelKey = new NamespacedKey(this, "level");
        restoreKey = new NamespacedKey(this, "restore");
        getServer().getPluginManager().registerEvents(this, this);
    }

    // ---------- pomocne ----------

    private int getLevel(Player p) {
        return p.getPersistentDataContainer().getOrDefault(levelKey, PersistentDataType.INTEGER, 0);
    }

    private Tier tierOf(int level) {
        Tier[] t = Tier.values();
        return t[Math.max(0, Math.min(level, t.length - 1))];
    }

    private String typeOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(weaponKey, PersistentDataType.STRING);
    }

    private Material materialFor(String type, Tier t) {
        if (type.equals("mace")) return Material.MACE;
        Material m = Material.matchMaterial(t.spearMat);
        return m != null ? m : Material.IRON_SWORD;
    }

    private void apply(ItemStack it, String type, Tier t) {
        boolean mace = type.equals("mace");
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize("<!italic>" + t.color + t.label + (mace ? " Mace" : " Kopí")));
        double mult = mace ? t.maceMult : t.spearMult;
        meta.lore(List.<Component>of(
                MM.deserialize("<!italic><gray>Úroveň: <white>" + (t.ordinal() + 1) + "/" + Tier.values().length),
                MM.deserialize("<!italic><gray>Damage: <white>×" + mult),
                MM.deserialize("<!italic><dark_gray>Vylepšuje se za každý kill")
        ));
        meta.setUnbreakable(true);
        meta.setEnchantmentGlintOverride(t.ordinal() >= Tier.EMERALD.ordinal());
        meta.getPersistentDataContainer().set(weaponKey, PersistentDataType.STRING, type);
        it.setItemMeta(meta);
    }

    private ItemStack build(String type, Tier t) {
        ItemStack it = new ItemStack(materialFor(type, t));
        apply(it, type, t);
        return it;
    }

    private void giveWeapons(Player p) {
        Tier t = tierOf(getLevel(p));
        for (String type : new String[]{"spear", "mace"}) {
            p.getInventory().addItem(build(type, t)).values()
                    .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        }
    }

    private void upgradeInventory(Player p) {
        Tier t = tierOf(getLevel(p));
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            String type = typeOf(it);
            if (type == null) continue;
            it.setType(materialFor(type, t));
            apply(it, type, t);
            inv.setItem(i, it);
        }
    }

    // ---------- eventy ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!p.hasPlayedBefore()) giveWeapons(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null || killer.equals(e.getEntity())) return;
        if (!(e.getEntity() instanceof Player) && !countMobs) return;

        var pdc = killer.getPersistentDataContainer();
        int level = getLevel(killer);
        if (level >= Tier.values().length - 1) return;

        int kills = pdc.getOrDefault(new NamespacedKey(this, "kills"), PersistentDataType.INTEGER, 0) + 1;
        if (kills >= killsPerLevel) {
            kills = 0;
            pdc.set(levelKey, PersistentDataType.INTEGER, level + 1);
            upgradeInventory(killer);
            Tier t = tierOf(level + 1);
            killer.sendActionBar(MM.deserialize("<green>Zbraně vylepšeny na: " + t.color + t.label));
        }
        pdc.set(new NamespacedKey(this, "kills"), PersistentDataType.INTEGER, kills);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        String type = typeOf(hand);
        if (type == null) return;
        Tier t = tierOf(getLevel(p));
        e.setDamage(e.getDamage() * (type.equals("mace") ? t.maceMult : t.spearMult));
    }

    // smrt: zbrane nedropnout, vratit po respawnu, level zustava v PDC hrace
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (e.getKeepInventory()) return;
        boolean had = e.getDrops().removeIf(it -> typeOf(it) != null);
        if (had) e.getEntity().getPersistentDataContainer().set(restoreKey, PersistentDataType.BYTE, (byte) 1);
    }

    @EventHandler
    public void onRespawn(PlayerPostRespawnEvent e) {
        Player p = e.getPlayer();
        var pdc = p.getPersistentDataContainer();
        if (pdc.has(restoreKey, PersistentDataType.BYTE)) {
            pdc.remove(restoreKey);
            giveWeapons(p);
        }
    }

    // ---------- prikazy ----------

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0) return false;
        Player target = (a.length >= 3 ? getServer().getPlayer(a[2]) : (s instanceof Player pl ? pl : null));
        if (target == null) { s.sendMessage("Hráč nenalezen."); return true; }
        switch (a[0].toLowerCase()) {
            case "give" -> giveWeapons(target);
            case "setlevel" -> {
                try {
                    int lv = Math.max(0, Math.min(Integer.parseInt(a[1]), Tier.values().length - 1));
                    target.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, lv);
                    upgradeInventory(target);
                    s.sendMessage("Level nastaven na " + lv);
                } catch (Exception ex) { return false; }
            }
            default -> { return false; }
        }
        return true;
    }
}
