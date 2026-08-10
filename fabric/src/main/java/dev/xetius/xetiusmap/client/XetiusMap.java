package dev.xetius.xetiusmap.client;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Mod-wide constants. */
public final class XetiusMap {

    public static final String MOD_ID = "xetiusmap";
    public static final Logger LOGGER = LoggerFactory.getLogger("XetiusMap");

    private XetiusMap() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
