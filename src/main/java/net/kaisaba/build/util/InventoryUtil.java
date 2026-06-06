package net.kaisaba.build.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.List;

/**
 * GUI アイテム生成ユーティリティ。
 */
public final class InventoryUtil {

    private InventoryUtil() {}

    /**
     * 表示名・説明付きの ItemStack を作成する。
     *
     * @param material  素材
     * @param name      表示名（色コードなし、呼び出し元で Component を渡す）
     * @param loreLines 説明文（各行）
     */
    public static ItemStack makeItem(Material material, Component name, Component... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        if (loreLines.length > 0) {
            meta.lore(Arrays.asList(loreLines));
        }
        item.setItemMeta(meta);
        return item;
    }

    /** 表示名のみ設定するシンプル版。 */
    public static ItemStack makeItem(Material material, Component name) {
        return makeItem(material, name, new Component[0]);
    }

    /** フィラー（グレーのガラス板）。GUI の空スロット埋めに使う。 */
    public static ItemStack filler() {
        return makeItem(
            Material.GRAY_STAINED_GLASS_PANE,
            Component.text(" ", NamedTextColor.DARK_GRAY)
        );
    }

    /**
     * アイテムの表示名を plain text で返す。表示名がなければ null。
     */
    public static String getDisplayName(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasDisplayName()) return null;
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
            .plainText().serialize(meta.displayName());
    }

    /**
     * アイテムの表示名が指定文字列と一致するか判定する。
     */
    public static boolean hasDisplayName(ItemStack item, String name) {
        return name.equals(getDisplayName(item));
    }
}
