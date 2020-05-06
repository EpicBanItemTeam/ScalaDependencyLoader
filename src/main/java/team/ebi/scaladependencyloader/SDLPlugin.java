package team.ebi.scaladependencyloader;

import com.google.common.collect.ImmutableMap;
import io.github.classgraph.ClassGraph;
import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.util.annotation.NonnullByDefault;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

@NonnullByDefault
@Plugin(version = SDLPlugin.VERSION, id = SDLPlugin.ID, name = SDLPlugin.NAME, description = SDLPlugin.NAME)
public class SDLPlugin
{
    public static final String VERSION = "1.0.0";
    public static final String ID = "scala" + "dependency" + "loader";
    public static final String NAME = "Scala" + "Dependency" + "Loader";

    @Inject
    public SDLPlugin(Logger l, @ConfigDir(sharedRoot = false) Path p)
    {
        try
        {
            l.info("{} (version {})", SDLPlugin.NAME, SDLPlugin.VERSION);
            this.loadLibraries(l, p);
        }
        catch (Throwable t)
        {
            l.error("\n#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#" +
                    "\n#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#" +
                    "\n#@#@#                                                     #@#@#" +
                    "\n#@#@#   FAILED TO LOAD SCALA LIBRARIES FOR DEPENDENCIES   #@#@#" +
                    "\n#@#@#                                                     #@#@#" +
                    "\n#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#" +
                    "\n#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#@#", t);
            Sponge.getServer().shutdown();
        }
    }

    private void loadLibraries(Logger logger, Path path) throws Exception
    {
        String prefix = "net.minecraft.launchwrapper.";
        Object classLoader = Class.forName(prefix + "Launch").getField("classLoader").get(null);
        List<File> resources = new ClassGraph().overrideClassLoaders(((ClassLoader) classLoader)).getClasspathFiles();
        Set<Path> resourceFileNames = resources.stream().map(r -> r.toPath().getFileName()).collect(Collectors.toSet());

        Path libraryPath = path.resolve("libraries");
        ImmutableMap<Path, URL> scalaLibraries = getScalaLibraries();
        List<Path> librariesToBeLoaded = new ArrayList<>(scalaLibraries.size());
        List<Runnable> downloadCallbacks = new ArrayList<>(scalaLibraries.size());
        for (Map.Entry<Path, URL> entry : scalaLibraries.entrySet())
        {
            Path scalaLibraryPath = entry.getKey();
            if (resourceFileNames.contains(scalaLibraryPath))
            {
                logger.info("[Y] {} (loaded in classpath)", scalaLibraryPath);
            }
            else if (checkIsZip(libraryPath.resolve(scalaLibraryPath)))
            {
                logger.info("[Y] {} (available in local files)", scalaLibraryPath);
                librariesToBeLoaded.add(scalaLibraryPath);
            }
            else
            {
                downloadCallbacks.add(() ->
                {
                    URL downloadLink = entry.getValue();
                    try (InputStream stream = downloadLink.openStream())
                    {
                        Files.createDirectories(libraryPath);
                        logger.info("Started downloading: from {}", downloadLink);
                        Path resolvedPath = libraryPath.resolve(scalaLibraryPath);
                        Path tmpPath = Files.createTempFile(SDLPlugin.ID + "-", ".jar");
                        Files.copy(stream, tmpPath, StandardCopyOption.REPLACE_EXISTING);
                        Files.move(tmpPath, resolvedPath, StandardCopyOption.REPLACE_EXISTING);
                        if (!checkIsZip(resolvedPath))
                        {
                            String cause = "Downloaded file corrupted: " + downloadLink;
                            throw new CompletionException(new IOException(cause));
                        }
                        logger.info("Finished downloading: to {}", resolvedPath.toAbsolutePath());
                    }
                    catch (IOException e)
                    {
                        throw new CompletionException(e);
                    }
                });
                logger.info("[N] {} (not found in the local file, required downloading)", scalaLibraryPath);
                librariesToBeLoaded.add(scalaLibraryPath);
            }
        }

        waitForCallbacks(downloadCallbacks);
        Method addURL = Class.forName(prefix + "LaunchClassLoader").getMethod("addURL", URL.class);
        for (Path scalaLibraryPath : librariesToBeLoaded)
        {
            Path absolutePath = libraryPath.resolve(scalaLibraryPath).toAbsolutePath();
            addURL.invoke(classLoader, absolutePath.toUri().toURL());
            logger.info("Added to classpath: {}", absolutePath);
        }
    }

    private boolean checkIsZip(Path file)
    {
        try (ZipFile zip = new ZipFile(file.toFile()))
        {
            return zip.getEntry("META-INF/MANIFEST.MF") != null;
        }
        catch (IOException e)
        {
            return false;
        }
    }

    private ImmutableMap<Path, URL> getScalaLibraries() throws IOException
    {
        return ImmutableMap.<Path, URL>builder()
                .put(Paths.get("akka-actor_2.11-2.3.3.jar"), new URL("https://files.minecraftforge.net/maven/com/typesafe/akka/akka-actor_2.11/2.3.3/akka-actor_2.11-2.3.3.jar"))
                .put(Paths.get("scala-actors-migration_2.11-1.1.0.jar"), new URL("https://files.minecraftforge.net/maven/org/scala-lang/scala-actors-migration_2.11/1.1.0/scala-actors-migration_2.11-1.1.0.jar"))
                .put(Paths.get("scala-compiler-2.11.1.jar"), new URL("https://files.minecraftforge.net/maven/org/scala-lang/scala-compiler/2.11.1/scala-compiler-2.11.1.jar"))
                .put(Paths.get("scala-continuations-library_2.11-1.0.2.jar"), new URL("https://files.minecraftforge.net/maven/org/scala-lang/plugins/scala-continuations-library_2.11/1.0.2/scala-continuations-library_2.11-1.0.2.jar"))
                .put(Paths.get("scala-continuations-plugin_2.11.1-1.0.2.jar"), new URL("https://files.minecraftforge.net/maven/org/scala-lang/plugins/scala-continuations-plugin_2.11.1/1.0.2/scala-continuations-plugin_2.11.1-1.0.2.jar"))
                .put(Paths.get("scala-library-2.11.1.jar"), new URL("https://files.minecraftforge.net/maven/org/scala-lang/scala-library/2.11.1/scala-library-2.11.1.jar"))
                .put(Paths.get("scala-parser-combinators_2.11-1.0.1.jar"), new URL("https://files.minecraftforge.net/maven/org/scala-lang/scala-parser-combinators_2.11/1.0.1/scala-parser-combinators_2.11-1.0.1.jar"))
                .put(Paths.get("scala-reflect-2.11.1.jar"), new URL("https://files.minecraftforge.net/maven/org/scala-lang/scala-reflect/2.11.1/scala-reflect-2.11.1.jar"))
                .put(Paths.get("scala-swing_2.11-1.0.1.jar"), new URL("https://files.minecraftforge.net/maven/org/scala-lang/scala-swing_2.11/1.0.1/scala-swing_2.11-1.0.1.jar"))
                .put(Paths.get("scala-xml_2.11-1.0.2.jar"), new URL("https://files.minecraftforge.net/maven/org/scala-lang/scala-xml_2.11/1.0.2/scala-xml_2.11-1.0.2.jar"))
                .build();

    }

    private void waitForCallbacks(List<Runnable> list) // throws Exception
    {
        list.forEach(Runnable::run);
        // CompletableFuture.allOf(list.stream().map(CompletableFuture::runAsync).toArray(CompletableFuture[]::new)).get();
    }
}
