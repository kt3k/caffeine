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

import static org.hamcrest.Matchers.is;

import org.hamcrest.Description;
import org.hamcrest.Factory;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.matchers.DescriptionBuilder;

/**
 * A matcher that evaluates if the {@link CacheStats} recorded all of the statistical events.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class HasStats extends TypeSafeDiagnosingMatcher<CacheContext> {
  private enum StatsType {
    HIT, MISS, EVICTION, LOAD_SUCCESS, LOAD_FAILURE, TOTAL_LOAD_TIME
  }

  private final long count;
  private final StatsType type;

  private HasStats(StatsType type, long count) {
    this.count = count;
    this.type = type;
  }

  @Override
  public void describeTo(Description description) {
    description.appendText("stats: ").appendText(type.name());
  }

  @Override
  protected boolean matchesSafely(CacheContext context, Description description) {
    if (!context.isRecordingStats()) {
      return true;
    }

    CacheStats stats = context.stats();
    DescriptionBuilder desc = new DescriptionBuilder(description);
    switch (type) {
      case HIT:
        return desc.expectThat(type.name(), stats.hitCount(), is(count)).matches();
      case MISS:
        return desc.expectThat(type.name(), stats.missCount(), is(count)).matches();
      case EVICTION:
        return desc.expectThat(type.name(), stats.evictionCount(), is(count)).matches();
      case LOAD_SUCCESS:
        return desc.expectThat(type.name(), stats.loadSuccessCount(), is(count)).matches();
      case LOAD_FAILURE:
        return desc.expectThat(type.name(), stats.loadFailureCount(), is(count)).matches();
      default:
        throw new AssertionError("Unknown stats type");
    }
  }

  @Factory public static HasStats hasHitCount(long count) {
    return new HasStats(StatsType.HIT, count);
  }

  @Factory public static HasStats hasMissCount(long count) {
    return new HasStats(StatsType.MISS, count);
  }

  @Factory public static HasStats hasEvictionCount(long count) {
    return new HasStats(StatsType.EVICTION, count);
  }

  @Factory public static HasStats hasLoadSuccessCount(long count) {
    return new HasStats(StatsType.LOAD_SUCCESS, count);
  }

  @Factory public static HasStats hasLoadFailureCount(long count) {
    return new HasStats(StatsType.LOAD_FAILURE, count);
  }
}
