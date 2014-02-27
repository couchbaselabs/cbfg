This cdl.spec file ("cluster definition language") covers mapping of
logical resources to nodes, including rebalance, failover, add/remove
nodes, swap rebalancing, rolling upgrades, simple & smart add-back.

Clients/applications would need to know a subset of these concepts,
including the partition mapping to nodes.

node
- state: known, wanted, unwanted

wanted nodes should have active partition maps

old partition mappings should be kept for a bit (for swap-rebalance,
smart add-back).

node: namedObj
- uuid (the one thing a node needs to know on startup, along with cfg server)
- parent/containment path (rack/zone)
- usage (kv only? index and index.fullText only?)
- host / port
- weight
- memoryQuota
- addrBindings
- numProcessors
- directory+
-- logical data volumes (leverage more spindles)
- maxConns
- maxChannels
- maxInflightRequestsPerChannel

partitionMap

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

rebalance
- partitions to nodes (must have)
- partitions to processors
-- (nice to have, or just rebalance (or swap rebalance) to new or same node)
- partitions to storages
-- (nice to have, or just rebalance (or swap rebalance) to new or same node)

