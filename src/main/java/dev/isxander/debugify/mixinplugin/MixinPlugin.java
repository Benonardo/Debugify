package dev.isxander.debugify.mixinplugin;

import dev.isxander.debugify.Debugify;
import dev.isxander.debugify.api.DebugifyApi;
import dev.isxander.debugify.fixes.BugFix;
import dev.isxander.debugify.fixes.FixCategory;
import dev.isxander.debugify.fixes.BugFixData;
import dev.isxander.debugify.fixes.OS;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;
import org.spongepowered.asm.util.Annotations;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class MixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
        Debugify.onPreInitialize();

        var entrypoints = FabricLoader.getInstance().getEntrypointContainers("debugify", DebugifyApi.class);
        entrypoints.forEach(container -> {
            DebugifyApi api = container.getEntrypoint();
            String containerModId = container.getProvider().getMetadata().getId();
            for (String bugId : api.getDisabledFixes()) {
                BugFixData.registerApiConflict(containerModId, bugId);
            }

            api.getProvidedDisabledFixes().forEach((modId, bugs) -> {
                if (FabricLoader.getInstance().isModLoaded(modId)) {
                    bugs.forEach(bugId -> BugFixData.registerApiConflict(modId, bugId));
                }
            });
        });
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }


    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        ClassNode mixinClassNode = getClassNode(mixinClassName);
        Optional<BugFixData> bugFixOptional = getBugFixForMixin(mixinClassNode);

        if (bugFixOptional.isEmpty())
            return true;

        BugFixData bugFix = bugFixOptional.get();
        var multipleMixins = Debugify.CONFIG.getBugFixes().containsKey(bugFix);
        Debugify.CONFIG.registerBugFix(bugFix);

        Set<String> conflicts = bugFix.getActiveConflicts();
        if (!conflicts.isEmpty()) {
            if (Debugify.CONFIG.isBugFixEnabled(bugFix) && !multipleMixins)
                Debugify.LOGGER.warn("Force disabled {} because it's conflicting with: {}", bugFix.bugId(), String.join(", ", conflicts));
            return false;
        } else if (!bugFix.satisfiesOSRequirement()) {
            if (Debugify.CONFIG.isBugFixEnabled(bugFix) && !multipleMixins)
                Debugify.LOGGER.warn("Force disabled {} because it only applies to OS: {}", bugFix.bugId(), bugFix.requiredOs().name());
            return false;
        }

        return Debugify.CONFIG.isBugFixEnabled(bugFix);
    }

    private ClassNode getClassNode(String className) {
        ClassNode classNode;

        try {
            classNode = MixinService.getService().getBytecodeProvider().getClassNode(className);
        } catch (ClassNotFoundException | IOException e) {
            classNode = null;
        }

        return classNode;
    }

    private Optional<BugFixData> getBugFixForMixin(ClassNode mixinClassNode) {
        AnnotationNode annotationNode = Annotations.getVisible(mixinClassNode, BugFix.class);

        if (annotationNode == null)
            return Optional.empty();

        String id = Annotations.getValue(annotationNode, "id");
        FixCategory category = getAnnotationEnumValue(annotationNode, "category", FixCategory.class);
        BugFix.Env env = getAnnotationEnumValue(annotationNode, "env", BugFix.Env.class);
        boolean enabledByDefault = Annotations.getValue(annotationNode, "enabled", Boolean.valueOf(true));
        List<String> conflicts = Annotations.getValue(annotationNode, "modConflicts", true);
        OS requiredOS = Annotations.getValue(annotationNode, "os", OS.class, OS.UNKNOWN);

        return Optional.of(new BugFixData(id, category, env, enabledByDefault, conflicts, requiredOS));
    }

    private static <T extends Enum<T>> T getAnnotationEnumValue(AnnotationNode annotation, String key, Class<T> enumClass) {
        String[] value = Annotations.getValue(annotation, key);
        return Enum.valueOf(enumClass, value[1]);
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
