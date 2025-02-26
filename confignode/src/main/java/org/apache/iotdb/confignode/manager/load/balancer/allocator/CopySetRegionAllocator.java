/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.confignode.manager.load.balancer.allocator;

import org.apache.iotdb.common.rpc.thrift.TConsensusGroupId;
import org.apache.iotdb.common.rpc.thrift.TDataNodeInfo;
import org.apache.iotdb.common.rpc.thrift.TDataNodeLocation;
import org.apache.iotdb.common.rpc.thrift.TRegionReplicaSet;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Allocate Region by CopySet algorithm. Reference:
 * https://www.usenix.org/conference/atc13/technical-sessions/presentation/cidon
 */
public class CopySetRegionAllocator implements IRegionAllocator {

  private static final int maximumRandomNum = 10;

  private int maxId = 0;
  private int intersectionSize = 0;
  private List<TDataNodeLocation> weightList;

  public CopySetRegionAllocator() {
    // Empty constructor
  }

  @Override
  public TRegionReplicaSet allocateRegion(
      List<TDataNodeInfo> onlineDataNodes,
      List<TRegionReplicaSet> allocatedRegions,
      int replicationFactor,
      TConsensusGroupId consensusGroupId) {
    TRegionReplicaSet result = null;

    // Build weightList for weighted random
    buildWeightList(onlineDataNodes, allocatedRegions);

    boolean accepted = false;
    while (true) {
      for (int retry = 0; retry < maximumRandomNum; retry++) {
        result = genWeightedRandomRegion(replicationFactor);
        if (intersectionCheck(allocatedRegions, result)) {
          accepted = true;
          break;
        }
      }
      if (accepted) {
        break;
      }
      intersectionSize += 1;
    }

    clear();
    result.setRegionId(consensusGroupId);
    return result;
  }

  private void buildWeightList(
      List<TDataNodeInfo> onlineDataNodes, List<TRegionReplicaSet> allocatedRegions) {

    // TODO: The remaining disk capacity of DataNode can also be calculated into the weightList

    int maximumRegionNum = 0;
    Map<TDataNodeLocation, Integer> countMap = new HashMap<>();
    for (TDataNodeInfo dataNodeInfo : onlineDataNodes) {
      maxId = Math.max(maxId, dataNodeInfo.getLocation().getDataNodeId());
      countMap.put(dataNodeInfo.getLocation(), 0);
    }
    for (TRegionReplicaSet regionReplicaSet : allocatedRegions) {
      for (TDataNodeLocation dataNodeLocation : regionReplicaSet.getDataNodeLocations()) {
        countMap.computeIfPresent(dataNodeLocation, (dataNode, count) -> (count + 1));
        maximumRegionNum = Math.max(maximumRegionNum, countMap.get(dataNodeLocation));
      }
    }

    weightList = new ArrayList<>();
    for (Map.Entry<TDataNodeLocation, Integer> countEntry : countMap.entrySet()) {
      int weight = maximumRegionNum - countEntry.getValue() + 1;
      // Repeatedly add DataNode copies equal to the number of their weights
      for (int repeat = 0; repeat < weight; repeat++) {
        weightList.add(countEntry.getKey().deepCopy());
      }
    }
  }

  /** @return A new CopySet based on weighted random */
  private TRegionReplicaSet genWeightedRandomRegion(int replicationFactor) {
    Set<Integer> checkSet = new HashSet<>();
    TRegionReplicaSet randomRegion = new TRegionReplicaSet();
    Collections.shuffle(weightList);

    for (TDataNodeLocation dataNodeLocation : weightList) {
      if (checkSet.contains(dataNodeLocation.getDataNodeId())) {
        continue;
      }

      checkSet.add(dataNodeLocation.getDataNodeId());
      randomRegion.addToDataNodeLocations(dataNodeLocation);

      if (randomRegion.getDataNodeLocationsSize() == replicationFactor) {
        break;
      }
    }

    return randomRegion;
  }

  /**
   * Do intersection check.
   *
   * @param allocatedRegions Allocated CopySets.
   * @param newRegion A new CopySet.
   * @return True if the intersection size between every allocatedRegions and the newRegion are not
   *     exceed intersectionSize.
   */
  private boolean intersectionCheck(
      List<TRegionReplicaSet> allocatedRegions, TRegionReplicaSet newRegion) {
    BitSet newBit = new BitSet(maxId + 1);
    for (TDataNodeLocation dataNodeLocation : newRegion.getDataNodeLocations()) {
      newBit.set(dataNodeLocation.getDataNodeId());
    }

    for (TRegionReplicaSet allocatedRegion : allocatedRegions) {
      BitSet allocatedBit = new BitSet(maxId + 1);
      for (TDataNodeLocation dataNodeLocation : allocatedRegion.getDataNodeLocations()) {
        allocatedBit.set(dataNodeLocation.getDataNodeId());
      }

      allocatedBit.and(newBit);
      if (allocatedBit.cardinality() > intersectionSize) {
        // In order to ensure the maximum scatter width and the minimum disaster rate
        return false;
      }
    }
    return true;
  }

  private void clear() {
    maxId = 0;
    intersectionSize = 0;
    weightList.clear();
    weightList = null;
  }
}
