package com.spirit.mixin.jmixin;

import com.spirit.koil.api.bytecode.jmixin.JMixinPrelaunchCompiler;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class JMixinMixinConfigPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
        System.out.println("[JMIXIN] onLoad: " + mixinPackage);
        JMixinPrelaunchCompiler.compileIfNeeded();
        System.out.println("[JMIXIN] mixins: " + JMixinPrelaunchCompiler.mixinClassNames());
    }

    @Override
    public String getRefMapperConfig() {return null;}

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {return JMixinPrelaunchCompiler.mixinClassNames();}

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        System.out.println("[JMIXIN] shouldApplyMixin target=" + targetClassName + " mixin=" + mixinClassName);
        return true;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        System.out.println("[JMIXIN] preApply target=" + targetClassName + " mixin=" + mixinClassName);
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        System.out.println("[JMIXIN] postApply target=" + targetClassName + " mixin=" + mixinClassName);
    }
}
