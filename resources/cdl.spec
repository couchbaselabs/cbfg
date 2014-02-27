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
