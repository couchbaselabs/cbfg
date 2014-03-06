This ddl.spec file covers logical cluster configuration concepts.

  depends:
    - base

The main "containment" hierarchy is...

  hierarchy:
    - cluster
      - pool
        - bucket
          - indexCfg
          - designDoc
            - view
          - xdcrCfg
      - user
      - group

Definitions...

  cluster: acled

  pool: acled nodeQuotaed

  bucket: acled nodeQuotaed replicated collection
    - bucketType (readOnly)
    - CAP (cp vs ap) ?
    - partitionFunc
    - numPartitions (readOnly)
    - capped, cache, lru ?

  nodeQuotaed:
    - perNodeMemoryQuota
    - perNodeStorageQuota

  replicated:
    - replicaCount
    - replicaPlacement

  designDoc: acled nodeQuotaed replicated collection
  indexCfg: acled nodeQuotaed replicated collection

  user:
    credentials

  group:
    userUUID*

  acl:
    accessRule
    userUUID*
    groupUUID*

  acled:
    acl*

  userUUID: uuid
  groupUUID: uuid

A cluster configuration hierarchy is stored in a special system
bucket, so it will have changeStream, cascading delete, upwards ver
propagation, etc.

  bucketType:
    * memcached
    * couchbase
    * strongcp
    * couchdb
    * ap

  bucketTypeDef:   | memcached | couchbase | strongcp | couchdb | ap
    - backend      | def-eng   | ep-eng    | etcd     |         |
    - changeStream |           |           | y        |         |
    - groupMembership |        |           | y        |         |
    - replication
    - xdcr
    - persistence
    - indexes
    - conflictResolution
    - compactionPolicy
    - ops
      - get
      - set
      - delete
      - merge
        - append/prepend
        - union
        - add
        - replace
        - arith

  partitionFunc:
    * hashCRC
    * range

max partition id
- 16 bits?
- 24 bits?
- or just a string

system user ("_system")
- a "system user or conductor", which is super-priviledged
-- not meant for end-user usage
-- used by the system to force changes on nodes
-- without needing special case networking pathways
-- and should always work even if user deletes/changes any other admin users
-- only the system knows _system credentials

some buckets are special system buckets
- system catalog bucket (read-only?)
- system stats bucket (read-only?)
- so that they are replicated, backed-up, scanable, UPR/TAP-able
- without any special machinery.

  collectionType: | changeStream | subItem | tx | mvcc | indexable
  * primary       | y            | y       | y  | y    | y
  * backIndex     | y            | n       | n  | y    | n
  * index         | n            | n       | n  | y    | n

  collectionType:
    - partitionFunc
    - supports
    -- range
    -- value
    -- cas
    -- revId
    -- subItem
    -- revisionTree
    -- attachment
    -- tx
    --- NBTA proposed changes
    -- changeStream
    -- flags
    -- expiration
    -- LRU / capped
    - usedFor
      * app
      * indexing
      * replication
      * system
    - allowedDataType*
    - validtor*

  firstTimeSetup:
    - pool(default)
      - bucket(default)
    - user: default
    - user: _system
    - user: _anonymous
    - acl: ALL _system
    - acl: ALL default

capped collections
- used as cache
- drops LRU data items
