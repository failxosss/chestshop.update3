package org.simpleshop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

public class ShopListener implements Listener {

    // Kolik "vecnych" sloupcu ma GUI k dispozici na radek (27/54 slotu = radky po 9,
    // krajni sloupce 0 a 8 jsou vzdy ramecek).
    private static final int ITEMS_PER_ROW = 7;
    // Maximalni pocet radku GUI (54 slotu = dvojita truhla). Min. 3 (jeden radek na zbozi).
    private static final int MAX_ROWS = 6;
    // Maximalni pocet ruznych druhu zbozi, ktere shop pojme.
    private static final int MAX_ITEM_TYPES = (MAX_ROWS - 2) * ITEMS_PER_ROW;

    private final SimpleShop plugin;
    private final NamespacedKey KEY_ITEM;
    private final NamespacedKey KEY_OWNER;
    private final NamespacedKey KEY_MODE;
    private final NamespacedKey KEY_PRICE;

    public ShopListener(SimpleShop plugin) {
        this.plugin = plugin;
        this.KEY_ITEM = new NamespacedKey(plugin, "item");
        this.KEY_OWNER = new NamespacedKey(plugin, "owner");
        this.KEY_MODE = new NamespacedKey(plugin, "mode");
        this.KEY_PRICE = new NamespacedKey(plugin, "price");
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        String line0 = event.getLine(0) == null ? "" : ChatColor.stripColor(event.getLine(0)).trim();
        if (!line0.equalsIgnoreCase("[shop]")) {
            return;
        }
        Player player = event.getPlayer();
        String raw = event.getLine(1) == null ? "" : event.getLine(1).replace(",", ".").trim();
        double price;
        try {
            price = Double.parseDouble(raw);
            if (price <= 0.0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Druhy radek musi byt kladne cislo (cena), napr. 100");
            this.cancelSign(event);
            return;
        }
        String modeRaw = event.getLine(2) == null ? "" : event.getLine(2).trim();
        if (!modeRaw.equalsIgnoreCase("B") && !modeRaw.equalsIgnoreCase("S")) {
            player.sendMessage(ChatColor.RED + "Treti radek musi byt 'B' (vykup od hracu) nebo 'S' (prodej hracum)");
            this.cancelSign(event);
            return;
        }
        boolean buyFromPlayer = modeRaw.equalsIgnoreCase("B");
        Block chestBlock = this.getAttachedContainer(event.getBlock());
        if (chestBlock == null || !(chestBlock.getState() instanceof Chest)) {
            player.sendMessage(ChatColor.RED + "Cedule musi byt postavena na truhle nebo pripevnena na jeji predni stranu.");
            this.cancelSign(event);
            return;
        }
        Chest chest = (Chest) chestBlock.getState();

        List<ItemStack> found = this.collectDistinctItems(chest.getInventory());
        if (found.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Nejdriv vloz do truhly alespon 1 kus itemu (klidne i vic ruznych druhu, treba i shulker s vecma), ktery chces "
                    + (buyFromPlayer ? "vykupovat." : "prodavat.") + " Pak postav ceduli znovu.");
            this.cancelSign(event);
            return;
        }
        if (found.size() > MAX_ITEM_TYPES) {
            player.sendMessage(ChatColor.YELLOW + "Shop zvladne max " + MAX_ITEM_TYPES + " ruznych druhu zbozi, pouziva se prvnich " + MAX_ITEM_TYPES + ".");
            found = new ArrayList<>(found.subList(0, MAX_ITEM_TYPES));
        }

        String serialized = this.serializeItems(found);
        event.setLine(0, ChatColor.GREEN + "[Shop]");
        event.setLine(1, this.formatPrice(price));
        event.setLine(2, (buyFromPlayer ? ChatColor.GOLD : ChatColor.AQUA) + (buyFromPlayer ? "VYKUP (B)" : "PRODEJ (S)"));
        event.setLine(3, this.formatShopLabel(found));

        double finalPrice = price;
        boolean finalBuyFromPlayer = buyFromPlayer;
        Block signBlock = event.getBlock();
        UUID ownerId = player.getUniqueId();
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (signBlock.getState() instanceof Sign) {
                Sign sign = (Sign) signBlock.getState();
                sign.getPersistentDataContainer().set(this.KEY_ITEM, PersistentDataType.STRING, serialized);
                sign.getPersistentDataContainer().set(this.KEY_OWNER, PersistentDataType.STRING, ownerId.toString());
                sign.getPersistentDataContainer().set(this.KEY_MODE, PersistentDataType.STRING, finalBuyFromPlayer ? "B" : "S");
                sign.getPersistentDataContainer().set(this.KEY_PRICE, PersistentDataType.DOUBLE, finalPrice);
                sign.update(true, false);
            }
        });
        player.sendMessage(ChatColor.GREEN + "Shop vytvoren: " + (buyFromPlayer ? "vykupujes " : "prodavas ")
                + this.formatShopLabel(found) + " za " + this.formatPrice(price) + " / ks");
    }

    /**
     * Projde obsah truhly a vrati seznam odlisnych druhu itemu (podle isSimilar), kazdy jako
     * jeden kus (mnozstvi 1). Poradi odpovida poradi, v jakem se dany druh v truhle poprve objevi.
     */
    private List<ItemStack> collectDistinctItems(Inventory chestInventory) {
        List<ItemStack> found = new ArrayList<>();
        for (ItemStack item : chestInventory.getContents()) {
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            boolean already = false;
            for (ItemStack existing : found) {
                if (existing.isSimilar(item)) {
                    already = true;
                    break;
                }
            }
            if (!already) {
                ItemStack clone = item.clone();
                clone.setAmount(1);
                found.add(clone);
            }
        }
        return found;
    }

    private void cancelSign(SignChangeEvent event) {
        event.setLine(0, ChatColor.RED + "[Chyba]");
        event.setLine(1, "");
        event.setLine(2, "");
        event.setLine(3, "");
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !(block.getState() instanceof Sign)) {
            return;
        }
        Sign sign = (Sign) block.getState();
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        if (!pdc.has(this.KEY_ITEM, PersistentDataType.STRING)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        String itemData = pdc.get(this.KEY_ITEM, PersistentDataType.STRING);
        String ownerUuidStr = pdc.get(this.KEY_OWNER, PersistentDataType.STRING);
        String modeRaw = pdc.get(this.KEY_MODE, PersistentDataType.STRING);
        Double price = pdc.get(this.KEY_PRICE, PersistentDataType.DOUBLE);
        if (itemData == null || ownerUuidStr == null || modeRaw == null || price == null) {
            player.sendMessage(ChatColor.RED + "Tato cedule je poskozena.");
            return;
        }
        List<ItemStack> templates = this.deserializeItems(itemData);
        if (templates.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Tato cedule je poskozena.");
            return;
        }
        UUID ownerId = UUID.fromString(ownerUuidStr);
        boolean buyFromPlayer = "B".equals(modeRaw);
        ShopHolder holder = new ShopHolder(block, templates, buyFromPlayer, price, ownerId);

        int rows = this.computeRows(templates.size());
        int[] slots = this.computeSlots(rows, templates.size());
        String title = (buyFromPlayer ? ChatColor.GOLD : ChatColor.AQUA) + (buyFromPlayer ? "Vykup: " : "Prodej: ") + this.formatShopLabel(templates);
        Inventory inv = Bukkit.createInventory(holder, rows * 9, title);
        holder.setInventory(inv);
        holder.setSlotMapping(slots);
        this.fillBorders(inv);
        this.refreshDisplay(inv, holder);
        player.openInventory(inv);
    }

    /**
     * Kolik radku (3-6, tj. 27-54 slotu) GUI potrebuje pro dany pocet druhu zbozi.
     * Kazdy radek (krome hornich/dolnich ramecku) pojme az ITEMS_PER_ROW kusu zbozi.
     */
    private int computeRows(int itemCount) {
        int interiorRowsNeeded = (int) Math.ceil(itemCount / (double) ITEMS_PER_ROW);
        if (interiorRowsNeeded < 1) {
            interiorRowsNeeded = 1;
        }
        int rows = interiorRowsNeeded + 2;
        if (rows > MAX_ROWS) {
            rows = MAX_ROWS;
        }
        if (rows < 3) {
            rows = 3;
        }
        return rows;
    }

    /**
     * Spocita, do kterych slotu (mimo ramecek) se zbozi rozmisti, v poradi radek po radku.
     */
    private int[] computeSlots(int rows, int itemCount) {
        List<Integer> slots = new ArrayList<>();
        for (int r = 1; r <= rows - 2 && slots.size() < itemCount; r++) {
            for (int c = 1; c <= ITEMS_PER_ROW && slots.size() < itemCount; c++) {
                slots.add(r * 9 + c);
            }
        }
        int[] result = new int[slots.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = slots.get(i);
        }
        return result;
    }

    private void fillBorders(Inventory inv) {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            filler.setItemMeta(meta);
        }
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }
    }

    private void refreshDisplay(Inventory inv, ShopHolder holder) {
        List<ItemStack> templates = holder.getTemplates();
        int[] slots = holder.getSlotMapping();

        Inventory chestInv = null;
        Block chestBlock = this.getAttachedContainer(holder.getSignBlock());
        if (chestBlock != null && chestBlock.getState() instanceof Chest) {
            chestInv = ((Chest) chestBlock.getState()).getInventory();
        }

        for (int i = 0; i < templates.size() && i < slots.length; i++) {
            ItemStack template = templates.get(i);
            ItemStack display = template.clone();
            ItemMeta meta = display.getItemMeta();
            List<String> lore = new ArrayList<>();
            if (meta != null && meta.hasLore() && meta.getLore() != null) {
                lore.addAll(meta.getLore());
                lore.add("");
            }

            int stock = chestInv != null ? this.countMatching(chestInv, template) : -1;
            lore.add(ChatColor.GRAY + "Cena: " + ChatColor.WHITE + this.formatPrice(holder.getPrice()) + " / ks");
            if (holder.isBuyFromPlayer()) {
                lore.add(ChatColor.YELLOW + "Klikni pro prodej 1 ks");
                lore.add(ChatColor.YELLOW + "Shift+klik pro prodej cele stacky");
            } else {
                if (stock >= 0) {
                    lore.add(ChatColor.WHITE + "Sklad: " + stock + " ks");
                }
                lore.add(ChatColor.YELLOW + "Klikni pro koupi 1 ks");
                lore.add(ChatColor.YELLOW + "Shift+klik pro koupi cele stacky");
                if (this.isShulkerBox(template)) {
                    lore.add(ChatColor.YELLOW + "Pravy klik pro nahled obsahu");
                }
            }
            if (meta != null) {
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            inv.setItem(slots[i], display);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (topInventory.getHolder() instanceof ShulkerPreviewHolder) {
            event.setCancelled(true);
            return;
        }
        if (!(topInventory.getHolder() instanceof ShopHolder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(topInventory)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        ShopHolder holder = (ShopHolder) topInventory.getHolder();
        Integer templateIndex = holder.getTemplateIndexForSlot(event.getSlot());
        if (templateIndex == null) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        Block chestBlock = this.getAttachedContainer(holder.getSignBlock());
        if (chestBlock == null || !(chestBlock.getState() instanceof Chest)) {
            player.sendMessage(ChatColor.RED + "Truhla tohoto shopu chybi nebo byla znicena.");
            player.closeInventory();
            return;
        }
        Chest chest = (Chest) chestBlock.getState();
        Inventory chestInv = chest.getInventory();
        OfflinePlayer owner = Bukkit.getOfflinePlayer(holder.getOwnerId());
        Economy econ = SimpleShop.getEconomy();
        ItemStack template = holder.getTemplates().get(templateIndex);
        ClickType click = event.getClick();

        if (!holder.isBuyFromPlayer() && this.isShulkerBox(template) && (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT)) {
            this.openShulkerPreview(player, template);
            return;
        }

        int amount = click.isShiftClick() ? template.getMaxStackSize() : 1;
        if (holder.isBuyFromPlayer()) {
            this.handlePlayerSells(player, owner, econ, chestInv, template, holder.getPrice(), amount);
        } else {
            this.handlePlayerBuys(player, owner, econ, chestInv, template, holder.getPrice(), amount);
        }
        this.refreshDisplay(topInventory, holder);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShopHolder) {
            event.setCancelled(true);
        }
    }

    private boolean isShulkerBox(ItemStack item) {
        return item != null && item.getType().name().endsWith("SHULKER_BOX");
    }

    private void openShulkerPreview(Player player, ItemStack shulkerItem) {
        ItemMeta meta = shulkerItem.getItemMeta();
        if (!(meta instanceof BlockStateMeta)) {
            player.sendMessage(ChatColor.RED + "Tento item nejde nahlednout.");
            return;
        }
        BlockState state = ((BlockStateMeta) meta).getBlockState();
        if (!(state instanceof ShulkerBox)) {
            player.sendMessage(ChatColor.RED + "Tento item nejde nahlednout.");
            return;
        }
        ShulkerBox shulkerBox = (ShulkerBox) state;
        Inventory shulkerInv = shulkerBox.getInventory();
        ShulkerPreviewHolder previewHolder = new ShulkerPreviewHolder();
        Inventory preview = Bukkit.createInventory(previewHolder, shulkerInv.getSize(),
                ChatColor.DARK_PURPLE + "Obsah: " + this.formatItemName(shulkerItem));
        previewHolder.setInventory(preview);
        ItemStack[] contents = shulkerInv.getContents();
        for (int i = 0; i < contents.length && i < preview.getSize(); i++) {
            preview.setItem(i, contents[i] == null ? null : contents[i].clone());
        }
        player.openInventory(preview);
    }

    private void handlePlayerBuys(Player player, OfflinePlayer owner, Economy econ, Inventory chestInv, ItemStack template, double price, int amount) {
        int available = this.countMatching(chestInv, template);
        if (available <= 0) {
            player.sendMessage(ChatColor.RED + "Shop je vyprodany.");
            return;
        }
        amount = Math.min(amount, available);
        double total = price * (double) amount;
        if (econ.getBalance(player) < total) {
            player.sendMessage(ChatColor.RED + "Nemas dostatek penez. Potrebujes " + this.formatPrice(total));
            return;
        }
        econ.withdrawPlayer(player, total);
        econ.depositPlayer(owner, total);
        this.removeMatching(chestInv, template, amount);
        ItemStack giveStack = template.clone();
        giveStack.setAmount(amount);
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(giveStack);
        for (ItemStack item : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
        player.sendMessage(ChatColor.GREEN + "Koupil jsi " + amount + "x " + this.formatItemName(template) + " za " + this.formatPrice(total));
    }

    private void handlePlayerSells(Player player, OfflinePlayer owner, Economy econ, Inventory chestInv, ItemStack template, double price, int amount) {
        int playerHas = this.countMatching(player.getInventory(), template);
        if (playerHas <= 0) {
            player.sendMessage(ChatColor.RED + "Nemas co prodat - potrebujes " + this.formatItemName(template));
            return;
        }
        amount = Math.min(amount, playerHas);
        double total = price * (double) amount;
        if (econ.getBalance(owner) < total) {
            player.sendMessage(ChatColor.RED + "Majitel shopu nema dostatek penez na vykup.");
            return;
        }
        int space = this.freeSpaceForTemplate(chestInv, template);
        if (space <= 0) {
            player.sendMessage(ChatColor.RED + "Truhla shopu je plna, nelze prodat.");
            return;
        }
        amount = Math.min(amount, space);
        total = price * (double) amount;
        this.removeMatching(player.getInventory(), template, amount);
        ItemStack addStack = template.clone();
        addStack.setAmount(amount);
        chestInv.addItem(addStack);
        econ.withdrawPlayer(owner, total);
        econ.depositPlayer(player, total);
        player.sendMessage(ChatColor.GREEN + "Prodal jsi " + amount + "x " + this.formatItemName(template) + " za " + this.formatPrice(total));
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!(block.getState() instanceof Sign)) {
            return;
        }
        Sign sign = (Sign) block.getState();
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        if (!pdc.has(this.KEY_ITEM, PersistentDataType.STRING)) {
            return;
        }
        Player player = event.getPlayer();
        if (player.isOp() || player.hasPermission("simpleshop.admin")) {
            return;
        }
        String ownerUuidStr = pdc.get(this.KEY_OWNER, PersistentDataType.STRING);
        if (ownerUuidStr != null && ownerUuidStr.equals(player.getUniqueId().toString())) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(ChatColor.RED + "Tuto cedulku shopu muze zbourat jen jeji majitel.");
    }

    private Block getAttachedContainer(Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof WallSign) {
            WallSign wallSign = (WallSign) data;
            return block.getRelative(wallSign.getFacing().getOppositeFace());
        }
        return block.getRelative(BlockFace.DOWN);
    }

    private int countMatching(Inventory inventory, ItemStack template) {
        int count = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item == null || !item.isSimilar(template)) {
                continue;
            }
            count += item.getAmount();
        }
        return count;
    }

    private void removeMatching(Inventory inventory, ItemStack template, int amount) {
        int remaining = amount;
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || !item.isSimilar(template)) {
                continue;
            }
            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            if (item.getAmount() <= 0) {
                inventory.setItem(i, null);
            } else {
                inventory.setItem(i, item);
            }
            remaining -= take;
        }
    }

    private int freeSpaceForTemplate(Inventory inventory, ItemStack template) {
        int space = 0;
        int maxStack = template.getMaxStackSize();
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType() == Material.AIR) {
                space += maxStack;
                continue;
            }
            if (!item.isSimilar(template)) {
                continue;
            }
            space += maxStack - item.getAmount();
        }
        return space;
    }

    private String formatPrice(double price) {
        if (price == Math.floor(price)) {
            return String.valueOf((long) price);
        }
        return String.format("%.2f", price);
    }

    private String formatItemName(ItemStack item) {
        String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                ? ChatColor.stripColor(item.getItemMeta().getDisplayName())
                : item.getType().name().replace("_", " ").toLowerCase();
        if (name.length() > 14) {
            name = name.substring(0, 14);
        }
        return name;
    }

    /**
     * Text pouzity na 4. radku cedule a v nadpisu GUI - jmeno itemu, pokud je jen jeden druh,
     * jinak pocet druhu zbozi.
     */
    private String formatShopLabel(List<ItemStack> templates) {
        if (templates.size() == 1) {
            return this.formatItemName(templates.get(0));
        }
        return templates.size() + " druhu zbozi";
    }

    private String serializeItems(List<ItemStack> items) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            dataOutput.writeInt(items.size());
            for (ItemStack item : items) {
                dataOutput.writeObject(item);
            }
            dataOutput.close();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Nepodarilo se ulozit itemy do cedule", e);
        }
    }

    private List<ItemStack> deserializeItems(String data) {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            int count = dataInput.readInt();
            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                items.add((ItemStack) dataInput.readObject());
            }
            dataInput.close();
            return items;
        } catch (Exception e) {
            // Zpetna kompatibilita se starymi cedulemi ulozenymi jeste jako jeden item (bez poctu na zacatku).
            try {
                ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
                BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
                ItemStack single = (ItemStack) dataInput.readObject();
                dataInput.close();
                List<ItemStack> items = new ArrayList<>();
                items.add(single);
                return items;
            } catch (Exception legacyFailure) {
                throw new RuntimeException("Nepodarilo se nacist itemy z cedule", legacyFailure);
            }
        }
    }
}
