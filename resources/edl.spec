This edl.spec file ("engine definition language") covers engine
implementation including internal queues, stats, error conditions.

- statsAggregator
- partitionMapValidator
-- detects unbalancedness and not enough replicas
- partitionMapPlanner
- partitionMapScheduler
-- max scanners per node
-- max movers per pair of nodes
- partitionMapMover
- n2nr
-- ongoing streams
-- takeover streams
- xdcr
- dmlEngine
-- n1ql
-- hashJoiner
-- mapper
- backIndex
- indexMaintainer
- index
- view
- fullText
- backup
- directFileCopier
- autoFailOverer
- doctor
-- heartBeat
-- nodeHealth
- healthChecker
- integrations
-- hadoop
-- elasticSearch

each service...
- short label
- needs storage?
-- ephemeral storage
-- permanent storage
- needs partitionMap
-- reads partitionMap
-- writes partitionMap
- limits  (per cluster, per node,  per bucket, per partition)...
-- memory
-- storage
-- processors

system user ("_sys")
- a "system user or conductor", which is super-priviledged?
-- not meant for end-user usage
-- used by the system to force changes on nodes
-- without needing special networking pathways
-- and should always work even if user deletes/changes any other admin users

node: namedObj
- uuid (the one thing a node needs to know on startup, along with cfg server)
- parent/containment path (rack/zone)
- usage (kv only? index and index.fullText only?)
- host / port
- weight
- memoryQuota
- addrBindings
- numProcessors
- directory+
-- logical data volumes (leverage more spindles)
- maxConns
- maxChannels
- maxInflightRequestsPerChannel

partitionMap

engineProcess
  conn*
  ePool*
    eBucket*
      modelBucket
      acl*
      eColl*
        for: primary, secondaryIndex, system
        ePartition*
          item*
          change*
          failOverLog
          partitionId
          rangeStartInclusive (bottom)
          rangeLimitExclusive (top)
          state: master, replica, pending, dead
          readiness: warming, cooling, running, stopped
          mailBox
          ephemeral
            subscribers (with filters (keyOnly, matchKeyExpr, matchValueExpr))
            partition assignment to processor (mem quota)
            partition assignment to storage (storage quota)
  storage
    scanner*
    snapshot*
      snapshotScanner*
  volume*
    type: ephemeral, permanent
    kind: ssd, hdd
    fsVolume* (directory)
  task*
    storageScanner
       (current num and max num of these per node, per volume;
        state: working/running vs stopped)
      compactor / expirer / tombstonePurger
      backFiller
    memScanner
      evictor, expirer
    inPlaceBackUpper / directFileCopier (after compaction)
  stream*
    replicaStream, changeStream, persister
  memAllocator
  stats
  - startTime
  - currTime
  - upTime is calculated by tool
  - aggregates calculated elsewhere?
  - speed in/out and current queue lengths tracked by node

conn
  channel*

channel
  authedUser
  inflightRequest*

inflightRequest
  startAtHRTime
  endAtHRTime
  nextInFlightRequest
  prevInflightRequest
  opaqueId
  op
  requestHeader*
  requestChunk*
    start processing even before all request chunks are received

(need to handle "stats" or other open requests with multiple responses)

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

multiple partitions in one file
- for better batching
- still logically separate, though
- add partitionId as key prefix
-- perhaps also add one more level of indirection to allow for fast delete-and-recreate
-- true deletion during compaction

proposed leveldb layout...
  cust-0001:m            (metaData: revId, cas, flags, expiration,
                          dataType, compression)
  cust-0001:v            (value)
  cust-0001/_at/01:m     (attachment metaData)
  cust-0001/_at/01:v     (attachment value)
  cust-0001/_fm:m        (flexMetaData metaData)
  cust-0001/_fm:v        (flexMetaData value)
  cust-0001/_rt:m        (revTree metaData)
  cust-0001/_rt:v        (revTree value)
  cust-0001/_tx:m        (transaction metaData)
  cust-0001/_tx:v        (transaction metaData)
  cust-0001/<subKey>:m
  cust-0001/<subKey>:v
  cust-0001/campaign-001:m
  cust-0001/campaign-001:v
  cust-0001/campaign-002:m
  cust-0001/campaign-002:v
  cust-0001/campaign-002/response-000001:m
  cust-0001/campaign-002/response-000001:v

some buckets are special system buckets
  system catalog bucket (read-only?)
  stats bucket (read-only?)

storage / dir
- quota
- allowed file types
- allowed partition types
- allowed collection types
- priority by usage type

proxy

syncGW

clusterChangeRequest
- add/remove node
- maintenance mode
- add back
- swap rebalance
- failover

rebalance
- build partition replica on future master
- ensure indexes are built on future master (concurrently)
- do rest of partition takeover
- index (view) compaction (not concurrent with partition moves)

script building blocks / lua
- lua contexts
- sha's of registered, available functions
- node-local processors vs further away cluster-local processors

design-docs and other config
- stored in a special partition 0xffffffff?
-- so that they're replicated, backed-up, scan'able,
   UPR/TAP'able without any special machinery.

storage error cases
- out of space
- checksum problem
- storage inaccessible

thread
- conn affinity
- partition affinity
- partition slice affinity (concurrent hashmap)
- NUMA affinity?

indexDB key layout...
- hash  partitionId | key . subKey (m|v)
- range partitionId | key . subKey (m|v)
- index usage - emitKey . docId . partitionId
- changeStream - partitionId | seqNum | key . subKey

pill injection
- respond only when persisted
- respond only when replicated
- respond only when indexed
- but, don't do real update?
- or, do real update in scratch area

snapshot
- queue dedupe until there's a snapshot
-- persist dedupe
-- replica dedupe
-- read dedupe
--- snapshot reads

transactions
- locking (lease) / lock table?

item ephemeral
- dirty
-- not persisted (persistenceConcern)
-- not replicated (replicationConcern)
- cached

rebalance
- partitions to nodes (must have)
- partitions to processors (nice to have, or just rebalance (or swap rebalance) to new or same node)
- partitions to storages (nice to have, or just rebalance (or swap rebalance) to new or same node)

-------------------------------
partition / collection properties
- CAP (cp vs ap)
- partitioning funcs
- range / scan / iteration support
- has values
- ops
-- get
-- set
-- delete
-- merge
--- append/prepend
--- union
-- add
-- replace
-- arith
- cas
- revId
- attachments
- eviction policy
- expiration / TTL
- compaction policy
- changesStream
- upstream source
-- for indexing
-- for replication
- state
- range config
- xdcr'able
- index'able
- auth
- subItems
- revision tree
- flags
- dataType
- compression
- tx
-- NBtA proposed changes

cluster / pull constructor
- pool: default
- bucket: default
- user: default
- user: _sys

tombstone purge