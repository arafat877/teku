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

package tech.pegasys.teku.api;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static tech.pegasys.teku.api.DataProviderFailures.chainUnavailable;
import static tech.pegasys.teku.datastructures.util.BeaconStateUtil.compute_epoch_at_slot;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.api.response.GetBlockResponse;
import tech.pegasys.teku.api.response.GetForkResponse;
import tech.pegasys.teku.api.response.v1.beacon.BlockHeader;
import tech.pegasys.teku.api.response.v1.beacon.EpochCommitteeResponse;
import tech.pegasys.teku.api.response.v1.beacon.FinalityCheckpointsResponse;
import tech.pegasys.teku.api.response.v1.beacon.ValidatorBalanceResponse;
import tech.pegasys.teku.api.response.v1.beacon.ValidatorResponse;
import tech.pegasys.teku.api.schema.BLSPubKey;
import tech.pegasys.teku.api.schema.BeaconChainHead;
import tech.pegasys.teku.api.schema.BeaconHead;
import tech.pegasys.teku.api.schema.BeaconState;
import tech.pegasys.teku.api.schema.BeaconValidators;
import tech.pegasys.teku.api.schema.Committee;
import tech.pegasys.teku.api.schema.Fork;
import tech.pegasys.teku.api.schema.PublicKeyException;
import tech.pegasys.teku.api.schema.SignedBeaconBlock;
import tech.pegasys.teku.api.schema.ValidatorsRequest;
import tech.pegasys.teku.datastructures.state.Checkpoint;
import tech.pegasys.teku.datastructures.state.CommitteeAssignment;
import tech.pegasys.teku.datastructures.state.ForkInfo;
import tech.pegasys.teku.datastructures.util.BeaconStateUtil;
import tech.pegasys.teku.datastructures.util.CommitteeUtil;
import tech.pegasys.teku.datastructures.util.Merkleizable;
import tech.pegasys.teku.datastructures.util.ValidatorsUtil;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.storage.client.ChainDataUnavailableException;
import tech.pegasys.teku.storage.client.CombinedChainDataClient;
import tech.pegasys.teku.storage.client.RecentChainData;

public class ChainDataProvider {
  private final CombinedChainDataClient combinedChainDataClient;

  private final RecentChainData recentChainData;

  public ChainDataProvider(
      final RecentChainData recentChainData,
      final CombinedChainDataClient combinedChainDataClient) {
    this.combinedChainDataClient = combinedChainDataClient;
    this.recentChainData = recentChainData;
  }

  public UInt64 getGenesisTime() {
    if (!isStoreAvailable()) {
      throw new ChainDataUnavailableException();
    }
    return recentChainData.getGenesisTime();
  }

  public Optional<BeaconHead> getBeaconHead() {
    if (!isStoreAvailable()) {
      throw new ChainDataUnavailableException();
    }

    return recentChainData.getHeadBlockAndState().map(BeaconHead::new);
  }

  public GetForkResponse getForkInfo() {
    if (!isStoreAvailable()) {
      throw new ChainDataUnavailableException();
    }

    tech.pegasys.teku.datastructures.state.BeaconState bestBlockRootState =
        recentChainData.getBestState().orElseThrow(ChainDataUnavailableException::new);

    final ForkInfo forkInfo = bestBlockRootState.getForkInfo();
    return new GetForkResponse(forkInfo);
  }

  public SafeFuture<Optional<List<Committee>>> getCommitteesAtEpoch(final UInt64 epoch) {
    if (!combinedChainDataClient.isChainDataFullyAvailable()) {
      return chainUnavailable();
    }

    final UInt64 earliestQueryableSlot =
        CommitteeUtil.getEarliestQueryableSlotForTargetEpoch(epoch);
    if (recentChainData.getHeadSlot().isLessThan(earliestQueryableSlot)) {
      return SafeFuture.completedFuture(Optional.empty());
    }

    final UInt64 queryEpoch = compute_epoch_at_slot(earliestQueryableSlot);
    return combinedChainDataClient
        .getCheckpointStateAtEpoch(queryEpoch)
        .thenApply(
            maybeResult ->
                maybeResult.map(
                    checkpointState ->
                        combinedChainDataClient
                            .getCommitteesFromState(checkpointState.getState(), epoch).stream()
                            .map(Committee::new)
                            .collect(toList())));
  }

  public SafeFuture<Optional<List<EpochCommitteeResponse>>> getCommitteesAtEpochBySlotV1(
      final UInt64 stateSlot,
      final UInt64 epoch,
      final Optional<UInt64> maybeSlot,
      final Optional<Integer> maybeIndex) {
    final UInt64 earliestQueryableSlot =
        CommitteeUtil.getEarliestQueryableSlotForTargetEpoch(epoch);

    if (stateSlot.isLessThan(earliestQueryableSlot)) {
      return SafeFuture.completedFuture(Optional.empty());
    }

    final Predicate<CommitteeAssignment> filterForSlot =
        (committee) -> maybeSlot.map(slot -> committee.getSlot().equals(slot)).orElse(true);

    final Predicate<CommitteeAssignment> filterForCommitteeIndex =
        (committee) ->
            maybeIndex.map(index -> committee.getCommitteeIndex().intValue() == index).orElse(true);

    return combinedChainDataClient
        .getStateAtSlotExact(stateSlot)
        .thenApply(
            maybeResult ->
                maybeResult.map(
                    state ->
                        combinedChainDataClient.getCommitteesFromState(state, epoch).stream()
                            .filter(filterForSlot)
                            .filter(filterForCommitteeIndex)
                            .map(EpochCommitteeResponse::new)
                            .collect(toList())));
  }

  public SafeFuture<Optional<List<EpochCommitteeResponse>>> getCommitteesAtEpochByStateRoot(
      final Bytes32 stateRoot,
      final UInt64 epoch,
      final Optional<UInt64> maybeSlot,
      final Optional<Integer> maybeIndex) {
    final UInt64 earliestQueryableSlot =
        CommitteeUtil.getEarliestQueryableSlotForTargetEpoch(epoch);

    final Predicate<CommitteeAssignment> filterForSlot =
        (committee) -> maybeSlot.map(slot -> committee.getSlot().equals(slot)).orElse(true);

    final Predicate<CommitteeAssignment> filterForCommitteeIndex =
        (committee) ->
            maybeIndex.map(index -> committee.getCommitteeIndex().intValue() == index).orElse(true);

    return combinedChainDataClient
        .getStateByStateRoot(stateRoot)
        .thenApply(
            maybeResult ->
                maybeResult
                    .flatMap(
                        state ->
                            state.getSlot().isLessThan(earliestQueryableSlot)
                                ? Optional.empty()
                                : Optional.of(state))
                    .map(
                        state ->
                            combinedChainDataClient.getCommitteesFromState(state, epoch).stream()
                                .filter(filterForSlot)
                                .filter(filterForCommitteeIndex)
                                .map(EpochCommitteeResponse::new)
                                .collect(toList())));
  }

  public SafeFuture<Optional<GetBlockResponse>> getBlockBySlot(final UInt64 slot) {
    if (!isStoreAvailable()) {
      return chainUnavailable();
    }
    return combinedChainDataClient
        .getBlockInEffectAtSlot(slot)
        .thenApply(block -> block.map(GetBlockResponse::new));
  }

  public SafeFuture<Optional<BlockHeader>> getBlockHeaderByBlockId(final String slotParameter) {
    if (!isStoreAvailable()) {
      return chainUnavailable();
    }

    final Optional<UInt64> maybeSlot = blockParameterToSlot(slotParameter);
    if (maybeSlot.isEmpty()) {
      return getBlockHeaderByBlockRoot(Bytes32.fromHexString(slotParameter));
    }

    final UInt64 slot = maybeSlot.get();
    return combinedChainDataClient
        .getBlockAtSlotExact(slot)
        .thenApply(maybeBlock -> maybeBlock.map(block -> new BlockHeader(block, true)));
  }

  // because this is called after attempting to match a block to a slot, this function
  // will only ever be run for non canonical blocks. if made public, it will have to be updated to
  // first check that the block doesnt have a slot, before it can make that assumption.
  SafeFuture<Optional<BlockHeader>> getBlockHeaderByBlockRoot(final Bytes32 blockRoot) {
    return combinedChainDataClient
        .getBlockByBlockRoot(blockRoot)
        .thenApply(maybeBlock -> maybeBlock.map(block -> new BlockHeader(block, false)));
  }

  public boolean isStoreAvailable() {
    return combinedChainDataClient.isStoreAvailable();
  }

  public Optional<Bytes32> getBestBlockRoot() {
    return combinedChainDataClient.getBestBlockRoot();
  }

  public UInt64 getCurrentEpoch() {
    return combinedChainDataClient.getCurrentEpoch();
  }

  public SafeFuture<Optional<GetBlockResponse>> getBlockByBlockRoot(final Bytes32 blockParam) {
    if (!isStoreAvailable()) {
      return chainUnavailable();
    }
    return combinedChainDataClient
        .getBlockByBlockRoot(blockParam)
        .thenApply(block -> block.map(GetBlockResponse::new));
  }

  public SafeFuture<Optional<BeaconState>> getStateByBlockRoot(final Bytes32 blockRoot) {
    if (!isStoreAvailable()) {
      return chainUnavailable();
    }
    return combinedChainDataClient
        .getStateByBlockRoot(blockRoot)
        .thenApply(state -> state.map(BeaconState::new));
  }

  public SafeFuture<Optional<FinalityCheckpointsResponse>> getStateFinalityCheckpointsByStateRootV1(
      final Bytes32 stateRoot) {
    return combinedChainDataClient
        .getStateByStateRoot(stateRoot)
        .thenApply(state -> state.map(FinalityCheckpointsResponse::fromState));
  }

  public SafeFuture<Optional<FinalityCheckpointsResponse>> getStateFinalityCheckpointsBySlot(
      final UInt64 slot) {
    return combinedChainDataClient
        .getStateAtSlotExact(slot)
        .thenApply(state -> state.map(FinalityCheckpointsResponse::fromState));
  }

  public SafeFuture<Optional<BeaconState>> getStateByStateRoot(final Bytes32 stateRoot) {
    if (!isStoreAvailable()) {
      return chainUnavailable();
    }
    return combinedChainDataClient
        .getStateByStateRoot(stateRoot)
        .thenApply(state -> state.map(BeaconState::new));
  }

  public SafeFuture<Optional<BeaconState>> getStateAtSlot(final UInt64 slot) {
    if (!combinedChainDataClient.isChainDataFullyAvailable()) {
      return chainUnavailable();
    }

    return combinedChainDataClient
        .getStateAtSlotExact(slot)
        .thenApply(stateInternal -> stateInternal.map(BeaconState::new));
  }

  // TODO: remove once old API's are removed
  public SafeFuture<Optional<Bytes32>> getStateRootAtSlot(final UInt64 slot) {
    if (!combinedChainDataClient.isChainDataFullyAvailable()) {
      return chainUnavailable();
    }

    return SafeFuture.of(
        () ->
            combinedChainDataClient
                .getStateAtSlotExact(slot)
                .thenApplyChecked(
                    maybeState ->
                        maybeState.map(
                            tech.pegasys.teku.datastructures.state.BeaconState::hash_tree_root)));
  }

  public SafeFuture<Optional<Bytes32>> getStateRootAtSlotV1(final UInt64 slot) {
    return SafeFuture.of(
        () ->
            combinedChainDataClient
                .getStateAtSlotExact(slot)
                .thenApplyChecked(
                    maybeState ->
                        maybeState.map(
                            tech.pegasys.teku.datastructures.state.BeaconState::hash_tree_root)));
  }

  public SafeFuture<Optional<BeaconValidators>> getValidatorsByValidatorsRequest(
      final ValidatorsRequest request) {
    if (request.pubkeys.isEmpty()) {
      // Short-circuit if we're not requesting anything
      return SafeFuture.completedFuture(Optional.of(BeaconValidators.emptySet()));
    }
    if (!combinedChainDataClient.isChainDataFullyAvailable()) {
      return chainUnavailable();
    }

    return SafeFuture.of(
        () -> {
          UInt64 slot =
              request.epoch == null
                  ? combinedChainDataClient.getHeadSlot()
                  : BeaconStateUtil.compute_start_slot_at_epoch(request.epoch);

          return combinedChainDataClient
              .getBlockAndStateInEffectAtSlot(slot)
              .thenApply(
                  optionalState ->
                      optionalState.map(
                          state -> new BeaconValidators(state.getState(), request.pubkeys)));
        });
  }

  public boolean isFinalized(final SignedBeaconBlock signedBeaconBlock) {
    return combinedChainDataClient.isFinalized(signedBeaconBlock.message.slot);
  }

  public boolean isFinalized(final UInt64 slot) {
    return combinedChainDataClient.isFinalized(slot);
  }

  public boolean isFinalizedEpoch(final UInt64 epoch) {
    return combinedChainDataClient.isFinalizedEpoch(epoch);
  }

  public Optional<BeaconChainHead> getHeadState() {
    if (!isStoreAvailable()) {
      throw new ChainDataUnavailableException();
    }
    return recentChainData.getHeadBlockAndState().map(BeaconChainHead::new);
  }

  public Optional<UInt64> blockParameterToSlot(final String pathParam) {
    if (!isStoreAvailable()) {
      throw new ChainDataUnavailableException();
    }
    try {
      switch (pathParam) {
        case ("head"):
          return recentChainData.getCurrentSlot();
        case ("genesis"):
          return Optional.of(UInt64.ZERO);
        case ("finalized"):
          return recentChainData.getFinalizedCheckpoint().map(Checkpoint::getEpochStartSlot);
      }
      if (pathParam.toLowerCase().startsWith("0x")) {
        // block root
        Bytes32 blockRoot = Bytes32.fromHexString(pathParam);
        return combinedChainDataClient.getSlotByBlockRoot(blockRoot).join();
      } else {
        final UInt64 slot = UInt64.valueOf(pathParam);
        final UInt64 headSlot = recentChainData.getHeadSlot();
        if (slot.isGreaterThan(headSlot)) {
          throw new IllegalArgumentException(
              String.format("Invalid block: %s is beyond head slot %s", slot, headSlot));
        }
        return Optional.of(UInt64.valueOf(pathParam));
      }
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException(String.format("Invalid block: %s", pathParam));
    }
  }

  public void requireStoreAvailable() {
    if (!isStoreAvailable()) {
      throw new ChainDataUnavailableException();
    }
  }

  /**
   * Convert a State Parameter {state_id} from a URL to a slot number
   *
   * @param pathParam head, genesis, finalized, justified, &lt;slot&gt;
   * @return Optional slot of the desired state
   * @throws IllegalArgumentException if state cannot be parsed or is in the future.
   */
  public Optional<UInt64> stateParameterToSlot(final String pathParam) {
    try {
      switch (pathParam) {
        case ("head"):
          return recentChainData.getCurrentSlot();
        case ("genesis"):
          return Optional.of(UInt64.ZERO);
        case ("finalized"):
          return recentChainData.getFinalizedCheckpoint().map(Checkpoint::getEpochStartSlot);
        case ("justified"):
          return recentChainData.getJustifiedCheckpoint().map(Checkpoint::getEpochStartSlot);
      }
      final UInt64 slot = UInt64.valueOf(pathParam);
      final UInt64 headSlot = recentChainData.getHeadSlot();
      if (slot.isGreaterThan(headSlot)) {
        throw new IllegalArgumentException(
            String.format("Invalid state: %s is beyond head slot %s", slot, headSlot));
      }
      return Optional.of(UInt64.valueOf(pathParam));
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException(String.format("Invalid state: %s", pathParam));
    }
  }

  /**
   * Convert a {validator_id} from a URL to a validator index
   *
   * @param validatorParameter numeric, or validator pubkey
   * @return optional of the validator index
   * @throws IllegalArgumentException if validator cannot be parsed or is out of bounds.
   * @throws ChainDataUnavailableException if store is not able to process requests.
   */
  public Optional<Integer> validatorParameterToIndex(final String validatorParameter) {
    if (!isStoreAvailable()) {
      throw new ChainDataUnavailableException();
    }
    final Optional<tech.pegasys.teku.datastructures.state.BeaconState> maybeState =
        recentChainData.getBestState();
    if (maybeState.isEmpty()) {
      return Optional.empty();
    }
    final tech.pegasys.teku.datastructures.state.BeaconState state = maybeState.get();

    if (validatorParameter.toLowerCase().startsWith("0x")) {
      try {
        BLSPubKey publicKey = BLSPubKey.fromHexString(validatorParameter);
        return ValidatorsUtil.getValidatorIndex(state, publicKey.asBLSPublicKey());
      } catch (PublicKeyException ex) {
        throw new IllegalArgumentException(
            String.format("Invalid public key: %s", validatorParameter));
      }
    } else {
      try {
        final UInt64 numericValidator = UInt64.valueOf(validatorParameter);
        if (numericValidator.isGreaterThan(UInt64.valueOf(Integer.MAX_VALUE))) {
          throw new IllegalArgumentException(
              String.format("Validator Index is too high to use: %s", validatorParameter));
        }
        final int validatorIndex = numericValidator.intValue();
        final int validatorCount = state.getValidators().size();
        if (validatorIndex > validatorCount) {
          throw new IllegalArgumentException(
              String.format(
                  "Invalid validator index: %d, exceeds validator count: %d",
                  validatorIndex, validatorCount));
        }
        return Optional.of(validatorIndex);
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException(
            String.format("Invalid validator: %s", validatorParameter));
      }
    }
  }

  public SafeFuture<Optional<Fork>> getForkAtStateRoot(final Bytes32 root) {
    return combinedChainDataClient
        .getStateByStateRoot(root)
        .thenApply(maybeState -> maybeState.map(state -> new Fork(state.getFork())));
  }

  public SafeFuture<Optional<Fork>> getForkAtSlot(final UInt64 slot) {
    return combinedChainDataClient
        .getStateAtSlotExact(slot)
        .thenApply(maybeState -> maybeState.map(state -> new Fork(state.getFork())));
  }

  public SafeFuture<Optional<ValidatorResponse>> getValidatorDetailsBySlot(
      final UInt64 slot, final Optional<Integer> validatorIndex) {
    if (validatorIndex.isEmpty()) {
      return SafeFuture.completedFuture(Optional.empty());
    } else {
      return getValidatorsDetailsBySlot(slot, List.of(validatorIndex.get()))
          .thenApply(
              maybeValidators ->
                  maybeValidators.flatMap(
                      validators -> {
                        checkState(
                            validators.size() <= 1,
                            "Received more than one validator for a single index");
                        return validators.stream().findFirst();
                      }));
    }
  }

  public SafeFuture<Optional<List<ValidatorResponse>>> getValidatorsDetailsBySlot(
      final UInt64 slot, final List<Integer> validatorIndices) {
    if (validatorIndices.isEmpty()) {
      return SafeFuture.completedFuture(Optional.of(emptyList()));
    }

    return combinedChainDataClient
        .getStateAtSlotExact(slot)
        .thenApply(maybeState -> maybeState.map(state -> getValidators(validatorIndices, state)));
  }

  public SafeFuture<Optional<ValidatorResponse>> getValidatorDetailsByStateRoot(
      final Bytes32 stateRoot, final Optional<Integer> validatorIndex) {
    if (validatorIndex.isEmpty()) {
      return SafeFuture.completedFuture(Optional.empty());
    } else {
      return getValidatorsDetailsByStateRoot(stateRoot, List.of(validatorIndex.get()))
          .thenApply(
              maybeValidators ->
                  maybeValidators.flatMap(
                      validators -> {
                        checkState(
                            validators.size() <= 1,
                            "Received more than one validator for a single index");
                        return validators.stream().findFirst();
                      }));
    }
  }

  public SafeFuture<Optional<List<ValidatorResponse>>> getValidatorsDetailsByStateRoot(
      final Bytes32 stateRoot, final List<Integer> validatorIndices) {
    if (validatorIndices.isEmpty()) {
      return SafeFuture.completedFuture(Optional.of(emptyList()));
    }

    return combinedChainDataClient
        .getStateByStateRoot(stateRoot)
        .thenApply(maybeState -> maybeState.map(state -> getValidators(validatorIndices, state)));
  }

  public SafeFuture<Optional<List<ValidatorBalanceResponse>>> getValidatorsBalancesBySlot(
      final UInt64 slot, final List<Integer> validatorIndices) {
    if (validatorIndices.isEmpty()) {
      return SafeFuture.completedFuture(Optional.of(emptyList()));
    }

    return combinedChainDataClient
        .getStateAtSlotExact(slot)
        .thenApply(
            maybeState -> maybeState.map(state -> getValidatorBalances(validatorIndices, state)));
  }

  public SafeFuture<Optional<List<ValidatorBalanceResponse>>> getValidatorsBalancesByStateRoot(
      final Bytes32 stateRoot, final List<Integer> validatorIndices) {
    if (validatorIndices.isEmpty()) {
      return SafeFuture.completedFuture(Optional.of(emptyList()));
    }

    return combinedChainDataClient
        .getStateByStateRoot(stateRoot)
        .thenApply(
            maybeState -> maybeState.map(state -> getValidatorBalances(validatorIndices, state)));
  }

  private List<ValidatorResponse> getValidators(
      final List<Integer> validatorIndices,
      final tech.pegasys.teku.datastructures.state.BeaconState state) {
    return validatorIndices.stream()
        .map(index -> ValidatorResponse.fromState(state, index))
        .collect(toList());
  }

  private List<ValidatorBalanceResponse> getValidatorBalances(
      final List<Integer> validatorIndices,
      final tech.pegasys.teku.datastructures.state.BeaconState state) {
    return validatorIndices.stream()
        .map(index -> ValidatorBalanceResponse.fromState(state, index))
        .collect(toList());
  }

  public Optional<Bytes32> getStateRootFromBlockRoot(final Bytes32 blockRoot) {
    return combinedChainDataClient
        .getStateByBlockRoot(blockRoot)
        .join()
        .map(Merkleizable::hash_tree_root);
  }

  public SafeFuture<List<BlockHeader>> getBlockHeaders(
      final Optional<Bytes32> parentRoot, final Optional<UInt64> slot) {
    if (!isStoreAvailable()) {
      throw new ChainDataUnavailableException();
    }
    if (parentRoot.isPresent()) {
      return SafeFuture.completedFuture(List.of());
    }

    final UInt64 slotToLoad = slot.or(recentChainData::getCurrentSlot).orElseThrow();
    return combinedChainDataClient
        .getBlockAtSlotExact(slotToLoad)
        .thenApply(
            maybeBlock ->
                maybeBlock.map(block -> List.of(new BlockHeader(block, true))).orElse(List.of()));
  }
}
