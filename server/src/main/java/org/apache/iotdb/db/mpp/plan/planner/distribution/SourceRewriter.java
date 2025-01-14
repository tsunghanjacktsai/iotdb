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

package org.apache.iotdb.db.mpp.plan.planner.distribution;

import org.apache.iotdb.common.rpc.thrift.TRegionReplicaSet;
import org.apache.iotdb.commons.exception.IllegalPathException;
import org.apache.iotdb.commons.partition.RegionReplicaSetInfo;
import org.apache.iotdb.commons.path.PartialPath;
import org.apache.iotdb.db.mpp.common.schematree.PathPatternTree;
import org.apache.iotdb.db.mpp.plan.analyze.Analysis;
import org.apache.iotdb.db.mpp.plan.expression.Expression;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.PlanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.SimplePlanNodeRewriter;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.read.CountSchemaMergeNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.read.SchemaFetchMergeNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.read.SchemaFetchScanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.read.SchemaQueryMergeNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.read.SchemaQueryScanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.write.DeleteTimeSeriesNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.process.AggregationNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.process.GroupByLevelNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.process.LastQueryMergeNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.process.MultiChildNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.process.TimeJoinNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.source.AlignedLastQueryScanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.source.AlignedSeriesAggregationScanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.source.AlignedSeriesScanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.source.LastQueryScanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.source.SeriesAggregationScanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.source.SeriesAggregationSourceNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.source.SeriesScanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.source.SeriesSourceNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.source.SourceNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.write.DeleteDataNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.parameter.AggregationDescriptor;
import org.apache.iotdb.db.mpp.plan.planner.plan.parameter.AggregationStep;
import org.apache.iotdb.db.mpp.plan.planner.plan.parameter.GroupByLevelDescriptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static org.apache.iotdb.commons.conf.IoTDBConstant.MULTI_LEVEL_PATH_WILDCARD;

public class SourceRewriter extends SimplePlanNodeRewriter<DistributionPlanContext> {

  private Analysis analysis;

  public SourceRewriter(Analysis analysis) {
    this.analysis = analysis;
  }

  // TODO: (xingtanzjr) implement the method visitDeviceMergeNode()
  public PlanNode visitDeviceMerge(TimeJoinNode node, DistributionPlanContext context) {
    return null;
  }

  public PlanNode visitDeleteTimeseries(
      DeleteTimeSeriesNode node, DistributionPlanContext context) {
    // Step 1: split DeleteDataNode by partition
    checkArgument(node.getChildren().size() == 1, "DeleteTimeSeriesNode should have 1 child");
    checkArgument(
        node.getChildren().get(0) instanceof DeleteDataNode,
        "Child of DeleteTimeSeriesNode should be DeleteDataNode");

    DeleteDataNode deleteDataNode = (DeleteDataNode) node.getChildren().get(0);
    List<DeleteDataNode> deleteDataNodes = splitDeleteDataNode(deleteDataNode, context);

    // Step 2: split DeleteTimeseriesNode by partition
    List<DeleteTimeSeriesNode> deleteTimeSeriesNodes = splitDeleteTimeseries(node, context);

    // Step 3: construct them as a Tree
    checkArgument(
        deleteTimeSeriesNodes.size() > 0,
        "Size of DeleteTimeseriesNode splits should be larger than 0");
    deleteDataNodes.forEach(split -> deleteTimeSeriesNodes.get(0).addChild(split));
    for (int i = 1; i < deleteTimeSeriesNodes.size(); i++) {
      deleteTimeSeriesNodes.get(i).addChild(deleteTimeSeriesNodes.get(i - 1));
    }
    return deleteTimeSeriesNodes.get(deleteTimeSeriesNodes.size() - 1);
  }

  private List<DeleteTimeSeriesNode> splitDeleteTimeseries(
      DeleteTimeSeriesNode node, DistributionPlanContext context) {
    List<DeleteTimeSeriesNode> ret = new ArrayList<>();
    List<PartialPath> rawPaths = node.getPathList();
    List<RegionReplicaSetInfo> relatedRegions =
        analysis.getSchemaPartitionInfo().getSchemaDistributionInfo();
    for (RegionReplicaSetInfo regionReplicaSetInfo : relatedRegions) {
      List<PartialPath> newPaths =
          getRelatedPaths(rawPaths, regionReplicaSetInfo.getOwnedStorageGroups());
      DeleteTimeSeriesNode split =
          new DeleteTimeSeriesNode(context.queryContext.getQueryId().genPlanNodeId(), newPaths);
      split.setRegionReplicaSet(regionReplicaSetInfo.getRegionReplicaSet());
      ret.add(split);
    }
    return ret;
  }

  private List<DeleteDataNode> splitDeleteDataNode(
      DeleteDataNode node, DistributionPlanContext context) {
    List<DeleteDataNode> ret = new ArrayList<>();
    List<PartialPath> rawPaths = node.getPathList();
    List<RegionReplicaSetInfo> relatedRegions =
        analysis.getDataPartitionInfo().getDataDistributionInfo();
    for (RegionReplicaSetInfo regionReplicaSetInfo : relatedRegions) {
      List<PartialPath> newPaths =
          getRelatedPaths(rawPaths, regionReplicaSetInfo.getOwnedStorageGroups());
      DeleteDataNode split =
          new DeleteDataNode(
              context.queryContext.getQueryId().genPlanNodeId(),
              context.queryContext.getQueryId(),
              newPaths,
              regionReplicaSetInfo.getOwnedStorageGroups());
      split.setRegionReplicaSet(regionReplicaSetInfo.getRegionReplicaSet());
      ret.add(split);
    }
    return ret;
  }

  private List<PartialPath> getRelatedPaths(List<PartialPath> paths, List<String> storageGroups) {
    List<PartialPath> ret = new ArrayList<>();
    PathPatternTree patternTree = new PathPatternTree(paths);
    for (String storageGroup : storageGroups) {
      try {
        ret.addAll(
            patternTree.findOverlappedPaths(
                new PartialPath(storageGroup).concatNode(MULTI_LEVEL_PATH_WILDCARD)));
      } catch (IllegalPathException e) {
        // The IllegalPathException is definitely not threw here
        throw new RuntimeException(e);
      }
    }
    return ret;
  }

  @Override
  public PlanNode visitSchemaQueryMerge(
      SchemaQueryMergeNode node, DistributionPlanContext context) {
    SchemaQueryMergeNode root = (SchemaQueryMergeNode) node.clone();
    SchemaQueryScanNode seed = (SchemaQueryScanNode) node.getChildren().get(0);
    TreeSet<TRegionReplicaSet> schemaRegions =
        new TreeSet<>(Comparator.comparingInt(region -> region.getRegionId().getId()));
    analysis
        .getSchemaPartitionInfo()
        .getSchemaPartitionMap()
        .forEach(
            (storageGroup, deviceGroup) -> {
              deviceGroup.forEach(
                  (deviceGroupId, schemaRegionReplicaSet) ->
                      schemaRegions.add(schemaRegionReplicaSet));
            });
    int count = schemaRegions.size();
    schemaRegions.forEach(
        region -> {
          SchemaQueryScanNode schemaQueryScanNode = (SchemaQueryScanNode) seed.clone();
          schemaQueryScanNode.setPlanNodeId(context.queryContext.getQueryId().genPlanNodeId());
          schemaQueryScanNode.setRegionReplicaSet(region);
          if (count > 1) {
            schemaQueryScanNode.setLimit(
                schemaQueryScanNode.getOffset() + schemaQueryScanNode.getLimit());
            schemaQueryScanNode.setOffset(0);
          }
          root.addChild(schemaQueryScanNode);
        });
    return root;
  }

  @Override
  public PlanNode visitCountMerge(CountSchemaMergeNode node, DistributionPlanContext context) {
    CountSchemaMergeNode root = (CountSchemaMergeNode) node.clone();
    SchemaQueryScanNode seed = (SchemaQueryScanNode) node.getChildren().get(0);
    Set<TRegionReplicaSet> schemaRegions = new HashSet<>();
    analysis
        .getSchemaPartitionInfo()
        .getSchemaPartitionMap()
        .forEach(
            (storageGroup, deviceGroup) -> {
              deviceGroup.forEach(
                  (deviceGroupId, schemaRegionReplicaSet) ->
                      schemaRegions.add(schemaRegionReplicaSet));
            });
    schemaRegions.forEach(
        region -> {
          SchemaQueryScanNode schemaQueryScanNode = (SchemaQueryScanNode) seed.clone();
          schemaQueryScanNode.setPlanNodeId(context.queryContext.getQueryId().genPlanNodeId());
          schemaQueryScanNode.setRegionReplicaSet(region);
          root.addChild(schemaQueryScanNode);
        });
    return root;
  }

  // TODO: (xingtanzjr) a temporary way to resolve the distribution of single SeriesScanNode issue
  @Override
  public PlanNode visitSeriesScan(SeriesScanNode node, DistributionPlanContext context) {
    TimeJoinNode timeJoinNode =
        new TimeJoinNode(context.queryContext.getQueryId().genPlanNodeId(), node.getScanOrder());
    return processRawSeriesScan(node, context, timeJoinNode);
  }

  @Override
  public PlanNode visitAlignedSeriesScan(
      AlignedSeriesScanNode node, DistributionPlanContext context) {
    TimeJoinNode timeJoinNode =
        new TimeJoinNode(context.queryContext.getQueryId().genPlanNodeId(), node.getScanOrder());
    return processRawSeriesScan(node, context, timeJoinNode);
  }

  @Override
  public PlanNode visitLastQueryScan(LastQueryScanNode node, DistributionPlanContext context) {
    LastQueryMergeNode mergeNode =
        new LastQueryMergeNode(
            context.queryContext.getQueryId().genPlanNodeId(), node.getPartitionTimeFilter());
    return processRawSeriesScan(node, context, mergeNode);
  }

  @Override
  public PlanNode visitAlignedLastQueryScan(
      AlignedLastQueryScanNode node, DistributionPlanContext context) {
    LastQueryMergeNode mergeNode =
        new LastQueryMergeNode(
            context.queryContext.getQueryId().genPlanNodeId(), node.getPartitionTimeFilter());
    return processRawSeriesScan(node, context, mergeNode);
  }

  private PlanNode processRawSeriesScan(
      SeriesSourceNode node, DistributionPlanContext context, MultiChildNode parent) {
    List<SeriesSourceNode> sourceNodes = splitSeriesSourceNodeByPartition(node, context);
    if (sourceNodes.size() == 1) {
      return sourceNodes.get(0);
    }
    sourceNodes.forEach(parent::addChild);
    return parent;
  }

  private List<SeriesSourceNode> splitSeriesSourceNodeByPartition(
      SeriesSourceNode node, DistributionPlanContext context) {
    List<SeriesSourceNode> ret = new ArrayList<>();
    List<TRegionReplicaSet> dataDistribution =
        analysis.getPartitionInfo(node.getPartitionPath(), node.getPartitionTimeFilter());
    if (dataDistribution.size() == 1) {
      node.setRegionReplicaSet(dataDistribution.get(0));
      ret.add(node);
      return ret;
    }

    for (TRegionReplicaSet dataRegion : dataDistribution) {
      SeriesSourceNode split = (SeriesSourceNode) node.clone();
      split.setPlanNodeId(context.queryContext.getQueryId().genPlanNodeId());
      split.setRegionReplicaSet(dataRegion);
      ret.add(split);
    }
    return ret;
  }

  @Override
  public PlanNode visitSeriesAggregationScan(
      SeriesAggregationScanNode node, DistributionPlanContext context) {
    return processSeriesAggregationSource(node, context);
  }

  @Override
  public PlanNode visitAlignedSeriesAggregationScan(
      AlignedSeriesAggregationScanNode node, DistributionPlanContext context) {
    return processSeriesAggregationSource(node, context);
  }

  private PlanNode processSeriesAggregationSource(
      SeriesAggregationSourceNode node, DistributionPlanContext context) {
    List<TRegionReplicaSet> dataDistribution =
        analysis.getPartitionInfo(node.getPartitionPath(), node.getPartitionTimeFilter());
    if (dataDistribution.size() == 1) {
      node.setRegionReplicaSet(dataDistribution.get(0));
      return node;
    }
    List<AggregationDescriptor> leafAggDescriptorList = new ArrayList<>();
    node.getAggregationDescriptorList()
        .forEach(
            descriptor -> {
              leafAggDescriptorList.add(
                  new AggregationDescriptor(
                      descriptor.getAggregationType(),
                      AggregationStep.PARTIAL,
                      descriptor.getInputExpressions()));
            });

    List<AggregationDescriptor> rootAggDescriptorList = new ArrayList<>();
    node.getAggregationDescriptorList()
        .forEach(
            descriptor -> {
              rootAggDescriptorList.add(
                  new AggregationDescriptor(
                      descriptor.getAggregationType(),
                      AggregationStep.FINAL,
                      descriptor.getInputExpressions()));
            });

    AggregationNode aggregationNode =
        new AggregationNode(
            context.queryContext.getQueryId().genPlanNodeId(), rootAggDescriptorList);
    for (TRegionReplicaSet dataRegion : dataDistribution) {
      SeriesAggregationScanNode split = (SeriesAggregationScanNode) node.clone();
      split.setAggregationDescriptorList(leafAggDescriptorList);
      split.setPlanNodeId(context.queryContext.getQueryId().genPlanNodeId());
      split.setRegionReplicaSet(dataRegion);
      aggregationNode.addChild(split);
    }
    return aggregationNode;
  }

  @Override
  public PlanNode visitSchemaFetchMerge(
      SchemaFetchMergeNode node, DistributionPlanContext context) {
    SchemaFetchMergeNode root = (SchemaFetchMergeNode) node.clone();
    Map<String, Set<TRegionReplicaSet>> storageGroupSchemaRegionMap = new HashMap<>();
    analysis
        .getSchemaPartitionInfo()
        .getSchemaPartitionMap()
        .forEach(
            (storageGroup, deviceGroup) -> {
              storageGroupSchemaRegionMap.put(storageGroup, new HashSet<>());
              deviceGroup.forEach(
                  (deviceGroupId, schemaRegionReplicaSet) ->
                      storageGroupSchemaRegionMap.get(storageGroup).add(schemaRegionReplicaSet));
            });

    for (PlanNode child : node.getChildren()) {
      for (TRegionReplicaSet schemaRegion :
          storageGroupSchemaRegionMap.get(
              ((SchemaFetchScanNode) child).getStorageGroup().getFullPath())) {
        SchemaFetchScanNode schemaFetchScanNode = (SchemaFetchScanNode) child.clone();
        schemaFetchScanNode.setPlanNodeId(context.queryContext.getQueryId().genPlanNodeId());
        schemaFetchScanNode.setRegionReplicaSet(schemaRegion);
        root.addChild(schemaFetchScanNode);
      }
    }
    return root;
  }

  @Override
  public PlanNode visitLastQueryMerge(LastQueryMergeNode node, DistributionPlanContext context) {
    return processRawMultiChildNode(node, context);
  }

  @Override
  public PlanNode visitTimeJoin(TimeJoinNode node, DistributionPlanContext context) {
    // Although some logic is similar between Aggregation and RawDataQuery,
    // we still use separate method to process the distribution planning now
    // to make the planning procedure more clear
    if (isAggregationQuery(node)) {
      return planAggregationWithTimeJoin(node, context);
    }
    return processRawMultiChildNode(node, context);
  }

  private PlanNode processRawMultiChildNode(MultiChildNode node, DistributionPlanContext context) {
    MultiChildNode root = (MultiChildNode) node.clone();
    // Step 1: Get all source nodes. For the node which is not source, add it as the child of
    // current TimeJoinNode
    List<SourceNode> sources = new ArrayList<>();
    for (PlanNode child : node.getChildren()) {
      if (child instanceof SeriesSourceNode) {
        // If the child is SeriesScanNode, we need to check whether this node should be seperated
        // into several splits.
        SeriesSourceNode handle = (SeriesSourceNode) child;
        List<TRegionReplicaSet> dataDistribution =
            analysis.getPartitionInfo(handle.getPartitionPath(), handle.getPartitionTimeFilter());
        // If the size of dataDistribution is m, this SeriesScanNode should be seperated into m
        // SeriesScanNode.
        for (TRegionReplicaSet dataRegion : dataDistribution) {
          SeriesSourceNode split = (SeriesSourceNode) handle.clone();
          split.setPlanNodeId(context.queryContext.getQueryId().genPlanNodeId());
          split.setRegionReplicaSet(dataRegion);
          sources.add(split);
        }
      }
    }
    // Step 2: For the source nodes, group them by the DataRegion.
    Map<TRegionReplicaSet, List<SourceNode>> sourceGroup =
        sources.stream().collect(Collectors.groupingBy(SourceNode::getRegionReplicaSet));

    // Step 3: For the source nodes which belong to same data region, add a TimeJoinNode for them
    // and make the
    // new TimeJoinNode as the child of current TimeJoinNode
    // TODO: (xingtanzjr) optimize the procedure here to remove duplicated TimeJoinNode
    final boolean[] addParent = {false};
    sourceGroup.forEach(
        (dataRegion, seriesScanNodes) -> {
          if (seriesScanNodes.size() == 1) {
            root.addChild(seriesScanNodes.get(0));
          } else {
            if (!addParent[0]) {
              seriesScanNodes.forEach(root::addChild);
              addParent[0] = true;
            } else {
              // We clone a TimeJoinNode from root to make the params to be consistent.
              // But we need to assign a new ID to it
              MultiChildNode parentOfGroup = (MultiChildNode) root.clone();
              parentOfGroup.setPlanNodeId(context.queryContext.getQueryId().genPlanNodeId());
              seriesScanNodes.forEach(parentOfGroup::addChild);
              root.addChild(parentOfGroup);
            }
          }
        });

    // Process the other children which are not SeriesSourceNode
    for (PlanNode child : node.getChildren()) {
      if (!(child instanceof SeriesSourceNode)) {
        // In a general logical query plan, the children of TimeJoinNode should only be
        // SeriesScanNode or SeriesAggregateScanNode
        // So this branch should not be touched.
        root.addChild(visit(child, context));
      }
    }
    return root;
  }

  private boolean isAggregationQuery(TimeJoinNode node) {
    for (PlanNode child : node.getChildren()) {
      if (child instanceof SeriesAggregationScanNode
          || child instanceof AlignedSeriesAggregationScanNode) {
        return true;
      }
    }
    return false;
  }

  private PlanNode planAggregationWithTimeJoin(TimeJoinNode root, DistributionPlanContext context) {

    List<SeriesAggregationSourceNode> sources = splitAggregationSourceByPartition(root, context);
    Map<TRegionReplicaSet, List<SeriesAggregationSourceNode>> sourceGroup =
        sources.stream().collect(Collectors.groupingBy(SourceNode::getRegionReplicaSet));

    // construct AggregationDescriptor for AggregationNode
    List<AggregationDescriptor> rootAggDescriptorList = new ArrayList<>();
    for (PlanNode child : root.getChildren()) {
      SeriesAggregationSourceNode handle = (SeriesAggregationSourceNode) child;
      handle
          .getAggregationDescriptorList()
          .forEach(
              descriptor -> {
                rootAggDescriptorList.add(
                    new AggregationDescriptor(
                        descriptor.getAggregationType(),
                        AggregationStep.FINAL,
                        descriptor.getInputExpressions()));
              });
    }
    AggregationNode aggregationNode =
        new AggregationNode(
            context.queryContext.getQueryId().genPlanNodeId(), rootAggDescriptorList);

    final boolean[] addParent = {false};
    sourceGroup.forEach(
        (dataRegion, sourceNodes) -> {
          if (sourceNodes.size() == 1) {
            aggregationNode.addChild(sourceNodes.get(0));
          } else {
            if (!addParent[0]) {
              sourceNodes.forEach(aggregationNode::addChild);
              addParent[0] = true;
            } else {
              // We clone a TimeJoinNode from root to make the params to be consistent.
              // But we need to assign a new ID to it
              TimeJoinNode parentOfGroup = (TimeJoinNode) root.clone();
              parentOfGroup.setPlanNodeId(context.queryContext.getQueryId().genPlanNodeId());
              sourceNodes.forEach(parentOfGroup::addChild);
              aggregationNode.addChild(parentOfGroup);
            }
          }
        });

    return aggregationNode;
  }

  public PlanNode visitGroupByLevel(GroupByLevelNode root, DistributionPlanContext context) {
    // Firstly, we build the tree structure for GroupByLevelNode
    List<SeriesAggregationSourceNode> sources = splitAggregationSourceByPartition(root, context);
    Map<TRegionReplicaSet, List<SeriesAggregationSourceNode>> sourceGroup =
        sources.stream().collect(Collectors.groupingBy(SourceNode::getRegionReplicaSet));

    GroupByLevelNode newRoot = (GroupByLevelNode) root.clone();
    final boolean[] addParent = {false};
    sourceGroup.forEach(
        (dataRegion, sourceNodes) -> {
          if (sourceNodes.size() == 1) {
            newRoot.addChild(sourceNodes.get(0));
          } else {
            if (!addParent[0]) {
              sourceNodes.forEach(newRoot::addChild);
              addParent[0] = true;
            } else {
              // We clone a TimeJoinNode from root to make the params to be consistent.
              // But we need to assign a new ID to it
              GroupByLevelNode parentOfGroup = (GroupByLevelNode) root.clone();
              parentOfGroup.setPlanNodeId(context.queryContext.getQueryId().genPlanNodeId());
              sourceNodes.forEach(parentOfGroup::addChild);
              newRoot.addChild(parentOfGroup);
            }
          }
        });

    // Then, we calculate the attributes for GroupByLevelNode in each level
    calculateGroupByLevelNodeAttributes(newRoot, 0);
    return newRoot;
  }

  private void calculateGroupByLevelNodeAttributes(PlanNode node, int level) {
    if (node == null) {
      return;
    }
    node.getChildren().forEach(child -> calculateGroupByLevelNodeAttributes(child, level + 1));
    if (!(node instanceof GroupByLevelNode)) {
      return;
    }
    GroupByLevelNode handle = (GroupByLevelNode) node;

    // Construct all outputColumns from children. Using Set here to avoid duplication
    Set<String> childrenOutputColumns = new HashSet<>();
    handle
        .getChildren()
        .forEach(child -> childrenOutputColumns.addAll(child.getOutputColumnNames()));

    // Check every OutputColumn of GroupByLevelNode and set the Expression of corresponding
    // AggregationDescriptor
    List<GroupByLevelDescriptor> descriptorList = new ArrayList<>();
    for (GroupByLevelDescriptor originalDescriptor : handle.getGroupByLevelDescriptors()) {
      List<Expression> descriptorExpression = new ArrayList<>();
      for (String childColumn : childrenOutputColumns) {
        // If this condition matched, the childColumn should come from GroupByLevelNode
        if (isAggColumnMatchExpression(childColumn, originalDescriptor.getOutputExpression())) {
          descriptorExpression.add(originalDescriptor.getOutputExpression());
          continue;
        }
        for (Expression exp : originalDescriptor.getInputExpressions()) {
          if (isAggColumnMatchExpression(childColumn, exp)) {
            descriptorExpression.add(exp);
          }
        }
      }
      if (descriptorExpression.size() == 0) {
        continue;
      }
      GroupByLevelDescriptor descriptor = originalDescriptor.deepClone();
      descriptor.setStep(level == 0 ? AggregationStep.FINAL : AggregationStep.PARTIAL);
      descriptor.setInputExpressions(descriptorExpression);

      descriptorList.add(descriptor);
    }
    handle.setGroupByLevelDescriptors(descriptorList);
  }

  // TODO: (xingtanzjr) need to confirm the logic when processing UDF
  private boolean isAggColumnMatchExpression(String columnName, Expression expression) {
    if (columnName == null) {
      return false;
    }
    return columnName.contains(expression.getExpressionString());
  }

  private List<SeriesAggregationSourceNode> splitAggregationSourceByPartition(
      MultiChildNode root, DistributionPlanContext context) {
    // Step 1: split SeriesAggregationSourceNode according to data partition
    List<SeriesAggregationSourceNode> sources = new ArrayList<>();
    Map<PartialPath, Integer> regionCountPerSeries = new HashMap<>();
    for (PlanNode child : root.getChildren()) {
      SeriesAggregationSourceNode handle = (SeriesAggregationSourceNode) child;
      List<TRegionReplicaSet> dataDistribution =
          analysis.getPartitionInfo(handle.getPartitionPath(), handle.getPartitionTimeFilter());
      for (TRegionReplicaSet dataRegion : dataDistribution) {
        SeriesAggregationSourceNode split = (SeriesAggregationSourceNode) handle.clone();
        split.setPlanNodeId(context.queryContext.getQueryId().genPlanNodeId());
        split.setRegionReplicaSet(dataRegion);
        // Let each split reference different object of AggregationDescriptorList
        split.setAggregationDescriptorList(
            handle.getAggregationDescriptorList().stream()
                .map(AggregationDescriptor::deepClone)
                .collect(Collectors.toList()));
        sources.add(split);
      }
      regionCountPerSeries.put(handle.getPartitionPath(), dataDistribution.size());
    }

    // Step 2: change the step for each SeriesAggregationSourceNode according to its split count
    for (SeriesAggregationSourceNode source : sources) {
      //        boolean isFinal = regionCountPerSeries.get(source.getPartitionPath()) == 1;
      // TODO: (xingtanzjr) need to optimize this step later. We make it as Partial now.
      boolean isFinal = false;
      source
          .getAggregationDescriptorList()
          .forEach(d -> d.setStep(isFinal ? AggregationStep.FINAL : AggregationStep.PARTIAL));
    }
    return sources;
  }

  public PlanNode visit(PlanNode node, DistributionPlanContext context) {
    return node.accept(this, context);
  }
}
