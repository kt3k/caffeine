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
package com.github.benmanes.caffeine.atomic;

import java.util.concurrent.atomic.AtomicReference;

/**
 * An {@link AtomicReference} with heuristic padding to lessen cache effects of this heavily CAS'ed
 * location. While the padding adds noticeable space, the improved throughput outweighs using extra
 * space.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class PaddedAtomicReference<V> extends AtomicReference<V> {
  private static final long serialVersionUID = 1L;

  // Improve likelihood of isolation on <= 64 byte cache lines
  long q0, q1, q2, q3, q4, q5, q6, q7, q8, q9, qa, qb, qc, qd, qe;

  public PaddedAtomicReference() {}

  public PaddedAtomicReference(V value) {
    super(value);
  }
}
