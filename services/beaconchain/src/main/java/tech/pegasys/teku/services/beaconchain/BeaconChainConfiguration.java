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

package tech.pegasys.teku.services.beaconchain;

import tech.pegasys.teku.validator.api.ValidatorConfig;
import tech.pegasys.teku.weaksubjectivity.config.WeakSubjectivityConfig;

public class BeaconChainConfiguration {
  private final WeakSubjectivityConfig weakSubjectivityConfig;
  private final ValidatorConfig validatorConfig;

  public BeaconChainConfiguration(
      final WeakSubjectivityConfig weakSubjectivityConfig, final ValidatorConfig validatorConfig) {
    this.weakSubjectivityConfig = weakSubjectivityConfig;
    this.validatorConfig = validatorConfig;
  }

  public WeakSubjectivityConfig weakSubjectivity() {
    return weakSubjectivityConfig;
  }

  public ValidatorConfig validatorConfig() {
    return validatorConfig;
  }
}
