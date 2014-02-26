miscellaneous model ideas/notes
-------------------------------

- view
- view aggregates might not work with LSM/leveldb?  use rocksdb merge?

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

------------------------------------
rocksdb

pros
- merge operator
- online backup
- lots of operational features

cons
- memory mgmt control (Put has SliceParts)
- managing values as 4KB chunks

----------------------
workload generator
- input
-- workload ops mixes
-- collection capabilitiies
-- cluster config (multicluster)

show the compare/contrast of one model and config and setup and fork versus another

follow several key/values/revID/transactions through the system
- write dedupe
- replica dedupe
- read dedupe
-- snapshot reads

admin chosen input          | app/end-user chosen input       | runtime/ephemeral
------------------------------------------------------------------------------------------------
"DDL"                       | "DML"                           |
CRUD for cfg / def / ddoc   | CRUD for item ops / n1ql / exec | "eng"
add/remove node, rebalance  |                                 |
                            | ops stats                       | eng stats (queue lengths, drain rates)
                            |                                 | startTime / upTime / % fragmentation

max partition id
- 16 bits?

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

hot-loading / hot-reloading code
