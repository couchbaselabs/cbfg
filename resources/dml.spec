This dml.spec file covers logical items and operations, including
consistency/availability (CAP) concerns.

  clusterOps:
    * ddl bucket/user/group/auth CRUD
    * node CRUD
      * orchestrator rebalance, autoFailOver
      * log
    - these have writeConcern, tx?

  bucketOps
    * ddl index/auth CRUD
      * xdcrCfg
    * mergedRangeScan
    * compact
    * log

ops          | cluster | bucket | partition
- getCached  |         |        | item
- get        |         |        | item, tx
- set        |         |        | item, tx, writeConcern
- del        |         |        | item, tx, writeConcern
- merge      |         |        | items, tx, writeConcern
- scan       |         |        | items, tx
- changeStream |       |        | y
- def        |         | y      | tx, writeConcern
- eval       |         | y      | tx, writeConcern
- mget       |         |        | itemHierarchy, tx
- mset       |         |        | itemHierarchy, tx, writeConcern
- mdel       |         |        | itemHierarchy, tx, writeConcern, cascadingDelete
- pillInject |         |        | tx, writeConcern
- logInject  | y       | y      | y
- fence      |         |        | y
- compactNow |         | y      | y
- exec(task) |         |        | y
- pause(task)
- resume(task)
- splitPartition
- mergePartition
- set/getPartitionConfig (including range startInclusive/limitExclusive)
- set/getPartitionState
- set/getFilter
- set/getErrorExtra(CCCP)
- passOnwardsToReplicaX
- features
- stats
- purgeTombstones
- partitionMetaData
  - takeOverLog
- partitionMailBox
- partitionEphemeralBlackBoard
  - locks

requests to not affect cache if possible
- e.g., replication streams
- backfill on master
- changes injest on replica
- maybe put this hint into request header?

writeConcern
- N < R + W
- synchronous vs asynchronous XDCR
- maybe put this hint into request header?

  errorResponseBody.errorCode:
    * NOT_MY_PARTITION
      - extraBody includes cccpOpaque & engineVelocity
    * NOT_MY_RANGE
      - extraBody includes cccpOpaque & engineVelocity
    * TEMP_OOM_
     - extraBody includes engineVelocity
    * NOT_CAUGHT_UP (e.g., index scan)
      - extraBody includes engineVelocity
    * EWOULDBLOCK (e.g., getCached)
      - extraBody includes engineVelocity

  engineVelocity:
    - queue in/out speeds and lengths
      // Basically, enough info so that client can calculate
      // when it should retry, and ideally with some random
      // client-side politeness jitter.

  cccpOpaque;
    // Probably defined in cdl.spec.
    // This is the "fast forward map" of where the client should
    // look for the partition or resource.

subItems
- modifying subItem does not change parent
-- unless a mset (changing parent and child in one ACID set)
-- beware that replication keeps the atomicity
- cascading deletes (always)
- used for
-- transactions
-- attachments
-- revisionTrees
-- subItems
-- flexMetaData
- upward ver propagation
-- is this optional?

QYOW - query your own writes
- header area for "at least" up to X seqId.
- NOT_CAUGHT_UP
- writeConcern

item
- partitionId
- key
- revId
- cas?
- flags
- expiration
- valueDataType
- valueCompression
-- in-memory data compression
- value
- childItems (same partitionId as parent)
-- appMetaData (will this work?)
-- flexMetaData
-- txMetaData
-- revisionTree (will this work?)
-- attachments
-- items

