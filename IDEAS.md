inspirations
------------

Visualize and simulate the complex system _before_ you build it.

http://thesecretlivesofdata.com/raft/

https://github.com/couchbaselabs/xdsim

https://github.com/couchbaselabs/cbgm

An interactive visualization & simulator explains better than a static
spec or document.

Rules to encode a simulator might be reusable...

* as parts of the final
* codegen and multi-platform targets.
* fuzzer / checker / test-case

ideas
-----

U/I to follow (or "star" or "bookmark") a key or value (or
sub-field/value) and watch it flow through the system.

U/I to follow a particular revId of an item.

U/I to follow several of the above.

Watch write dedupe / read dedupe.

Snapshot reads.

Show rebalance, failover logs, rollbacks.

Watch what happens to concurrent mutation races,
and "fork" the U/I to watch both outcomes of the race.

Watch how a transaction works.

Show where stats come from.

Show the impact of a lock.

Show different outcomes of distribution changes.
