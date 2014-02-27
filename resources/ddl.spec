This ddl.spec file covers logical cluster configuration including
pools, buckets, indexes, users, collections, partitions, xdcr.

site -> cluster -> pool

DDL definitions

  namedObj:
    uuid (readOnly)
    ver  (uint64IncrementLVar)
    name (readOnly, uniqueInParentContainer)

  cluster: namedObj
    pool*
    user*
    group*
    acl*

  pool: namedObj
    bucket*
    acl*

  bucket: namedObj collectionConfig
    bucketType (readOnly)
    partitionFunc
    numPartitions (readOnly)
    designDoc*
    indexConfig*
    xdcrConfig*

  collectionConfig:
    perNodeMaxMemory
    perNodeMaxStorage
    replicaCount
    replicaPlacement

  designDoc: namedObj
    view*

  indexConfig: namedObj collectionConfig

  xdcrConfig: namedObj

  user: namedObj
    credentials

  group: namedObj
    userUUID*

  acl: namedObj
    accessRule
    userUUID*
    groupUUID*

  name: string [a-zA-Z0-9][a-zA-Z0-9_-]*

  uuid: nonEmptyUUID
  userUUID: uuid
  groupUUID: uuid

cluster hierarchy has changeStream.
cluster hierarchy has cascading delete.
cluster hierarchy has upwards ver propagation.

bucketType:     | memcached | couchbase | strongCP | couchdb | ap
backend         | def-eng   | ep-eng    | etcd     |         |
subscription    |           |           | y        |         |
groupMembership |           |           | y        |         |
replication
xdcr
persistence
indexes
conflictResolution

partitionState
  master, pending, replica, dead

partitionFunc
  hashCRC
  range

max partition id
- 16 bits?

system user ("_system")
- a "system user or conductor", which is super-priviledged
-- not meant for end-user usage
-- used by the system to force changes on nodes
-- without needing special case networking pathways
-- and should always work even if user deletes/changes any other admin users
-- only the system knows _system credentials

some buckets are special system buckets
  system catalog bucket (read-only?)
  stats bucket (read-only?)

design-docs and other config
- might be stored in a special, highly replicated system collection?
-- or special system bucket?
-- so that they are replicated, backed-up, scanable,
   UPR/TAP-able without any special machinery.

-------------------------------
collection properties
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

cluster / pool constructor
- pool: default
-- bucket: default
-- acl: ALL _system
-- acl: ALL default
- user: default
- user: _system
- user: _anonymous
