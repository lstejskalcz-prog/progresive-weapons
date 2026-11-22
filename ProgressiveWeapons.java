package cz.progweapons;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;

public class ProgressiveWeapons extends JavaPlugin implements Listener {

    /** Tiery v poradi vylepsovani. Klic = klic v config.yml. */
    private enum Tier {
        WOODEN("wooden", "Dřevěný", NamedTextColor.GOLD, "WOODEN_SPEAR"),
        STONE("stone", "Kamenný", NamedTextColor.GRAY, "STONE_SPEAR"),
        COPPER("copper", "Měděný", TextColor.color(0xE07B53), "COPPER_SPEAR"),
        IRON("iron", "Železný", NamedTextColor.WHITE, "IRON_SPEAR"),
        DIAMOND("diamond", "Diamantový", NamedTextColor.AQUA, "DIAMOND_SPEAR"),
        NETHERITE("netherite", "Netheritový", TextColor.color(0x4A3F45), "NETHERITE_SPEAR"),
        // Emerald nema vanilla kopi -> zaklad zlate kopi + vlastni damage
        EMERALD("emerald", "Emeraldový", NamedTextColor.GREEN, "GOLDEN_SPEAR"),
        // God -> netheritove kopi + glint + vlastni damage
        GOD("god", "GOD", NamedTextColor.RED, "NETHERITE_SPEAR");

        final String key;
        final String display;
        final TextColor color;
        final String spearMaterial;

        Tier(String key, String display, TextColor color, String spearMaterial) {
            this.key = key;
            this.display = display;
            this.color = color;
            this.spearMaterial = spearMaterial;
        }
    }

    private static final Tier[] TIERS = Tier.values();

    private NamespacedKey weaponKey; // "mace" / "spear"
    private NamespacedKey tierKey;   // int na hraci
    private NamespacedKey killsKey;  // int na hraci
    private NamespacedKey receivedKey; // byte na hraci

    @Override
    public void onEnable() {
        saveDefaultConfig();
        weaponKey = new NamespacedKey(this, "weapon");
        tierKey = new NamespacedKey(this, "tier");
        killsKey = new NamespacedKey(this, "kills");
        receivedKey = new NamespacedKey(this, "received");
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getOnlinePlayers().forEach(this::ensureWeapons);
    }

    // ------------------------------------------------------------------ eventy

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (!pdc.has(receivedKey, PersistentDataType.BYTE)) {
            pdc.set(receivedKey, PersistentDataType.BYTE, (byte) 1);
            pdc.set(tierKey, PersistentDataType.INTEGER, 0);
            pdc.set(killsKey, PersistentDataType.INTEGER, 0);
            ensureWeapons(p);
        }
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null || killer.equals(e.getEntity())) return;
        if (!getConfig().getBoolean("count-mobs", true) && !(e.getEntity() instanceof Player)) return;

        PersistentDataContainer pdc = killer.getPersistentDataContainer();
        int tier = pdc.getOrDefault(tierKey, PersistentDataType.INTEGER, 0);
        int kills = pdc.getOrDefault(killsKey, PersistentDataType.INTEGER, 0) + 1;
        pdc.set(killsKey, PersistentDataType.INTEGER, kills);

        int perUpgrade = Math.max(1, getConfig().getInt("kills-per-upgrade", 1));
        if (kills % perUpgrade != 0 || tier >= TIERS.length - 1) return;

        tier++;
        pdc.set(tierKey, PersistentDataType.INTEGER, tier);
        upgradeInventory(killer, tier);

        Tier t = TIERS[tier];
        killer.sendActionBar(Component.text("Tvoje zbraně se vylepšily na: " + t.display + "!", t.color));
        killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
    }

    /** Zbrane se po smrti NEZTRATI a tier se NERESETUJE. */
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(this::isWeapon);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        getServer().getScheduler().runTask(this, () -> ensureWeapons(p));
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isWeapon(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ logika

    private int getTier(Player p) {
        int t = p.getPersistentDataContainer().getOrDefault(tierKey, PersistentDataType.INTEGER, 0);
        return Math.max(0, Math.min(TIERS.length - 1, t));
    }

    private boolean isWeapon(ItemStack item) {
        return weaponType(item) != null;
    }

    private String weaponType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(weaponKey, PersistentDataType.STRING);
    }

    /** Da hraci chybejici mace/spear podle jeho aktualniho tieru. */
    private void ensureWeapons(Player p) {
        int tier = getTier(p);
        boolean hasMace = false, hasSpear = false;
        for (ItemStack it : p.getInventory().getContents()) {
            String type = weaponType(it);
            if ("mace".equals(type)) hasMace = true;
            if ("spear".equals(type)) hasSpear = true;
        }
        if ("spear".equals(weaponType(p.getInventory().getItemInOffHand()))) hasSpear = true;
        if ("mace".equals(weaponType(p.getInventory().getItemInOffHand()))) hasMace = true;
        if (!hasSpear) give(p, build(TIERS[tier], "spear"));
        if (!hasMace) give(p, build(TIERS[tier], "mace"));
    }

    private void give(Player p, ItemStack item) {
        if (item == null) return;
        Map<Integer, ItemStack> left = p.getInventory().addItem(item);
        left.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
    }

    private void upgradeInventory(Player p, int tier) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            String type = weaponType(inv.getItem(i));
            if (type != null) {
                ItemStack n = build(TIERS[tier], type);
                if (n != null) inv.setItem(i, n);
            }
        }
        String offhandType = weaponType(inv.getItemInOffHand());
        if (offhandType != null) inv.setItemInOffHand(build(TIERS[tier], offhandType));
        // jistota: kdyby hrac zbran nemel (napr. v enderchestu), dostane novou
        ensureWeapons(p);
    }

    private ItemStack build(Tier tier, String type) {
        boolean mace = type.equals("mace");
        Material mat = mace ? Material.MACE : Material.matchMaterial(tier.spearMaterial);
        if (mat == null) {
            getLogger().severe("Material " + tier.spearMaterial + " neexistuje - potrebujes Paper 1.21.11+ (kopi).");
            return null;
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text(tier.display + (mace ? " Mace" : " Spear"), tier.color)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, tier == Tier.GOD));
        meta.lore(List.of(
                Component.text("Tier: " + (tier.ordinal() + 1) + "/" + TIERS.length, NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Vylepšuje se za každý kill.", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.setUnbreakable(true);
        if (tier == Tier.GOD) meta.setEnchantmentGlintOverride(true);

        // vlastni damage
        Double damage = mace
                ? getConfig().getDouble("mace-damage." + tier.key, 6.0)
                : (getConfig().contains("spear-damage." + tier.key)
                        ? getConfig().getDouble("spear-damage." + tier.key) : null);
        if (damage != null) applyDamage(meta, mat, damage);

        applyEnchants(meta, tier, mace);

        meta.getPersistentDataContainer().set(weaponKey, PersistentDataType.STRING, type);
        item.setItemMeta(meta);
        return item;
    }

    /** Enchanty z configu (sekce "enchants.<tier>"). Tiery bez sekce (dreveny..zelezny) zadne nemaji. */
    private void applyEnchants(ItemMeta meta, Tier tier, boolean mace) {
        ConfigurationSection sec = getConfig().getConfigurationSection("enchants." + tier.key);
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            // lunge jen na kopi, density a wind_burst jen na mace
            if (name.equals("lunge") && mace) continue;
            if ((name.equals("density") || name.equals("wind_burst")) && !mace) continue;

            Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(name));
            if (ench == null) {
                getLogger().warning("Neznamy enchant v configu: " + name);
                continue;
            }
            meta.addEnchant(ench, sec.getInt(name), true); // true = povoli i levely nad vanilla max
        }
    }

    /** Prepise damage, ale zachova vanilla attack speed apod. */
    private void applyDamage(ItemMeta meta, Material mat, double totalDamage) {
        Multimap<Attribute, AttributeModifier> mods = ArrayListMultimap.create();
        for (Map.Entry<Attribute, AttributeModifier> en : mat.getDefaultAttributeModifiers(EquipmentSlot.HAND).entries()) {
            if (en.getKey() != Attribute.ATTACK_DAMAGE) mods.put(en.getKey(), en.getValue());
        }
        mods.put(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                NamespacedKey.minecraft("base_attack_damage"),
                totalDamage - 1.0, // hrac ma zaklad 1
                AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.MAINHAND));
        meta.setAttributeModifiers(mods);
    }
}
