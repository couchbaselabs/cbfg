itemOperation
- headers
- op
- item

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
- compactNow |         |        | y
- exec(task) |         |        | y
- pause(task)
- resume(task)
- splitPartition
- mergePartition
- set/getPartitionConfig (including range)
- set/getPartitionState
- set/getFilter
- set/getErrorExtra(CCCP)
- passOnwardsToReplicaX
- features
- stats

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

