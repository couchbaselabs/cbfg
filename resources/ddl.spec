site -> cluster -> pool

DDL definitions

  name: nonEmptyString

  uuid: nonEmptyUUID
  userUUID: uuid
  groupUUID: uuid

  namedObj:
    uuid (readOnly)
    ver
    name (readOnly)

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

cluster hierarchy has changeStream.
cluster hierarchy has cascading delete.
cluster hierarchy has upscading ver propagation.

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

