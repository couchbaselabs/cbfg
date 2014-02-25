miscellaneous model ideas/notes
-------------------------------

cfg-model vs cfg-actual
- cluster config change event

eng-model is a model of engine process
  based on inputs from cfg

eng-actual is instantiated model

lvars
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

collection
- primaryIndex
- secondaryIndex

auth
- user
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

item
- partitionId
- key
- revId / cas?
- flags
- expiration
- valueDataType
- valueCompression
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
         mailBox
         ephemeral
           subscribers (with filters (keyOnly, matchKeyExpr, matchValueExpr))
     secondaryIndex collection
       partition
  auth

highConsistencyCluster
- subscription
- group membership
- etcd

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

lua
- lua contexts
- sha's of registered, available functions
- node-local processors vs further away cluster-local processors

