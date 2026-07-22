/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.kevinwen.jsonstream.QueryCompilationException;

/** Compiles one generated source unit into an isolated in-memory classloader. */
public final class InMemoryJavaCompiler {
  private final ClassLoader parentClassLoader;

  public InMemoryJavaCompiler(ClassLoader parentClassLoader) {
    this.parentClassLoader = parentClassLoader;
  }

  public Class<?> compile(String className, String source) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new QueryCompilationException(
          "A full JDK is required; no system Java compiler is available", source);
    }
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager standard =
            compiler.getStandardFileManager(diagnostics, Locale.ROOT, null);
        MemoryFileManager files = new MemoryFileManager(standard, parentClassLoader)) {
      List<String> options = new ArrayList<>();
      options.add("--release");
      options.add(Integer.toString(Runtime.version().feature()));
      options.add("-classpath");
      options.add(System.getProperty("java.class.path"));
      Boolean success =
          compiler
              .getTask(
                  null,
                  files,
                  diagnostics,
                  options,
                  null,
                  List.of(new SourceFile(className, source)))
              .call();
      if (!Boolean.TRUE.equals(success)) {
        throw new QueryCompilationException(formatDiagnostics(diagnostics), source);
      }
      return files.loadClass(className);
    } catch (IOException | ClassNotFoundException e) {
      throw new QueryCompilationException("Could not load generated class " + className, source, e);
    }
  }

  private static String formatDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
    StringBuilder message = new StringBuilder("Generated Java compilation failed");
    for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
      message
          .append(System.lineSeparator())
          .append("line ")
          .append(diagnostic.getLineNumber())
          .append(": ")
          .append(diagnostic.getMessage(Locale.ROOT));
    }
    return message.toString();
  }

  private static final class SourceFile extends SimpleJavaFileObject {
    private final String source;

    private SourceFile(String className, String source) {
      super(
          URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension),
          Kind.SOURCE);
      this.source = source;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return source;
    }
  }

  private static final class ByteCodeFile extends SimpleJavaFileObject {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    private ByteCodeFile(String className, Kind kind) {
      super(URI.create("bytes:///" + className.replace('.', '/') + kind.extension), kind);
    }

    @Override
    public OutputStream openOutputStream() {
      return bytes;
    }

    private byte[] bytes() {
      return bytes.toByteArray();
    }
  }

  private static final class MemoryFileManager
      extends ForwardingJavaFileManager<StandardJavaFileManager> {
    private final Map<String, ByteCodeFile> byteCode = new ConcurrentHashMap<>();
    private final MemoryClassLoader classLoader;

    private MemoryFileManager(StandardJavaFileManager fileManager, ClassLoader parentClassLoader) {
      super(fileManager);
      classLoader = new MemoryClassLoader(parentClassLoader, byteCode);
    }

    @Override
    public JavaFileObject getJavaFileForOutput(
        JavaFileManager.Location location,
        String className,
        JavaFileObject.Kind kind,
        FileObject sibling) {
      ByteCodeFile output = new ByteCodeFile(className, kind);
      byteCode.put(className, output);
      return output;
    }

    @Override
    public ClassLoader getClassLoader(JavaFileManager.Location location) {
      return classLoader;
    }

    private Class<?> loadClass(String className) throws ClassNotFoundException {
      return classLoader.loadClass(className);
    }
  }

  private static final class MemoryClassLoader extends ClassLoader {
    private final Map<String, ByteCodeFile> byteCode;

    private MemoryClassLoader(ClassLoader parent, Map<String, ByteCodeFile> byteCode) {
      super(parent);
      this.byteCode = byteCode;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
      ByteCodeFile file = byteCode.get(name);
      if (file == null) {
        throw new ClassNotFoundException(name);
      }
      byte[] bytes = file.bytes();
      return defineClass(name, bytes, 0, bytes.length);
    }
  }
}
