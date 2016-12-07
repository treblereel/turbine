/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.turbine.binder;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import java.io.IOError;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Sets up an environment for symbols on the classpath. */
public class ClassPathBinder {

  /**
   * Creates an environment containing symbols in the given classpath and bootclasspath, and adds
   * them to the top-level index.
   */
  public static CompoundEnv<ClassSymbol, BytecodeBoundClass> bind(
      Iterable<Path> classpath, Iterable<Path> bootclasspath, TopLevelIndex.Builder tli)
      throws IOException {
    // TODO(cushon): this is going to require an env eventually,
    // e.g. to look up type parameters in enclosing declarations
    Env<ClassSymbol, BytecodeBoundClass> cp = bindClasspath(tli, classpath);
    Env<ClassSymbol, BytecodeBoundClass> bcp = bindClasspath(tli, bootclasspath);
    return CompoundEnv.of(cp).append(bcp);
  }

  private static Env<ClassSymbol, BytecodeBoundClass> bindClasspath(
      TopLevelIndex.Builder tli, Iterable<Path> paths) throws IOException {
    Map<ClassSymbol, BytecodeBoundClass> map = new HashMap<>();
    Env<ClassSymbol, BytecodeBoundClass> benv =
        new Env<ClassSymbol, BytecodeBoundClass>() {
          @Override
          public BytecodeBoundClass get(ClassSymbol sym) {
            return map.get(sym);
          }
        };
    for (Path path : paths) {
      try {
        bindJar(tli, path, map, benv);
      } catch (IOException e) {
        throw new IOException("error reading " + path, e);
      }
    }
    return new SimpleEnv<>(ImmutableMap.copyOf(map));
  }

  private static void bindJar(
      TopLevelIndex.Builder tli,
      Path path,
      Map<ClassSymbol, BytecodeBoundClass> env,
      Env<ClassSymbol, BytecodeBoundClass> benv)
      throws IOException {
    // TODO(cushon): consider creating a nio-friendly jar reading abstraction for testing,
    // that yields something like `Iterable<Pair<String, Supplier<byte[]>>>`
    // TODO(cushon): don't leak jar files
    final JarFile jf = new JarFile(path.toFile());
    Enumeration<JarEntry> entries = jf.entries();
    while (entries.hasMoreElements()) {
      final JarEntry je = entries.nextElement();
      String name = je.getName();
      if (!name.endsWith(".class")) {
        continue;
      }
      ClassSymbol sym = new ClassSymbol(name.substring(0, name.length() - ".class".length()));
      if (!env.containsKey(sym)) {
        env.put(
            sym,
            new BytecodeBoundClass(sym, () -> toByteArrayOrDie(jf, je), benv, path.toString()));
        tli.insert(sym);
      }
    }
  }

  private static byte[] toByteArrayOrDie(JarFile jf, JarEntry je) {
    try {
      return ByteStreams.toByteArray(jf.getInputStream(je));
    } catch (IOException e) {
      throw new IOError(e);
    }
  }
}
