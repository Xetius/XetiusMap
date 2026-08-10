package dev.xetius.xetiusmap.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.xetius.xetiusmap.client.screen.ConfigScreen;

/**
 * Puts the settings screen behind Mod Menu's configure button.
 *
 * <p>Mod Menu is a compile-only dependency and this class is only ever loaded through the
 * {@code modmenu} entrypoint, which Mod Menu itself invokes. Without Mod Menu installed nothing
 * here is touched, so the mod carries no runtime dependency on it.
 */
public final class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ConfigScreen::new;
    }
}
