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

import static java.util.Objects.requireNonNull;

import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.ConcurrentModificationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.stats.ConcurrentStatsCounter;
import com.github.benmanes.caffeine.cache.stats.DisabledStatsCounter;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;

/**
 * A builder of {@link AsyncLoadingCache}, {@link LoadingCache}, and {@link Cache} instances
 * having any combination of the following features:
 *
 * <ul>
 *   <li>automatic loading of entries into the cache
 *   <li>least-recently-used eviction when a maximum size is exceeded
 *   <li>time-based expiration of entries, measured since last access or last write
 *   <li>keys automatically wrapped in {@linkplain WeakReference weak} references
 *   <li>values automatically wrapped in {@linkplain WeakReference weak} or
 *       {@linkplain SoftReference soft} references
 *   <li>notification of evicted (or otherwise removed) entries
 *   <li>accumulation of cache access statistics
 * </ul>
 * <p>
 * These features are all optional; caches can be created using all or none of them. By default
 * cache instances created by {@code Caffeine} will not perform any type of eviction.
 * <p>
 * Usage example:
 * <pre>{@code
 *   LoadingCache<Key, Graph> graphs = Caffeine.newBuilder()
 *       .maximumSize(10000)
 *       .expireAfterWrite(10, TimeUnit.MINUTES)
 *       .removalListener(MY_LISTENER)
 *       .build(key -> createExpensiveGraph(key));
 * }</pre>
 * <p>
 * Or equivalently,
 * <pre>{@code
 *   // In real life this would come from a command-line flag or config file
 *   String spec = "maximumSize=10000,expireAfterWrite=10m";
 *
 *   LoadingCache<Key, Graph> graphs = Caffeine.from(spec)
 *       .removalListener(MY_LISTENER)
 *       .build(key -> createExpensiveGraph(key));
 * }</pre>
 * <p>
 * The returned cache is implemented as a hash table with similar performance characteristics to
 * {@link ConcurrentHashMap}. The {@code asMap} view (and its collection views) have <i>weakly
 * consistent iterators</i>. This means that they are safe for concurrent use, but if other threads
 * modify the cache after the iterator is created, it is undefined which of these changes, if any,
 * are reflected in that iterator. These iterators never throw {@link
 * ConcurrentModificationException}.
 * <p>
 * <b>Note:</b> by default, the returned cache uses equality comparisons (the
 * {@link Object#equals equals} method) to determine equality for keys or values. However, if
 * {@link #weakKeys} was specified, the cache uses identity ({@code ==}) comparisons instead for
 * keys. Likewise, if {@link #weakValues} or {@link #softValues} was specified, the cache uses
 * identity comparisons for values.
 * <p>
 * Entries are automatically evicted from the cache when any of
 * {@linkplain #maximumSize(long) maximumSize}, {@linkplain #maximumWeight(long) maximumWeight},
 * {@linkplain #expireAfterWrite expireAfterWrite},
 * {@linkplain #expireAfterAccess expireAfterAccess}, {@linkplain #weakKeys weakKeys},
 * {@linkplain #weakValues weakValues}, or {@linkplain #softValues softValues} are requested.
 * <p>
 * If {@linkplain #maximumSize(long) maximumSize} or {@linkplain #maximumWeight(long) maximumWeight}
 * is requested entries may be evicted on each cache modification.
 * <p>
 * If {@linkplain #expireAfterWrite expireAfterWrite} or
 * {@linkplain #expireAfterAccess expireAfterAccess} is requested entries may be evicted on each
 * cache modification, on occasional cache accesses, or on calls to {@link Cache#cleanUp}. Expired
 * entries may be counted by {@link Cache#estimatedSize()}, but will never be visible to read or
 * write operations.
 * <p>
 * If {@linkplain #weakKeys weakKeys}, {@linkplain #weakValues weakValues}, or
 * {@linkplain #softValues softValues} are requested, it is possible for a key or value present in
 * the cache to be reclaimed by the garbage collector. Entries with reclaimed keys or values may be
 * removed from the cache on each cache modification, on occasional cache accesses, or on calls to
 * {@link Cache#cleanUp}; such entries may be counted in {@link Cache#estimatedSize()}, but will
 * never be visible to read or write operations.
 * <p>
 * Certain cache configurations will result in the accrual of periodic maintenance tasks which
 * will be performed during write operations, or during occasional read operations in the absence of
 * writes. The {@link Cache#cleanUp} method of the returned cache will also perform maintenance, but
 * calling it should not be necessary with a high throughput cache. Only caches built with
 * {@linkplain #maximumSize}, {@linkplain #maximumWeight},
 * {@linkplain #expireAfterWrite expireAfterWrite},
 * {@linkplain #expireAfterAccess expireAfterAccess}, {@linkplain #weakKeys weakKeys},
 * {@linkplain #weakValues weakValues}, or {@linkplain #softValues softValues} perform periodic
 * maintenance.
 * <p>
 * The caches produced by {@code CacheBuilder} are serializable, and the deserialized caches
 * retain all the configuration properties of the original cache. Note that the serialized form does
 * <i>not</i> include cache contents, but only configuration.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 * @param <K> the base key type for all caches created by this builder
 * @param <V> the base value type for all caches created by this builder
 */
public final class Caffeine<K, V> {
  private static final Supplier<StatsCounter> DISABLED_STATS_COUNTER_SUPPLIER =
      () -> DisabledStatsCounter.INSTANCE;
  private static final Supplier<StatsCounter> ENABLED_STATS_COUNTER_SUPPLIER =
      () -> new ConcurrentStatsCounter();
  private static final Ticker DISABLED_TICKER = () -> 0;
  enum Strength { STRONG, WEAK, SOFT }

  static final int UNSET_INT = -1;

  private static final int DEFAULT_INITIAL_CAPACITY = 16;
  private static final int DEFAULT_EXPIRATION_NANOS = 0;
  private static final int DEFAULT_REFRESH_NANOS = 0;

  private long maximumSize = UNSET_INT;
  private long maximumWeight = UNSET_INT;
  private int initialCapacity = UNSET_INT;

  private long refreshNanos = UNSET_INT;
  private long expireAfterWriteNanos = UNSET_INT;
  private long expireAfterAccessNanos = UNSET_INT;

  private RemovalListener<? super K, ? super V> removalListener;
  private Weigher<? super K, ? super V> weigher;
  private Supplier<StatsCounter> statsCounterSupplier;
  private Executor executor;
  private Ticker ticker;

  private Strength keyStrength;
  private Strength valueStrength;

  private Caffeine() {}

  /** Ensures that the argument expression is true. */
  static void requireArgument(boolean expression, String template, Object... args) {
    if (!expression) {
      throw new IllegalArgumentException(String.format(template, args));
    }
  }

  /** Ensures that the argument expression is true. */
  static void requireArgument(boolean expression) {
    if (!expression) {
      throw new IllegalArgumentException();
    }
  }

  /** Ensures that the state expression is true. */
  static void requireState(boolean expression) {
    if (!expression) {
      throw new IllegalStateException();
    }
  }

  /** Ensures that the state expression is true. */
  static void requireState(boolean expression, String template, Object... args) {
    if (!expression) {
      throw new IllegalStateException(String.format(template, args));
    }
  }

  /**
   * Constructs a new {@code Caffeine} instance with default settings, including strong keys, strong
   * values, and no automatic eviction of any kind.
   */
  @Nonnull
  public static Caffeine<Object, Object> newBuilder() {
    return new Caffeine<Object, Object>();
  }

  /**
   * Sets the minimum total size for the internal hash tables. Providing a large enough estimate at
   * construction time avoids the need for expensive resizing operations later, but setting this
   * value unnecessarily high wastes memory.
   *
   * @throws IllegalArgumentException if {@code initialCapacity} is negative
   * @throws IllegalStateException if an initial capacity was already set
   */
  @Nonnull
  public Caffeine<K, V> initialCapacity(@Nonnegative int initialCapacity) {
    requireState(this.initialCapacity == UNSET_INT,
        "initial capacity was already set to %s", this.initialCapacity);
    requireArgument(initialCapacity >= 0);
    this.initialCapacity = initialCapacity;
    return this;
  }

  int getInitialCapacity() {
    return (initialCapacity == UNSET_INT) ? DEFAULT_INITIAL_CAPACITY : initialCapacity;
  }

  /**
   * Specifies the executor to use when running asynchronous tasks. The executor is delegated to
   * when sending removal notifications and asynchronous computations requested through the
   * {@link AsyncLoadingCache} and {@link LoadingCache#refresh}. By default,
   * {@link ForkJoinPool#commonPool()} is used.
   * <p>
   * The primary intent of this method is to facilitate testing of caches which have been
   * configured with {@link #removalListener} or utilize asynchronous computations. A test may
   * instead prefer to configure the cache that executes directly on the same thread.
   * <p>
   * Beware that configuring a cache with an executor that throws {@link RejectedExecutionException}
   * may experience non-deterministic behavior.
   *
   * @param executor the executor to use for asynchronous execution
   * @throws NullPointerException if the specified executor is null
   */
  @Nonnull
  public Caffeine<K, V> executor(@Nonnull Executor executor) {
    this.executor = requireNonNull(executor);
    return this;
  }

  @Nonnull
  Executor getExecutor() {
    return (executor == null) ? ForkJoinPool.commonPool() : executor;
  }

  /**
   * Specifies the maximum number of entries the cache may contain. Note that the cache <b>may evict
   * an entry before this limit is exceeded</b>. As the cache size grows close to the maximum, the
   * cache evicts entries that are less likely to be used again. For example, the cache may evict an
   * entry because it hasn't been used recently or very often.
   * <p>
   * When {@code size} is zero, elements will be evicted immediately after being loaded into the
   * cache. This can be useful in testing, or to disable caching temporarily without a code change.
   * <p>
   * This feature cannot be used in conjunction with {@link #maximumWeight}.
   *
   * @param maximumSize the maximum size of the cache
   * @throws IllegalArgumentException if {@code size} is negative
   * @throws IllegalStateException if a maximum size or weight was already set
   */
  @Nonnull
  public Caffeine<K, V> maximumSize(@Nonnegative long maximumSize) {
    requireState(this.maximumSize == UNSET_INT,
        "maximum size was already set to %s", this.maximumSize);
    requireState(this.maximumWeight == UNSET_INT,
        "maximum weight was already set to %s", this.maximumWeight);
    requireState(this.weigher == null, "maximum size can not be combined with weigher");
    requireArgument(maximumSize >= 0, "maximum size must not be negative");
    this.maximumSize = maximumSize;
    return this;
  }

  /**
   * Specifies the maximum weight of entries the cache may contain. Weight is determined using the
   * {@link Weigher} specified with {@link #weigher}, and use of this method requires a
   * corresponding call to {@link #weigher} prior to calling {@link #build}.
   * <p>
   * Note that the cache <b>may evict an entry before this limit is exceeded</b>. As the cache size
   * grows close to the maximum, the cache evicts entries that are less likely to be used again. For
   * example, the cache may evict an entry because it hasn't been used recently or very often.
   * <p>
   * When {@code weight} is zero, elements will be evicted immediately after being loaded into
   * cache. This can be useful in testing, or to disable caching temporarily without a code change.
   * <p>
   * Note that weight is only used to determine whether the cache is over capacity; it has no effect
   * on selecting which entry should be evicted next.
   * <p>
   * This feature cannot be used in conjunction with {@link #maximumSize}.
   *
   * @param maximumWeight the maximum total weight of entries the cache may contain
   * @throws IllegalArgumentException if {@code weight} is negative
   * @throws IllegalStateException if a maximum weight or size was already set
   */
  @Nonnull
  public Caffeine<K, V> maximumWeight(@Nonnegative long maximumWeight) {
    requireState(this.maximumWeight == UNSET_INT,
        "maximum weight was already set to %s", this.maximumWeight);
    requireState(this.maximumSize == UNSET_INT,
        "maximum size was already set to %s", this.maximumSize);
    this.maximumWeight = maximumWeight;
    requireArgument(maximumWeight >= 0, "maximum weight must not be negative");
    return this;
  }

  /**
   * Specifies the weigher to use in determining the weight of entries. Entry weight is taken into
   * consideration by {@link #maximumWeight(long)} when determining which entries to evict, and use
   * of this method requires a corresponding call to {@link #maximumWeight(long)} prior to calling
   * {@link #build}. Weights are measured and recorded when entries are inserted into the cache, and
   * are thus effectively static during the lifetime of a cache entry.
   * <p>
   * When the weight of an entry is zero it will not be considered for size-based eviction (though
   * it still may be evicted by other means).
   * <p>
   * <b>Important note:</b> Instead of returning <em>this</em> as a {@code Caffeine} instance, this
   * method returns {@code Caffeine<K1, V1>}. From this point on, either the original reference or
   * the returned reference may be used to complete configuration and build the cache, but only the
   * "generic" one is type-safe. That is, it will properly prevent you from building caches whose
   * key or value types are incompatible with the types accepted by the weigher already provided;
   * the {@code Caffeine} type cannot do this. For best results, simply use the standard
   * method-chaining idiom, as illustrated in the documentation at top, configuring a
   * {@code Caffeine} and building your {@link Cache} all in a single statement.
   * <p>
   * <b>Warning:</b> if you ignore the above advice, and use this {@code Caffeine} to build a cache
   * whose key or value type is incompatible with the weigher, you will likely experience a
   * {@link ClassCastException} at some <i>undefined</i> point in the future.
   *
   * @param weigher the weigher to use in calculating the weight of cache entries
   * @throws IllegalArgumentException if {@code size} is negative
   * @throws IllegalStateException if a maximum size was already set
   */
  @Nonnull
  public <K1 extends K, V1 extends V> Caffeine<K1, V1> weigher(
      @Nonnull Weigher<? super K1, ? super V1> weigher) {
    requireNonNull(weigher);
    requireState(this.weigher == null);
    requireState(this.maximumSize == UNSET_INT,
        "weigher can not be combined with maximum size", this.maximumSize);
    @SuppressWarnings("unchecked")
    Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;
    self.weigher = weigher;
    return self;
  }

  @Nonnegative
  long getMaximumWeight() {
    if ((expireAfterWriteNanos == 0) || (expireAfterAccessNanos == 0)) {
      return 0;
    }
    return (weigher == null) ? maximumSize : maximumWeight;
  }

  @Nonnull @SuppressWarnings("unchecked")
  <K1 extends K, V1 extends V> Weigher<K1, V1> getWeigher() {
    return (Weigher<K1, V1>) ((weigher == null) ? Weigher.singleton() : weigher);
  }

  /**
   * Specifies that each key (not value) stored in the cache should be wrapped in a
   * {@link WeakReference} (by default, strong references are used).
   * <p>
   * <b>Warning:</b> when this method is used, the resulting cache will use identity ({@code ==})
   * comparison to determine equality of keys.
   * <p>
   * Entries with keys that have been garbage collected may be counted in
   * {@link Cache#estimatedSize()}, but will never be visible to read or write operations; such
   * entries are cleaned up as part of the routine maintenance described in the class javadoc.
   *
   * @throws IllegalStateException if the key strength was already set
   */
  @Nonnull
  public Caffeine<K, V> weakKeys() {
    requireState(keyStrength == null, "Key strength was already set to %s", keyStrength);
    keyStrength = Strength.WEAK;
    return this;
  }

  Strength getKeyStrength() {
    return (keyStrength == null) ? Strength.STRONG : keyStrength;
  }

  /**
   * Specifies that each value (not key) stored in the cache should be wrapped in a
   * {@link WeakReference} (by default, strong references are used).
   * <p>
   * Weak values will be garbage collected once they are weakly reachable. This makes them a poor
   * candidate for caching; consider {@link #softValues} instead.
   * <p>
   * <b>Note:</b> when this method is used, the resulting cache will use identity ({@code ==})
   * comparison to determine equality of values.
   * <p>
   * Entries with values that have been garbage collected may be counted in
   * {@link Cache#estimatedSize()}, but will never be visible to read or write operations; such
   * entries are cleaned up as part of the routine maintenance described in the class javadoc.
   * <p>
   * This feature cannot be used in conjunction with {@link #buildAsync}.
   *
   * @throws IllegalStateException if the value strength was already set
   */
  @Nonnull
  public Caffeine<K, V> weakValues() {
    requireState(valueStrength == null, "Value strength was already set to %s", valueStrength);
    valueStrength = Strength.WEAK;
    return this;
  }

  Strength getValueStrength() {
    return (valueStrength == null) ? Strength.STRONG : valueStrength;
  }

  /**
   * Specifies that each value (not key) stored in the cache should be wrapped in a
   * {@link SoftReference} (by default, strong references are used). Softly-referenced objects will
   * be garbage-collected in a <i>globally</i> least-recently-used manner, in response to memory
   * demand.
   * <p>
   * <b>Warning:</b> in most circumstances it is better to set a per-cache
   * {@linkplain #maximumSize(long) maximum size} instead of using soft references. You should only
   * use this method if you are well familiar with the practical consequences of soft references.
   * <p>
   * <b>Note:</b> when this method is used, the resulting cache will use identity ({@code ==})
   * comparison to determine equality of values.
   * <p>
   * Entries with values that have been garbage collected may be counted in
   * {@link Cache#estimatedSize()}, but will never be visible to read or write operations; such
   * entries are cleaned up as part of the routine maintenance described in the class javadoc.
   * <p>
   * This feature cannot be used in conjunction with {@link #buildAsync}.
   *
   * @throws IllegalStateException if the value strength was already set
   */
  @Nonnull
  public Caffeine<K, V> softValues() {
    requireState(valueStrength == null, "Value strength was already set to %s", valueStrength);
    valueStrength = Strength.SOFT;
    return this;
  }

  /**
   * Specifies that each entry should be automatically removed from the cache once a fixed duration
   * has elapsed after the entry's creation, or the most recent replacement of its value.
   * <p>
   * When {@code duration} is zero, this method hands off to {@link #maximumSize(long) maximumSize}
   * {@code (0)}, ignoring any otherwise-specified maximum size or weight. This can be useful in
   * testing, or to disable caching temporarily without a code change.
   * <p>
   * Expired entries may be counted in {@link Cache#estimatedSize()}, but will never be visible to
   * read or write operations. Expired entries are cleaned up as part of the routine maintenance
   * described in the class javadoc.
   *
   * @param duration the length of time after an entry is created that it should be automatically
   *        removed
   * @param unit the unit that {@code duration} is expressed in
   * @throws IllegalArgumentException if {@code duration} is negative
   * @throws IllegalStateException if the time to live or time to idle was already set
   */
  @Nonnull
  public Caffeine<K, V> expireAfterWrite(@Nonnegative long duration, @Nonnull TimeUnit unit) {
    requireState(expireAfterWriteNanos == UNSET_INT,
        "expireAfterWrite was already set to %s ns", expireAfterWriteNanos);
    requireArgument(duration >= 0, "duration cannot be negative: %s %s", duration, unit);
    this.expireAfterWriteNanos = unit.toNanos(duration);
    return this;
  }

  @Nonnegative
  long getExpireAfterWriteNanos() {
    return (expireAfterWriteNanos == UNSET_INT) ? DEFAULT_EXPIRATION_NANOS : expireAfterWriteNanos;
  }

  /**
   * Specifies that each entry should be automatically removed from the cache once a fixed duration
   * has elapsed after the entry's creation, the most recent replacement of its value, or its last
   * access. Access time is reset by all cache read and write operations (including
   * {@code Cache.asMap().get(Object)} and {@code Cache.asMap().put(K, V)}), but not by operations
   * on the collection-views of {@link Cache#asMap}.
   * <p>
   * When {@code duration} is zero, this method hands off to {@link #maximumSize(long) maximumSize}
   * {@code (0)}, ignoring any otherwise-specified maximum size or weight. This can be useful in
   * testing, or to disable caching temporarily without a code change.
   * <p>
   * Expired entries may be counted in {@link Cache#estimatedSize()}, but will never be visible to
   * read or write operations. Expired entries are cleaned up as part of the routine maintenance
   * described in the class javadoc.
   *
   * @param duration the length of time after an entry is last accessed that it should be
   *        automatically removed
   * @param unit the unit that {@code duration} is expressed in
   * @throws IllegalArgumentException if {@code duration} is negative
   * @throws IllegalStateException if the time to idle or time to live was already set
   */
  @Nonnull
  public Caffeine<K, V> expireAfterAccess(@Nonnegative long duration, @Nonnull TimeUnit unit) {
    requireState(expireAfterAccessNanos == UNSET_INT,
        "expireAfterAccess was already set to %s ns", expireAfterAccessNanos);
    requireArgument(duration >= 0, "duration cannot be negative: %s %s", duration, unit);
    this.expireAfterAccessNanos = unit.toNanos(duration);
    return this;
  }

  @Nonnegative
  long getExpireAfterAccessNanos() {
    return (expireAfterAccessNanos == UNSET_INT)
        ? DEFAULT_EXPIRATION_NANOS
        : expireAfterAccessNanos;
  }

  boolean isExpirable() {
    return (expireAfterAccessNanos != UNSET_INT) && (expireAfterWriteNanos != UNSET_INT);
  }

  /**
   * Specifies that active entries are eligible for automatic refresh once a fixed duration has
   * elapsed after the entry's creation, or the most recent replacement of its value. The semantics
   * of refreshes are specified in {@link LoadingCache#refresh}, and are performed by calling
   * {@link CacheLoader#reload}.
   * <p>
   * As the default implementation of {@link CacheLoader#reload} is synchronous, it is recommended
   * that users of this method override {@link CacheLoader#reload} with an asynchronous
   * implementation; otherwise refreshes will be performed during unrelated cache read and write
   * operations.
   * <p>
   * Currently automatic refreshes are performed when the first stale request for an entry occurs.
   * The request triggering refresh will make a blocking call to {@link CacheLoader#reload} and
   * immediately return the new value if the returned future is complete, and the old value
   * otherwise.
   * <p>
   * <b>Note:</b> <i>all exceptions thrown during refresh will be logged and then swallowed</i>.
   *
   * @param duration the length of time after an entry is created that it should be considered
   *        stale, and thus eligible for refresh
   * @param unit the unit that {@code duration} is expressed in
   * @throws IllegalArgumentException if {@code duration} is negative
   * @throws IllegalStateException if the refresh interval was already set
   */
  @Nonnull
  public Caffeine<K, V> refreshAfterWrite(@Nonnegative long duration, @Nonnull TimeUnit unit) {
    requireNonNull(unit);
    requireState(refreshNanos == UNSET_INT, "refresh was already set to %s ns", refreshNanos);
    requireArgument(duration > 0, "duration must be positive: %s %s", duration, unit);
    this.refreshNanos = unit.toNanos(duration);
    return this;
  }

  @Nonnegative
  long getRefreshNanos() {
    return (refreshNanos == UNSET_INT) ? DEFAULT_REFRESH_NANOS : refreshNanos;
  }

  /**
   * Specifies a nanosecond-precision time source for use in determining when entries should be
   * expired. By default, {@link System#nanoTime} is used.
   * <p>
   * The primary intent of this method is to facilitate testing of caches which have been configured
   * with {@link #expireAfterWrite} or {@link #expireAfterAccess}.
   *
   * @throws IllegalStateException if a ticker was already set
   * @throws NullPointerException if the specified ticker is null
   */
  @Nonnull
  public Caffeine<K, V> ticker(@Nonnull Ticker ticker) {
    requireState(this.ticker == null);
    this.ticker = requireNonNull(ticker);
    return this;
  }

  @Nonnull
  Ticker getTicker() {
    return (ticker == null)
        ? (isExpirable() || isRecordingStats() ? Ticker.systemTicker() : DISABLED_TICKER)
        : ticker;
  }

  /**
   * Specifies a listener instance that caches should notify each time an entry is removed for any
   * {@linkplain RemovalCause reason}. Each cache created by this builder will invoke this listener
   * as part of the routine maintenance described in the class documentation above.
   * <p>
   * <b>Warning:</b> after invoking this method, do not continue to use <i>this</i> cache builder
   * reference; instead use the reference this method <i>returns</i>. At runtime, these point to the
   * same instance, but only the returned reference has the correct generic type information so as
   * to ensure type safety. For best results, use the standard method-chaining idiom illustrated in
   * the class documentation above, configuring a builder and building your cache in a single
   * statement. Failure to heed this advice can result in a {@link ClassCastException} being thrown
   * by a cache operation at some <i>undefined</i> point in the future.
   * <p>
   * <b>Warning:</b> any exception thrown by {@code listener} will <i>not</i> be propagated to the
   * {@code Cache} user, only logged via a {@link Logger}.
   *
   * @return the cache builder reference that should be used instead of {@code this} for any
   *         remaining configuration and cache building
   * @throws IllegalStateException if a removal listener was already set
   * @throws NullPointerException if the specified removal listener is null
   */
  @Nonnull
  public <K1 extends K, V1 extends V> Caffeine<K1, V1> removalListener(
      @Nonnull RemovalListener<? super K1, ? super V1> removalListener) {
    requireState(this.removalListener == null);

    @SuppressWarnings("unchecked")
    Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;
    self.removalListener = requireNonNull(removalListener);
    return self;
  }

  @SuppressWarnings("unchecked")
  @Nonnull <K1 extends K, V1 extends V> RemovalListener<K1, V1> getRemovalListener() {
    return (RemovalListener<K1, V1>) removalListener;
  }

  /**
   * Enable the accumulation of {@link CacheStats} during the operation of the cache. Without this
   * {@link Cache#stats} will return zero for all statistics. Note that recording stats requires
   * bookkeeping to be performed with each operation, and thus imposes a performance penalty on
   * cache operation.
   */
  @Nonnull
  public Caffeine<K, V> recordStats() {
    statsCounterSupplier = ENABLED_STATS_COUNTER_SUPPLIER;
    return this;
  }

  boolean isRecordingStats() {
    return (statsCounterSupplier == ENABLED_STATS_COUNTER_SUPPLIER);
  }

  @Nonnull
  Supplier<? extends StatsCounter> getStatsCounterSupplier() {
    return (statsCounterSupplier == null)
        ? DISABLED_STATS_COUNTER_SUPPLIER
        : ENABLED_STATS_COUNTER_SUPPLIER;
  }

  boolean isBounded() {
    return (maximumSize != UNSET_INT)
        || (maximumWeight != UNSET_INT)
        || (expireAfterAccessNanos != UNSET_INT)
        || (expireAfterWriteNanos != UNSET_INT)
        || (keyStrength != null)
        || (valueStrength != null);
  }

  /**
   * Builds a cache which does not automatically load values when keys are requested.
   * <p>
   * Consider {@link #build(CacheLoader)} instead, if it is feasible to implement a
   * {@code CacheLoader}.
   * <p>
   * This method does not alter the state of this {@code Caffeine} instance, so it can be invoked
   * again to create multiple independent caches.
   *
   * @return a cache having the requested features
   */
  @Nonnull
  public <K1 extends K, V1 extends V> Cache<K1, V1> build() {
    checkWeightWithWeigher();
    checkNonLoadingCache();

    @SuppressWarnings("unchecked")
    Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;
    return isBounded()
        ? new BoundedLocalCache.LocalManualCache<K1, V1>(self)
        : new UnboundedLocalCache.LocalManualCache<K1, V1>(self);
  }

  /**
   * Builds a cache, which either returns an already-loaded value for a given key or atomically
   * computes or retrieves it using the supplied {@code CacheLoader}. If another thread is currently
   * loading the value for this key, simply waits for that thread to finish and returns its loaded
   * value. Note that multiple threads can concurrently load values for distinct keys.
   * <p>
   * This method does not alter the state of this {@code Caffeine} instance, so it can be invoked
   * again to create multiple independent caches.
   *
   * @param loader the cache loader used to obtain new values
   * @return a cache having the requested features
   * @throws NullPointerException if the specified cache loader is null
   */
  @Nonnull
  public <K1 extends K, V1 extends V> LoadingCache<K1, V1> build(
      @Nonnull CacheLoader<? super K1, V1> loader) {
    checkWeightWithWeigher();

    @SuppressWarnings("unchecked")
    Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;
    return isBounded()
        ? new BoundedLocalCache.LocalLoadingCache<K1, V1>(self, loader)
        : new UnboundedLocalCache.LocalLoadingCache<K1, V1>(self, loader);
  }

  /**
   * Builds a cache, which either returns an already-loaded value for a given key or atomically
   * computes or retrieves it using the supplied {@code CacheLoader}. If another thread is currently
   * loading the value for this key, simply waits for that thread to finish and returns its loaded
   * value. Note that multiple threads can concurrently load values for distinct keys.
   * <p>
   * This method does not alter the state of this {@code Caffeine} instance, so it can be invoked
   * again to create multiple independent caches.
   *
   * @param loader the cache loader used to obtain new values
   * @return a cache having the requested features
   * @throws IllegalStateException if the value strength is weak or soft
   * @throws NullPointerException if the specified cache loader is null
   */
  @Nonnull
  public <K1 extends K, V1 extends V> AsyncLoadingCache<K1, V1> buildAsync(
      @Nonnull CacheLoader<? super K1, V1> loader) {
    requireState(valueStrength == null);
    requireNonNull(loader);
    throw new UnsupportedOperationException();
  }

  private void checkNonLoadingCache() {
    requireState(refreshNanos == UNSET_INT, "refreshAfterWrite requires a LoadingCache");
  }

  private void checkWeightWithWeigher() {
    if (weigher == null) {
      requireState(maximumWeight == UNSET_INT, "maximumWeight requires weigher");
    } else {
      requireState(maximumWeight != UNSET_INT, "weigher requires maximumWeight");
    }
  }

  /**
   * Returns a string representation for this Caffeine instance. The exact form of the returned
   * string is not specified.
   */
  @Override
  public String toString() {
    StringBuilder s = new StringBuilder(64);
    s.append(getClass().getSimpleName()).append('{');
    int baseLength = s.length();
    if (initialCapacity != UNSET_INT) {
      s.append("initialCapacity=").append(initialCapacity).append(',');
    }
    if (maximumSize != UNSET_INT) {
      s.append("maximumSize").append(maximumSize).append(',');
    }
    if (maximumWeight != UNSET_INT) {
      s.append("maximumWeight").append(maximumWeight).append(',');
    }
    if (expireAfterWriteNanos != UNSET_INT) {
      s.append("expireAfterWrite").append(expireAfterWriteNanos).append("ns,");
    }
    if (expireAfterAccessNanos != UNSET_INT) {
      s.append("expireAfterAccess").append(expireAfterAccessNanos).append("ns,");
    }
    if (keyStrength != null) {
      s.append("keyStrength").append(keyStrength.toString().toLowerCase()).append(',');
    }
    if (valueStrength != null) {
      s.append("valueStrength").append(valueStrength.toString().toLowerCase()).append(',');
    }
    if (removalListener != null) {
      s.append("removalListener").append(',');
    }
    if (s.length() > baseLength) {
      s.deleteCharAt(s.length() - 1);
    }
    return s.append('}').toString();
  }
}
