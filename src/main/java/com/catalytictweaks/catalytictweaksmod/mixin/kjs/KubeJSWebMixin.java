package com.catalytictweaks.catalytictweaksmod.mixin.kjs;

import com.google.gson.JsonArray;
import dev.latvian.apps.tinyserver.http.response.HTTPResponse;
import dev.latvian.mods.kubejs.web.JsonContent;
import dev.latvian.mods.kubejs.web.KJSHTTPRequest;
import dev.latvian.mods.kubejs.web.LocalWebServerRegistry;
import dev.latvian.mods.kubejs.web.local.KubeJSWeb;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

@Mixin(value = KubeJSWeb.class, remap = false)
public class KubeJSWebMixin
{

    @Inject(method = "register", at = @At("RETURN"))
    private static void onRegister(LocalWebServerRegistry registry, CallbackInfo ci)
    {
        registry.get("/api/resources", KubeJSWebMixin::getResources);
    }

    @Unique
    private static HTTPResponse getResources(KJSHTTPRequest req)
    {
        return HTTPResponse.ok().content(JsonContent.object(json -> {
            for(var mod : ModList.get().getSortedMods())
            {
                var modFile = mod.getModInfo().getOwningFile().getFile();
                var rootPath = modFile.findResource("");
                var modPaths = new JsonArray();

                for(String folderName : List.of("assets", "data"))
                {
                    Path folderPath = rootPath.resolve(folderName);

                    if(Files.exists(folderPath))
                    {
                        try(Stream<Path> stream = Files.walk(folderPath))
                        {
                            stream.filter(Files::isRegularFile).forEach(path -> {
                                String relativePath = rootPath.relativize(path).toString().replace('\\', '/');
                                modPaths.add(relativePath);
                            });
                        }
                        catch(Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                }

                if(modPaths.size() > 0)
                {
                    json.add(mod.getModId(), modPaths);
                }
            }
        }));
    }
}