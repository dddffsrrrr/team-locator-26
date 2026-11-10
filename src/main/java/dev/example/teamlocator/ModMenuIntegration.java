package dev.example.teamlocator;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Adds the "Configure" button for Team Locator in Mod Menu (only used if Mod Menu is installed). */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return TeamLocatorClient::createConfigScreen;
    }
}
