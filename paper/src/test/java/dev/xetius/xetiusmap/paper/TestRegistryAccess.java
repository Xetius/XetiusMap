package dev.xetius.xetiusmap.paper;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * Enough of Paper's registry for tests to touch registry-backed types such as {@code Biome}.
 *
 * <p>Those types are interfaces whose constants resolve through {@link RegistryAccess} in their
 * static initialiser, so merely mentioning {@code Biome} outside a running server throws. Paper
 * finds this implementation through {@code ServiceLoader}, which is why it is registered in
 * {@code META-INF/services} rather than installed by any test.
 *
 * <p>Entries are conjured on demand: every lookup returns a proxy that knows its own key and
 * refuses everything else. That is all the map renderers ask of a biome, and anything that wants
 * more should be failing loudly rather than reading a fabricated answer.
 */
public final class TestRegistryAccess implements RegistryAccess {

    @Override
    public <T extends Keyed> Registry<T> getRegistry(Class<T> type) {
        return registryOf(type);
    }

    @Override
    public <T extends Keyed> Registry<T> getRegistry(RegistryKey<T> key) {
        return registryOf(elementTypeOf(key));
    }

    /**
     * The type a registry holds, which callers cast their lookups to.
     *
     * <p>{@link RegistryKey} carries it only in the generic signature of its constants, so the
     * constant is found by identity and its declared type read back. Reflection beats a hand-written
     * table of the sixty-odd registries, which would silently rot as Paper adds more.
     */
    private static Class<?> elementTypeOf(RegistryKey<?> key) {
        for (Field field : RegistryKey.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !RegistryKey.class.equals(field.getType())) {
                continue;
            }
            try {
                if (field.get(null) != key) {
                    continue;
                }
            } catch (IllegalAccessException e) {
                continue;
            }
            if (field.getGenericType() instanceof ParameterizedType parameterized) {
                Type element = parameterized.getActualTypeArguments()[0];
                if (element instanceof ParameterizedType nested) {
                    element = nested.getRawType();
                }
                if (element instanceof Class<?> type) {
                    return type;
                }
            }
        }
        return Keyed.class;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Keyed> Registry<T> registryOf(Class<?> type) {
        Map<NamespacedKey, Object> entries = new HashMap<>();
        return (Registry<T>) Proxy.newProxyInstance(
                TestRegistryAccess.class.getClassLoader(),
                new Class<?>[] {Registry.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "get", "getOrThrow" -> entries.computeIfAbsent(
                            asNamespacedKey(args[0]), key -> keyed(type, key));
                    case "iterator" -> entries.values().iterator();
                    case "stream" -> entries.values().stream();
                    default -> throw new UnsupportedOperationException(
                            "TestRegistryAccess does not implement Registry#" + method.getName());
                });
    }

    /** Lookups arrive keyed by either Bukkit's own key type or Adventure's. */
    private static NamespacedKey asNamespacedKey(Object key) {
        if (key instanceof NamespacedKey namespaced) {
            return namespaced;
        }
        Key adventure = (Key) key;
        return new NamespacedKey(adventure.namespace(), adventure.value());
    }

    private static Object keyed(Class<?> type, NamespacedKey key) {
        Class<?> asInterface = type.isInterface() ? type : Keyed.class;
        return Proxy.newProxyInstance(
                TestRegistryAccess.class.getClassLoader(),
                new Class<?>[] {asInterface},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getKey", "key" -> key;
                    case "toString" -> key.toString();
                    case "hashCode" -> key.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            "TestRegistryAccess entry " + key + " does not implement " + method.getName());
                });
    }
}
