package net.juniknytt.createrailgrinding.compat;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import net.neoforged.fml.loading.LoadingModList;

/**
 * Optional-dependency gate. Each enum constant represents an external mod whose API we may
 * want to interact with iff it's installed. Sampled once at enum-init via {@link LoadingModList}
 * — the loading mod-list is final after FML's discovery phase, so a single read is sufficient.
 *
 * <p>Class-loading discipline: callers MUST gate every Sable-touching code path behind
 * {@link #runIfInstalled} / {@link #executeIfInstalled}. The inner suppliers/runnables only
 * execute when {@link #isLoaded} is true, which means the JVM doesn't resolve any classes
 * referenced inside the inner lambda body — so {@link SableSubLevels#ensureReady()}'s
 * {@code Class.forName("dev.ryanhcode.sable.*")} calls never fire when Sable isn't installed.
 *
 * <p>Pattern lifted from Create's {@code com.simibubi.create.compat.Mods} — see
 * <a href="https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/compat/Mods.java">
 * Create's Mods.java</a>. Two layers of {@code Supplier} on {@code runIfInstalled} ensure that
 * neither the inner Supplier nor the body it captures gets evaluated until both gates pass.
 */
public enum Mods {
    SABLE;

    private final String id;
    private final boolean isLoaded;

    Mods() {
        // Enum-constant name → mod id by lower-casing. SABLE → "sable". Matches Create's
        // Lang.asId(name()) idiom without dragging in catnip just for one helper.
        this.id = name().toLowerCase(Locale.ROOT);
        // LoadingModList is populated during FML's discovery/scanning phase — earlier than
        // ModList.get(), and valid for every later access point too. The conservative choice
        // for an enum that might be touched from any layer (gameplay, registration, mixin).
        this.isLoaded = LoadingModList.get().getModFileById(this.id) != null;
    }

    public String id() {
        return this.id;
    }

    public boolean isLoaded() {
        return this.isLoaded;
    }

    /**
     * Run {@code toRun} only when the mod is loaded. The outer {@code Supplier} returns the
     * inner {@code Supplier}; both layers are evaluated only behind the {@code isLoaded} gate
     * so neither the inner Supplier nor the body it captures gets evaluated until the gate
     * passes — protecting the JVM from resolving classes referenced inside the inner body
     * when the optional dependency is absent.
     *
     * <p>Uses {@link Optional#ofNullable} so the inner supplier may legitimately return
     * {@code null} (e.g. "the optional-mod query found no result") without throwing — a null
     * inner result collapses to {@link Optional#empty()}, indistinguishable to callers from
     * the mod-not-installed case. This is a deliberate divergence from Create's
     * {@code Mods.java}, which uses {@link Optional#of} and forbids null returns.
     *
     * @return {@link Optional#empty()} when the mod isn't loaded or the inner supplier returns
     *         null, otherwise the result wrapped in {@code Optional}.
     */
    public <T> Optional<T> runIfInstalled(Supplier<Supplier<T>> toRun) {
        if (this.isLoaded) {
            return Optional.ofNullable(toRun.get().get());
        }
        return Optional.empty();
    }

    /**
     * Execute {@code toExecute} only when the mod is loaded. Same two-layer gating as
     * {@link #runIfInstalled} but with a {@link Runnable} inner because the caller has no
     * return value.
     */
    public void executeIfInstalled(Supplier<Runnable> toExecute) {
        if (this.isLoaded) {
            toExecute.get().run();
        }
    }
}
