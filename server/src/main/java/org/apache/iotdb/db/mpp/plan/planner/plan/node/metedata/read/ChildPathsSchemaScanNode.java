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

package org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.read;

import org.apache.iotdb.commons.path.PartialPath;
import org.apache.iotdb.db.metadata.path.PathDeserializeUtil;
import org.apache.iotdb.db.mpp.common.header.HeaderConstant;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.PlanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.PlanNodeId;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.PlanNodeType;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

public class ChildPathsSchemaScanNode extends SchemaQueryScanNode {
  // the path could be a prefix path with wildcard
  private PartialPath prefixPath;

  public ChildPathsSchemaScanNode(PlanNodeId id, PartialPath prefixPath) {
    super(id);
    this.prefixPath = prefixPath;
  }

  public PartialPath getPrefixPath() {
    return prefixPath;
  }

  @Override
  public PlanNode clone() {
    return new ChildPathsSchemaScanNode(getPlanNodeId(), prefixPath);
  }

  @Override
  public List<String> getOutputColumnNames() {
    return HeaderConstant.showChildPathsHeader.getRespColumns();
  }

  @Override
  protected void serializeAttributes(ByteBuffer byteBuffer) {
    PlanNodeType.CHILD_PATHS_SCAN.serialize(byteBuffer);
    prefixPath.serialize(byteBuffer);
  }

  public static PlanNode deserialize(ByteBuffer buffer) {
    PartialPath path = (PartialPath) PathDeserializeUtil.deserialize(buffer);
    PlanNodeId planNodeId = PlanNodeId.deserialize(buffer);
    return new ChildPathsSchemaScanNode(planNodeId, path);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    ChildPathsSchemaScanNode that = (ChildPathsSchemaScanNode) o;
    return prefixPath == that.prefixPath;
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), prefixPath);
  }
}
