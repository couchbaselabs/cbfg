miscellaneous model ideas/notes
-------------------------------

cross cutting
- stateMachine
- lvar
  - vectorClock
  - revisionTree (might be pruned, so maybe not an lvar?)
  - transactionMetaData (might be pruned, so maybe not an lvar?)

platform
- mobile
-- android
-- ios
-- javascript/sqlite
- server
-- linux
--- docker
-- osx
-- solaris
-- windows
-- javascript/nodejs

services (all pluggable)
- stats
-- stats aggregator
- partition map validator
  ("you're currently unbalanced; don't have enough nodes for # of replicas")
- partition map planner
-- max movers per pair of nodes
- partition map plan scheduler
- partition map move executor
-- ongoing takeover streams
- intraClusterReplicator (ongoing stream manager)
-- regular streams
-- takeover streams
- xdcr
- n1ql
-- distributed n1ql execution engines (hash join, mappers, etc)
- storedProcs
- primaryIndex (kv)
- secondaryIndex
-- backIndex
-- 2i
-- view
--- view aggregates might not work with LSM/leveldb?  use rocksdb merge?
-- fullText
- integrations
-- hadoop
-- elasticSearch
- backup
- direct file copier
- autoFailOver
- encrypt/decrypt
- authManager
- nodeHealth
-- nodeHeartBeat
-- nodeDoctor

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

node
- uuid (the one thing a node needs to know on startup, along with cfg server)
- parent / containment (rack / zone)
- usage (kv only? index and index.fullText only?)
- memory
- processors
- storage
-- logical data volumes (leverage more spindles)

collection
- primaryIndex
- secondaryIndex

auth
- user
-- a "system user or conductor", which is super-priviledged?
--- not meant for end-user usage
--- used by the system to force changes on nodes
--- without needing special networking pathways
--- and should always work even if user deletes/changes any other admin users
- group
- ACL's

cross cutting
- versioning / mvcc

internals
- queue
-- send queue
-- recv queue
-- write queue
-- read queue
- snapshotable
-- iterator
-- storage
-- config

storage
- warming / running / cooling / stopped

drive
- type
-- permanent
-- ephemeral (ec2 local drive)

storageServices
- (current num and max num of these per node, per drive; state: working/running vs stopped)
- scanners
-- backFiller
--- state: backFilling
- drainers
- compactor (isa scanner & isa drainer?)
-- forceCompactNow (during rebalance)
- inPlaceBackuper

drive
- type (ssd, hdd)

protobuf's and clojure

item
- partitionId
- key
- revId / cas?
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

proposed leveldb layout...
  cust-0001:m            (metaData: revId, cas, flags, expiration, dataType, compression)
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

itemOperation
- headers
- op
- item

cluster / site
  pool
    bucket
     type
       memcached
       couchbase (cp)
       couchdbap (ap)
       couchdb (ap), edge / mobile
       etcd (strong-cp)
         subscription
         group membership
         etcd
     primaryIndex collection
       partition
         partitionState
           master (but in takeover), replica, dead
         partitionFunc
           hash
           range
         errorOpaque (cccp)
         items
         changes
         failOverLog
         mailBox (ACID message queue)
         ephemeral
           subscribers (with filters (keyOnly, matchKeyExpr, matchValueExpr))
           partition assignment to processor (mem quota)
           partition assignment to storage (storage quota)
     secondaryIndex collection
       partition
     by the way
       some buckets are special system buckets
         system catalog bucket (read-only?)
         stats bucket (read-only?)
  auth

storage / dir
- quota
- allowed file types
- allowed partition types
- allowed collection types
- priority by usage type

proxy
- syncGW
- moxi2

requests & responses / green stack
- responses might out of order
- up to client to re-sequence the responses, such as by using opaqueId's.

proxy'ability
- requests have an optional a opaqueId
- proxy keeps a opaqueId(client) <-> opaqueId(proxy) map
- proxy might run out of opaqueId's? (opaqueId's need to be big enough)
- fence request (or request flag) to make sure all responses are
  on the wire before handling more requests.
-- if proxy uses fence, it will block up a connection
-- answer: level of indirection via channels
--- allow for optional channelId in request "header"

ops          | cluster | bucket | partition
- getCached  |         | item   | item
- get        |         | item   | item, tx
- set        |         | item   | item, tx, writeConcern
- del        |         | item   | item, tx, writeConcern
- merge      |         | item   | items, tx, writeConcern
- scan       |         | items  | items, tx
- changes    |         | y      |
- def        |         | y      | tx, writeConcern
- eval       |         | y      | tx, writeConcern
- mget       |         |        | itemHierarchy, tx
- mset       |         |        | itemHierarchy, tx, writeConcern
- mdel       |         |        | itemHierarchy, tx, writeConcern, cascadingDelete
- ddl        | y       | y      | tx, writeConcern
- conductor  | y       | y      |
- pillInject |         |        | tx, writeConcern
- logInject  | y       | y      | y
- fence      |         |        | y

writeConcern
- N < R + W

errors
- not my partition
-- gives cccp opaque?
- not my range
-- gives cccp opaque?
- temp OOM
-- gives queue in/out speeds and lengths?
- not caught up (index scan)
-- gives queue in/out speeds and lengths?
- not cached (getCached)
-- gives queue in/out speeds and lengths?

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

a cluster (a.k.a site):
  is configured to contain:
    a set of 1 or more pool's (identified by name),
    a set of 1 or more user's (identified by name),
    a set of 1 or more role's (identified by name),
    a set of 1 or more server's (identified by host_port);
  must have:
    1 pool instance (instance.name is "default");
    1 user instance (instance.roles includes "admin");
    1 role instance (instance.name is "admin");

design-docs and other config
- stored in a special partition 0xffffffff?
-- so that they're replicated, backed-up, scan'able,
   UPR/TAP'able without any special machinery.

------------------------------------
a pool:
  has configured properties:
    name;
  is configured to contain:
    a set of 0 or more bucket's (identified by name),
    a set of 0 or more users;

a bucket:
  has configured properties:
    name;
  is configured to contain:
    a set of 0 or more partitions;

a partition:
  has assigned properties:
    id,
    state,

a server:
  has a parent cluster;
  has configured properties:
    host_port,
    ram_quota,
    storage_path;

a ram-quota:
  is a property of type uint32 of units MB;

a host:
  is a property of type string;

---------------------------
multiple partitions in one file
- for better batching
- still logically separate, though
- add partitionId as key prefix
-- perhaps also add one more level of indirection to allow for fast delete-and-recreate
-- true deletion during compaction

---------------------------
rocksdb

pros
- merge operator
- online backup
- lots of operational features

cons
- memory mgmt control
- managing values as 4KB chunks

----------------------
workload generator
- input
-- workload ops mixes
-- collection capabilitiies
-- cluster config (multicluster)

boom
  {join [x y z]
   where (> x.b y.b)
   select [x.a z.a z.b]
   into [w w2 w3]}

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

rebalance
- partitions to nodes (must have)
- partitions to processors (nice to have, or just rebalance (or swap rebalance) to new or same node)
- partitions to storages (nice to have, or just rebalance (or swap rebalance) to new or same node)

transactions
- locking (lease) / lock table?

item ephemeral
- dirty
-- not persisted (persistenceConcern)
-- not replicated (replicationConcern)
- cached

headers
- controls processing?
- quiet / verbose / fenced
- compression
- dataType
- txId
- channel
- writeConcern
- uncompressed value size (for stats)
- checksum
- partitionId

subItems
- modifying subItem does not change parent
-- unless a mset (changing parent and child in one ACID set)
-- beware that replication keeps the atomicity
- cascading deletes
- used for
-- transactions
-- attachments
-- revisionTrees
-- subItems
-- flexMetaData

follow several key/values/revID/transactions through the system
- write dedupe
- replica dedupe
- read dedupe
-- snapshot reads

show the compare/contrast of one model and config and setup and fork versus another

node
- startTime
- currTime
- upTime is calculated by tool
- aggregates calculated elsewhere?
- speed in/out and current queue lengths tracked by node

pill injection
- respond only when persisted
- respond only when replicated
- respond only when indexed
- but, don't do real update?
- or, do real update in scratch area

admin chosen input          | app/end-user chosen input       | runtime/ephemeral
------------------------------------------------------------------------------------------------
"DDL"                       | "DML"                           |
CRUD for cfg / def / ddoc   | CRUD for item ops / n1ql / exec | "eng"
add/remove node, rebalance  |                                 |
                            | ops stats                       | eng stats (queue lengths, drain rates)
                            |                                 | startTime / upTime / % fragmentation

max partition id
- 16 bits?

indexDB key layout...
- hash  partitionId | key . subKey (m|v)
- range partitionId | key . subKey (m|v)
- index usage - partitionId | emitKey . docId
- changeStream - partitionId | seqNum | key . subKey

thread
- conn affinity
- partition affinity
- partition slice affinity (concurrent hashmap)
- NUMA affinity?

storage error cases
- checksum
- storage inaccessible
