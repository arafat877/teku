/*
 * Copyright 2020 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.artemis.ssz.backing;

import tech.pegasys.artemis.ssz.backing.type.CompositeViewType;

/**
 * Represents composite immutable view which has descendant views
 *
 * @param <ChildType> the type of children
 */
public interface CompositeViewRead<ChildType> extends ViewRead {

  /** Returns number of children in this view */
  default int size() {
    return (int) getType().getMaxLength();
  }

  /**
   * Returns the child at index
   *
   * @throws IndexOutOfBoundsException if index >= size()
   */
  ChildType get(int index);

  @Override
  CompositeViewType getType();
}