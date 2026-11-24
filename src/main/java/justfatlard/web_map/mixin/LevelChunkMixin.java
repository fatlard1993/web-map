package justfatlard.web_map.mixin;

import justfatlard.web_map.Main;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A chunk marked unsaved has changed since it was last saved, so it may look different on the map. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

	@Inject(method = "markUnsaved", at = @At("HEAD"))
	private void webMap$changed(CallbackInfo ci) {
		LevelChunk chunk = (LevelChunk) (Object) this;
		if (chunk.getLevel() instanceof ServerLevel level && level.getServer().isSameThread()) {
			Main.changed(level, chunk.getPos());
		}
	}
}
