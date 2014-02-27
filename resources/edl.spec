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

eMemoryItem ephemeral
- dirty
-- not persisted (persistenceConcern)
-- not replicated (replicationConcern)
- cached
- deleted
- hotness (LRU, NRU)

ePersistItem

script building blocks / lua
- lua contexts
- SHAs of registered, available functions
- node-local processors vs further away cluster-local processors

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
- respond only when received
- respond only when authed/acl-checked
- respond only when queued
- respond only when persisted
- respond only when replicated
- respond only when indexed
- but, do not do real update?
- or, do real update in scratch area

snapshot
- queue dedupe until there is a snapshot
-- persist dedupe
-- replica dedupe
-- read dedupe
--- snapshot reads

transactions
- locking (lease) / lock table?
-- lock per ePartition
-- lock per eCollection
-- lock per eBucket

tombstone purge

eMemoryPartition
ePersistedPartition

eMemoryCollection
ePersistedCollection
