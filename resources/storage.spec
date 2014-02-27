This storage.spec file covers storage concerns.

kvStore
- batchGet
- batchMutate
-- create
-- update
-- delete
-- merge
- snapshot
- chunks
- compact
- purgeTombstones
- stats
- storage

multiple partitions in one storage
- for better batching
- still logically separate, though
- add partitionId as key prefix
-- perhaps also add one more level of indirection to allow for fast delete-and-recreate
-- true deletion during compaction

clever key naming allows optional app user data
- per partition

hierarchy of storage and performance
- memory
- ssd
- hdd
- hdfs
- s3
- glacier

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

map partitionId to storePartitionId

map storePartitionId to kvStore

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

storage / dir (perhaps this goes into cdl.spec?)
- quota
- allowed file types
- allowed partition types
- allowed collection types
- priority by usage type

