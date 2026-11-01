package com.github.claudecodegui.pet;

import javax.imageio.ImageIO;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;

/** Fresh host registry and isolated plugin loaders, as used by separate JetBrains products. */
public final class CodexPetWebpClassLoaderProcess {
    public static void main(String[] args) throws Exception {
        Thread.currentThread().setContextClassLoader(ClassLoader.getPlatformClassLoader());
        if (ImageIO.getImageReadersByFormatName("webp").hasNext()) {
            throw new AssertionError("Fixture must start without a host WebP provider");
        }
        URL[] urls = Arrays.stream(args[0].split(java.util.regex.Pattern.quote(File.pathSeparator))).map(path -> {
            try {
                return Path.of(path).toUri().toURL();
            } catch (Exception failure) {
                throw new IllegalArgumentException(failure);
            }
        }).toArray(URL[]::new);
        Path imagePath = Path.of(args[1]);
        byte[] bytes = java.nio.file.Files.readAllBytes(imagePath);
        for (String product : new String[]{"IDEA", "PyCharm", "WebStorm"}) {
            try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
                Class<?> support = Class.forName("com.github.claudecodegui.pet.CodexPetImageSupport", true, loader);
                var image = (java.awt.image.BufferedImage) support.getMethod("readImage", byte[].class).invoke(null, (Object) bytes);
                if (image == null || image.getWidth() != 8 || image.getHeight() != 8 || image.getRGB(0, 0) != 0xdc309eda) {
                    throw new AssertionError(product + " did not decode the shared WebP");
                }
                Class<?> dimensions = Class.forName("com.github.claudecodegui.pet.CodexPetImageSupport$ImageDimensions", true, loader);
                Object decoded = support.getMethod("readDecodedDimensions", Path.class).invoke(null, imagePath);
                boolean readable = decoded != null && (int) dimensions.getMethod("getWidth").invoke(decoded) == 8
                        && (int) dimensions.getMethod("getHeight").invoke(decoded) == 8;
                if (!readable || ImageIO.getImageReadersByFormatName("webp").hasNext()) {
                    throw new AssertionError(product + " metadata failed or leaked its reader into the host registry");
                }
                System.out.println(product + ": catalog metadata and preview decoded; host registry unchanged");
            }
        }
    }
}
