/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.testing;

import static java.util.Objects.requireNonNull;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

import org.testng.annotations.DataProvider;

import com.github.benmanes.caffeine.cache.Advanced;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.google.common.base.Enums;


/**
 * A data provider that generates caches based on the {@link CacheSpec} configuration.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheProvider {

  private CacheProvider() {}

  /**
   * The provided test parameters are optional and may be specified in any order. Supports injecting
   * {@link LoadingCache}, {@link Cache}, {@link CacheContext}, the {@link ConcurrentMap}
   * {@link Cache#asMap()} view, {@link Advanced.Eviction}, {@link Advanced.Expiration},
   * and {@link FakeTicker}.
   */
  @DataProvider(name = "caches")
  public static Iterator<Object[]> providesCaches(Method testMethod) throws Exception {
    CacheSpec cacheSpec = testMethod.getAnnotation(CacheSpec.class);
    requireNonNull(cacheSpec, "@CacheSpec not found");

    Optional<ReferenceType> keys = Optional.ofNullable(Enums.getIfPresent(ReferenceType.class,
        System.getProperties().getProperty("keys", "").toUpperCase()).orNull());
    Optional<ReferenceType> values = Optional.ofNullable(Enums.getIfPresent(ReferenceType.class,
        System.getProperties().getProperty("values", "").toUpperCase()).orNull());

    boolean isLoadingOnly = hasLoadingCache(testMethod);
    CacheGenerator generator = new CacheGenerator(cacheSpec, isLoadingOnly);
    return asTestCases(testMethod, generator.generate(keys, values));
  }

  /** Converts each scenario into test case parameters. */
  private static Iterator<Object[]> asTestCases(Method testMethod,
      Stream<Entry<CacheContext, Cache<Integer, Integer>>> scenarios) {
    Parameter[] parameters = testMethod.getParameters();
    CacheContext[] stashed = new CacheContext[1];
    return scenarios.map(entry -> {
      // Retain a strong reference to the context throughout the test execution so that the
      // cache entries are not collected due to the test not accepting the context parameter
      stashed[0] = entry.getKey();

      Object[] params = new Object[parameters.length];
      for (int i = 0; i < params.length; i++) {
        Class<?> clazz = parameters[i].getType();
        if (clazz.isAssignableFrom(CacheContext.class)) {
          params[i] = entry.getKey();
          stashed[0] = null;
        } else if (clazz.isAssignableFrom(entry.getValue().getClass())) {
          params[i] = entry.getValue(); // Cache, LoadingCache, or AsyncLoadingCache
        } else if (clazz.isAssignableFrom(Map.class)) {
          params[i] = entry.getValue().asMap();
        } else if (clazz.isAssignableFrom(Advanced.Eviction.class)) {
          params[i] = entry.getValue().advanced().eviction().get();
        } else if (clazz.isAssignableFrom(Advanced.Expiration.class)) {
          if (parameters[i].isAnnotationPresent(ExpireAfterAccess.class)) {
            params[i] = entry.getValue().advanced().expireAfterAccess().get();
          } else if (parameters[i].isAnnotationPresent(ExpireAfterWrite.class)) {
            params[i] = entry.getValue().advanced().expireAfterWrite().get();
          } else {
            throw new AssertionError("Expiration parameter must have a qualifier annotation");
          }
        } else if (clazz.isAssignableFrom(Ticker.class)) {
          params[i] = entry.getKey().ticker();
        } else {
          throw new AssertionError("Unknown parameter type: " + clazz);
        }
      }
      return params;
    }).iterator();
  }

  private static boolean hasLoadingCache(Method testMethod) {
    return Arrays.stream(testMethod.getParameterTypes()).anyMatch(param ->
        LoadingCache.class.isAssignableFrom(param));
  }
}
