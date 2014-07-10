This cdl.spec file ("cluster definition language") covers mapping of
logical resources to nodes.

  depends:
    - base, ddl

Key concepts include...

* node
* resourceMap, partitionMap
* rebalance, failover, addNode, removeNode
* swapRebalance, addBack, smartAddBack
* onlineUpgrade, rollingUpgrade

Definitions...

  node:
    - uuid       // Node needs to remember/self-assign its UUID on startup.
    - addrPort*  // A node should try to get its main ports early,
                 // otherwise fail to start.
    - cfgServer* // Should try to contact cfgServer early.
    - cfgToken
    - container  // Path for shelf/rack/row/zone awareness.
    - nodeState
      * known
      * wanted   // Should have active partitions and/or resources assign-able.
      * unwanted
    - usage      // e.g., kv only, index only, stats ony?
    - weight
    - memoryQuota
    - numProcessors
    - storageQuota
    - storageLocation*
    - maxConns
    - maxChannels
    - maxChannelsPerConn
    - maxInflightPerChannel

Node addr:port might change over time / is ephemeral?

  nodeState:

Old partition mappings should be kept for a bit (for swap-rebalance,
smart add-back).

  partitionMap:

  partitionCfg:
    - partitionState
      * master, pending, replica, dead
    - rangeStartInclusive
    - rangeLimitExclusive

  clusterChangeRequest:
    - requestor
    - reason/description
    * addNode
      - addBack (smart, stable addBack?)
    * removeNode
      - maintenanceMode
    * swapRebalance // Probably computed.
    * failOver
    * onlineUpgrade

More ideas...

  partitionMoveSteps:
    * build partition replica on future master
    * ensure indexes are built on future master (concurrently)
    * do rest of partition takeover
    * index (view) compaction (not concurrent with partition moves)

consistent indexes during rebalance
- looks for backfill-done messages before starting
  next backfill on another vbucket
- and also waits for indexes to be done before starting a real vbucket takeover
-- by forcing a checkpoint on source node
-- and waiting until checkpoint persisted on target node
-- pause indexing on source node
-- force another checkpoint on source node
-- and waiting until 2nd checkpoint persisted on target node
-- and wait for indexes to catchup to forced checkpoint on target node
-- before starting actual vbucket-takeover dance

Partition moves need to have limited number of backfills to not
overload node and storageLocation I/O.

Some new feature / operation allowed only when all nodes have reached
the same minimal version.

Rebalance is usually about assigning partitions to nodes (must have).
But, perhaps can also cover assigning partitions to processors?
e.g. NUMA.  And, to storageLocations?  The first, cheap approach to
this is to allow more than 1 engine to run on a node.

chain replication
- A -> B -> C
- if server A fails over to server X, how does server C learn
  about the failOver news, where there are new takeOver logs
  and rollback in server B that need to propagated to server C.
