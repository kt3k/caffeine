/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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

import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.MaximumSize;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;

/**
 * A factory that constructs a {@link Cache} from the {@link CacheContext}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheFromContext {

  private CacheFromContext() {}

  @SuppressWarnings("unchecked")
  public static <K, V> LoadingCache<K, V> newLoadingCache(CacheContext context) {
    requireNonNull(context.loader);
    return (LoadingCache<K, V>) newCache(context);
  }

  /**
   * Creates a new cache based on the context's configuration and update's the context's reference.
   */
  public static <K, V> Cache<K, V> newCache(CacheContext context) {
    switch (context.implementation()) {
      case Caffeine:
        return newCaffeineCache(context);
      case Guava:
        return GuavaLocalCache.newGuavaCache(context);
      default:
        throw new IllegalStateException();
    }
  }

  private static <K, V> Cache<K, V> newCaffeineCache(CacheContext context) {
    Caffeine<Object, Object> builder = Caffeine.newBuilder();
    if (context.initialCapacity != InitialCapacity.DEFAULT) {
      builder.initialCapacity(context.initialCapacity.size());
    }
    if (context.isRecordingStats()) {
      builder.recordStats();
    }
    if (context.maximumSize != MaximumSize.DISABLED) {
      if (context.weigher == CacheWeigher.DEFAULT) {
        builder.maximumSize(context.maximumSize.max());
      } else {
        builder.weigher(context.weigher);
        builder.maximumWeight(context.maximumWeight());
      }
    }
    if (context.afterAccess != Expire.DISABLED) {
      builder.expireAfterAccess(context.afterAccess.timeNanos(), TimeUnit.NANOSECONDS);
    }
    if (context.afterWrite != Expire.DISABLED) {
      builder.expireAfterWrite(context.afterWrite.timeNanos(), TimeUnit.NANOSECONDS);
    }
    if (context.expires()) {
      builder.ticker(context.ticker());
    }
    if (context.keyStrength == ReferenceType.WEAK) {
      builder.weakKeys();
    } else if (context.keyStrength == ReferenceType.SOFT) {
      throw new IllegalStateException();
    }
    if (context.valueStrength == ReferenceType.WEAK) {
      builder.weakValues();
    } else if (context.valueStrength == ReferenceType.SOFT) {
      builder.softValues();
    }
    if (context.executor != null) {
      builder.executor(context.executor);
    }
    if (context.removalListenerType != Listener.DEFAULT) {
      builder.removalListener(context.removalListener);
    }
    if (context.loader == null) {
      context.cache = builder.build();
    } else {
      context.cache = builder.build(context.loader);
    }
    @SuppressWarnings("unchecked")
    Cache<K, V> castedCache = (Cache<K, V>) context.cache;
    return castedCache;
  }
}
