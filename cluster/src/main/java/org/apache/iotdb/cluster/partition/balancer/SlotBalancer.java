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

package org.apache.iotdb.cluster.partition.balancer;

import org.apache.iotdb.cluster.rpc.thrift.Node;
import org.apache.iotdb.cluster.rpc.thrift.RaftNode;

import java.util.List;
import java.util.Map;

/** When add/remove a node, the slots need to be redistributed. */
public interface SlotBalancer {

  /**
   * When add a new node, new raft groups will take over some hash slots from another raft groups.
   */
  void moveSlotsToNew(Node newNode, List<Node> oldRing);

  /**
   * When remove an old node, all hash slots of the removed groups will assigned to other raft
   * groups.
   *
   * @param target the node to be removed
   */
  Map<RaftNode, List<Integer>> retrieveSlots(Node target);
}
