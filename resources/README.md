Overview of the various *.spec files, where they might
be read and unerstood in this order...

ddl.spec
--------

Covers logical cluster configuration including pools, buckets,
indexes, users, collections, partitions, xdcr.

dml.spec
--------

Covers logical items and operations.

cdl.spec
--------

Covers mapping of logical resources to nodes, including rebalance,
failover, add/remove nodes, swap rebalancing, rolling upgrades, simple
& smart add-back.

edl.spec
--------

Covers engine implementation including internal queues, stats, error
conditions.

framing.spec
------------

Covers network protocol framing.

storage.spec
------------

Covers storage concerns.

base.spec
---------

Covers some shared, base concepts and types.