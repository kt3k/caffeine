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
package com.github.benmanes.caffeine.cache;

import org.hamcrest.Description;
import org.hamcrest.Factory;
import org.hamcrest.TypeSafeDiagnosingMatcher;

/**
 * A matcher that evaluates a {@link Cache} to determine if it is in a valid state.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IsValidCache<K, V>
    extends TypeSafeDiagnosingMatcher<Cache<K, V>> {

  @Override
  public void describeTo(Description description) {
    description.appendText("cache");
  }

  @Override
  protected boolean matchesSafely(Cache<K, V> cache, Description description) {
    if (cache instanceof BoundedLocalCache.LocalManualCache<?, ?>) {
      BoundedLocalCache.LocalManualCache<K, V> local =
          (BoundedLocalCache.LocalManualCache<K, V>) cache;
      return IsValidBoundedLocalCache.<K, V>valid().matchesSafely(local.cache, description);
    }
    return true;
  }

  @Factory
  public static <K, V> IsValidCache<K, V> validCache() {
    return new IsValidCache<K, V>();
  }
}
