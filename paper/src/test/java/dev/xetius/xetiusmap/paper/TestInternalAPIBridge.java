package dev.xetius.xetiusmap.paper;

import io.papermc.paper.InternalAPIBridge;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;

import java.lang.reflect.Proxy;

/**
 * Satisfies the one internal hook {@code Biome} needs before its constants will initialise.
 *
 * <p>{@code Biome}'s static initialiser asks the bridge for the legacy custom biome, so without an
 * implementation on the classpath the class cannot load at all and no test may so much as name it.
 * Paper discovers this through {@code ServiceLoader}, alongside {@link TestRegistryAccess}.
 *
 * <p>Every other method throws. Tests should fail loudly rather than quietly read invented answers
 * out of a stub, and the throwing bodies double as a to-do list: if Paper grows a method this class
 * stops compiling, which is the right moment to decide what it should do.
 */
public final class TestInternalAPIBridge implements InternalAPIBridge {

    @Override
    public Biome constructLegacyCustomBiome() {
        NamespacedKey key = NamespacedKey.minecraft("custom");
        return (Biome) Proxy.newProxyInstance(
                TestInternalAPIBridge.class.getClassLoader(),
                new Class<?>[] {Biome.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getKey", "key" -> key;
                    case "toString" -> key.toString();
                    case "hashCode" -> key.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> throw unsupported("legacy custom biome: " + method.getName());
                });
    }

    private static UnsupportedOperationException unsupported(String what) {
        return new UnsupportedOperationException("TestInternalAPIBridge does not implement " + what);
    }

    @Override
    public io.papermc.paper.world.damagesource.CombatEntry createCombatEntry(org.bukkit.entity.LivingEntity a, org.bukkit.damage.DamageSource b, float c) {
        throw unsupported("createCombatEntry");
    }

    @Override
    public io.papermc.paper.world.damagesource.CombatEntry createCombatEntry(org.bukkit.damage.DamageSource a, float b, io.papermc.paper.world.damagesource.FallLocationType c, float d) {
        throw unsupported("createCombatEntry");
    }

    @Override
    public java.util.function.Predicate<io.papermc.paper.command.brigadier.CommandSourceStack> restricted(java.util.function.Predicate<io.papermc.paper.command.brigadier.CommandSourceStack> a) {
        throw unsupported("restricted");
    }

    @Override
    public io.papermc.paper.datacomponent.item.ResolvableProfile defaultMannequinProfile() {
        throw unsupported("defaultMannequinProfile");
    }

    @Override
    public com.destroystokyo.paper.SkinParts.Mutable allSkinParts() {
        throw unsupported("allSkinParts");
    }

    @Override
    public net.kyori.adventure.text.Component defaultMannequinDescription() {
        throw unsupported("defaultMannequinDescription");
    }

    @Override
    public <MODERN, LEGACY> org.bukkit.GameRule<LEGACY> legacyGameRuleBridge(org.bukkit.GameRule<MODERN> a, java.util.function.Function<LEGACY, MODERN> b, java.util.function.Function<MODERN, LEGACY> c, java.lang.Class<LEGACY> d) {
        throw unsupported("legacyGameRuleBridge");
    }

    @Override
    public java.util.Set<org.bukkit.entity.Pose> validMannequinPoses() {
        throw unsupported("validMannequinPoses");
    }

    @Override
    public io.papermc.paper.entity.poi.PoiType.Occupancy createOccupancy(java.lang.String a) {
        throw unsupported("createOccupancy");
    }

    @Override
    public org.bukkit.damage.DamageSource.Builder createDamageSourceBuilder(org.bukkit.damage.DamageType a) {
        throw unsupported("createDamageSourceBuilder");
    }

    @Override
    public org.bukkit.damage.DamageEffect getDamageEffect(java.lang.String a) {
        throw unsupported("getDamageEffect");
    }

    @Override
    public java.lang.String getTranslationKey(org.bukkit.entity.EntityType a) {
        throw unsupported("getTranslationKey");
    }

    @Override
    public org.bukkit.entity.SpawnCategory getSpawnCategory(org.bukkit.entity.EntityType a) {
        throw unsupported("getSpawnCategory");
    }

    @Override
    public com.destroystokyo.paper.util.VersionFetcher getVersionFetcher() {
        throw unsupported("getVersionFetcher");
    }

    @Override
    public org.bukkit.inventory.ItemStack deserializeItem(byte[] a) {
        throw unsupported("deserializeItem");
    }

    @Override
    public boolean hasDefaultEntityAttributes(org.bukkit.NamespacedKey a) {
        throw unsupported("hasDefaultEntityAttributes");
    }

    @Override
    public org.bukkit.attribute.Attributable getDefaultEntityAttributes(org.bukkit.NamespacedKey a) {
        throw unsupported("getDefaultEntityAttributes");
    }

    @Override
    public java.lang.String getStatisticCriteriaKey(org.bukkit.Statistic a) {
        throw unsupported("getStatisticCriteriaKey");
    }

    @Override
    public io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager<org.bukkit.plugin.Plugin> createPluginLifecycleEventManager(org.bukkit.plugin.java.JavaPlugin a, java.util.function.BooleanSupplier b) {
        throw unsupported("createPluginLifecycleEventManager");
    }

    @Override
    public org.bukkit.inventory.ItemStack createEmptyStack() {
        throw unsupported("createEmptyStack");
    }

    @Override
    public net.kyori.adventure.text.Component resolveWithContext(net.kyori.adventure.text.Component a, org.bukkit.command.CommandSender b, org.bukkit.entity.Entity c, boolean d) throws java.io.IOException {
        throw unsupported("resolveWithContext");
    }

    @Override
    public net.kyori.adventure.text.flattener.ComponentFlattener componentFlattener() {
        throw unsupported("componentFlattener");
    }
}
