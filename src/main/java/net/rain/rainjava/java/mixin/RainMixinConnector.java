package net.rain.rainjava.mixin;

import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.connect.IMixinConnector;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;

@SuppressWarnings("unused")
public class RainMixinConnector implements IMixinConnector {
    @Override
    public void connect() {
        net.rain.rainjava.RainJava.LOGGER.info("尝试添加MixinConfig");
        Mixins.addConfiguration(FMLPaths.GAMEDIR.get().resolve(".rain_mixin").resolve("rainjava.mixins.json").toString());
        net.rain.rainjava.RainJava.LOGGER.info("添加MixinConfig成功！");
    }
}
