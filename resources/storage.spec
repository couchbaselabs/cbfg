This storage.spec file covers storage concerns.

partitionStorage vs kvStore
  partitionStorage has queues and uses thread/worker/task pool
    limits the number of file I/O for less thrashing / less seeking
    limited number of concurrent scans depending on actual storage
  kvStore is simpler concept that does not have its own threads
    (might not map to rocksdb reality, which does have its own threads
     for compactions and memtable flushes
       - max concurrent background compactions (low priority thread pool)
       - max concurrent background memtable flushes (high or low priority)

kvStore
- getCached
- batchGet
- batchMutate
-- create
-- update
-- delete
-- merge
- snapshot
- scan
- changesStream (optional follow)
- chunks
- compact
- purgeTombstones
- stats
- pause/resume file purging (for same-machine online backups)

mapping of logicalPartition(s) to kvStore(s)
- logicalPartition == bucketId/collectionId/partitionId
- logicalPartition is many-to-one to kvStore
- kvStore does not know about logicalPartition concept
-- just looks like storing keys and values to the kvStore

multiple logicalPartitions in one kvStore
- for better batching?
- fewer file descriptors?
- still logically separate, though
- add logicalPartitionId as key prefix
-- perhaps also add one more level of indirection
--- to allow for fast delete-and-recreate
--- map partitionId to storePartitionId
--- map storePartitionId to logicalPartitionId
-- true deletion during compaction

clever key naming allows optional app user data
- per logicalPartition

hierarchy of storage and performance
- this is managed by kvStore abstraction
-- memory
-- ssd
-- hdd
-- hdfs
-- s3
-- glacier
- partitionStorage does not know the physical hierarchy

synonyms?
- store
- storage
- directory/file?
- volume?
- logical volume?
- drive
- mount
- persistence

ephemeral (survives node restart) vs persistent

proposed leveldb layout...
  cust-0001:m            (metaData: revId, cas, flags, expiration,
                          dataType, compression)
  cust-0001:v            (value)
  cust-0001:_at/01:m     (attachment metaData)
  cust-0001:_at/01:v     (attachment value)
  cust-0001:_fm:m        (flexMetaData metaData)
  cust-0001:_fm:v        (flexMetaData value)
  cust-0001:_rt:m        (revTree metaData)
  cust-0001:_rt:v        (revTree value)
  cust-0001:_tx:m        (transaction metaData)
  cust-0001:_tx:v        (transaction metaData)
  cust-0001/<subKey>:m
  cust-0001/<subKey>:v
  cust-0001/campaign-001:m
  cust-0001/campaign-001:v
  cust-0001/campaign-002:m
  cust-0001/campaign-002:v
  cust-0001/campaign-002/response-000001:m
  cust-0001/campaign-002/response-000001:v

some subkeys are atomic with item (":"), transactional,
  and replicated atomically
other subkeys are independent items ("/"), but are stored "close by"

storage / dir (perhaps this goes into cdl.spec?)
- quota
-- cache quotas
-- storage quotas (generally, not a good idea)
- allowed file types
-- e.g., kvStore vs on-disk-sorting
-- e.g., kv vs index vs backIndex vs logs
- allowed partition types
- allowed collection types
- priority by usage type

high-multitenancy and file I/O resources
- rocksdb has Env level of indirection for O/S fileSystem ops.

high-multitenancy and file I/O quiesce-ing?
- some kvStore have low cache settings
-- e.g., replica partitions vs active/pending partitions
-- when replica switched to active, kvStore cache config is changed
-- e.g., replica kvStore(s) are close-able / quiescable

kvStoreMgr
  getCached
  asyncGets(..., callback)
  asyncScan(..., callback)
  asyncMutations(..., callback)
  asyncChangeStream(..., callback)

  usage sketch
    for msg in range msgChannel
       switch msg.opCode
         case GET
           r, err = kvStoreMgr.getCached(bucket, collection,
                                         msg.partitionId, msg.key)
           if err == nil
             msgChannel.enqueueResponse(msg, r, true /* done */)
           else if err == EWOULDBLOCK
             check err = kvStoreMgr.asyncGet(bucket, collection,
                                             msg.partitionId, msg.key)
               func(r, err) {
                 msgChannel, enqueueResponse(msg, r, r.isLast())
               })
         case SET
           check err = kvStoreMgr.asyncMutations(bucket, collection,
                                                 msg.partitionId,
                                                 [[msg.key, msg.val]])
             func(r, err) {
               msgChannel, enqueueResponse(msg, r, r.isLast())
             })
       if msg.fenced
         msg.waitUntilDone()

  kvStoreMgr thread
    for batch in requestBatches
      kvStore = batch.kvStore
      for req in batch.sortByKey()
        kvStore.perform(req)
        how to coallesce gets and scans and mutations?
        how to handle kvStore admin/meta CRUD?
          (list / create / delete kvStore)