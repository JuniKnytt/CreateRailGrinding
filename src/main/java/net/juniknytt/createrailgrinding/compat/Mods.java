package net.juniknytt.createrailgrinding.compat;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import net.neoforged.fml.loading.LoadingModList;

public enum Mods {
    SABLE,
    EMF("entity_model_features");

    private final String id;
    private final boolean isLoaded;

    Mods() {

        this.id = name().toLowerCase(Locale.ROOT);

        this.isLoaded = LoadingModList.get().getModFileById(this.id) != null;
    }

    Mods(String explicitId) {

        this.id = explicitId;

        this.isLoaded = LoadingModList.get().getModFileById(this.id) != null;
    }

    public String id() {
        return this.id;
    }

    public boolean isLoaded() {
        return this.isLoaded;
    }

    public <T> Optional<T> runIfInstalled(Supplier<Supplier<T>> toRun) {
        if (this.isLoaded) {
            return Optional.ofNullable(toRun.get().get());
        }
        return Optional.empty();
    }

    public void executeIfInstalled(Supplier<Runnable> toExecute) {
        if (this.isLoaded) {
            toExecute.get().run();
        }
    }
}
