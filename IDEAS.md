inspirations
------------

http://thesecretlivesofdata.com/raft/

https://github.com/couchbaselabs/xdsim

https://github.com/couchbaselabs/cbgm

ideas
-----

Visualize and simulate the complex system _before_ you build it.

An interactive visualization and model simulator explains (and
convinces) better than a static spec or "wall of words" document
(which nobody will read).

Use higher level abstractions for encoding rules & model...

* DSL's
* state machines
* boom-like approaches
* declarative approaches

Rules to encode a simulator might be reusable...

* as parts of a final system
* codegen and multi-platform targets
* fuzzer / checker / test-case generator
* presentations / docs
* stats monitoring U/I
* basic CRUD U/I

U/I visualization ideas
-----------------------

U/I to follow (or "star" or "bookmark") a key or value (or
sub-field/value) and watch it flow through the system.

Watch write dedupe / read dedupe.
- see persistence & replication dedupe in action
- see read batching & convoying in action

U/I to follow a particular revId of an item.

U/I to follow several of the above.

Snapshot reads.

Show rebalance, failover logs, rollbacks.

Watch what happens to concurrent mutation races,
and "fork" the U/I to watch both outcomes of the race.

Watch how a transaction works.

Show where stats come from.

Show the impact of a lock.

Show different outcomes of distribution changes.

code-gen
--------

Keep in mind platforms like...

- server
-- linux
--- docker
-- osx
-- solaris
-- windows
-- javascript/nodejs

- mobile
-- android
-- ios
-- javascript/sqlite

- client / app platforms

- router / proxy platforms

miscellaneous model ideas/notes
-------------------------------

rules...

rebalance is the most important feature
- even at the cost of slower ops
- rebalance, like car brakes, must work in emergencies and under duress

uniform naming
- know where stats come from
- allow stats to be 'git grep'-able all the way to the source

----------------------------------------
rocksdb thoughts

pros
- merge operator
- online backup
- lots of operational features

cons
- memory mgmt control (although Put has SliceParts)
- managing values as 4KB chunks
- view aggregates might not work with LSM/leveldb? use rocksdb merge?

----------------------------------------
workload generator

input
- workload ops mixes
- collection capabilitiies
- cluster config (multicluster)

----------------------------------------
operational

keep in mind hot-loading / hot-reloading code

----------------------------------------
stuff to look at / learn

- clojure.test
- hiccup ~ html DSL
- clojure slurp API
- clojure.java.io
-- io/resource API
- engelberge/instaparse for clojure
- total programing
- idris
- brett victor
- lighttable
