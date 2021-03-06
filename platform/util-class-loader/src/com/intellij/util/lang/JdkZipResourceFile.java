// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.util.lang;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Enumeration;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@ApiStatus.Internal
// ZipFile's native implementation (ZipFile.c, zip_util.c) has path -> file descriptor cache
public final class JdkZipResourceFile implements ResourceFile {
  private volatile SoftReference<ZipFile> zipFileSoftReference;
  private final boolean lockJars;
  private final File file;
  private final boolean isSecureLoader;

  private static final Object lock = new Object();

  public JdkZipResourceFile(@NotNull Path path, boolean lockJars, boolean isSecureLoader) {
    this.lockJars = lockJars;
    this.file = path.toFile();
    this.isSecureLoader = isSecureLoader;
  }

  @NotNull ZipFile getZipFile() throws IOException {
    // This code is executed at least 100K times (O(number of classes needed to load)) and it takes considerable time to open ZipFile's
    // such number of times so we store reference to ZipFile if we allowed to lock the file (assume it isn't changed)
    if (!lockJars) {
      return createZipFile(file);
    }

    SoftReference<ZipFile> ref = zipFileSoftReference;
    ZipFile zipFile = ref == null ? null : ref.get();
    if (zipFile != null) {
      return zipFile;
    }

    synchronized (lock) {
      ref = zipFileSoftReference;
      zipFile = ref == null ? null : ref.get();
      if (zipFile != null) {
        return zipFile;
      }

      zipFile = createZipFile(file);
      zipFileSoftReference = new SoftReference<>(zipFile);
    }
    return zipFile;
  }

  private ZipFile createZipFile(@NotNull File file) throws IOException {
    return isSecureLoader ? new JarFile(file) : new ZipFile(file);
  }

  @Override
  public void close() throws IOException {
    SoftReference<ZipFile> ref = zipFileSoftReference;
    ZipFile zipFile = ref == null ? null : ref.get();
    if (zipFile != null) {
      zipFileSoftReference = null;
      zipFile.close();
    }
  }

  @Override
  public @Nullable Resource getResource(@NotNull String name, @NotNull JarLoader jarLoader) throws IOException {
    try {
      ZipEntry entry = getZipFile().getEntry(name);
      if (entry == null) {
        return null;
      }
      if (isSecureLoader) {
        return new SecureJarResource(jarLoader.url, (JarEntry)entry, (SecureJarLoader)jarLoader);
      }
      else {
        return new ZipFileResource(jarLoader.url, entry, jarLoader);
      }
    }
    finally {
      if (!lockJars) {
        close();
      }
    }
  }

  @Override
  public @Nullable JarMemoryLoader preload(@NotNull Path basePath, @Nullable JarLoader jarLoader) throws IOException {
    ZipFile zipFile = getZipFile();
    Enumeration<? extends ZipEntry> entries = zipFile.entries();
    if (!entries.hasMoreElements()) {
      return null;
    }

    ZipEntry sizeEntry = entries.nextElement();
    if (sizeEntry == null || !sizeEntry.getName().equals(JarMemoryLoader.SIZE_ENTRY)) {
      return null;
    }

    byte[] bytes = Resource.loadBytes(zipFile.getInputStream(sizeEntry), 2);
    int size = ((bytes[1] & 0xFF) << 8) + (bytes[0] & 0xFF);

    Map<Resource.Attribute, String> attributes = jarLoader == null ? null : jarLoader.loadManifestAttributes(this);

    Object[] table = new Object[((size * 4) + 1) & ~1];
    String baseUrl = JarLoader.fileToUri(basePath).toString();
    for (int i = 0; i < size && entries.hasMoreElements(); i++) {
      ZipEntry entry = entries.nextElement();
      String name = entry.getName();
      int index = JarMemoryLoader.probePlain(name, table);
      if (index >= 0) {
        throw new IllegalArgumentException("duplicate name: " + name);
      }
      else {
        byte[] content;
        try (InputStream stream = zipFile.getInputStream(entry)) {
          content = Resource.loadBytes(stream, (int)entry.getSize());
        }

        int dest = -(index + 1);
        table[dest] = name;
        table[dest + 1] = new MemoryResource(baseUrl, content, name, attributes);
      }
    }
    return new JarMemoryLoader(table);
  }

  @Override
  public @Nullable Attributes loadManifestAttributes() throws IOException {
    ZipFile zipFile = getZipFile();
    ZipEntry entry = zipFile.getEntry(JarFile.MANIFEST_NAME);
    if (entry == null) {
      return null;
    }

    try (InputStream stream = zipFile.getInputStream(entry)) {
      return new Manifest(stream).getMainAttributes();
    }
    catch (Exception ignored) {
    }
    return null;
  }

  @Override
  public @NotNull ClasspathCache.IndexRegistrar buildClassPathCacheData() throws IOException {
    try {
      ClasspathCache.LoaderDataBuilder builder = new ClasspathCache.LoaderDataBuilder(true);
      Enumeration<? extends ZipEntry> entries = getZipFile().entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        String name = entry.getName();
        if (name.endsWith(UrlClassLoader.CLASS_EXTENSION)) {
          builder.addClassPackageFromName(name);
          builder.transformClassNameAndAddPossiblyDuplicateNameEntry(name, name.lastIndexOf('/') + 1);
        }
        else {
          builder.addResourcePackageFromName(name);
          if (name.endsWith("/")) {
            builder.addPossiblyDuplicateNameEntry(name, name.lastIndexOf('/', name.length() - 2) + 1, name.length() - 1);
          }
          else {
            builder.addPossiblyDuplicateNameEntry(name, name.lastIndexOf('/') + 1, name.length());
          }
        }
      }
      return builder;
    }
    finally {
      if (!lockJars) {
        close();
      }
    }
  }

  private static class ZipFileResource extends Resource {
    protected final URL baseUrl;
    private URL url;
    protected final ZipEntry entry;
    protected final JarLoader jarLoader;

    private ZipFileResource(@NotNull URL baseUrl, @NotNull ZipEntry entry, @NotNull JarLoader jarLoader) {
      this.baseUrl = baseUrl;
      this.entry = entry;
      this.jarLoader = jarLoader;
    }

    @Override
    public @NotNull URL getURL() {
      URL result = url;
      if (result == null) {
        try {
          result = new URL(baseUrl, entry.getName());
        }
        catch (MalformedURLException e) {
          throw new RuntimeException(e);
        }
        url = result;
      }
      return result;
    }

    @Override
    public @NotNull InputStream getInputStream() throws IOException {
      return new ByteArrayInputStream(getBytes());
    }

    @Override
    public byte @NotNull [] getBytes() throws IOException {
      JdkZipResourceFile file = (JdkZipResourceFile)jarLoader.zipFile;
      try (InputStream stream = file.getZipFile().getInputStream(entry)) {
        return Resource.loadBytes(stream, (int)entry.getSize());
      }
      finally {
        jarLoader.releaseZipFile(file);
      }
    }

    @Override
    public Map<Attribute, String> getAttributes() throws IOException {
      return jarLoader.loadManifestAttributes(jarLoader.zipFile);
    }
  }

  private static final class SecureJarResource extends JdkZipResourceFile.ZipFileResource {
    SecureJarResource(@NotNull URL baseUrl, @NotNull JarEntry entry, @NotNull SecureJarLoader jarLoader) {
      super(baseUrl, entry, jarLoader);
    }

    @Override
    public byte @NotNull [] getBytes() throws IOException {
      JdkZipResourceFile resourceFile = (JdkZipResourceFile)jarLoader.zipFile;
      try (InputStream stream = resourceFile.getZipFile().getInputStream(entry)) {
        return Resource.loadBytes(stream, (int)entry.getSize());
      }
      finally {
        jarLoader.releaseZipFile(resourceFile);
      }
    }

    @Override
    public ProtectionDomain getProtectionDomain() {
      return ((SecureJarLoader)jarLoader).getProtectionDomain((JarEntry)entry, getURL());
    }
  }
}
