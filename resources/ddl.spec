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

